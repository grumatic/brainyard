;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.explore-agent-test
  "Tests for explore-agent: registration, inherited bt-factory (CoAct),
   curated agent-tools roster across the four exploration surfaces (positive
   + negative assertions), instruction-content anchors that pin the routing
   + persistence + handoff contract, and unit tests for the explore$* helper
   commands (slug determinism, frontmatter round-trip, write-file collision
   suffix, INDEX.md prepend ordering, find)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.agent.common.explore :as explore]
            [ai.brainyard.agent.common.explore-agent]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.tool :as tool])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; ============================================================================
;; Registration
;; ============================================================================

(deftest registration-test
  (testing "explore-agent is registered as an agent"
    (let [agent-defs (tool/get-tool-defs :type :agent)]
      (is (contains? agent-defs :explore-agent))
      (let [agent-def (get agent-defs :explore-agent)]
        (is (= :explore-agent (:id agent-def)))
        (is (some? (:fn agent-def)))
        (is (some? (:meta agent-def)))))))

;; ============================================================================
;; Inheritance via run-coact-derived
;;
;; explore-agent pins :bt-factory explicitly (mirroring rlm-agent) so direct
;; entry-points (e.g. setup-agent-by-id used by `bb tui ask`) that resolve
;; agent metadata without going through run-coact-derived still pick up the
;; correct CoAct BT.
;; ============================================================================

