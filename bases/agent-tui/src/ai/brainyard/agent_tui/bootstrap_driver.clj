;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.bootstrap-driver
  "Non-interactive driver for the bootstrap ladder.

   The wizard (config_wizard.clj) and config-agent's bootstrap$re-run-rung
   both need to execute install / start-daemon / pull / smoke-test side
   effects. The wizard wraps each with interactive prompts; config-agent runs
   deterministically (refuses if interactivity is required).

   This namespace owns the executor functions plus a `re-run-rung!` entry
   point. Callers inject callbacks for status messages, install confirmation,
   and pull progress; defaults are silent (good for programmatic use).

   `re-run-rung!` PERSISTS the new :llm + :bootstrap blocks to config.edn
   after successful actions — that's the whole point of a programmatic rerun.
   Callers that want pre-write snapshot semantics (e.g. config-agent) should
   take a snapshot before calling."
  (:require [ai.brainyard.agent-tui.bootstrap :as boot]
            [ai.brainyard.agent-tui.smoke-test :as smoke]
            ;; Depend on the agent brick via its interface (Polylith rule);
            ;; init-dirs!/read-edn-config/write-edn-config! are re-exported there.
            [ai.brainyard.agent.interface :as core-config]
            [ai.brainyard.env-detect.interface :as env]))

;; ============================================================================
;; Callback defaults
;; ============================================================================

(def silent-callbacks
  "Defaults — programmatic callers (config-agent) get these. Status messages
   are dropped; install confirmation defaults to deny; pull progress is ignored."
  {:on-status         (fn [_msg & _])
   :on-install-hint   (fn [_hints])
   :on-install-confirm (fn [_hints] false)
   :on-pull-progress   (fn [_evt])})

(defn- merge-callbacks [overrides]
  (merge silent-callbacks overrides))

;; ============================================================================
;; Executors (callback-driven; ZERO println)
;; ============================================================================

(defn install-ollama!
  "Side effect: install Ollama if hints permit + the on-install-confirm callback
   approves. Returns {:ok? :installed? :detail ...}."
  [{:keys [hints]} cbs]
  (let [{:keys [on-status on-install-hint on-install-confirm]} (merge-callbacks cbs)
        {:keys [command auto-installable?]} hints]
    (on-install-hint hints)
    (cond
      (not auto-installable?)
      (do (on-status :install-manual-required)
          {:ok? false :installed? false :detail "manual install required"})

      (not (on-install-confirm hints))
      (do (on-status :install-declined)
          {:ok? false :installed? false :detail "user declined install"})

      :else
      (let [r (env/install-ollama! command)]
        (on-status (if (:ok? r) :install-ok :install-failed)
                   {:detail (:detail r)})
        (assoc r :installed? (:ok? r))))))

(defn start-daemon!
  "Side effect: start the Ollama daemon and poll. Returns env helper's result."
  [_action cbs]
  (let [{:keys [on-status]} (merge-callbacks cbs)
        _ (on-status :daemon-starting)
        r (env/start-ollama-daemon!)]
    (on-status (if (:ok? r) :daemon-ok :daemon-failed) {:detail (:detail r)})
    r))

(defn start-apple-fm-daemon!
  "Side effect: start the apfel server and poll. Returns env helper's result."
  [_action cbs]
  (let [{:keys [on-status]} (merge-callbacks cbs)
        _ (on-status :apple-fm-starting)
        r (env/start-apple-fm-daemon!)]
    (on-status (if (:ok? r) :apple-fm-ok :apple-fm-failed) {:detail (:detail r)})
    r))

(defn pull-model!
  "Side effect: pull a model, streaming progress through the callback."
  [{:keys [model]} cbs]
  (let [{:keys [on-status on-pull-progress]} (merge-callbacks cbs)
        _ (on-status :pull-starting {:model model})
        r (env/pull-ollama-model! model on-pull-progress)]
    (on-status (if (:ok? r) :pull-ok :pull-failed) {:detail (:detail r)})
    r))

(defn smoke-test!
  "Side effect: smoke-test the chosen provider+model."
  [{:keys [provider model timeout-ms]} cbs]
  (let [{:keys [on-status]} (merge-callbacks cbs)
        _ (on-status :smoke-starting {:provider provider :model model})
        r (smoke/smoke-test! provider model (or timeout-ms 30000))]
    (on-status (if (:ok? r) :smoke-ok :smoke-failed)
               {:latency-ms (:latency-ms r) :error (:error r)})
    r))

(defn execute-action!
  "Dispatch one action from bootstrap/plan-actions."
  [action cbs]
  (case (:action action)
    :install-ollama        (install-ollama! action cbs)
    :start-daemon          (start-daemon! action cbs)
    :start-apple-fm-daemon (start-apple-fm-daemon! action cbs)
    :pull-model            (pull-model! action cbs)
    :smoke-test            (smoke-test! action cbs)
    {:ok? true}))

;; ============================================================================
;; Synthesize a "chosen" map from {:rung :provider :model} for re-run-rung
;; ============================================================================

