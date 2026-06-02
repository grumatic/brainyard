;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.bootstrap
  "Pure decision functions for the `bb tui config` bootstrap ladder.

   The ladder (see docs/design/bootstrapping-design.md §5) is:
     (a) existing config with a reachable :default-provider — no-op
     (b) API-key provider is :available?                    — interactive picker
     (c) `claude` CLI on PATH                               — claude-code provider
     (d) Ollama daemon reachable AND has pulled models      — :ollama with that model
     (e) install Ollama + pull free GLM model               — fallback that recovers
     (f) Apple Foundation Models server reachable           — :apple-fm
     (g) nothing else works                                 — stop with instructions

   Nothing in this namespace performs I/O. The interactive shell wires the
   chosen rung to actual install/start/pull/write side effects."
  (:require [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.env-detect.interface :as env]
            [clojure.string :as str]))

;; ============================================================================
;; Provider model suggestions — sourced from clj-llm's curated popular-models
;; catalog (same registry that powers `bb tui models`) so wizard and listing
;; agree. We trim Bedrock from the wizard suggestions because it needs AWS
;; creds and a region selection that doesn't fit a one-line picker.
;; ============================================================================

(defn- model-entry->suggestion [{:keys [model description]}]
  {:label model :value model :description description})

(def ^:private bootstrap-skipped-providers
  "Providers that get a default-model but no interactive picker — they need
   extra setup the wizard doesn't drive (AWS creds, region, etc.)."
  #{:bedrock :anthropic-max})

(defn provider-model-suggestions
  "Map of provider → vec of {:label :value :description} for the bootstrap
   wizard's pickers. Derived live from clj-llm/get-popular-models so newly
   added models become selectable without editing the wizard."
  []
  (->> (clj-llm/get-popular-models)
       (remove #(contains? bootstrap-skipped-providers (:provider %)))
       (group-by :provider)
       (reduce-kv (fn [m provider entries]
                    (assoc m provider (mapv model-entry->suggestion entries)))
                  {})))

(defn default-model
  "Default model for a provider, used when --auto skips the interactive picker."
  [provider]
  (some-> (get (provider-model-suggestions) provider) first :value))

;; ============================================================================
;; Profiles (§9)
;; ============================================================================

(def default-profile
  {:name                    :dev
   :allowed-rungs           #{:a :b :c :d :e :f :g}
   :install-allowed?        true
   :pull-allowed?           true
   :ollama-model-preference :local
   :smoke-test-timeout-ms   45000})

(def builtin-profiles
  {:dev     {:name :dev
             :description "Developer laptop, fast network, expects local model."
             :allowed-rungs #{:a :b :c :d :e :f :g}
             :install-allowed? true
             :pull-allowed?    true
             :ollama-model-preference :local}
   :ci      {:name :ci
             :description "CI: API keys only, never install or pull."
             :allowed-rungs #{:a :b :c :g}
             :install-allowed? false
             :pull-allowed?    false
             :ollama-model-preference :local}
   :offline {:name :offline
             :description "Air-gapped: use what's already there, never reach out."
             :allowed-rungs #{:a :b :c :d :g}
             :install-allowed? false
             :pull-allowed?    false
             :ollama-model-preference :local}
   :cloud   {:name :cloud
             :description "Disk-constrained, ok with cloud calls."
             :allowed-rungs #{:a :b :c :e :g}
             :install-allowed? true
             :pull-allowed?    true
             :ollama-model-preference :cloud}})

(defn resolve-profile
  "Merge the named profile onto `default-profile`. nil → :dev."
  [profile-name]
  (let [k (or profile-name :dev)
        prof (get builtin-profiles k)]
    (when-not prof
      (throw (ex-info (str "Unknown profile: " k)
                      {:profile k :known (sort (keys builtin-profiles))})))
    (merge default-profile prof)))

;; ============================================================================
;; Per-rung predicates
;; ============================================================================

(defn- find-provider [providers kw]
  (some #(when (= kw (:provider %)) %) providers))

(defn- allowed? [profile rung]
  (contains? (:allowed-rungs profile) rung))

(defn- priority-index [provider]
  (or (some (fn [[i p]] (when (= p provider) i))
            (map-indexed vector env/provider-priority))
      (count env/provider-priority)))

(defn- chat-capable?
  "Heuristic: filter out obvious embedding/code-only models from `ollama list`."
  [model-name]
  (let [m (str/lower-case (or model-name ""))]
    (not (some #(str/includes? m %) ["embed" "embedding" "reranker"]))))

(defn applies-a?
  "Rung (a): existing :default-provider in config is reachable in detection."
  [detection existing-config profile]
  (when (allowed? profile :a)
    (let [provider (get-in existing-config [:llm :default-provider])
          model    (get-in existing-config [:llm :default-model])
          entry    (when provider (find-provider (:providers detection) provider))]
      (when (and provider model (:available? entry))
        {:rung     :a
         :provider provider
         :model    model
         :install? false
         :pull?    false
         :reason   (str "Existing LLM " (name provider) "/" model " is reachable.")}))))

(defn applies-b?
  "Rung (b): at least one API-key provider is reachable. Picks by priority."
  [detection _existing-config profile]
  (when (allowed? profile :b)
    (let [available (->> (:providers detection)
                         (filter #(and (= :api-key (:method %)) (:available? %))))
          chosen    (first (sort-by #(priority-index (:provider %)) available))]
      (when chosen
        {:rung                :b
         :provider            (:provider chosen)
         :model               (default-model (:provider chosen))
         :install?            false
         :pull?               false
         :available-providers (mapv :provider available)
         :reason              (str "API key for " (name (:provider chosen)) " detected.")}))))

(defn applies-c?
  "Rung (c): `claude` CLI is on PATH."
  [detection _existing-config profile]
  (when (allowed? profile :c)
    (let [claude (find-provider (:providers detection) :claude-code)]
      (when (:available? claude)
        {:rung     :c
         :provider :claude-code
         :model    (default-model :claude-code)
         :install? false
         :pull?    false
         :reason   "claude CLI is on PATH."}))))

(defn applies-d?
  "Rung (d): Ollama daemon reachable AND has at least one chat-capable model."
  [detection _existing-config profile]
  (when (allowed? profile :d)
    (let [{:keys [daemon-running? pulled-models]} (:ollama-install detection)
          chat (filter chat-capable? (or pulled-models []))]
      (when (and daemon-running? (seq chat))
        {:rung     :d
         :provider :ollama
         :model    (first chat)
         :install? false
         :pull?    false
         :reason   (str "Ollama daemon is running with " (count chat) " chat model(s).")}))))

(defn- can-pull?
  "Rung (e) requires either: network egress to ollama.com (for `glm-5:cloud`)
   OR egress to huggingface.co (for the local pull). If neither, skip."
  [detection profile]
  (let [{:keys [huggingface? ollama?]} (:network detection)
        pref (:ollama-model-preference profile)]
    (and (:pull-allowed? profile)
         (case pref
           :cloud ollama?
           :local (or huggingface? ollama?)
           (or huggingface? ollama?)))))

(defn applies-e?
  "Rung (e): install Ollama (if missing) and pull a free model. The interactive
   shell still asks the user; this predicate only says we _can_ offer it."
  [detection _existing-config profile]
  (when (allowed? profile :e)
    (let [{:keys [installed?]} (:ollama-install detection)
          pref (:ollama-model-preference profile)
          model (if (= :cloud pref)
                  (env/cloud-ollama-model)
                  (env/recommended-ollama-model))]
      (cond
        ;; Need install but profile forbids it
        (and (not installed?) (not (:install-allowed? profile)))
        nil

        ;; Need to pull but profile forbids
        (not (can-pull? detection profile))
        nil

        :else
        {:rung     :e
         :provider :ollama
         :model    model
         :install? (not installed?)
         :pull?    true
         :reason   (if installed?
                     (str "Ollama installed; offer to pull " model ".")
                     (str "Offer to install Ollama and pull " model "."))}))))

(defn applies-f?
  "Rung (f): Apple Foundation Models. Two sub-cases:
     (1) server already reachable                — :start? false
     (2) `apfel` binary on PATH but not serving  — :start? true (offers to launch)
   The wizard's install profile gate (`:install-allowed?`) doubles as the
   start gate; offline/ci profiles disallow starting external daemons."
  [detection _existing-config profile]
  (when (allowed? profile :f)
    (let [apple (find-provider (:providers detection) :apple-fm)]
      (cond
        (and (:available? apple) (= :network (:method apple)))
        {:rung     :f
         :provider :apple-fm
         :model    (default-model :apple-fm)
         :install? false
         :pull?    false
         :start?   false
         :reason   "Apple FM server reachable."}

        (and (:installed? apple) (:install-allowed? profile))
        {:rung     :f
         :provider :apple-fm
         :model    (default-model :apple-fm)
         :install? false
         :pull?    false
         :start?   true
         :reason   (str "apfel at " (:binary-path apple)
                        "; offer to start --serve.")}))))

(defn applies-g?
  "Rung (g): stop with instructions. Always applies last."
  [_detection _existing-config _profile]
  {:rung      :g
   :provider  nil
   :model     nil
   :install?  false
   :pull?     false
   :reason    "No reachable LLM and nothing in the ladder we can offer."})

(def rung-fns
  "In ladder order. choose-rung walks until the first match."
  [#'applies-a? #'applies-b? #'applies-c? #'applies-d?
   #'applies-e? #'applies-f? #'applies-g?])

(defn choose-rung
  "Walk the ladder and return the first rung that applies.
   Always returns a non-nil map (rung (g) is the unconditional fallback)."
  [detection existing-config profile]
  (let [prof (if (keyword? profile) (resolve-profile profile) (or profile default-profile))]
    (some #(% detection existing-config prof) rung-fns)))

;; ============================================================================
;; plan-actions: convert a rung selection into an ordered action list
;; ============================================================================

(defn plan-actions
  "Return the ordered list of side-effect actions the interactive shell must
   perform for `chosen`. Pure — emits data only."
  [chosen detection]
  (let [{:keys [rung provider model install? pull? start?]} chosen
        {:keys [installed? daemon-running?]} (:ollama-install detection)
        os-info (:os detection)]
    (cond-> []
      ;; No-op rung (a)
      (= rung :a)
      (conj {:action :smoke-test :provider provider :model model :timeout-ms 30000})

      ;; Rung (b) or (c): pick provider/model, smoke-test, write
      (contains? #{:b :c} rung)
      (conj {:action :smoke-test :provider provider :model model :timeout-ms 30000})

      ;; Rung (d): smoke-test against the already-pulled model
      (= rung :d)
      (conj {:action :smoke-test :provider provider :model model :timeout-ms 45000})

      ;; Rung (e): full install/start/pull pipeline
      (and (= rung :e) install? (not installed?))
      (conj {:action  :install-ollama
             :os      os-info
             :hints   (env/ollama-install-instructions os-info)})

      (and (= rung :e) (not daemon-running?))
      (conj {:action :start-daemon})

      (and (= rung :e) pull?)
      (conj {:action :pull-model :model model})

      (= rung :e)
      (conj {:action :smoke-test :provider provider :model model :timeout-ms 45000})

      ;; Rung (f): start apfel if needed, then smoke-test
      (and (= rung :f) start?)
      (conj {:action :start-apple-fm-daemon})

      (= rung :f)
      (conj {:action :smoke-test :provider provider :model model :timeout-ms 30000}))))

;; Persistence is not an action — `config_wizard/run!` always calls
;; `write-edn-config!` after `merge-delta`, which differentiates the
;; rung-(g) stub case via the `:incomplete?` branch. The earlier
;; `:write-config` / `:write-stub-config` plan markers were dead code
;; (no executor arm, filtered out by both runners); dropped May 2026.

;; ============================================================================
;; merge-delta: apply the rung outcome onto an existing config
;; ============================================================================

(defn- now-iso []
  (.toString (java.time.Instant/now)))

(defn- available-providers-of [detection]
  (->> (:providers detection)
       (filter :available?)
       (mapv :provider)))

(defn merge-delta
  "Produce the post-bootstrap config map. Existing keys are preserved; only
   :llm, :bootstrap, :version, :updated-at, :created-at are touched here
   (steps 2–6 still own :environment / :permissions / :agent / :mcp).
   `chosen`   — output of choose-rung
   `result`   — {:installed [kw] :pulled [str] :smoke-test {:ok? :latency-ms ...}}"
  [existing-config detection chosen result]
  (let [{:keys [rung provider model]} chosen
        now (now-iso)
        incomplete? (= rung :g)
        llm (if incomplete?
              (or (:llm existing-config) {:default-provider nil :default-model nil})
              {:default-provider    provider
               :default-model       model
               :available-providers (available-providers-of detection)})
        bootstrap {:rung       rung
                   :installed  (vec (:installed result []))
                   :pulled     (vec (:pulled result []))
                   :smoke-test (:smoke-test result)
                   :next-steps (vec (:next-steps result []))
                   :incomplete incomplete?}]
    (-> existing-config
        (assoc :version 1)
        (update :created-at #(or % now))
        (assoc :updated-at now)
        (assoc :llm llm)
        (assoc :bootstrap bootstrap))))

;; ============================================================================
;; bootstrap-log-entry: a single row in ~/.brainyard/bootstrap-log.edn
;; ============================================================================

(defn- condense-detection [detection]
  {:providers      (mapv #(select-keys % [:provider :available? :method])
                         (:providers detection))
   :ollama-install (select-keys (:ollama-install detection)
                                [:installed? :daemon-running? :pulled-models])
   :network        (:network detection)
   :os             (:os detection)})

(defn bootstrap-log-entry
  "Build one entry for ~/.brainyard/bootstrap-log.edn."
  [detection chosen result]
  {:ts          (now-iso)
   :duration-ms (:duration-ms result)
   :detection   (condense-detection detection)
   :chosen      (select-keys chosen [:rung :provider :model :install? :pull? :reason])
   :actions     (:actions result [])
   :outcome     (if (:ok? result) :success :failure)})

(defn rotate-log
  "Keep the last 5 entries. `old` is the previous file contents or {}."
  [old new-entry]
  {:version 1
   :entries (vec (take 5 (cons new-entry (:entries old []))))})