(deftest inheritance-test
  (require 'ai.brainyard.agent.common.coact-agent)
  (let [exp-def   (get (tool/get-tool-defs :type :agent) :explore-agent)
        coact-def (get (tool/get-tool-defs :type :agent) :coact-agent)]

    (testing "explore-agent's :fn is registered (the wrap-fn that invokes run-coact-derived)"
      (is (some? (:fn exp-def))))

    (testing "explore-agent pins :bt-factory explicitly (so setup-agent-by-id picks it up)"
      (let [bt-factory (get-in exp-def [:meta :bt-factory])]
        (is (fn? bt-factory))
        (let [bt (bt-factory {:max-iterations 3})]
          (is (vector? bt))
          (is (= :sequence (first bt)))
          (is (= "coact.sequence" (namespace (get-in bt [1 :id])))))))

    (testing "coact-agent (the parent) has the same bt-factory shape"
      (let [bt-factory (get-in coact-def [:meta :bt-factory])]
        (is (fn? bt-factory))
        (let [bt (bt-factory {:max-iterations 3})]
          (is (= :sequence (first bt)))
          (is (= "coact.sequence" (namespace (get-in bt [1 :id])))))))))

;; ============================================================================
;; Agent tools binding — positive + negative assertions
;; ============================================================================

(defn- explore-tool-ids []
  (let [agent-def   (get (tool/get-tool-defs :type :agent) :explore-agent)
        agent-tools (get-in agent-def [:meta :agent-tools])]
    (set (map (comp :id meta deref) (:tools agent-tools)))))

(deftest agent-tools-test
  (testing "explore-agent :agent-tools covers all four exploration surfaces"
    (let [ids (explore-tool-ids)]
      ;; A. FILESYSTEM
      (is (contains? ids :grep))
      (is (contains? ids :read-file))
      (is (contains? ids :write-file))
      (is (contains? ids :bash))
      (is (contains? ids :search))

      ;; B. WEB
      (is (contains? ids :web-search))
      (is (contains? ids :fetch-url))

      ;; C. MCP — at least the polymorphic trio
      (is (contains? ids :mcp$server))
      (is (contains? ids :mcp$tools))
      (is (contains? ids :mcp$lifecycle))

      ;; D. SKILLS — read-only subset
      (is (contains? ids :skills$list))
      (is (contains? ids :skills$find))
      (is (contains? ids :skills$read))
      (is (contains? ids :skills$reload))

      ;; Synthesis
      (is (contains? ids :query$llm))

      ;; Bookkeeping / invocation
      (is (contains? ids :list-tools))
      (is (contains? ids :get-tool-info))
      (is (contains? ids :call-tool))

      ;; Background fan-out
      (is (contains? ids :task$run))

      ;; Runtime config (per-turn threshold tuning)
      (is (contains? ids :agent-runtime$config))

      ;; explore$* helpers (Milestone B)
      (is (contains? ids :explore$slug))
      (is (contains? ids :explore$frontmatter))
      (is (contains? ids :explore$write))
      (is (contains? ids :explore$index-append))
      (is (contains? ids :explore$read-frontmatter))
      (is (contains? ids :explore$find))))

  (testing "explore-agent :agent-tools EXCLUDES forbidden + out-of-scope tools"
    (let [ids (explore-tool-ids)]
      ;; Hard Rule 1 — no clone-self recursion
      (is (not (contains? ids :query$clone))
          "query$clone must not be in explore-agent's roster (clone-self forbidden)")

      ;; Skill-authoring lives in skill-agent, not explore-agent
      (is (not (contains? ids :skills$write)))
      (is (not (contains? ids :skills$install)))
      (is (not (contains? ids :skills$sync))))))

;; ============================================================================
;; Instruction content anchors — pin the routing + persistence + handoff contract
;; ============================================================================

(deftest instruction-content-test
  (testing "instruction string contains the cardinal explore-agent anchors"
    (let [agent-def   (get (tool/get-tool-defs :type :agent) :explore-agent)
          instruction (get-in agent-def [:meta :instruction])]
      (is (string? instruction))
      (is (not (str/blank? instruction)))

      ;; Four-surface routing is the headline contract
      (is (str/includes? instruction "FOUR"))
      (is (str/includes? instruction "FILESYSTEM"))
      (is (str/includes? instruction "WEB"))
      (is (str/includes? instruction "MCP"))
      (is (str/includes? instruction "SKILLS"))

      ;; Decision flow + parallel probe
      (is (str/includes? instruction "DECISION FLOW"))
      (is (str/includes? instruction "ParallelBlock"))

      ;; Persistence contract
      (is (str/includes? instruction ".brainyard/agents/explore-agent/"))
      (is (str/includes? instruction "frontmatter"))
      (is (str/includes? instruction "Saved exploration:"))

      ;; Hard Rule 1 — stay flat, no clone-self dispatch
      (is (str/includes? instruction "clone-self"))

      ;; Hard Rule 2 — write-side MCP confirmation
      (is (str/includes? instruction "confirmation")))))

(deftest tool-context-content-test
  (testing "tool-context names tools from each of the four surfaces"
    (let [agent-def    (get (tool/get-tool-defs :type :agent) :explore-agent)
          tool-context (get-in agent-def [:meta :tool-context])]
      (is (string? tool-context))
      (is (not (str/blank? tool-context)))

      ;; Per-surface section anchors
      (is (str/includes? tool-context "FILESYSTEM"))
      (is (str/includes? tool-context "WEB"))
      (is (str/includes? tool-context "MCP"))
      (is (str/includes? tool-context "SKILLS"))

      ;; Key tools per surface named explicitly so the model can find them
      (is (str/includes? tool-context "grep"))
      (is (str/includes? tool-context "web-search"))
      (is (str/includes? tool-context "mcp$server"))
      (is (str/includes? tool-context "mcp$tools"))
      (is (str/includes? tool-context "mcp$lifecycle"))
      (is (str/includes? tool-context "skills$find"))
      (is (str/includes? tool-context "skills$read"))

      ;; Synthesis primitive
      (is (str/includes? tool-context "query$llm"))

      ;; Helpers documented (Milestone B)
      (is (str/includes? tool-context "explore$slug"))
      (is (str/includes? tool-context "explore$frontmatter"))
      (is (str/includes? tool-context "explore$write"))
      (is (str/includes? tool-context "explore$index-append"))
      (is (str/includes? tool-context "explore$read-frontmatter"))
      (is (str/includes? tool-context "explore$find"))
      (is (str/includes? tool-context "INDEX.md")))))

;; ============================================================================
;; Helper test fixtures
;; ============================================================================

(defn- make-tmp-dir []
  (-> (Files/createTempDirectory "explore-test-" (into-array FileAttribute []))
      .toFile
      .getAbsolutePath))

(defn- delete-recursive [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)]
      (delete-recursive c)))
  (.delete f))

;; ============================================================================
;; Helper unit tests — explore$slug
;; ============================================================================