(defn- requires-interactivity?
  "Rung (e) on a clean machine needs install AND pull confirmation. Programmatic
   callers (config-agent without --auto) cannot do this safely."
  [chosen detection]
  (let [{:keys [install? pull? rung]} chosen
        {:keys [installed?]} (:ollama-install detection)]
    (and (= :e rung)
         (or (and install? (not installed?))
             pull?))))

(defn- synthesize-chosen
  "Build a `chosen` map for a forced rung. For rungs (a/b/c/d/f) this is a
   pure projection of detection. For rung (e) we need at minimum provider+model;
   :install? / :pull? are derived from detection state."
  [rung detection {:keys [provider model]}]
  (let [{:keys [installed?]} (:ollama-install detection)]
    (case rung
      :a (let [;; rung (a) is the existing-LLM no-op; provider/model come from caller
               p provider
               m model]
           {:rung :a :provider p :model m :install? false :pull? false
            :reason (str "Forced rung (a): use existing " (some-> p name) "/" m)})
      :b (let [p (or provider
                     (->> (:providers detection)
                          (filter #(and (= :api-key (:method %)) (:available? %)))
                          (sort-by #(or (some (fn [[i x]] (when (= x (:provider %)) i))
                                              (map-indexed vector env/provider-priority))
                                        (count env/provider-priority)))
                          first
                          :provider))
               m (or model (boot/default-model p))]
           {:rung :b :provider p :model m :install? false :pull? false
            :reason (str "Forced rung (b): " (name p))})
      :c {:rung :c :provider :claude-code :model (or model (boot/default-model :claude-code))
          :install? false :pull? false :reason "Forced rung (c)"}
      :d {:rung :d :provider :ollama
          :model (or model (first (:pulled-models (:ollama-install detection))))
          :install? false :pull? false :reason "Forced rung (d)"}
      :e {:rung :e :provider :ollama
          :model (or model (env/recommended-ollama-model))
          :install? (not installed?) :pull? true
          :reason "Forced rung (e)"}
      :f {:rung :f :provider :apple-fm :model (or model (boot/default-model :apple-fm))
          :install? false :pull? false :reason "Forced rung (f)"}
      (throw (ex-info (str "Cannot force rung " rung) {:rung rung})))))

(defn re-run-rung!
  "Programmatic re-run of a chosen ladder rung. Used by config-agent's
   bootstrap$re-run-rung command.

   Returns {:ok? :chosen :result :requires-interactivity? :detection}.

   When `:auto? false` AND the rung requires user prompts (e.g. rung (e) on
   a clean machine), refuses with `:requires-interactivity? true`.

   `callbacks` is the same callback map executors accept; defaults are silent."
  [{:keys [rung provider model auto?]
    :or {auto? false}}
   {:keys [callbacks]
    :or   {callbacks {}}}]
  (if (= rung :g)
    {:ok? false :reason "Rung (g) is the stop sentinel — not a rerun target."}
    (let [detection (env/detect-all)
          chosen    (synthesize-chosen rung detection
                                       {:provider provider :model model})]
      (cond
        (and (not auto?) (requires-interactivity? chosen detection))
        {:ok? false
         :requires-interactivity? true
         :chosen chosen
         :detection detection
         :reason "Rung (e) needs install/pull confirmation. Pass :auto? true to allow."}

        :else
        (let [actions (boot/plan-actions chosen detection)
              results (reduce
                       (fn [acc action]
                         (let [r (execute-action! action callbacks)]
                           (conj acc {:action (:action action)
                                      :ok?    (:ok? r true)
                                      :detail (:detail r)
                                      :raw    r})))
                       []
                       actions)
              all-ok?   (every? :ok? results)
              smoke     (some #(when (= :smoke-test (:action %)) (:raw %)) results)
              installed (when (some #(and (= :install-ollama (:action %))
                                          (:ok? %)) results)
                          [:ollama])
              pulled    (when-let [m (some #(when (and (= :pull-model (:action %))
                                                       (:ok? %))
                                              (-> % :raw :model)) results)]
                          [m])
              result    {:actions    results
                         :smoke-test (when smoke
                                       (select-keys smoke
                                                    [:ok? :latency-ms :error :ts]))
                         :installed  (or installed [])
                         :pulled     (or pulled [])}
              ;; On success, persist the new :llm + :bootstrap blocks. This
              ;; is the whole point of a programmatic rerun: rung (c) etc.
              ;; must actually update config.edn, otherwise the user's
              ;; "switch me to claude" request silently no-ops.
              {:keys [config-path delta-config]}
              (when all-ok?
                (let [dirs     (core-config/init-dirs!)
                      existing (core-config/read-edn-config dirs)
                      now      (str (java.time.Instant/now))
                      delta    (boot/merge-delta existing detection chosen result)
                      delta    (-> delta
                                   (assoc :updated-at now)
                                   (update :created-at #(or % now)))
                      path     (core-config/write-edn-config! dirs delta)]
                  {:config-path path :delta-config delta}))]
          (cond-> {:ok?       all-ok?
                   :chosen    chosen
                   :detection detection
                   :result    result}
            config-path  (assoc :config-path  config-path
                                :delta-config delta-config)))))))
