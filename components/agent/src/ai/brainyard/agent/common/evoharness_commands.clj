;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.evoharness-commands
  "EvoHarness commands — the training loop for a brainyard-native policy.

   These call the `evoharness-backend` FastAPI control plane over HTTP rather
   than reimplementing any part of the pipeline. That is a deliberate choice,
   and there are two reasons rather than the usual one.

   ONE IMPLEMENTATION OF THE REWARD. The control plane owns reward.py, split
   enforcement and the episode store. A second Clojure implementation of the
   reward decomposition would drift from the one that produced the numbers in
   the store — and the failure mode is not a crash, it is the agent and the
   console quietly disagreeing about what an episode scored. The reward is
   versioned (EVO_REWARD_VERSION), so \"which reward scored this\" is a real
   question with a real answer, and only the store knows it.

   ROLLOUTS ARE MINUTES LONG AND THEY MUTATE. An episode creates a throwaway
   project and a throwaway memory DB, writes files, edits config and consumes a
   model server. An agent turn is the wrong container for that, so every
   command here ASKS FOR A RUN AND POLLS. None of them blocks on one.

   TWO THINGS ARE ABSENT ON PURPOSE, and their absence is the design:

     * There is no command to write a reward. The agent reads episodes to
       diagnose and never scores one. If it could, every curve would partly be
       a measurement of the agent, and a model that got better at pleasing this
       agent would be indistinguishable from one that got better at the task.
       The control plane exposes no such endpoint either.

     * There is no \"try this checkpoint\" command. An agent turn is never an
       eval: it would be one anecdote, off the held-out split, under an unknown
       context profile, with the agent both administering and interpreting.
       Evaluation is `evo$run :kind \"eval\"`.

   Base URL comes from `EVO_API_URL`, which the workspace section puts on this
   agent's owner process (SECTION_CONTEXTS.evoharness gates) so that one
   project's agent talks to that project's control plane. `EVO_PROJECT` is set
   on the BACKEND, not read here.

   Every command returns `{:error \"...\"}` rather than throwing when the
   control plane is unreachable, so the agent can say the control plane is not
   running instead of failing the turn.

   Design: brainyard-playground-apps/docs/design/evoharness-section-plan.md §10,
   and docs/design/evoharness-agent-design.md in this repo."
  (:require [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.clj-http-native.interface :as http]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URLEncoder]
           [java.nio.charset StandardCharsets]))

;; =====================================================
;; Transport
;; =====================================================

(def ^:private default-base-url "http://127.0.0.1:8200")