(deftest slug-determinism-test
  (testing "same question → same slug"
    (let [q "Where is the loop guard implemented and what does it block?"
          s1 (:slug (explore/explore$slug :question q))
          s2 (:slug (explore/explore$slug :question q))]
      (is (= s1 s2))
      (is (string? s1))))

  (testing "stopwords are dropped"
    (let [s (:slug (explore/explore$slug :question "Where is the loop guard"))]
      ;; "where", "is", "the" are stopwords → only "loop guard" survives.
      (is (= "loop-guard" s))))

  (testing "kebab-case normalization"
    (let [s (:slug (explore/explore$slug :question "MCP server health check!"))]
      (is (= "mcp-server-health-check" s))))

  (testing "60-char default cap"
    (let [long-q (str/join " " (repeat 30 "supercalifragilistic"))
          s      (:slug (explore/explore$slug :question long-q))]
      (is (<= (count s) 60))))

  (testing "max-chars override"
    (let [s (:slug (explore/explore$slug :question "loop guard implementation deep dive"
                                         :max-chars 10))]
      (is (<= (count s) 10))))

  (testing "blank/empty question → fallback slug"
    (is (= "exploration" (:slug (explore/explore$slug :question ""))))
    (is (= "exploration" (:slug (explore/explore$slug :question "   "))))
    ;; All-stopwords question also collapses to fallback.
    (is (= "exploration" (:slug (explore/explore$slug :question "what is the")))))

  (testing "validation"
    (is (contains? (explore/explore$slug :question 123) :error))
    (is (contains? (explore/explore$slug :question "x" :max-chars 0) :error))))

;; ============================================================================
;; Helper unit tests — explore$frontmatter (round-trip with read-frontmatter)
;; ============================================================================

(deftest frontmatter-build-test
  (testing "all required keys present in output"
    (let [{:keys [frontmatter]}
          (explore/explore$frontmatter
           :question "Where is the loop guard?"
           :slug "loop-guard"
           :surfaces ["filesystem"]
           :entities {:files ["a.clj" "b.clj"]
                      :urls []
                      :mcp_tools []
                      :skills []}
           :summary "Loop guard is a pre-hook on tool-use that detects repetition.")]
      (is (str/starts-with? frontmatter "---\n"))
      (is (str/ends-with? frontmatter "---\n"))
      (is (str/includes? frontmatter "slug: loop-guard"))
      (is (str/includes? frontmatter "agent: explore-agent"))
      (is (str/includes? frontmatter "surfaces: [filesystem]"))
      ;; Bareword paths (alnum + `_./:-`) are emitted unquoted — matches design doc.
      (is (str/includes? frontmatter "files: [a.clj, b.clj]"))))

  (testing "values with whitespace / special chars are double-quoted"
    (let [{:keys [frontmatter]}
          (explore/explore$frontmatter
           :question "Q with spaces and ?"
           :slug "q-spaces"
           :surfaces ["filesystem"]
           :entities {:urls ["https://example.com/a b"]}
           :summary "ok")]
      ;; Question always quoted (it has spaces).
      (is (str/includes? frontmatter "question: \"Q with spaces and ?\""))
      ;; URL with embedded space → quoted; URL without → bareword.
      (is (str/includes? frontmatter "\"https://example.com/a b\""))))

  (testing "summary with internal newlines is collapsed to single line"
    (let [{:keys [frontmatter]}
          (explore/explore$frontmatter
           :question "Q"
           :slug "q"
           :surfaces ["filesystem"]
           :entities {}
           :summary "first line\nsecond line\nthird")]
      ;; Summary lives under `summary: >` and the content line should not contain \n.
      (let [summary-section (subs frontmatter (str/index-of frontmatter "summary: "))]
        (is (str/includes? summary-section "first line second line third"))
        (is (not (str/includes? summary-section "first line\nsecond"))))))

  (testing "validation"
    (is (contains? (explore/explore$frontmatter
                    :slug "x" :surfaces [] :summary "y") :error))
    (is (contains? (explore/explore$frontmatter
                    :question "x" :surfaces [] :summary "y") :error))
    (is (contains? (explore/explore$frontmatter
                    :question "x" :slug "x" :surfaces "not a vector" :summary "y") :error))))

