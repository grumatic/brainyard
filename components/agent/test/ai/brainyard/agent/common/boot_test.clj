;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.boot-test
  "Tests for process-boot registration of user-authored defs.

   The load-bearing property is the PHASE SPLIT: `boot-registries!` must put a
   user tool's metadata into the real registry without an agent, without a tool
   palette, and without creating an SCI sandbox — while `install-bodies!` at the
   first turn must still make that same tool callable, including bodies that
   compose a builtin from the palette or a peer user tool by symbol.

   Exercised against the REAL `tool/!tool-defs` and real temp directories; no
   LLM and no agent instance is involved anywhere."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [ai.brainyard.agent.common.boot :as boot]
            [ai.brainyard.agent.common.user-tools :as ut]
            [ai.brainyard.agent.common.user-agents :as ua]
            [ai.brainyard.agent.common.sandbox-bindings :as sb-bind]
            [ai.brainyard.agent.common.coact-agent]   ;; so run-coact-derived resolves
            [ai.brainyard.agent.core.tool :as tool]))

(def ^:private base-dir
  (str (System/getProperty "java.io.tmpdir") "/by-boot-test"))

(def ^:private test-dirs {:project-dir base-dir})

(def ^:private our-ids
  [:user$tool$solo :user$tool$aaa-caller :user$tool$zzz-base
   :user$tool$uses-builtin :user$tool$good :user$tool$broken
   :user$agent$probe])

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (rm-rf! c)))
  (.delete f))

(defn- clean! []
  (ut/reset-tools-sandbox!)
  (ua/reset-loaded!)
  (apply swap! tool/!tool-defs dissoc our-ids)
  (rm-rf! (io/file base-dir)))

(use-fixtures :each (fn [f] (clean!) (try (f) (finally (clean!)))))

(defn- write-tool!
  "Persist a user tool in the two-file form `def-store` writes."
  [name meta-map body]
  (let [dir (io/file base-dir ".brainyard/tools")]
    (.mkdirs dir)
    (spit (io/file dir (str name ".edn")) (pr-str (assoc meta-map :name name)))
    (spit (io/file dir (str name ".clj")) body)))

(defn- write-agent! [name description instruction]
  (let [dir (io/file base-dir ".brainyard/agents/user$agent" name)]
    (.mkdirs dir)
    (spit (io/file dir "agent.edn")
          (pr-str {:name name :description description :scope :project :version 1}))
    (spit (io/file dir "instruction.md") instruction)))

(defn- palette [] (sb-bind/auto-tool-bindings nil))

;; ============================================================================
;; Phase 1 — metadata only, no agent, no sandbox
;; ============================================================================

(deftest boot-registers-tool-metadata-without-a-sandbox
  (testing "a persisted tool reaches !tool-defs at boot, with no SCI context built"
    (write-tool! "solo" {:description "stands alone"
                         :input-schema [:map [:text :string]]}
                 "(fn [args] {:echo (:text args)})")
    (let [r (boot/boot-registries! :dirs test-dirs :skills :skip)]
      (is (= ["solo"] (:tools r)))
      (is (nil? (:skills r)) ":skills :skip must not scan"))
    (let [td (get @tool/!tool-defs :user$tool$solo)]
      (is (some? td) "registered into the shared registry")
      (is (= :tool (:type td)))
      (is (true? (get-in td [:meta :user-defined])))
      (is (= [:map [:text :string]] (get-in td [:meta :input-schema]))
          "schema survives, so the tool is callable-shaped to the LLM"))
    ;; The point of the split: registration must cost no SCI context, because
    ;; `by agents` registers and then never evaluates anything.
    (is (nil? @ut/!tools-sandbox)
        "phase 1 must not create the tools sandbox")))

