;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.tool-permission-test
  "Tests for the general tool permission gate — §6 of
   docs/design/tool-permission-gate-design.md.

   The properties worth pinning here are mostly about FAILING IN THE RIGHT
   DIRECTION. `fire-decision!` fails open three ways (a malformed decision is
   skipped, a thrown handler is stepped over, a `:match` that never matches
   never runs), and for a cache or a nudge that is correct. For a permission
   gate each one silently permits the call, and nothing above debug level says
   so — which is why several tests below assert the SHAPE of what the gate
   returns rather than merely that a call was refused."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.brainyard.agent.common.tool-permission :as tperm]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.tool :as tool]))

(defn- with-gate-installed
  "Re-install the gate around every test, and drop this suite's probes after.

   The gate installs itself at namespace load, which is enough in production
   and NOT enough in a shared test JVM: `hooks_test`, `fsm_test`, `watch_test`
   and several others call `hooks/reset-hooks!`, which wipes the registry for
   everything that runs after them. Whichever suite happens to be ordered last
   would otherwise decide whether this one sees its own gate. (MCP's suite
   sidesteps the same hazard by calling its handler directly rather than
   through `fire-decision!`.)

   Re-installing also exercises the idempotency claim: `register-hook!`
   replaces on [event-key handler-id], so calling this before every test must
   leave exactly one entry, not N."
  [f]
  (tperm/install-tool-permission-gate!)
  (tperm/install-tool-deny-gate!)
  (try (f) (finally (hooks/unregister-source! :tperm-test))))

(use-fixtures :each with-gate-installed)

(defn- agent-with
  "A stub agent carrying a session `:permission-fn`. nil ⇒ headless."
  [pfn]
  {:!session (atom (cond-> {:config {}}
                     pfn (assoc :config {:permission-fn pfn})))})

(defn- with-cfg
  "Run `f` with the two gate keys resolving to `m`, everything else real."
  [m f]
  (with-redefs [config/get-config
                (fn [& args] (get m (last args)))]
    (f)))

(defn- fire [event]
  (hooks/fire-decision! :agent.tool-use/pre event))

;; ============================================================================
;; The glob matcher — the two bugs that made check-permission inert
;; ============================================================================

(deftest glob-matcher-test
  (testing "$ is an ANCHOR in a regex, and every tool name contains one"
    ;; (str/replace "mcp$*" "*" ".*") compiles to ^mcp$.*$ — matching NOTHING
    ;; that starts with mcp$. This is half of why check-permission could not
    ;; deny anything, and it is shared with :script-bridge-tools.
    (is (true?  (tperm/glob-match? "mcp$*" "mcp$tools")))
    (is (true?  (tperm/glob-match? "mcp$*" "mcp$linear$create_issue")))
    (is (false? (tperm/glob-match? "mcp$*" "memory$recall"))))

  (testing "a match is WHOLE-NAME, never a substring"
    ;; The other half: re-find under :deny ["read"] denied read-file AND
    ;; spread-metrics.
    (is (true?  (tperm/glob-match? "read-file" "read-file")))
    (is (false? (tperm/glob-match? "read" "read-file")))
    (is (false? (tperm/glob-match? "read" "spread-metrics"))))

  (testing "a . is literal, or a typo silently widens the gate"
    (is (false? (tperm/glob-match? "memory.recall" "memory$recall"))))

  (testing "* spans $, so a family pattern nests"
    (is (true? (tperm/glob-match? "user$*" "user$tool$create")))
    (is (true? (tperm/glob-match? "*" "anything$at$all"))))

  (testing "first-match returns the PATTERN — the unit an approval caches on"
    (is (= "mcp$*" (tperm/first-match ["nope$*" "mcp$*" "*"] "mcp$tools")))
    (is (nil? (tperm/first-match ["nope$*"] "mcp$tools")))))

;; ============================================================================
;; Inertness — the shipped default
;; ============================================================================