(deftest frontmatter-round-trip-test
  (testing "frontmatter built by explore$frontmatter parses back via explore$read-frontmatter"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (let [question "Where do we configure MCP servers?"
              slug     "mcp-servers-configured"
              entities {:files ["mcp/integration.clj" "mcp/client.clj"]
                        :urls ["https://example.com/mcp"]
                        :mcp_tools ["linear:get-issue"]
                        :skills []}
              {:keys [frontmatter]}
              (explore/explore$frontmatter
               :question question
               :slug     slug
               :surfaces ["filesystem" "mcp"]
               :entities entities
               :summary  "MCP servers configured in integration.clj; 3/4 healthy.")
              {:keys [path]}
              (explore/explore$write :slug slug
                                     :content (str frontmatter "\n# Body\n…\n")
                                     :base-dir tmp-dir)
              parsed (explore/explore$read-frontmatter :path path :base-dir tmp-dir)]

          (testing "scalar keys round-trip"
            (is (= slug (:slug parsed)))
            (is (= question (:question parsed)))
            (is (= "explore-agent" (:agent parsed))))

          (testing "surfaces vector round-trips"
            (is (= ["filesystem" "mcp"] (:surfaces parsed))))

          (testing "entities sub-map round-trips"
            (is (= ["mcp/integration.clj" "mcp/client.clj"]
                   (get-in parsed [:entities :files])))
            (is (= ["https://example.com/mcp"]
                   (get-in parsed [:entities :urls])))
            (is (= ["linear:get-issue"]
                   (get-in parsed [:entities :mcp_tools])))
            (is (= [] (get-in parsed [:entities :skills]))))

          (testing "summary round-trips as folded single line"
            (is (string? (:summary parsed)))
            (is (str/includes? (:summary parsed) "MCP servers configured")))

          (testing "no spurious :error key"
            (is (not (contains? parsed :error)))))
        (finally
          (delete-recursive (io/file tmp-dir)))))))

;; ============================================================================
;; Helper unit tests — explore$write (collision suffix)
;; ============================================================================

(deftest write-collision-suffix-test
  (testing "first write uses bare slug, second auto-suffixes -2"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (let [r1 (explore/explore$write :slug "loop-guard"
                                        :content "---\nslug: loop-guard\n---\n# body 1\n"
                                        :base-dir tmp-dir)
              r2 (explore/explore$write :slug "loop-guard"
                                        :content "---\nslug: loop-guard-2\n---\n# body 2\n"
                                        :base-dir tmp-dir)
              r3 (explore/explore$write :slug "loop-guard"
                                        :content "---\nslug: loop-guard-3\n---\n# body 3\n"
                                        :base-dir tmp-dir)]
          (is (= "loop-guard"   (:slug r1)))
          (is (= "loop-guard-2" (:slug r2)))
          (is (= "loop-guard-3" (:slug r3)))

          ;; All three files exist, none overwritten
          (is (.isFile (io/file (:path r1))))
          (is (.isFile (io/file (:path r2))))
          (is (.isFile (io/file (:path r3))))

          (is (= "---\nslug: loop-guard\n---\n# body 1\n"
                 (slurp (io/file (:path r1)))))
          (is (= "---\nslug: loop-guard-2\n---\n# body 2\n"
                 (slurp (io/file (:path r2))))))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "different slugs do not collide"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (let [r1 (explore/explore$write :slug "alpha" :content "x" :base-dir tmp-dir)
              r2 (explore/explore$write :slug "beta"  :content "y" :base-dir tmp-dir)]
          (is (= "alpha" (:slug r1)))
          (is (= "beta"  (:slug r2))))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "validation"
    (is (contains? (explore/explore$write :content "x") :error))
    (is (contains? (explore/explore$write :slug "x")    :error))))

;; ============================================================================
;; Helper unit tests — explore$index-append (PREPENDS newest-first)
;; ============================================================================