(defn base-url
  "The control plane this agent talks to. Trailing slashes trimmed so callers
   can concatenate a path without thinking about it."
  []
  (let [raw (str/trim (or (System/getenv "EVO_API_URL") ""))]
    (str/replace (if (str/blank? raw) default-base-url raw) #"/+$" "")))

(defn- encode ^String [v]
  (URLEncoder/encode (str v) (.name StandardCharsets/UTF_8)))

(defn- query-string
  "Build a query string from a map, dropping nil/blank values."
  [params]
  (->> params
       (keep (fn [[k v]]
               (when (and (some? v) (not (and (string? v) (str/blank? v))))
                 (str (name k) "=" (encode v)))))
       (str/join "&")))

(defn- parse-body
  "Parse a JSON body, keywordizing keys. Returns nil when it is not JSON."
  [body]
  (try
    (json/read-str (str body) :key-fn keyword)
    (catch Exception _ nil)))

(defn- unreachable-error
  "The message the agent should relay when the service is not there.

   Deliberately actionable: the operator's next step differs by how the control
   plane is managed, and 'connection refused' does not tell them which."
  [ex]
  {:error (str "EvoHarness control plane is not reachable at " (base-url)
               " (" (.getMessage ^Exception ex) "). "
               "Start it with `npm run dev -w @brainyard/evoharness-backend`, or — under "
               "Brainyard Desktop — from the Backend tab. Nothing was read or written.")})

(defn- request
  "One HTTP call to the control plane, normalised to data.

   Never throws: a dead service, a timeout and a 500 all become {:error ...},
   because an agent that dies on an unreachable sidecar cannot explain itself.

   A 409 from the promotion gate carries a STRUCTURED detail — the list of
   reasons it refused — and flattening that to a string would throw away the
   only part anyone needs, so it is passed through whole."
  [method path {:keys [body timeout-ms] :or {timeout-ms 60000}}]
  (let [url (str (base-url) path)
        opts (cond-> {:as :string
                      :throw-exceptions false
                      :timeout-ms timeout-ms
                      :connect-timeout-ms 5000}
               body (assoc :body (json/write-str body) :content-type :json))]
    (try
      (let [{:keys [status body]} (case method
                                    :get    (http/get* url opts)
                                    :post   (http/post url opts)
                                    :put    (http/put url opts)
                                    :delete (http/delete url opts))
            parsed (parse-body body)
            detail (:detail parsed)]
        (cond
          (<= 200 status 299) (or parsed {})
          (map? detail)       (assoc detail :error "refused")
          :else
          {:error (str "EvoHarness control plane returned " status ": "
                       (or detail
                           (some-> body (subs 0 (min 300 (count (str body)))))
                           "no detail"))}))
      (catch Exception e
        (mulog/log ::evo-request-failed :url url :error (.getMessage e))
        (unreachable-error e)))))

;; =====================================================
;; Health & inventory
;; =====================================================

(defcommand evo$health
  "Check the training control plane. FOUR things can be down separately — the control plane itself, the brainyard checkout episodes are driven through, the executor that owns the GPU, and whichever checkpoint is being served — and each has a different fix. Say WHICH."
  (fn [& _]
    (request :get "/health" {:timeout-ms 15000}))
  :input-schema  [:map]
  :output-schema [:map
                  [:ok       {:optional true} [:boolean {:desc "Control plane is up and configured"}]]
                  [:profile  {:optional true} [:string  {:desc "Harness profile: the context regime runs execute under"}]]
                  [:runtime  {:optional true} [:any     {:desc "The brainyard checkout rollouts drive. Absent = no episodes, ever"}]]
                  [:executor {:optional true} [:any     {:desc "kind/host/configured. NOT probed — configured is not reachable"}]]
                  [:served   {:optional true} [:any     {:desc "Checkpoints currently being served, with the profile each was TRAINED under"}]]
                  [:worker   {:optional true} [:any     {:desc "The run the worker is on right now"}]]
                  [:error    {:optional true} [:string]]])

(defcommand evo$stats
  "Corpus and spend: task counts BY SPLIT, run counts by status, episodes and how many voided, tokens spent to date, checkpoints."
  (fn [& _]
    (request :get "/stats" {:timeout-ms 30000}))
  :input-schema  [:map]
  :output-schema [:map
                  [:suites       {:optional true} [:int]]
                  [:tasks        {:optional true} [:any {:desc "Task counts keyed by split — test is held out"}]]
                  [:runs         {:optional true} [:any {:desc "Run counts keyed by status"}]]
                  [:episodes     {:optional true} [:any {:desc "Total and void. A void is a RUNNER failure, not a zero"}]]
                  [:tokens_spent {:optional true} [:int]]
                  [:checkpoints  {:optional true} [:int]]
                  [:error        {:optional true} [:string]]])

;; =====================================================
;; Suites & tasks
;; =====================================================

(defcommand evo$suites
  "List task suites, or the harness scripts that could still be imported when :importable? is true."
  (fn [& {:keys [importable?]}]
    (if importable?
      (request :get "/suites/importable" {:timeout-ms 15000})
      (request :get "/suites" {:timeout-ms 15000})))
  :input-schema  [:map
                  [:importable? {:optional true} [:boolean {:desc "List importable test-agent-*.sh scripts instead of imported suites"}]]]
  :output-schema [:map
                  [:suites    {:optional true} [:any]]
                  [:available {:optional true} [:any {:desc "Importable scripts and whether each is already imported"}]]
                  [:error     {:optional true} [:string]]])

(defcommand evo$import-suite
  "Import one scripts/test-agent-*.sh from the brainyard checkout as a suite of tasks: one task per turn, one assertion per assert_* line. READ THE WARNINGS — a task with no assertions cannot produce a task reward, and rolling it out spends money to learn nothing."
  (fn [& {:keys [source split replace?]}]
    (if (str/blank? (str source))
      {:error "source is required — the bare filename of a test-agent-*.sh script"}
      (request :post "/suites/import"
               {:body {:source source
                       :split (or split "train")
                       :replace (boolean replace?)}
                :timeout-ms 60000})))
  :input-schema  [:map
                  [:source [:string {:desc "Bare filename, e.g. test-agent-todo.sh"}]]
                  [:split {:optional true} [:enum {:desc "Which split these tasks land in. test is evaluated only at promotion and never trained on."} "train" "dev" "test"]]
                  [:replace? {:optional true} [:boolean {:desc "Re-import over an existing suite"}]]]
  :output-schema [:map
                  [:suite_id {:optional true} [:string]]
                  [:tasks    {:optional true} [:int]]
                  [:warnings {:optional true} [:any {:desc "Import problems worth reading — especially \"no assertions\""}]]
                  [:error    {:optional true} [:string]]])

(defcommand evo$tasks
  "List tasks, optionally filtered by suite or split. A task is a prompt, a setup fragment, assertions and budgets."
  (fn [& {:keys [suite-id split]}]
    (let [qs (query-string {:suite_id suite-id :split split})]
      (request :get (str "/tasks" (when-not (str/blank? qs) (str "?" qs)))
               {:timeout-ms 30000})))
  :input-schema  [:map
                  [:suite-id {:optional true} [:string]]
                  [:split    {:optional true} [:enum "train" "dev" "test"]]]
  :output-schema [:map
                  [:tasks {:optional true} [:any]]
                  [:error {:optional true} [:string]]])

(defcommand evo$set-split
  "Move a task between splits. Moving a task INTO train after it has been evaluated on cannot be undone by moving it back — the numbers it produced are already contaminated. Say what it costs before doing it."
  (fn [& {:keys [id split]}]
    (cond
      (str/blank? (str id)) {:error "id is required"}
      (not (#{"train" "dev" "test"} (str split))) {:error "split must be train|dev|test"}
      :else (request :put (str "/tasks/" (encode id))
                     {:body {:split split} :timeout-ms 15000})))
  :input-schema  [:map
                  [:id [:string {:desc "Task id"}]]
                  [:split [:enum "train" "dev" "test"]]]
  :output-schema [:map
                  [:task  {:optional true} [:any]]
                  [:error {:optional true} [:string]]])

;; =====================================================
;; Runs
;; =====================================================

(def ^:private run-kinds #{"distill" "sft" "rollout" "grpo" "eval"})

(defcommand evo$run
  "Start a run. THE EXPENSIVE VERB: distill spends frontier tokens, sft and grpo spend GPU-hours on the trainer. Estimate and state the cost BEFORE calling this. Returns a run id immediately — it does not wait."
  (fn [& {:keys [kind split profile checkpoint-id suite-id group-size samples-per-task bundle-run]}]
    (if-not (run-kinds (str kind))
      {:error (str "kind must be one of " (str/join ", " (sort run-kinds)))}
      (let [params (cond-> {}
                     suite-id         (assoc :suite_id suite-id)
                     group-size       (assoc :group_size group-size)
                     samples-per-task (assoc :samples_per_task samples-per-task)
                     bundle-run       (assoc :bundle_run bundle-run))]
        (request :post "/runs"
                 {:body (cond-> {:kind kind :params params}
                          split         (assoc :split split)
                          profile       (assoc :profile profile)
                          checkpoint-id (assoc :checkpoint_id checkpoint-id))
                  :timeout-ms 30000}))))
  :input-schema  [:map
                  [:kind [:enum {:desc "distill (teacher rollouts → SFT bundle) | sft (bundle → adapter, remote) | rollout (episodes) | grpo (adapter + curves, remote) | eval (held-out report)"} "distill" "sft" "rollout" "grpo" "eval"]]
                  [:split {:optional true} [:enum {:desc "Defaults to test for eval, train otherwise. A training run CANNOT use test."} "train" "dev" "test"]]
                  [:profile {:optional true} [:enum {:desc "The context regime. Train and serve must match or the checkpoint is off-distribution."} "lean" "full"]]
                  [:checkpoint-id {:optional true} [:string {:desc "The checkpoint under test; it must already be served"}]]
                  [:suite-id {:optional true} [:string]]
                  [:group-size {:optional true} [:int {:desc "grpo only — rollouts per task, the unit an advantage is computed over"}]]
                  [:samples-per-task {:optional true} [:int {:desc "distill only — teacher samples per task"}]]
                  [:bundle-run {:optional true} [:string {:desc "sft only — the distill run that built the data"}]]]
  :output-schema [:map
                  [:run_id {:optional true} [:string]]
                  [:run    {:optional true} [:any]]
                  [:error  {:optional true} [:string]]])

(defcommand evo$runs
  "List runs, or one run with its episode statistics. Every run carries its SPLIT and its PROFILE — never report a number without both."
  (fn [& {:keys [id kind]}]
    (if id
      (request :get (str "/runs/" (encode id)) {:timeout-ms 30000})
      (let [qs (query-string {:kind kind})]
        (request :get (str "/runs" (when-not (str/blank? qs) (str "?" qs)))
                 {:timeout-ms 30000}))))
  :input-schema  [:map
                  [:id   {:optional true} [:string {:desc "One run, with stats"}]]
                  [:kind {:optional true} [:enum "distill" "sft" "rollout" "grpo" "eval"]]]
  :output-schema [:map
                  [:runs  {:optional true} [:any]]
                  [:run   {:optional true} [:any]]
                  [:stats {:optional true} [:any]]
                  [:error {:optional true} [:string]]])

(defcommand evo$cancel
  "Cancel a run. Cheap and always available — the counterweight to evo$run. A local run stops after the current episode; a remote one is cancelled on the trainer."
  (fn [& {:keys [id]}]
    (if (str/blank? (str id))
      {:error "id is required"}
      (request :post (str "/runs/" (encode id) "/cancel") {:timeout-ms 30000})))
  :input-schema  [:map [:id [:string]]]
  :output-schema [:map
                  [:cancelling {:optional true} [:string]]
                  [:error      {:optional true} [:string]]])

(defcommand evo$log
  "Tail a run's log. The FIRST place an infrastructure failure shows — check it before diagnosing the model."
  (fn [& {:keys [id tail]}]
    (if (str/blank? (str id))
      {:error "id is required"}
      (request :get (str "/runs/" (encode id) "/log?tail=" (encode (or tail 200)))
               {:timeout-ms 30000})))
  :input-schema  [:map
                  [:id [:string]]
                  [:tail {:optional true} [:int {:desc "Lines from the end (default 200)"}]]]
  :output-schema [:map
                  [:log    {:optional true} [:string]]
                  [:detail {:optional true} [:string]]
                  [:error  {:optional true} [:string]]])

(defcommand evo$report
  "A run's report: void rate FIRST, then pass rate and mean reward, then iterations/turn beside tokens/turn, then the annealing frequencies. session_health is deliberately null — it is a diagnostic that comes from session$analytics and is kept out of the reward."
  (fn [& {:keys [id]}]
    (if (str/blank? (str id))
      {:error "id is required"}
      (request :get (str "/runs/" (encode id) "/report") {:timeout-ms 60000})))
  :input-schema  [:map [:id [:string]]]
  :output-schema [:map
                  [:split          {:optional true} [:string {:desc "Never report a number without this"}]]
                  [:profile        {:optional true} [:string {:desc "Nor without this"}]]
                  [:episodes       {:optional true} [:any {:desc "total/scored/void/void_rate/void_reasons — read this first"}]]
                  [:outcome        {:optional true} [:any {:desc "pass_rate, mean_reward, assertion tallies"}]]
                  [:cost           {:optional true} [:any {:desc "iterations_per_turn AND tokens_per_turn — never one alone"}]]
                  [:annealing      {:optional true} [:any {:desc "Per-episode action frequencies"}]]
                  [:session_health {:optional true} [:any {:desc "Always null here; see session_health_note"}]]
                  [:error          {:optional true} [:string]]])

;; =====================================================
;; Episodes — the diagnosis surface
;; =====================================================

(defcommand evo$episodes
  "Read episodes, with their REWARD DECOMPOSITION. This is the only place a reward-shaped failure and a model-shaped failure look different — they produce the same flat curve. Read at least three before saying anything about an aggregate. A void episode is a RUNNER failure and has no reward at all; it is not a zero."
  (fn [& {:keys [id run-id limit]}]
    (if id
      (request :get (str "/episodes/" (encode id)) {:timeout-ms 30000})
      (let [qs (query-string {:run_id run-id :limit (or limit 50)})]
        (request :get (str "/episodes" (when-not (str/blank? qs) (str "?" qs)))
                 {:timeout-ms 30000}))))
  :input-schema  [:map
                  [:id     {:optional true} [:string {:desc "One episode, in full"}]]
                  [:run-id {:optional true} [:string {:desc "Episodes of one run"}]]
                  [:limit  {:optional true} [:int {:desc "Default 50"}]]]
  :output-schema [:map
                  [:episodes {:optional true} [:any]]
                  [:episode  {:optional true} [:any {:desc "status, void_reason, reward, reward_parts, assertions, channels"}]]
                  [:error    {:optional true} [:string]]])

;; =====================================================
;; Checkpoints
;; =====================================================

(defcommand evo$checkpoints
  "List checkpoints with their lineage and eval history, or one in detail. `profile` on a checkpoint is what it was TRAINED under — compare it against the serving profile before believing any number."
  (fn [& {:keys [id]}]
    (if id
      (request :get (str "/checkpoints/" (encode id)) {:timeout-ms 30000})
      (request :get "/checkpoints" {:timeout-ms 30000})))
  :input-schema  [:map [:id {:optional true} [:string]]]
  :output-schema [:map
                  [:checkpoints {:optional true} [:any]]
                  [:checkpoint  {:optional true} [:any]]
                  [:evals       {:optional true} [:any]]
                  [:error       {:optional true} [:string]]])

(defcommand evo$serve
  "Record where a checkpoint is being served. That URL becomes FREELLM_BASE_URL for every rollout that evaluates it — which is all it takes to reach a trained model from brainyard, because :free-llm already resolves its base URL from that variable. Serving it is the TRAINER's job; this records it. Verify through the ATTACHING path: the free-llm non-attach chat path raises \"URI with undefined scheme\", which is a property of that path and not evidence about the server."
  (fn [& {:keys [id url]}]
    (cond
      (str/blank? (str id))  {:error "id is required"}
      (str/blank? (str url)) {:error "url is required — where the checkpoint is being served"}
      :else (request :post (str "/checkpoints/" (encode id) "/serve")
                     {:body {:url url} :timeout-ms 30000})))
  :input-schema  [:map
                  [:id  [:string]]
                  [:url [:string {:desc "OpenAI-compatible base URL, e.g. http://trainer:8000/v1"}]]]
  :output-schema [:map
                  [:checkpoint {:optional true} [:any]]
                  [:note       {:optional true} [:string]]
                  [:error      {:optional true} [:string]]])

(defcommand evo$promote
  "Promote a checkpoint. EXPECT THIS TO BE REFUSED, and relay every reason rather than the first — each names a different fix. The gate is: the held-out test split, under the SERVING profile, void rate under 10%, pass rate at or above the recorded baseline, and a sandbox error rate below threshold. Promotion records the decision and prints the configuration; it does NOT reach into a running runtime's config."
  (fn [& {:keys [id serving-profile baseline-pass-rate]}]
    (if (str/blank? (str id))
      {:error "id is required"}
      (request :post (str "/checkpoints/" (encode id) "/promote")
               {:body (cond-> {}
                        serving-profile    (assoc :serving_profile serving-profile)
                        baseline-pass-rate (assoc :baseline_pass_rate baseline-pass-rate))
                :timeout-ms 60000})))
  :input-schema  [:map
                  [:id [:string]]
                  [:serving-profile {:optional true} [:enum {:desc "The profile it will actually be served under"} "lean" "full"]]
                  [:baseline-pass-rate {:optional true} [:double {:desc "The number it must meet, 0..1"}]]]
  :output-schema [:map
                  [:promoted  {:optional true} [:string]]
                  [:configure {:optional true} [:any {:desc "What to set on the runtime — this service does not set it"}]]
                  [:refused   {:optional true} [:any {:desc "Every reason the gate said no. Relay all of them."}]]
                  [:gate      {:optional true} [:any {:desc "The conditions, as data"}]]
                  [:error     {:optional true} [:string]]])

(def all-evoharness-commands
  "The evo$* family, for a defagent's :agent-tools roster."
  [#'evo$health #'evo$stats
   #'evo$suites #'evo$import-suite #'evo$tasks #'evo$set-split
   #'evo$run #'evo$runs #'evo$cancel #'evo$log #'evo$report
   #'evo$episodes
   #'evo$checkpoints #'evo$serve #'evo$promote])