(deftest ships-inert-test
  (testing "both keys default to empty in the schema"
    (is (= [] (get-in config/config-schema [:tool-approval-patterns :default])))
    (is (= [] (get-in config/config-schema [:tool-allow-tools :default]))))

  (testing "no approval patterns ⇒ :match never fires, so the gate cannot run"
    (with-cfg {:tool-approval-patterns [] :tool-allow-tools []}
      (fn []
        (is (false? (tperm/gate-matches? {:agent nil :tool-name "write-file"})))
        (is (nil? (tperm/approval-pattern nil "write-file"))))))

  (testing "an empty approve list is not widened by a non-empty allow list"
    (with-cfg {:tool-approval-patterns [] :tool-allow-tools ["*"]}
      (fn [] (is (false? (tperm/gate-matches? {:agent nil :tool-name "write-file"}))))))

  (testing "and the gate calls no permission-fn when inert"
    (let [called (atom 0)
          a      (agent-with (fn [_] (swap! called inc) {:allowed true}))]
      (with-cfg {:tool-approval-patterns [] :tool-allow-tools []}
        (fn [] (is (nil? (tperm/tool-permission-gate {:agent a :tool-name "write-file"})))))
      (is (zero? @called) "an inert gate must not reach the prompt channel"))))

;; ============================================================================
;; Classification — allow is checked first
;; ============================================================================

(deftest approval-pattern-test
  (testing "a matching approval pattern is returned, not merely a boolean"
    (with-cfg {:tool-approval-patterns ["config$*"] :tool-allow-tools []}
      (fn [] (is (= "config$*" (tperm/approval-pattern nil "config$apply"))))))

  (testing "allow is checked FIRST, so a broad approve can carry a narrow exemption"
    ;; The reverse ordering makes the exemption unreachable.
    (with-cfg {:tool-approval-patterns ["mcp$*"] :tool-allow-tools ["mcp$linear$*"]}
      (fn []
        (is (= "mcp$*" (tperm/approval-pattern nil "mcp$slack$post")))
        (is (nil? (tperm/approval-pattern nil "mcp$linear$create_issue"))))))

  (testing "an unmatched tool is untouched"
    (with-cfg {:tool-approval-patterns ["config$*"] :tool-allow-tools []}
      (fn [] (is (nil? (tperm/approval-pattern nil "read-file")))))))

;; ============================================================================
;; The verdict — mode branching and fail-closed headless
;; ============================================================================