(deftest index-prepend-ordering-test
  (testing "INDEX.md prepends newest-first; existing content preserved verbatim"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (explore/explore$index-append
         :path ".brainyard/agents/explore-agent/results/20260101-120000-alpha.md"
         :slug "alpha" :surfaces ["filesystem"] :summary "first entry"
         :base-dir tmp-dir)
        (explore/explore$index-append
         :path ".brainyard/agents/explore-agent/results/20260102-120000-beta.md"
         :slug "beta" :surfaces ["mcp"] :summary "second entry"
         :base-dir tmp-dir)
        (explore/explore$index-append
         :path ".brainyard/agents/explore-agent/results/20260103-120000-gamma.md"
         :slug "gamma" :surfaces ["filesystem" "mcp"] :summary "third entry"
         :base-dir tmp-dir)

        (let [content (slurp (io/file tmp-dir ".brainyard/agents/explore-agent/INDEX.md"))
              lines   (str/split-lines content)]
          ;; 3 entries; newest-first means gamma → beta → alpha
          (is (= 3 (count lines)))
          (is (str/includes? (nth lines 0) "gamma"))
          (is (str/includes? (nth lines 1) "beta"))
          (is (str/includes? (nth lines 2) "alpha"))

          ;; Format anchors: timestamp prefix, markdown link, surfaces, summary
          (is (re-find #"^- \d{4}-\d{2}-\d{2} \d{2}:\d{2} \[gamma\]\(results/.*\.md\) — filesystem, mcp · \*third entry\*"
                       (nth lines 0))))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "long summary is truncated at 200 chars with ellipsis"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (let [long-summary (apply str (repeat 300 "x"))
              {:keys [line]} (explore/explore$index-append
                              :path "results/test.md"
                              :slug "test" :surfaces [] :summary long-summary
                              :base-dir tmp-dir)]
          (is (str/includes? line "…"))
          (is (< (count line) 350)))
        (finally
          (delete-recursive (io/file tmp-dir)))))))

;; ============================================================================
;; Helper unit tests — explore$find
;; ============================================================================

