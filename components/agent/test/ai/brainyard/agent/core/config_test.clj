;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.config-test
  "Unit tests for the unified config API in core.config — `get-config`,
   `set-config!`, `!global-config` cache, and the deprecated alias."
  (:require [ai.brainyard.agent.core.config :as cfg]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *tmp-home* nil)
(def ^:dynamic *tmp-project* nil)
(def ^:dynamic *saved-home* nil)
(def ^:dynamic *saved-proj* nil)

(defn- rm-rf [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-rf c)))
    (.delete f)))

(defn with-tmp-dirs [t]
  (let [home (str "/tmp/by-core-config-test-home-" (System/nanoTime))
        proj (str "/tmp/by-core-config-test-proj-" (System/nanoTime))
        saved-home (System/getProperty "user.home")
        saved-proj (System/getenv "BY_PROJECT_DIR")]
    (.mkdirs (io/file home))
    (.mkdirs (io/file proj))
    (System/setProperty "user.home" home)
    ;; Force project-dir resolution to our temp dir even though it's not a git root.
    (binding [*tmp-home* home
              *tmp-project* proj
              *saved-home* saved-home
              *saved-proj* saved-proj]
      (try
        ;; Reset cache + working-dir override before each test (the override is
        ;; a process-global atom — clear it so tests don't leak into each other).
        (cfg/invalidate-global-config!)
        (cfg/set-working-dir-override! nil)
        (with-redefs [cfg/resolve-dirs
                      (fn [] {:user-dir    home
                              :project-dir proj
                              :working-dir proj})]
          (t))
        (finally
          (cfg/set-working-dir-override! nil)
          (rm-rf (io/file home))
          (rm-rf (io/file proj))
          (when saved-home (System/setProperty "user.home" saved-home))
          (cfg/invalidate-global-config!))))))

(use-fixtures :each with-tmp-dirs)

(defn- seed-project-config! [m]
  (let [d (io/file *tmp-project* ".brainyard")]
    (.mkdirs d)
    (spit (io/file d "config.edn") (pr-str m))))

;; ============================================================================
;; default-config / deprecated alias
;; ============================================================================

(deftest default-config-derived-from-schema
  (testing "every schema key declares at least one resolution source (:default, :default-fn, or :env-fn)"
    (doseq [k cfg/config-keys
            :let [entry (get cfg/config-schema k)]]
      (is (or (contains? entry :default)
              (contains? entry :default-fn)
              (contains? entry :env-fn))
          (str "schema entry " k " has no :default / :default-fn / :env-fn"))))
  (testing ":default-fn-only entries are excluded from default-config"
    (doseq [k cfg/config-keys
            :let [entry (get cfg/config-schema k)]]
      (if (contains? entry :default)
        (is (contains? cfg/default-config k)
            (str "missing default-config entry for " k))
        (is (not (contains? cfg/default-config k))
            (str ":default-fn-only entry leaked into default-config: " k))))))

(deftest every-schema-key-has-doc
  (testing "every config-schema entry declares a non-blank :doc (backs agent-runtime$config :query search + result descriptions)"
    (doseq [k cfg/config-keys
            :let [doc (:doc (get cfg/config-schema k))]]
      (is (and (string? doc) (not (str/blank? doc)))
          (str "schema entry " k " has no :doc")))))

;; ============================================================================
;; search-config-keys (agent-runtime$config :query mode)
;; ============================================================================

(deftest search-config-keys-matches-key-name
  (let [hits (cfg/search-config-keys nil "oauth")
        keys (set (map :key hits))]
    (is (contains? keys "oauth-flow"))
    (is (contains? keys "oauth-token-store"))
    (testing "every hit carries the resolved value + metadata"
      (doseq [h hits]
        (is (string? (:key h)))
        (is (contains? h :value))
        (is (string? (:doc h)))))))

(deftest search-config-keys-matches-doc-text
  (testing "concept search hits keys whose :doc (not name) contains the term"
    (let [keys (set (map :key (cfg/search-config-keys nil "truncation")))]
      ;; max-output-chars has no 'truncation' in its name — only its :doc
      (is (contains? keys "max-output-chars")))))

(deftest search-config-keys-is-case-insensitive
  (is (= (cfg/search-config-keys nil "OAuth")
         (cfg/search-config-keys nil "oauth"))))

(deftest search-config-keys-blank-query-returns-empty
  (is (= [] (cfg/search-config-keys nil "")))
  (is (= [] (cfg/search-config-keys nil "   ")))
  (is (= [] (cfg/search-config-keys nil nil))))

(deftest search-config-keys-sorted-by-key
  (let [hits (cfg/search-config-keys nil "memory")]
    (is (= (map :key hits) (sort (map :key hits))))))

(deftest search-config-keys-marks-read-only
  (let [by-key (into {} (map (juxt :key identity)) (cfg/search-config-keys nil "dirs"))]
    (is (true? (:read-only (get by-key "dirs"))))
    (testing "non-read-only keys omit the flag"
      (let [oauth (first (cfg/search-config-keys nil "oauth-flow"))]
        (is (not (contains? oauth :read-only)))))))

(deftest search-config-keys-redacts-secret-value
  (testing "a sensitive key's value is masked in search results"
    (with-redefs [cfg/get-config (fn [_ _] "sk-supersecret")]
      (let [hit (first (cfg/search-config-keys nil "tavily"))]
        (is (= "***redacted***" (:value hit)))))))

;; ============================================================================
;; read-only keys + redaction
;; ============================================================================

(deftest read-only-keys-derived-from-schema
  (is (= #{:dirs :lm-config} cfg/read-only-keys))
  (is (cfg/read-only-key? :dirs))
  (is (cfg/read-only-key? :lm-config))
  (is (not (cfg/read-only-key? :max-iterations))))

(deftest restart-required-keys-derived-from-schema
  (testing "the startup-baked memory/graph keys are flagged :requires-restart"
    (is (= #{:enable-graph-memory :graph-embed-model :graph-extract-model
             :enable-memory-capture :memory-question-max-chars :memory-answer-max-chars}
           cfg/restart-required-keys))
    (is (cfg/requires-restart-key? :enable-graph-memory))
    (is (cfg/requires-restart-key? :graph-embed-model))
    (testing "live-read keys are NOT flagged"
      (is (not (cfg/requires-restart-key? :enable-memory-consolidation)))
      (is (not (cfg/requires-restart-key? :enable-mid-turn-recall)))
      (is (not (cfg/requires-restart-key? :max-iterations))))))

(deftest search-config-keys-marks-requires-restart
  (let [by-key (into {} (map (juxt :key identity)) (cfg/search-config-keys nil "graph-extract"))]
    (is (true? (:requires-restart (get by-key "graph-extract-model"))))
    (testing "a live key omits the flag"
      (let [hit (first (cfg/search-config-keys nil "enable-mid-turn-recall"))]
        (is (not (contains? hit :requires-restart)))))))

(deftest redact-config-value-masks-secrets
  (is (= "***redacted***" (cfg/redact-config-value :tavily-api-key "sk-abc")))
  (testing "unset sensitive key stays nil"
    (is (nil? (cfg/redact-config-value :tavily-api-key nil))))
  (testing "api-key leaf inside a map is masked"
    (is (= {:provider :bedrock :api-key "***redacted***"}
           (cfg/redact-config-value :lm-config {:provider :bedrock :api-key "z"}))))
  (testing "ordinary values pass through"
    (is (= 7 (cfg/redact-config-value :max-iterations 7)))))

(deftest redact-config-snapshot-masks-across-map
  (let [m (cfg/redact-config-snapshot {:tavily-api-key "sk-1" :max-iterations 7})]
    (is (= "***redacted***" (:tavily-api-key m)))
    (is (= 7 (:max-iterations m)))))

;; ============================================================================
;; config-overview (agent-runtime$config no-arg curated read)
;; ============================================================================

(deftest config-overview-shows-only-non-default-overrides
  (seed-project-config! {:agent {:config {:show-llm-streaming true
                                          :max-context-tokens 8192}}})
  (cfg/invalidate-global-config!)
  (let [ov (cfg/config-overview nil)]
    (is (= (count cfg/config-keys) (:total ov)))
    (testing "overridden keys appear with their effective value"
      (is (= true (get-in ov [:overrides :show-llm-streaming])))
      (is (= 8192 (get-in ov [:overrides :max-context-tokens]))))
    (testing "untouched defaults are omitted"
      (is (not (contains? (:overrides ov) :max-iterations)))
      (is (not (contains? (:overrides ov) :permission-mode))))
    (testing "hint explains how to drill in"
      (is (string? (:hint ov)))
      (is (str/includes? (:hint ov) ":query"))
      (is (str/includes? (:hint ov) ":all true")))))

(deftest config-overview-redacts-secret-overrides
  (seed-project-config! {:agent {:config {:tavily-api-key "sk-leak"}}})
  (cfg/invalidate-global-config!)
  (let [ov (cfg/config-overview nil)]
    (is (= "***redacted***" (get-in ov [:overrides :tavily-api-key])))))

(deftest config-overview-omits-untouched-defaults
  (seed-project-config! {:agent {:config {}}})
  (cfg/invalidate-global-config!)
  (let [ov (cfg/config-overview nil)]
    (is (= (count cfg/config-keys) (:total ov)))
    (testing "purely default-sourced keys (no env-fn, not seeded) never appear as overrides"
      ;; robust to ambient BY_* env vars, which can still overlay via env-overlay
      (is (not (contains? (:overrides ov) :max-iterations)))
      (is (not (contains? (:overrides ov) :conversation-limit)))
      (is (not (contains? (:overrides ov) :recall-limit))))))

(deftest analytics-lm-config-is-string-typed
  (is (= "string" (:type (get cfg/config-schema :analytics-lm-config))))
  (testing "settable via the string coercion path (no JSON needed)"
    (is (= "bedrock:amazon.nova-lite-v1:0"
           (cfg/coerce-config-value :analytics-lm-config "bedrock:amazon.nova-lite-v1:0")))))

(deftest resolve-analytics-lm-falls-back-to-main-lm
  (testing "blank/nil analytics label → main :lm-config"
    (with-redefs [cfg/get-config (fn [_ k] (case k
                                             :lm-config {:provider :bedrock :model "main"}
                                             :analytics-lm-config nil))]
      (is (= {:provider :bedrock :model "main"} (cfg/resolve-analytics-lm :stub))))))

;; ============================================================================
;; load-global-config! / cache invalidation
;; ============================================================================

(deftest load-global-config-merges-persisted-over-defaults
  (seed-project-config! {:agent {:config {:max-iterations 7
                                          :enable-context-budget false}}})
  (let [m (cfg/load-global-config!)]
    (is (= 7 (:max-iterations m)))
    (is (false? (:enable-context-budget m)))
    (testing "non-overridden keys retain schema defaults"
      (is (= (:max-output-tokens cfg/default-config) (:max-output-tokens m))))))

(deftest load-global-config-ignores-unknown-keys
  (seed-project-config! {:agent {:config {:not-a-real-key "garbage"
                                          :max-iterations 42}}})
  (let [m (cfg/load-global-config!)]
    (is (= 42 (:max-iterations m)))
    (is (not (contains? m :not-a-real-key)))))

(deftest invalidate-clears-cache
  (seed-project-config! {:agent {:config {:max-iterations 7}}})
  (cfg/load-global-config!)
  (is (some? @cfg/!global-config))
  (cfg/invalidate-global-config!)
  (is (nil? @cfg/!global-config)))

;; ============================================================================
;; get-config — precedence chain
;; ============================================================================

(deftest get-config-1-arity-falls-back-to-default
  (is (= (:max-iterations cfg/default-config)
         (cfg/get-config :max-iterations))))

(deftest get-config-1-arity-reads-persisted
  (seed-project-config! {:agent {:config {:max-iterations 13}}})
  (cfg/invalidate-global-config!)
  (is (= 13 (cfg/get-config :max-iterations))))

(defn- fake-agent
  "Build a minimal agent-shaped value: an Agent-like record exposing :!state
   with the {:st-memory-init <atom>} shape `agent-config` looks for."
  [override-map]
  (let [smi (atom {:config override-map})]
    (reify
      clojure.lang.ILookup
      (valAt [_ k] (when (= k :!state) (atom {:st-memory-init smi}))))))

(defn- fake-agent-with-session
  "Like `fake-agent` but also exposes `:!session` so the session-config
   layer is exercised. Both maps are optional — pass `nil` to omit one."
  [override-map session-cfg]
  (let [smi      (atom {:config (or override-map {})})
        !state   (atom {:st-memory-init smi})
        !session (atom (cond-> {} session-cfg (assoc :config session-cfg)))]
    (reify
      clojure.lang.ILookup
      (valAt [_ k]
        (case k
          :!state   !state
          :!session !session
          nil)))))

(deftest get-config-with-agent-overlays-override
  (seed-project-config! {:agent {:config {:max-iterations 13}}})
  (cfg/invalidate-global-config!)
  (let [ag (fake-agent {:max-iterations 21})]
    (is (= 21 (cfg/get-config ag :max-iterations)))))

(deftest get-config-with-agent-falls-through-when-override-missing
  (seed-project-config! {:agent {:config {:max-iterations 13}}})
  (cfg/invalidate-global-config!)
  (let [ag (fake-agent {:max-refinements 99})]
    (is (= 13 (cfg/get-config ag :max-iterations)))
    (is (= 99 (cfg/get-config ag :max-refinements)))))

(deftest get-config-honors-boolean-false-override
  ;; The bug `(or override (global))` would mask false. Verify we use
  ;; `contains?` to honor explicit false.
  (cfg/invalidate-global-config!)
  (let [ag (fake-agent {:enable-subagent-calls false})]
    (is (false? (cfg/get-config ag :enable-subagent-calls)))
    (testing "global default is true so the override matters"
      (is (true? (cfg/get-config :enable-subagent-calls))))))

(deftest get-config-with-nil-agent-is-global-only
  (is (= (cfg/get-config :max-iterations)
         (cfg/get-config nil :max-iterations))))

;; ============================================================================
;; get-config — ENV layer (highest precedence)
;; ============================================================================
;; A set env var is simulated by redefining `schema-env-value` (the real env
;; reads go through it), so these tests don't depend on the JVM's actual
;; environment.

(deftest env-overrides-global-and-agent
  (seed-project-config! {:agent {:config {:nrepl-enabled? false}}})
  (cfg/invalidate-global-config!)
  (with-redefs [cfg/schema-env-value (fn [k] (if (= k :nrepl-enabled?) true cfg/env-unset))]
    (is (true? (cfg/get-config :nrepl-enabled?))
        "env beats config.edn (1-arity)")
    (let [ag (fake-agent {:nrepl-enabled? false})]
      (is (true? (cfg/get-config ag :nrepl-enabled?))
          "env beats the per-agent override"))))

(deftest env-false-beats-persisted-true
  ;; The sentinel distinguishes a real env value of `false` from 'unset', so a
  ;; falsey env value still overrides a persisted `true`.
  (seed-project-config! {:agent {:config {:ask-channel-enabled? true}}})
  (cfg/invalidate-global-config!)
  (with-redefs [cfg/schema-env-value (fn [k] (if (= k :ask-channel-enabled?) false cfg/env-unset))]
    (is (false? (cfg/get-config :ask-channel-enabled?)))))

(deftest env-unset-falls-through-to-lower-layers
  (seed-project-config! {:agent {:config {:nrepl-enabled? true}}})
  (cfg/invalidate-global-config!)
  (with-redefs [cfg/schema-env-value (constantly cfg/env-unset)]
    (is (true? (cfg/get-config :nrepl-enabled?))
        "unset env → config.edn wins")
    (let [ag (fake-agent {:nrepl-enabled? false})]
      (is (false? (cfg/get-config ag :nrepl-enabled?))
          "unset env → per-agent override wins"))))

;; ============================================================================
;; get-config-snapshot
;; ============================================================================

(deftest snapshot-no-agent-equals-global
  (seed-project-config! {:agent {:config {:max-iterations 11}}})
  (cfg/invalidate-global-config!)
  (let [snap (cfg/get-config-snapshot)]
    (is (= 11 (:max-iterations snap)))
    (is (contains? snap :max-output-tokens))))

(deftest snapshot-with-agent-overlays
  (seed-project-config! {:agent {:config {:max-iterations 11}}})
  (cfg/invalidate-global-config!)
  (let [ag   (fake-agent {:max-iterations 99 :enable-subagent-calls false})
        snap (cfg/get-config-snapshot ag)]
    (is (= 99 (:max-iterations snap)))
    (is (false? (:enable-subagent-calls snap)))))

(deftest snapshot-overlays-env-on-top
  ;; A set env var overrides global AND the per-agent layer in the snapshot,
  ;; matching `get-config`'s precedence.
  (seed-project-config! {:agent {:config {:nrepl-enabled? false}}})
  (cfg/invalidate-global-config!)
  (with-redefs [cfg/schema-env-value (fn [k] (if (= k :nrepl-enabled?) true cfg/env-unset))]
    (is (true? (:nrepl-enabled? (cfg/get-config-snapshot)))
        "no-agent snapshot reflects the env override")
    (let [ag (fake-agent {:nrepl-enabled? false})]
      (is (true? (:nrepl-enabled? (cfg/get-config-snapshot ag)))
          "agent snapshot still has env on top"))))

;; ============================================================================
;; Session-config layer + :default-fn fallback (D1 + D2)
;; ============================================================================

(deftest get-config-session-fills-between-per-agent-and-global
  (seed-project-config! {:agent {:config {:max-iterations 7}}})
  (cfg/invalidate-global-config!)
  (testing "session value wins over global"
    (let [ag (fake-agent-with-session nil {:max-iterations 13})]
      (is (= 13 (cfg/get-config ag :max-iterations)))))
  (testing "per-agent override wins over session"
    (let [ag (fake-agent-with-session {:max-iterations 21}
                                      {:max-iterations 13})]
      (is (= 21 (cfg/get-config ag :max-iterations)))))
  (testing "session falls through to global when missing"
    (let [ag (fake-agent-with-session nil {:max-refinements 99})]
      (is (= 7 (cfg/get-config ag :max-iterations))))))

(deftest get-config-default-fn-resolves-at-read-time
  (cfg/invalidate-global-config!)
  (testing ":default-fn invoked when no override anywhere"
    (let [calls (atom 0)
          ;; Stand in a :default-fn that increments a counter so we can
          ;; assert it ran lazily (not at schema-build time).
          fake-schema (assoc cfg/config-schema
                             ::probe {:type "string"
                                      :default-fn (fn []
                                                    (swap! calls inc)
                                                    "resolved")})]
      (with-redefs [cfg/config-schema fake-schema]
        (is (= "resolved" (cfg/get-config ::probe)))
        (is (= 1 @calls)
            "fn invoked exactly once for the read"))))
  (testing "global wins over :default-fn"
    (with-redefs [cfg/config-schema
                  (assoc cfg/config-schema
                         ::probe {:type "string"
                                  :default-fn (constantly "from-fn")})]
      (swap! cfg/!global-config assoc ::probe "from-global")
      (is (= "from-global" (cfg/get-config ::probe))))))

;; ============================================================================
;; Agent dirs helpers — working-dir / allowed-dirs / resolve-agent-dirs
;; ============================================================================

(deftest working-dir-returns-default-fn-when-no-override
  (cfg/invalidate-global-config!)
  (let [v (cfg/working-dir)]
    (is (string? v))
    (is (not (clojure.string/blank? v)))))

(deftest working-dir-reads-from-dirs-map
  ;; working-dir is no longer a config key — it reads the resolved `:dirs`
  ;; map (mirroring project-dir), so a per-agent `:dirs` override drives it.
  (cfg/invalidate-global-config!)
  (let [ag (fake-agent {:dirs {:user-dir    "/tmp"
                               :project-dir "/tmp/agent-wd"
                               :working-dir "/tmp/agent-wd"}})]
    (is (= "/tmp/agent-wd" (cfg/working-dir ag)))))

(deftest working-dir-and-dirs-map-agree
  ;; The two surfaces must never diverge: config/working-dir and the
  ;; :working-dir of the resolved :dirs map come from one resolver.
  (cfg/invalidate-global-config!)
  (is (= (:working-dir (cfg/get-config :dirs)) (cfg/working-dir))))

(deftest resolve-working-dir-honors-override
  (let [d (str "/tmp/by-wd-override-" (System/nanoTime))]
    (.mkdirs (io/file d))
    (try
      (let [installed (cfg/set-working-dir-override! d)]
        (is (= (.getCanonicalPath (io/file d)) installed)
            "setter returns the canonical path")
        (is (= installed (cfg/resolve-working-dir))
            "resolver returns the installed override"))
      (testing "clearing the override falls back to the process cwd"
        (cfg/set-working-dir-override! nil)
        (is (= (System/getProperty "user.dir") (cfg/resolve-working-dir))))
      (finally
        (cfg/set-working-dir-override! nil)
        (rm-rf (io/file d))))))

(deftest set-working-dir-override-rejects-non-directory
  (is (thrown? clojure.lang.ExceptionInfo
               (cfg/set-working-dir-override!
                (str "/tmp/by-wd-does-not-exist-" (System/nanoTime))))
      "a non-existent path on the strict flag throws")
  (cfg/set-working-dir-override! nil))

(deftest allowed-dirs-reads-per-agent-override
  ;; Post-b2c371c: :allowed-dirs is a flat schema key. The user-facing
  ;; nested [:permissions :allowed-dirs] form is bridged onto it ONLY
  ;; during load-global-config! — per-agent / session layers use the
  ;; flat key directly through the unified get-config chain.
  (cfg/invalidate-global-config!)
  (let [ag (fake-agent {:allowed-dirs ["/tmp/x" "/tmp/y"]})]
    (is (= ["/tmp/x" "/tmp/y"] (cfg/allowed-dirs ag)))))

(deftest allowed-dirs-falls-back-to-default
  (cfg/invalidate-global-config!)
  (let [ag (fake-agent {})
        v  (cfg/allowed-dirs ag)]
    (is (vector? v))
    (is (some #(= "/tmp" %) v))))

(deftest resolve-agent-dirs-returns-structured-map
  (cfg/invalidate-global-config!)
  (let [ag (fake-agent {:allowed-dirs ["/tmp"]})
        r  (cfg/resolve-agent-dirs ag)]
    (is (string? (:base-dir r)))
    (is (vector? (:canonical-allowed r)))
    (is (>= (count (:canonical-allowed r)) 1))
    (testing ":permission-fn is nil when session has none"
      (is (nil? (:permission-fn r))))))

(deftest resolve-sub-lm-prefers-sub-lm-config-when-parseable
  (cfg/invalidate-global-config!)
  (let [ag (fake-agent {:lm-config     {:provider "main" :model "m"}
                        :sub-lm-config "claude-code:haiku"})
        sub (cfg/resolve-sub-lm ag)]
    (is (map? sub))
    (is (= :claude-code (:provider sub)))
    (is (= "haiku" (:model sub)))))

(deftest resolve-sub-lm-falls-back-to-main-when-sub-blank
  (cfg/invalidate-global-config!)
  (let [main-lm {:provider "main" :model "m"}
        ag (fake-agent {:lm-config main-lm :sub-lm-config ""})]
    (is (= main-lm (cfg/resolve-sub-lm ag)))))

(deftest resolve-sub-lm-falls-back-to-main-when-sub-nil
  (cfg/invalidate-global-config!)
  (let [main-lm {:provider "main" :model "m"}
        ag (fake-agent {:lm-config main-lm})]
    (is (= main-lm (cfg/resolve-sub-lm ag)))))

(deftest resolve-sub-lm-falls-back-to-main-when-sub-unparseable
  ;; A non-blank sub-lm-config that isn't a strict provider:model pair (e.g.
  ;; a bare model name like "opus") makes clj-llm/parse-lm-str return nil.
  ;; resolve-sub-lm must `or` that against the main LM — returning nil here
  ;; crashes query$llm with "No LM configuration provided".
  (cfg/invalidate-global-config!)
  (let [main-lm {:provider "main" :model "m"}]
    (doseq [bad ["opus" "claude-code/opus" "claude-opus-4-8" "sonnet"]]
      (let [ag (fake-agent {:lm-config main-lm :sub-lm-config bad})]
        (is (= main-lm (cfg/resolve-sub-lm ag))
            (str "unparseable sub-lm-config " (pr-str bad) " should fall back to main"))))))

(deftest set-allowed-dirs!-writes-per-agent-override
  ;; Post-b2c371c: set-allowed-dirs! writes the flat :allowed-dirs schema
  ;; key onto the per-agent override layer (`:st-memory-init :config`)
  ;; so the running agent observes it via get-config immediately.
  (cfg/invalidate-global-config!)
  (let [smi    (atom {:config {:max-iterations 7}})
        !state (atom {:st-memory-init smi})
        ag     (reify clojure.lang.ILookup
                 (valAt [_ k] (when (= k :!state) !state)))
        result (cfg/set-allowed-dirs! ag ["/tmp/foo"])]
    (is (= ["/tmp/foo"] result))
    (is (= ["/tmp/foo"] (get-in @smi [:config :allowed-dirs])))
    (testing "preserves sibling per-agent overrides"
      (is (= 7 (get-in @smi [:config :max-iterations]))))
    (testing "get-config sees the write through the unified chain"
      (is (= ["/tmp/foo"] (cfg/allowed-dirs ag))))))

;; ============================================================================
;; set-config!
;; ============================================================================

(deftest set-config-2-arity-writes-global-and-persists
  (cfg/invalidate-global-config!)
  (cfg/set-config! :max-iterations 42)
  (is (= 42 (cfg/get-config :max-iterations)))
  (testing "value lands in .brainyard/config.edn"
    (let [f (io/file *tmp-project* ".brainyard" "config.edn")
          m (edn/read-string (slurp f))]
      (is (= 42 (get-in m [:agent :config :max-iterations]))))))

(deftest set-config-rejects-unknown-key
  (is (thrown? AssertionError (cfg/set-config! :not-a-real-key 1))))

(deftest set-config-3-arity-updates-agent-and-global
  (cfg/invalidate-global-config!)
  (let [smi (atom {:config {}})
        ag  (reify
              clojure.lang.ILookup
              (valAt [_ k] (when (= k :!state) (atom {:st-memory-init smi}))))]
    (cfg/set-config! ag :max-iterations 77)
    (is (= 77 (cfg/get-config :max-iterations)))
    (is (= 77 (get-in @smi [:config :max-iterations])))
    (is (= 77 (cfg/get-config ag :max-iterations)))))

;; ============================================================================
;; valid-config-value? — schema-type check
;; ============================================================================

(deftest valid-config-value?-matches-schema-types
  (is (cfg/valid-config-value? :max-iterations 42))
  (is (not (cfg/valid-config-value? :max-iterations "forty-two")))
  (is (cfg/valid-config-value? :enable-context-budget true))
  (is (not (cfg/valid-config-value? :enable-context-budget "true")))
  (is (cfg/valid-config-value? :compaction-target-ratio 0.25))
  (is (cfg/valid-config-value? :tool-cache-readers ["tool-a" "tool-b"]))
  (is (not (cfg/valid-config-value? :tool-cache-readers "tool-a")))
  (is (cfg/valid-config-value? :acp-backend-opts {:foo 1}))
  (is (not (cfg/valid-config-value? :acp-backend-opts "not-a-map"))))

(deftest valid-config-value?-keyword-type
  (testing "keyword-typed entries accept keywords only"
    (is (cfg/valid-config-value? :clj-backend :sandbox))
    (is (cfg/valid-config-value? :clj-backend :nrepl))
    (is (cfg/valid-config-value? :acp-backend :stub))
    (is (cfg/valid-config-value? :permission-mode :ask-each-time)))
  (testing "string values rejected for keyword-typed entries"
    (is (not (cfg/valid-config-value? :clj-backend "nrepl")))
    (is (not (cfg/valid-config-value? :acp-backend "stub")))
    (is (not (cfg/valid-config-value? :permission-mode "ask-each-time")))))

(deftest coerce-config-value-keyword-type
  (testing "string → keyword for keyword-typed entries"
    (is (= :nrepl   (cfg/coerce-config-value :clj-backend "nrepl")))
    (is (= :sandbox (cfg/coerce-config-value :clj-backend "sandbox")))
    (is (= :nrepl   (cfg/coerce-config-value :clj-backend ":nrepl"))
        "leading ':' is stripped so callers can pass either form")
    (is (= :stub    (cfg/coerce-config-value :acp-backend "stub")))))

(deftest valid-config-value?-rejects-unknown-keys
  (is (not (cfg/valid-config-value? :not-a-real-key 0))))

;; ============================================================================
;; migrate-legacy-edn-shape — relocates legacy positions
;; ============================================================================

(deftest migrate-legacy-keys-relocated
  (let [{:keys [config changed?]}
        (cfg/migrate-legacy-edn-shape
         {:agent {:max-iterations 30
                  :default-agent :coact-agent
                  :config {:eval-lm-config "claude-code:opus"}}})]
    (is changed?)
    (is (= 30   (get-in config [:agent :config :max-iterations])))
    (is (= "claude-code:opus" (get-in config [:agent :config :eval-lm-config]))
        "existing :agent.config keys are preserved")
    (is (= :coact-agent (get-in config [:agent :default-agent])))
    (is (not (contains? (:agent config) :max-iterations))
        "legacy key is removed from :agent root")))

(deftest migrate-new-shape-is-noop
  (let [in {:agent {:default-agent :coact-agent
                    :config {:max-iterations 30 :enable-context-budget true}}}
        {:keys [config changed?]} (cfg/migrate-legacy-edn-shape in)]
    (is (not changed?))
    (is (= in config))))

(deftest load-global-config-applies-legacy-migration
  (seed-project-config! {:agent {:max-iterations 9}})
  (cfg/invalidate-global-config!)
  (cfg/load-global-config!)
  (is (= 9 (cfg/get-config :max-iterations))))

;; ---------------------------------------------------------------------------
;; Reference-artifact dedup (BRAINYARD.md / CLAUDE.md / AGENTS.md linked)
;; ---------------------------------------------------------------------------

(defn- nio-path [s]
  (java.nio.file.Paths/get s (make-array String 0)))

(deftest reference-artifact-descriptors-dedupes-linked-files
  (let [base (str (System/getProperty "java.io.tmpdir")
                  "/refdedupe-" (System/nanoTime))
        no-attrs (make-array java.nio.file.attribute.FileAttribute 0)]
    (.mkdirs (io/file base))
    (.mkdirs (io/file (str base "/.brainyard")))
    (try
      (spit (str base "/CLAUDE.md") "claude")
      (spit (str base "/.brainyard/BRAINYARD.md") "brainyard")
      ;; AGENTS.md is a symlink to CLAUDE.md (single source); HARD.md is a
      ;; hardlink to CLAUDE.md; CLAUDE2.md is a symlink to BRAINYARD.md.
      (java.nio.file.Files/createSymbolicLink
       (nio-path (str base "/AGENTS.md")) (nio-path (str base "/CLAUDE.md")) no-attrs)
      (java.nio.file.Files/createLink
       (nio-path (str base "/HARD.md")) (nio-path (str base "/CLAUDE.md")))
      (java.nio.file.Files/createSymbolicLink
       (nio-path (str base "/CLAUDE2.md"))
       (nio-path (str base "/.brainyard/BRAINYARD.md")) no-attrs)
      (let [dirs {:project-dir base :working-dir base :user-dir base}]
        (testing "symlinked CLAUDE.md + AGENTS.md collapse to one"
          (is (= 1 (count (cfg/reference-artifact-descriptors
                           dirs ["CLAUDE.md" "AGENTS.md"])))))
        (testing "hardlinked files collapse to one"
          (is (= 1 (count (cfg/reference-artifact-descriptors
                           dirs ["CLAUDE.md" "HARD.md"])))))
        (testing "distinct real files are kept"
          (spit (str base "/OTHER.md") "other")
          (is (= 2 (count (cfg/reference-artifact-descriptors
                           dirs ["CLAUDE.md" "OTHER.md"])))))
        (testing "load-brainyard-instructions exposes file identities"
          (let [bi (cfg/load-brainyard-instructions dirs)]
            (is (= "brainyard" (:project-instructions bi)))
            (is (= 1 (count (:instruction-identities bi))))))
        (testing ":exclude-identities drops a reference doc linked to BRAINYARD.md"
          (let [ids (:instruction-identities (cfg/load-brainyard-instructions dirs))]
            (is (= 0 (count (cfg/reference-artifact-descriptors
                             dirs ["CLAUDE2.md"] :exclude-identities ids))))
            ;; an unrelated doc still passes through
            (is (= 1 (count (cfg/reference-artifact-descriptors
                             dirs ["CLAUDE.md"] :exclude-identities ids)))))))
      (finally (rm-rf (io/file base))))))

;; ============================================================================
;; load-project-memory-index — project-scoped file memory
;; ============================================================================

(deftest load-project-memory-index-reads-index-and-counts-files
  (let [base (str (System/getProperty "java.io.tmpdir")
                  "/projmem-" (System/nanoTime))
        mem  (io/file base ".brainyard" "memory")
        dirs {:project-dir base :working-dir base :user-dir base}]
    (.mkdirs mem)
    (try
      (testing "absent index → nil content, zero files"
        (let [r (cfg/load-project-memory-index dirs)]
          (is (nil? (:content r)))
          (is (= 0 (:file-count r)))))
      (testing "present index → trimmed content; *.md topic files counted (index.md excluded)"
        (spit (io/file mem "index.md") "  # Project Memory\n- [Auth](auth.md) — jwt flow  \n")
        (spit (io/file mem "auth.md") "the auth note")
        (spit (io/file mem "deploy.md") "the deploy note")
        (let [r (cfg/load-project-memory-index dirs)]
          (is (= "# Project Memory\n- [Auth](auth.md) — jwt flow" (:content r)))
          (is (= 2 (:file-count r)))))
      (testing "blank index → nil content"
        (spit (io/file mem "index.md") "   \n  ")
        (is (nil? (:content (cfg/load-project-memory-index dirs)))))
      (finally (rm-rf (io/file base))))))

(deftest load-project-memory-index-nil-project-dir-is-safe
  (is (= {:content nil :file-count 0}
         (cfg/load-project-memory-index {:project-dir nil :user-dir "/tmp"}))))

(deftest memory-subdir-allowed-at-both-scopes
  ;; The SQLite L1/L2/L3 store is user-scoped; the file-based project memory
  ;; is project-scoped — both live under the `memory/` name.
  (is (= #{:user :project} (cfg/subdir-allowed-scopes "memory")))
  (is (cfg/subdir-scope-allowed? "memory" :project))
  (is (cfg/subdir-scope-allowed? "memory" :user)))

;; ============================================================================
;; resolve-sandbox-interop
;; ============================================================================

(deftest resolve-sandbox-interop-default-is-restricted
  ;; No persisted override and no BY_SANDBOX_INTEROP → schema :default → :restricted.
  (with-redefs [cfg/get-config (fn ([_] :restricted) ([_ _] :restricted))]
    (is (= :restricted (cfg/resolve-sandbox-interop)))
    (is (= :restricted (cfg/resolve-sandbox-interop (fake-agent {}))))))

(deftest resolve-sandbox-interop-full-passes-through
  (with-redefs [cfg/get-config (fn ([_] :full) ([_ _] :full))]
    (is (= :full (cfg/resolve-sandbox-interop)))
    (is (= :full (cfg/resolve-sandbox-interop (fake-agent {}))))))

(deftest resolve-sandbox-interop-auto-consults-container-detection
  (testing ":auto → :full when a container is detected"
    (with-redefs [cfg/get-config (fn ([_] :auto) ([_ _] :auto))
                  cfg/container-detected? (constantly true)]
      (is (= :full (cfg/resolve-sandbox-interop)))))
  (testing ":auto → :restricted when no container is detected"
    (with-redefs [cfg/get-config (fn ([_] :auto) ([_ _] :auto))
                  cfg/container-detected? (constantly false)]
      (is (= :restricted (cfg/resolve-sandbox-interop))))))

(deftest resolve-sandbox-interop-unknown-falls-back-to-restricted
  (with-redefs [cfg/get-config (fn ([_] :bogus) ([_ _] nil))]
    (is (= :restricted (cfg/resolve-sandbox-interop)))
    (is (= :restricted (cfg/resolve-sandbox-interop (fake-agent {}))))))
