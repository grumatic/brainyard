;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.config-test
  "Unit tests for the unified config API in core.config — `get-config`,
   `set-config!`, `!global-config` cache, and the deprecated alias."
  (:require [ai.brainyard.agent.core.config :as cfg]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
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
  (testing "every schema key declares either :default or :default-fn"
    (doseq [k cfg/config-keys
            :let [entry (get cfg/config-schema k)]]
      (is (or (contains? entry :default) (contains? entry :default-fn))
          (str "schema entry " k " has neither :default nor :default-fn"))))
  (testing ":default-fn-only entries are excluded from default-config"
    (doseq [k cfg/config-keys
            :let [entry (get cfg/config-schema k)]]
      (if (contains? entry :default)
        (is (contains? cfg/default-config k)
            (str "missing default-config entry for " k))
        (is (not (contains? cfg/default-config k))
            (str ":default-fn-only entry leaked into default-config: " k))))))

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
                  :config {:eval-lm "claude-code:opus"}}})]
    (is changed?)
    (is (= 30   (get-in config [:agent :config :max-iterations])))
    (is (= "claude-code:opus" (get-in config [:agent :config :eval-lm]))
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