(deftest find-test
  (testing "empty corpus → no matches, no error"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (let [r (explore/explore$find :query "anything" :base-dir tmp-dir)]
          (is (= 0 (:n-matches r)))
          (is (= [] (:matches r))))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "matches by slug or summary substring, newest-first"
    (let [tmp-dir (make-tmp-dir)]
      (try
        ;; Three results; explore$write timestamps each at call time so they
        ;; sort newest-first by filename.
        (let [w1 (explore/explore$write
                  :slug "alpha-loop-guard"
                  :content (str (:frontmatter
                                 (explore/explore$frontmatter
                                  :question "Q1" :slug "alpha-loop-guard"
                                  :surfaces ["filesystem"] :entities {}
                                  :summary "First exploration about loop guard."))
                                "\n# body\n")
                  :base-dir tmp-dir)]
          (Thread/sleep 1100)            ; ensure timestamp differs
          (let [w2 (explore/explore$write
                    :slug "beta-mcp"
                    :content (str (:frontmatter
                                   (explore/explore$frontmatter
                                    :question "Q2" :slug "beta-mcp"
                                    :surfaces ["mcp"] :entities {}
                                    :summary "Second exploration about Linear."))
                                  "\n# body\n")
                    :base-dir tmp-dir)]

            (testing "match on slug"
              (let [r (explore/explore$find :query "loop" :base-dir tmp-dir)]
                (is (= 1 (:n-matches r)))
                (is (= "alpha-loop-guard" (:slug (first (:matches r)))))))

            (testing "match on summary substring (case-insensitive)"
              (let [r (explore/explore$find :query "linear" :base-dir tmp-dir)]
                (is (= 1 (:n-matches r)))
                (is (= "beta-mcp" (:slug (first (:matches r)))))))

            (testing "match on multiple files, newest-first ordering"
              (let [r (explore/explore$find :query "exploration" :base-dir tmp-dir)]
                (is (= 2 (:n-matches r)))
                ;; w2 is newer (later ts) → first
                (is (= "beta-mcp" (:slug (first (:matches r)))))
                (is (= "alpha-loop-guard" (:slug (second (:matches r)))))))

            (testing "no match → empty"
              (let [r (explore/explore$find :query "nonsense-xyzzy" :base-dir tmp-dir)]
                (is (= 0 (:n-matches r)))))))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "validation"
    (is (contains? (explore/explore$find) :error))))

;; ============================================================================
;; Helper unit tests — explore$read-frontmatter (error paths)
;; ============================================================================

(deftest read-frontmatter-error-test
  (testing "missing file → :error"
    (let [r (explore/explore$read-frontmatter :path "/no/such/file.md")]
      (is (contains? r :error))))

  (testing "file without leading --- → :error"
    (let [tmp-dir (make-tmp-dir)
          plain   (io/file tmp-dir "plain.md")]
      (try
        (spit plain "Hello, no frontmatter here.\n")
        (let [r (explore/explore$read-frontmatter :path "plain.md" :base-dir tmp-dir)]
          (is (contains? r :error)))
        (finally
          (delete-recursive (io/file tmp-dir)))))))

;; ============================================================================
;; Hook unit tests — explore-auto-persist
;;
;; Bypass the actual :agent.ask/post hook firing path (which requires a real
;; agent + agent.clj process). Test the handler fn directly: it should write
;; the artifact, prepend INDEX.md, and skip when the answer already contains
;; the `Saved exploration:` marker. We use a fake agent map with the
;; defagent-type keyword the hook checks for via `proto/defagent-type`.
;; ============================================================================

(defn- fake-explore-agent
  "Minimal map that satisfies the hook's `proto/defagent-type` check via a
   protocol extension on `clojure.lang.IPersistentMap`. Implementing the
   protocol on Map at the ns level would be invasive — instead we extend
   only inside the test by reifying IAgent on a wrapping defrecord."
  []
  ;; Use the actual proto/agent-id keyword shape: :<defagent-type>/<suffix>.
  ;; The hook calls `proto/defagent-type` which extracts the namespace from
  ;; the agent-id keyword. We reify just enough of IAgent for that path.
  (reify ai.brainyard.agent.core.protocol/IAgent
    (agent-id [_] :explore-agent/test-instance)
    (agent-name [_] "test")
    (agent-description [_] "test")
    (user-id [_] "u1")
    (session-id [_] "s1")
    (defagent-type [_] :explore-agent)
    (process [_ _ _] nil)
    (get-tools [_] [])
    (get-state [_] {})))

(defn- fake-other-agent []
  (reify ai.brainyard.agent.core.protocol/IAgent
    (agent-id [_] :plan-agent/test-instance)
    (agent-name [_] "test")
    (agent-description [_] "test")
    (user-id [_] "u1")
    (session-id [_] "s1")
    (defagent-type [_] :plan-agent)
    (process [_ _ _] nil)
    (get-tools [_] [])
    (get-state [_] {})))

(deftest auto-persist-trivial-skip-test
  (testing "trivial answer (< 1000 chars, zero entities) is NOT persisted"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (with-redefs [config/project-dir (constantly tmp-dir)]
          (explore/explore-auto-persist
           {:agent  (fake-explore-agent)
            :input  "What's 2+2?"
            :result {:answer "4"}}))
        (is (not (.exists (io/file tmp-dir ".brainyard/agents/explore-agent/results")))
            "results dir should not exist for trivial answers")
        (is (not (.exists (io/file tmp-dir ".brainyard/agents/explore-agent/INDEX.md")))
            "INDEX should not exist for trivial answers")
        (finally
          (delete-recursive (io/file tmp-dir)))))))

(deftest auto-persist-non-trivial-test
  (testing "non-trivial answer (>= 1000 chars OR entities cited) is persisted"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (with-redefs [config/project-dir (constantly tmp-dir)]
          (let [answer (str "## Findings\n\n"
                            "The loop guard lives in `components/agent/src/ai/brainyard/agent/common/loop_guard_hook.clj`.\n\n"
                            "It blocks the 3rd consecutive identical tool call.\n")]
            (explore/explore-auto-persist
             {:agent  (fake-explore-agent)
              :input  "Where is the loop guard implemented?"
              :result {:answer answer}})))
        ;; Verify a result file was written
        (let [results-dir (io/file tmp-dir ".brainyard/agents/explore-agent/results")
              files       (when (.isDirectory results-dir) (vec (.listFiles results-dir)))]
          (is (.isDirectory results-dir))
          (is (= 1 (count files)))
          (let [content (slurp (first files))]
            (is (str/starts-with? content "---\n"))
            (is (str/includes? content "agent: explore-agent"))
            ;; Detected file entity should be in entities.files
            (is (str/includes? content "loop_guard_hook.clj"))
            ;; Surface inferred from file citation → "filesystem"
            (is (str/includes? content "surfaces: [filesystem]"))))
        ;; INDEX.md was prepended
        (let [idx (io/file tmp-dir ".brainyard/agents/explore-agent/INDEX.md")]
          (is (.isFile idx))
          (is (str/includes? (slurp idx) "loop guard")))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "long answer with no entity citations still persists by length"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (with-redefs [config/project-dir (constantly tmp-dir)]
          (let [answer (apply str (repeat 1500 "x "))]
            (explore/explore-auto-persist
             {:agent  (fake-explore-agent)
              :input  "Tell me about X"
              :result {:answer answer}})))
        (is (.isDirectory (io/file tmp-dir ".brainyard/agents/explore-agent/results")))
        (finally
          (delete-recursive (io/file tmp-dir)))))))