(deftest boot-registers-user-agents
  (testing "a user agent needs only dirs — no sandbox, no palette, no agent"
    (write-agent! "probe" "boot probe agent" "You are a probe.")
    (is (= ["probe"] (:agents (boot/boot-registries! :dirs test-dirs :skills :skip))))
    (let [td (get @tool/!tool-defs :user$agent$probe)]
      (is (= :agent (:type td)))
      (is (= "boot probe agent" (get-in td [:meta :description])))
      (is (= "You are a probe." (get-in td [:meta :instruction])))
      (is (fn? (get-in td [:meta :bt-factory]))))
    (is (some #(= "user$agent$probe" (:id %)) (ua/list-user-agents))
        "and is discoverable by the listing the CLI renders")))

(deftest boot-is-idempotent
  (testing "a second boot re-registers nothing (guarded per dir, per process)"
    (write-tool! "solo" {:description "d"} "(fn [args] {:ok true})")
    (is (= ["solo"] (:tools (boot/boot-registries! :dirs test-dirs :skills :skip))))
    (is (nil? (:tools (boot/boot-registries! :dirs test-dirs :skills :skip)))
        "guard short-circuits, so entry points may call boot freely")
    (is (contains? @tool/!tool-defs :user$tool$solo))))

(deftest boot-survives-a-missing-project
  (testing "no .brainyard dir is an empty result, never a throw"
    (let [r (boot/boot-registries! :dirs {:project-dir "/nonexistent/by-xyz"}
                                   :skills :skip)]
      (is (= [] (:tools r)))
      (is (= [] (:agents r))))))

;; ============================================================================
;; Phase 2 — bodies, which is where the palette is required
;; ============================================================================

(deftest boot-registered-tool-becomes-callable-after-body-install
  (testing "phase 1 alone does not make a tool runnable; phase 2 does"
    (write-tool! "solo" {:description "d" :input-schema [:map [:text :string]]}
                 "(fn [args] {:echo (:text args)})")
    (boot/boot-registries! :dirs test-dirs :skills :skip)
    (is (= ["solo"] (ut/install-bodies! :dirs test-dirs :extra-bindings (palette))))
    (is (= {:echo "hi"} (tool/call-tool :user$tool$solo {:text "hi"})))))

(deftest body-composing-a-builtin-runs-after-a-boot-first-load
  (testing "the palette bound at phase 2 is LIVE, not shadowed by phase 1"
    ;; This is the regression that would prove deferring bodies is wrong: if the
    ;; sandbox kept the bindings it was created with, a body calling a builtin
    ;; would analyse but fail at call time.
    (let [probe (io/file base-dir "probe.txt")]
      (.mkdirs (io/file base-dir))
      (spit probe "alpha beta gamma")
      (write-tool! "uses-builtin" {:description "reads a file via the palette"
                                   :input-schema [:map [:path :string]]}
                   "(fn [args] {:len (count (or (:content (read-file {:path (:path args)})) \"\"))})")
      (boot/boot-registries! :dirs test-dirs :skills :skip)
      (ut/install-bodies! :dirs test-dirs :extra-bindings (palette))
      (let [r (tool/call-tool :user$tool$uses-builtin {:path (.getPath probe)})]
        (is (pos? (long (or (:len r) 0)))
            "read-file resolved from the palette at CALL time")))))

(deftest peer-composition-is-order-independent
  (testing "a body may compose a peer by symbol regardless of .edn file order"
    ;; `aaa-caller` sorts before the `zzz-base` it calls, so this fails unless
    ;; every peer symbol is bound before any body is installed.
    (write-tool! "aaa-caller" {:description "composes a peer"
                               :input-schema [:map [:text :string]]}
                 "(fn [args] {:via (:shouted (user$tool$zzz-base {:text (:text args)}))})")
    (write-tool! "zzz-base" {:description "the peer"
                             :input-schema [:map [:text :string]]}
                 "(fn [args] {:shouted (clojure.string/upper-case (:text args))})")
    (boot/boot-registries! :dirs test-dirs :skills :skip)
    (is (= #{"aaa-caller" "zzz-base"}
           (set (ut/install-bodies! :dirs test-dirs :extra-bindings (palette)))))
    (is (= {:via "PEER WORKS"}
           (tool/call-tool :user$tool$aaa-caller {:text "peer works"})))))

(deftest peer-composition-over-boot-accepts-kwargs
  (testing "a rehydrated peer symbol takes kwargs, not just a map"
    ;; The boot path binds peers through `install-bodies!`, which is a different
    ;; call site from `define-tool` — both used to install a one-arity fn, so a
    ;; body restored from disk could only call a peer with a map.
    (write-tool! "aaa-caller" {:description "composes a peer with kwargs"
                               :input-schema [:map [:text :string]]}
                 "(fn [args] {:via (:shouted (user$tool$zzz-base :text (:text args)))})")
    (write-tool! "zzz-base" {:description "the peer"
                             :input-schema [:map [:text :string]]}
                 "(fn [args] {:shouted (clojure.string/upper-case (:text args))})")
    (boot/boot-registries! :dirs test-dirs :skills :skip)
    (ut/install-bodies! :dirs test-dirs :extra-bindings (palette))
    (is (= {:via "PEER WORKS"}
           (tool/call-tool :user$tool$aaa-caller {:text "peer works"})))))

(deftest a-broken-body-rolls-out-of-the-registry
  (testing "phase 1 registers both; phase 2 retracts only the one that cannot eval"
    (write-tool! "good" {:description "fine"} "(fn [args] {:ok true})")
    (write-tool! "broken" {:description "bad"} "(fn [args] (no-such-symbol-here))")
    (is (= #{"good" "broken"}
           (set (:tools (boot/boot-registries! :dirs test-dirs :skills :skip))))
        "registration is metadata-only, so it cannot detect a bad body")
    (is (= ["good"] (ut/install-bodies! :dirs test-dirs :extra-bindings (palette))))
    (is (not (contains? @tool/!tool-defs :user$tool$broken))
        "the LLM is never shown a tool that cannot run")
    (is (contains? @tool/!tool-defs :user$tool$good)
        "one bad tool must not cost the user the others")))

;; ============================================================================
;; The net: entry points that never boot
;; ============================================================================

(deftest ensure-loaded-still-works-without-a-boot
  (testing "a process that never calls boot-registries! (a2a serve, tests) loads whole"
    (write-tool! "solo" {:description "d" :input-schema [:map [:text :string]]}
                 "(fn [args] {:echo (:text args)})")
    (is (= ["solo"] (ut/ensure-loaded! :dirs test-dirs :extra-bindings (palette)))
        "ensure-loaded! registers AND installs when boot never ran")
    (is (= {:echo "x"} (tool/call-tool :user$tool$solo {:text "x"})))))

(deftest ensure-loaded-after-boot-installs-without-re-registering
  (testing "the common path: boot did phase 1, the first turn does phase 2"
    (write-tool! "solo" {:description "d" :input-schema [:map [:text :string]]}
                 "(fn [args] {:echo (:text args)})")
    (boot/boot-registries! :dirs test-dirs :skills :skip)
    (is (= ["solo"] (ut/ensure-loaded! :dirs test-dirs :extra-bindings (palette))))
    (is (= {:echo "y"} (tool/call-tool :user$tool$solo {:text "y"})))
    (is (nil? (ut/ensure-loaded! :dirs test-dirs :extra-bindings (palette)))
        "and is a no-op on every subsequent turn")))

;; ============================================================================
;; Phase 2 must not be skippable — the shape of the bug this split can produce
;; ============================================================================

(deftest phase-1-alone-is-a-tool-that-advertises-itself-and-cannot-run
  (testing "boot without phase 2 leaves a listed, bound, UNCALLABLE tool"
    ;; This is the exact state a resumed session used to sit in for the life of
    ;; the process: coact-init gated phase 2 on `(nil? existing-sandbox)`, and
    ;; the TUI's `--resume` path seeds that sandbox before the first turn. The
    ;; assertion is on the ERROR TEXT because that string — `__ut_<name>` — is
    ;; the only outward sign, and it names an internal the LLM cannot act on.
    (write-tool! "solo" {:description "d" :input-schema [:map [:text :string]]}
                 "(fn [args] {:echo (:text args)})")
    (boot/boot-registries! :dirs test-dirs :skills :skip)
    (is (contains? @tool/!tool-defs :user$tool$solo)
        "registered, so it lists and binds as `user$tool$solo`")
    (let [r (tool/call-tool :user$tool$solo {:text "hi"})]
      (is (re-find #"__ut_solo" (pr-str r))
          "and calling it fails on the body var phase 2 never installed"))
    ;; …and phase 2 is all that stands between that and a working tool.
    (ut/ensure-loaded! :dirs test-dirs :extra-bindings (palette))
    (is (= {:echo "hi"} (tool/call-tool :user$tool$solo {:text "hi"})))))

(deftest the-palette-is-built-only-when-there-is-something-to-load
  (testing ":extra-bindings may be a thunk, called once and only when loading"
    ;; coact-init calls this on EVERY turn now (the guard, not the call site,
    ;; decides). Building the palette eagerly there would be per-turn work for
    ;; a set lookup that almost always says "already loaded".
    (write-tool! "solo" {:description "d" :input-schema [:map [:text :string]]}
                 "(fn [args] {:echo (:text args)})")
    (let [calls (atom 0)
          thunk #(do (swap! calls inc) (palette))]
      (is (= ["solo"] (ut/ensure-loaded! :dirs test-dirs :extra-bindings thunk)))
      (is (= 1 @calls) "built once, for the load that needed it")
      (is (nil? (ut/ensure-loaded! :dirs test-dirs :extra-bindings thunk)))
      (is (= 1 @calls) "and never again while the guard holds"))
    (is (= {:echo "z"} (tool/call-tool :user$tool$solo {:text "z"})))))

(deftest a-load-that-throws-does-not-claim-the-dir
  (testing "the guard means `these are loaded`, so a failed load must not set it"
    (write-tool! "solo" {:description "d" :input-schema [:map [:text :string]]}
                 "(fn [args] {:echo (:text args)})")
    (is (thrown? Throwable
                 (with-redefs [ut/install-bodies!
                               (fn [& _] (throw (ex-info "sandbox unavailable" {})))]
                   (ut/ensure-loaded! :dirs test-dirs :extra-bindings (palette)))))
    ;; Without the rollback this returns nil and the tool stays uncallable for
    ;; the rest of the process — a transient failure made permanent.
    (is (= ["solo"] (ut/ensure-loaded! :dirs test-dirs :extra-bindings (palette)))
        "the next turn retries")
    (is (= {:echo "r"} (tool/call-tool :user$tool$solo {:text "r"})))))