(deftest gate-verdict-modes-test
  (let [cfg  {:tool-approval-patterns ["config$*"] :tool-allow-tools []}
        run  (fn [mode pfn]
               (with-redefs [config/resolve-permission-mode (constantly mode)]
                 (with-cfg cfg
                   (fn [] (tperm/tool-permission-gate
                           {:agent (agent-with pfn) :tool-name "config$apply"})))))]

    (testing ":auto-approve runs without prompting"
      (let [called (atom 0)]
        (is (nil? (run :auto-approve (fn [_] (swap! called inc) {:allowed true}))))
        (is (zero? @called) ":auto-approve must not prompt")))

    (testing ":deny-by-default refuses without prompting"
      (let [called (atom 0)
            d      (run :deny-by-default (fn [_] (swap! called inc) {:allowed true}))]
        (is (= :replace (:result d)))
        (is (zero? @called))))

    (testing ":ask-each-time prompts, and an approval lets the call through"
      (is (nil? (run :ask-each-time (constantly {:allowed true})))))

    (testing ":ask-each-time with a denial refuses"
      (is (= :replace (:result (run :ask-each-time (constantly {:denied true :reason "nope"}))))))

    (testing "HEADLESS IS FAIL-CLOSED — no permission-fn ⇒ refusal, not allow"
      ;; A gate that silently allows when it cannot ask is worse than no gate:
      ;; the audit trail then reads as approved.
      (let [d (run :ask-each-time nil)]
        (is (= :replace (:result d)))
        (is (re-find #"headless" (:reason d)))))

    (testing "a permission-fn that THROWS refuses rather than allowing"
      (let [d (run :ask-each-time (fn [_] (throw (ex-info "boom" {}))))]
        (is (= :replace (:result d)))
        (is (re-find #"prompt error" (:reason d)))))))

(deftest prompt-request-shape-test
  (testing "the request carries the matched PATTERN, which is what caches"
    ;; A human approves the family they were shown, not the single call — the
    ;; MCP arm caches per server for the same reason.
    (let [seen (atom nil)]
      (with-redefs [config/resolve-permission-mode (constantly :ask-each-time)]
        (with-cfg {:tool-approval-patterns ["config$*"] :tool-allow-tools []}
          (fn [] (tperm/tool-permission-gate
                  {:agent (agent-with (fn [r] (reset! seen r) {:allowed true}))
                   :tool-name "config$apply"}))))
      (is (= :tool-use (:type @seen)) "the TUI dispatches make-permission-fn on :type")
      (is (= "config$apply" (:tool @seen)))
      (is (= "config$*" (:pattern @seen))))))

;; ============================================================================
;; The refusal is WELL-FORMED — fire-decision! fails OPEN on a malformed one
;; ============================================================================

(deftest refusal-is-well-formed-test
  (let [d (with-redefs [config/resolve-permission-mode (constantly :deny-by-default)]
            (with-cfg {:tool-approval-patterns ["config$*"] :tool-allow-tools []}
              (fn [] (tperm/tool-permission-gate
                      {:agent (agent-with nil) :tool-name "config$apply"}))))]

    (testing "it is a decision fire-decision! will actually honor"
      ;; valid-decision? requires :replacement on a :replace. Without it the
      ;; decision is logged as ::malformed-decision and SKIPPED — so a gate
      ;; whose refusal is malformed is indistinguishable from no gate.
      ;; Asserting "the tool did not run" would not catch that; asserting the
      ;; shape does.
      (is (true? (hooks/decision? d)))
      (is (= :replace (:result d)))
      (is (contains? d :replacement) ":replace without :replacement fails OPEN")
      (is (some? (:reason d)))
      (is (= ::tperm/tool-permission-gate (:by d))))

    (testing "the replacement is a readable error, so the turn can continue"
      (is (string? (get-in d [:replacement :error])))
      (is (re-find #"config\$apply" (get-in d [:replacement :error])))
      (is (re-find #":tool-allow-tools" (get-in d [:replacement :error]))
          "the refusal must say how to get permission"))))

(deftest gate-registration-test
  (let [entries (->> (hooks/list-hooks :agent.tool-use/pre)
                     (filter #(= ::tperm/tool-permission-gate (:id %))))
        entry   (first entries)]

    (testing "a gate that CRASHES must not permit the call"
      ;; :on-error :log (the house default) makes a thrown handler return nil,
      ;; which fire-decision! reads as an abstention — the walk continues and
      ;; the tool runs ungated. :throw surfaces the bug as a failed call.
      (is (some? entry))
      (is (= :throw (:on-error entry))))

    (testing "priority 85 — both neighbours are deliberate"
      ;; Below the tool cache (90) so a cache hit short-circuits before a
      ;; prompt; above the MCP gate (80) so a general deny reaches a tool
      ;; readOnlyHint would have auto-allowed.
      (is (= 85 (:priority entry))))

    (testing "installing is idempotent — a :reload must not accumulate gates"
      ;; register-hook! replaces on [event-key handler-id]. Two entries would
      ;; mean two prompts for one call.
      (tperm/install-tool-permission-gate!)
      (is (= 1 (count (->> (hooks/list-hooks :agent.tool-use/pre)
                           (filter #(= ::tperm/tool-permission-gate (:id %))))))))))

;; ============================================================================
;; Ordering — and the veto-only property every ordering claim rests on
;; ============================================================================

(deftest allow-is-an-abstention-test
  (testing "a high-priority :allow does NOT stop a lower gate refusing"
    ;; Every ordering claim in the design depends on this. A refactor that
    ;; "optimized" the walk to stop on the first explicit :allow would silently
    ;; disable every gate below the first permissive one — and no ordering test
    ;; written in terms of two REFUSALS would catch it.
    (hooks/register-hook! :agent.tool-use/pre ::allows (constantly {:result :allow})
                          :source :tperm-test :priority 90)
    (hooks/register-hook! :agent.tool-use/pre ::blocks
                          (constantly {:result :block :reason "reached"})
                          :source :tperm-test :priority 10)
    (let [d (fire {:tool-name "zzz$never-matched-by-a-real-gate" :args {}})]
      (is (= :block (:result d)))
      (is (= ::blocks (:by d))))))

(deftest ordering-test
  (let [cfg {:tool-approval-patterns ["mcp$*"] :tool-allow-tools []}
        ev  {:agent (agent-with nil) :tool-name "mcp$demo$read" :args {}}]

    (testing "the general gate (85) refuses before a permissive MCP gate (80)"
      ;; This is the ONE direction the 85>80 placement buys: a general deny
      ;; reaching an MCP tool that readOnlyHint would have auto-allowed.
      (hooks/register-hook! :agent.tool-use/pre ::fake-mcp (constantly nil)
                            :source :tperm-test :priority 84)
      (let [d (with-redefs [config/resolve-permission-mode (constantly :deny-by-default)]
                (with-cfg cfg (fn [] (fire ev))))]
        (is (= ::tperm/tool-permission-gate (:by d)))))

    (testing ":tool-allow-tools does NOT bypass a lower gate's refusal"
      ;; An allow is nil, so the walk continues and the MCP gate still gets its
      ;; say. :mcp-allow-tools remains the only key that bypasses that gate.
      (hooks/unregister-source! :tperm-test)
      ;; 84, not 80: the REAL mcp-permission-gate sits at 80, and a tie there
      ;; is broken by registration order — so this asserted against whichever
      ;; gate happened to be registered first, passing alone and failing in any
      ;; JVM that also loaded mcp/permission.clj. What is under test is "a gate
      ;; BELOW 85 still gets its say", and any priority under 85 says that.
      (hooks/register-hook! :agent.tool-use/pre ::fake-mcp
                            (constantly {:result :replace
                                         :replacement {:error "mcp refused"}
                                         :reason "mcp refused"})
                            :source :tperm-test :priority 84)
      (let [d (with-redefs [config/resolve-permission-mode (constantly :ask-each-time)]
                (with-cfg {:tool-approval-patterns ["mcp$*"]
                           :tool-allow-tools ["mcp$*"]}
                  (fn [] (fire ev))))]
        (is (= ::fake-mcp (:by d))
            "exempting THIS gate must not license the call for another")))

    (testing "a cache hit at 90 short-circuits before any prompt"
      (hooks/unregister-source! :tperm-test)
      (let [prompted (atom 0)]
        (hooks/register-hook! :agent.tool-use/pre ::fake-cache
                              (constantly {:result :replace :replacement {:cached true}
                                           :reason "cache hit"})
                              :source :tperm-test :priority 90)
        (let [d (with-redefs [config/resolve-permission-mode (constantly :ask-each-time)]
                  (with-cfg cfg
                    (fn [] (fire (assoc ev :agent (agent-with (fn [_] (swap! prompted inc)
                                                                {:allowed true})))))))]
          (is (= ::fake-cache (:by d)))
          (is (zero? @prompted)
              "approving a call about to be served from cache is a prompt paid for nothing"))))))

;; ============================================================================
;; :tool-deny-tools — the unconditional deny
;;
;; Everything here is about the ways a deny could quietly turn into an approval:
;; a permission mode talking it round, an allow glob carving a hole in it, or a
;; cached result being served before it ever runs.
;; ============================================================================

(defn- deny-ev [tool] {:agent (agent-with (fn [_] {:allowed true})) :tool-name tool})

(deftest deny-gate-is-inert-by-default-test
  (testing "the shipped default is an empty vector, and the match short-circuits on it"
    (is (= [] (:default (get config/config-schema :tool-deny-tools))))
    (with-cfg {:tool-deny-tools []}
      (fn []
        (is (false? (tperm/deny-matches? (deny-ev "read-file"))))
        (is (nil? (tperm/deny-pattern (:agent (deny-ev "read-file")) "read-file")))))))

(deftest deny-survives-every-permission-mode-test
  ;; The reason to write a deny rather than an approval pattern: no mode can
  ;; talk it round. :auto-approve is the one that matters — under the approval
  ;; gate it runs the tool.
  (doseq [mode [:auto-approve :ask-each-time :deny-by-default]]
    (testing (str "mode " mode)
      (let [prompted (atom 0)
            ag (agent-with (fn [_] (swap! prompted inc) {:allowed true}))
            d  (with-redefs [config/resolve-permission-mode (constantly mode)]
                 (with-cfg {:tool-deny-tools ["bash"]}
                   (fn [] (tperm/tool-deny-gate {:agent ag :tool-name "bash"}))))]
        (is (= :replace (:result d)) "a deny is a refusal in every mode")
        (is (= ::tperm/tool-deny-gate (:by d)))
        (is (some? (:replacement d))
            "the SHAPE matters: a :replace carrying a readable result, not a bare nil")
        (is (re-find #"tool-deny-tools" (get-in d [:replacement :error])))
        (is (zero? @prompted)
            "a prompt is an offer to run, and a denied tool is not on offer")))))

(deftest deny-is-not-exemptable-by-tool-allow-tools-test
  ;; :tool-allow-tools carves holes in :tool-approval-patterns ONLY. A deny
  ;; another key can undo is not a deny.
  (with-cfg {:tool-deny-tools        ["bash"]
             :tool-allow-tools       ["bash"]
             :tool-approval-patterns ["bash"]}
    (fn []
      (is (= "bash" (tperm/deny-pattern (:agent (deny-ev "bash")) "bash")))
      (is (true? (tperm/deny-matches? (deny-ev "bash"))))
      (testing "while the same allow DOES exempt it from the approval gate"
        (is (nil? (tperm/approval-pattern (:agent (deny-ev "bash")) "bash")))))))

(deftest deny-outranks-the-tool-cache-test
  ;; The one ordering difference from the approval gate. `tool-cache-lookup-pre`
  ;; caches every tool once :tool-cache-ttl is positive and returns a :replace,
  ;; which short-circuits the walk — so a deny BELOW it would hand back a
  ;; previously-read result for a tool the operator denied.
  (testing "the deny gate sits above the cache's priority"
    (let [pri (->> (hooks/list-hooks :agent.tool-use/pre)
                   (filter #(= ::tperm/tool-deny-gate (:id %)))
                   first :priority)]
      (is (= 95 pri))
      (is (> pri 90) "above context-actions/tool-cache-lookup")))

  (testing "and a cache hit at 90 does not get to serve a denied call"
    (hooks/register-hook! :agent.tool-use/pre ::fake-cache
                          (constantly {:result :replace :replacement {:cached "leaked"}
                                       :by ::fake-cache :reason "cache hit"})
                          :source :tperm-test :priority 90)
    (let [d (with-redefs [config/resolve-permission-mode (constantly :auto-approve)]
              (with-cfg {:tool-deny-tools ["read-file"]}
                (fn [] (fire {:agent (agent-with nil) :tool-name "read-file"}))))]
      (is (= ::tperm/tool-deny-gate (:by d))
          "the deny must win, or a denied read serves the content it denied")
      (is (nil? (get-in d [:replacement :cached]))))))

(deftest deny-gate-registration-test
  (testing "registered once, fail-closed, with a match predicate"
    (tperm/install-tool-deny-gate!)
    (let [hs (filter #(= ::tperm/tool-deny-gate (:id %))
                     (hooks/list-hooks :agent.tool-use/pre))
          h  (first hs)]
      (is (= 1 (count hs)) "idempotent: register-hook! replaces on [event id]")
      (is (= :throw (:on-error h))
          "under the house :log default a crashing deny returns nil, which reads as an abstention")
      (is (some? (:match h))))))

(deftest deny-glob-shape-matches-the-other-keys-test
  (with-cfg {:tool-deny-tools ["mcp$*" "config$apply"]}
    (fn []
      (let [ag (:agent (deny-ev "x"))]
        (is (= "mcp$*"        (tperm/deny-pattern ag "mcp$linear$create_issue")))
        (is (= "config$apply" (tperm/deny-pattern ag "config$apply")))
        (is (nil? (tperm/deny-pattern ag "config$read")))
        (is (nil? (tperm/deny-pattern ag "mcpx"))
            "$ is quoted, not treated as an end-of-input anchor")))))

(deftest deny-is-still-veto-only-test
  ;; Every ordering claim in the namespace rests on an allow being an
  ;; abstention. A deny gate that returned a positive allow would break it.
  (with-cfg {:tool-deny-tools ["bash"]}
    (fn []
      (is (nil? (tperm/tool-deny-gate {:agent (agent-with nil) :tool-name "read-file"}))
          "no match must abstain (nil), never license the call"))))

;; ============================================================================
;; check-permission is gone
;; ============================================================================

(deftest check-permission-is-gone-test
  (testing "the inert gate and its helpers no longer exist"
    (is (nil? (ns-resolve 'ai.brainyard.agent.core.tool 'check-permission)))
    (is (nil? (ns-resolve 'ai.brainyard.agent.core.tool 'permission-config))))

  (testing "and call-tool's docstring no longer claims a check it does not do"
    (let [d (:doc (meta #'tool/call-tool))]
      (is (some? d))
      (is (not (re-find #"Permission is checked before dispatch" d)))
      (is (re-find #"agent\.tool-use/pre" d)
          "it should point at where permission IS enforced"))))