(deftest auto-persist-idempotent-test
  (testing "answer already containing `Saved exploration:` line is NOT re-persisted"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (with-redefs [config/project-dir (constantly tmp-dir)]
          (let [answer (str "## Findings\n\n"
                            "The loop guard lives in `components/agent/src/ai/brainyard/agent/common/loop_guard_hook.clj`.\n\n"
                            "Saved exploration: .brainyard/agents/explore-agent/results/20260101-120000-loop-guard.md\n")]
            (explore/explore-auto-persist
             {:agent  (fake-explore-agent)
              :input  "Q"
              :result {:answer answer}})))
        (is (not (.exists (io/file tmp-dir ".brainyard/agents/explore-agent")))
            "should not create directory when LLM already persisted itself")
        (finally
          (delete-recursive (io/file tmp-dir)))))))

(deftest auto-persist-other-agent-skip-test
  (testing "non-explore-agent invocations are ignored"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (with-redefs [config/project-dir (constantly tmp-dir)]
          (let [answer (apply str (repeat 2000 "x "))]
            (explore/explore-auto-persist
             {:agent  (fake-other-agent)
              :input  "Q"
              :result {:answer answer}})))
        (is (not (.exists (io/file tmp-dir ".brainyard/agents/explore-agent")))
            "other-agent answer must not be auto-persisted by explore-agent's hook")
        (finally
          (delete-recursive (io/file tmp-dir)))))))

(deftest auto-persist-defensive-test
  (testing "hook never throws — malformed inputs are swallowed"
    ;; Each of these would cause a NullPointerException / type error inside
    ;; the hook body if not guarded. The contract is: the hook MUST NOT
    ;; affect the user-facing ask response.
    (is (nil? (explore/explore-auto-persist nil)))
    (is (nil? (explore/explore-auto-persist {})))
    (is (nil? (explore/explore-auto-persist {:agent nil})))
    (is (nil? (explore/explore-auto-persist {:agent (fake-explore-agent)
                                             :result :not-a-map})))
    (is (nil? (explore/explore-auto-persist {:agent (fake-explore-agent)
                                             :result {:answer 42}})))))

(deftest auto-persist-registration-test
  (testing "hook is registered for :agent.ask/post with :source :explore-agent"
    (require 'ai.brainyard.agent.core.hooks)
    ;; Re-install the auto-persist hook in case another test (e.g.
    ;; hooks-test) reset !hooks. install-auto-persist! is idempotent —
    ;; register-hook! replaces by id. Without this, alphabetical test
    ;; order (hooks-test runs before explore-agent-test) wipes the
    ;; namespace-load registration before this assertion runs.
    (explore/install-auto-persist!)
    ;; `!hooks` is a private atom — `(resolve sym)` returns the var, then
    ;; double-deref: var → atom value (the atom itself) → atom contents (map).
    (let [hooks-var   (resolve 'ai.brainyard.agent.core.hooks/!hooks)
          hooks-state @@hooks-var
          entries     (get hooks-state :agent.ask/post [])
          ours        (first (filter #(= ::explore/explore-auto-persist (:id %)) entries))]
      (is (some? ours))
      (is (= :explore-agent (:source ours)))
      (is (fn? (:handler ours)))
      (is (fn? (:match ours))))))
