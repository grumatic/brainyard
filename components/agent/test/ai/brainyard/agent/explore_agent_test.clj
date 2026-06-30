;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.explore-agent-test
  "Tests for explore-agent: registration, inherited bt-factory (CoAct),
   curated agent-tools roster across the four exploration surfaces (positive
   + negative assertions), instruction-content anchors that pin the routing +
   reuse + persistence + handoff contract, and unit tests for the surviving
   explore$* READER commands (explore$find corpus search, explore$reuse?
   freshness rule, explore$read-frontmatter lineage round-trip) plus the
   template-fill auto-persist safety net.

   The write-side helper chain (explore$slug / explore$frontmatter /
   explore$write / explore$index-append) is RETIRED — explore-agent now
   authors the dossier as markdown directly with write-file from the RESULT
   TEMPLATE (see docs/design/explore-agent-design.md), so there
   are no helper-roundtrip tests; the readers are tested against hand-written
   frontmatter (what the LLM actually produces)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.agent.common.explore :as explore]
            [ai.brainyard.agent.common.explore-agent]
            [ai.brainyard.agent.common.coact-agent :as rca]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.clj-sandbox.interface :as clj-sandbox])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)))

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
      ;; A. FILESYSTEM — write-file is the authoring path now
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

      ;; Runtime config (per-turn threshold / reuse-window tuning)
      (is (contains? ids :agent-runtime$config))

      ;; explore$* SURVIVING readers (write-side helpers retired)
      (is (contains? ids :explore$find))
      (is (contains? ids :explore$read-frontmatter))
      (is (contains? ids :explore$reuse?))))

  (testing "explore-agent :agent-tools EXCLUDES retired write-side helpers + forbidden tools"
    (let [ids (explore-tool-ids)]
      ;; Retired authoring helpers — replaced by direct write-file
      (is (not (contains? ids :explore$slug)))
      (is (not (contains? ids :explore$frontmatter)))
      (is (not (contains? ids :explore$write)))
      (is (not (contains? ids :explore$index-append)))

      ;; Hard Rule 1 — no clone-self recursion
      (is (not (contains? ids :query$clone))
          "query$clone must not be in explore-agent's roster (clone-self forbidden)")

      ;; Skill-authoring lives in skill-agent, not explore-agent
      (is (not (contains? ids :skills$write)))
      (is (not (contains? ids :skills$install)))
      (is (not (contains? ids :skills$sync))))))

;; ============================================================================
;; Instruction content anchors — routing + reuse + persistence + handoff
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

      ;; STEP 0 reuse gate (the new pillar)
      (is (str/includes? instruction "STEP 0"))
      (is (str/includes? instruction "REUSE"))
      (is (str/includes? instruction "explore$find"))
      (is (str/includes? instruction "explore$reuse?"))

      ;; Persistence contract — template-based, with lineage + freshness
      (is (str/includes? instruction ".brainyard/agents/explore-agent/"))
      (is (str/includes? instruction "RESULT TEMPLATE"))
      (is (str/includes? instruction "write-file"))
      (is (str/includes? instruction "related:"))
      (is (str/includes? instruction "freshness:"))
      (is (str/includes? instruction "Saved exploration:"))

      ;; The retired helper chain must NOT be advertised any more
      (is (not (str/includes? instruction "explore$write")))
      (is (not (str/includes? instruction "explore$frontmatter")))

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

      ;; Surviving readers documented; authoring is write-file
      (is (str/includes? tool-context "explore$find"))
      (is (str/includes? tool-context "explore$reuse?"))
      (is (str/includes? tool-context "explore$read-frontmatter"))
      (is (str/includes? tool-context "write-file"))
      (is (str/includes? tool-context "INDEX.md"))

      ;; Retired helpers must not be documented
      (is (not (str/includes? tool-context "explore$write")))
      (is (not (str/includes? tool-context "explore$index-append"))))))

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

(defn- write-dossier!
  "Spit a dossier file with hand-written frontmatter (what the LLM produces)
   under <base-dir>/.brainyard/agents/explore-agent/results/<filename>. Returns
   the repo-relative path. Body is minimal; the readers only parse frontmatter."
  [base-dir filename {:keys [slug question surfaces files urls related freshness
                             created summary]
                      :or   {surfaces [] files [] urls [] related []
                             freshness "static" question "Q?" summary "summary"}}]
  (let [dir (io/file base-dir ".brainyard/agents/explore-agent/results")
        f   (io/file dir filename)
        fm  (str "---\n"
                 "slug: " slug "\n"
                 "question: \"" question "\"\n"
                 "created: " (or created (str (Instant/now))) "\n"
                 "agent: explore-agent\n"
                 "surfaces: [" (str/join ", " surfaces) "]\n"
                 "entities:\n"
                 "  files: [" (str/join ", " files) "]\n"
                 "  urls: [" (str/join ", " urls) "]\n"
                 "  mcp_tools: []\n"
                 "  skills: []\n"
                 "related: [" (str/join ", " related) "]\n"
                 "freshness: " freshness "\n"
                 "summary: >\n  " summary "\n"
                 "---\n# body\n")]
    (.mkdirs dir)
    (spit f fm)
    (str ".brainyard/agents/explore-agent/results/" filename)))

;; ============================================================================
;; explore$find — corpus search (the reuse gate)
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
        ;; Timestamp prefixes sort newest-last lexically; explore$find reverses.
        (write-dossier! tmp-dir "20260101-120000-alpha-loop-guard.md"
                        {:slug "alpha-loop-guard" :question "Q1" :surfaces ["filesystem"]
                         :summary "First exploration about loop guard."})
        (write-dossier! tmp-dir "20260102-120000-beta-mcp.md"
                        {:slug "beta-mcp" :question "Q2" :surfaces ["mcp"]
                         :summary "Second exploration about Linear."})

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
            (is (= "beta-mcp" (:slug (first (:matches r)))))
            (is (= "alpha-loop-guard" (:slug (second (:matches r)))))))

        (testing "match on question (fallback scan only — INDEX absent)"
          (let [r (explore/explore$find :query "Q2" :base-dir tmp-dir)]
            (is (= 1 (:n-matches r)))
            (is (= "beta-mcp" (:slug (first (:matches r)))))))

        (testing "no match → empty"
          (let [r (explore/explore$find :query "nonsense-xyzzy" :base-dir tmp-dir)]
            (is (= 0 (:n-matches r)))))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "validation"
    (is (contains? (explore/explore$find) :error))))

;; ============================================================================
;; explore$read-frontmatter — lineage round-trip + error paths
;; ============================================================================

(deftest read-frontmatter-test
  (testing "frontmatter (incl. related + freshness) parses back"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (let [path (write-dossier! tmp-dir "20260601-090000-mcp-servers.md"
                                   {:slug "mcp-servers" :question "Where are MCP servers configured?"
                                    :surfaces ["filesystem" "mcp"]
                                    :files ["mcp/integration.clj" "mcp/client.clj"]
                                    :related [".brainyard/agents/explore-agent/results/20260101-000000-prior.md"]
                                    :freshness "volatile"
                                    :summary "MCP servers configured in integration.clj."})
              parsed (explore/explore$read-frontmatter :path path :base-dir tmp-dir)]
          (is (= "mcp-servers" (:slug parsed)))
          (is (= "explore-agent" (:agent parsed)))
          (is (= ["filesystem" "mcp"] (:surfaces parsed)))
          (is (= ["mcp/integration.clj" "mcp/client.clj"]
                 (get-in parsed [:entities :files])))
          (is (= [".brainyard/agents/explore-agent/results/20260101-000000-prior.md"]
                 (:related parsed)))
          (is (= "volatile" (:freshness parsed)))
          (is (str/includes? (:summary parsed) "MCP servers configured"))
          (is (not (contains? parsed :error))))
        (finally
          (delete-recursive (io/file tmp-dir))))))

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

(deftest read-frontmatter-block-list-test
  (testing "list keys parse from YAML block-list style, not just flow vectors
            (capable models emit `- item` lists even when the template shows [a, b])"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (let [dir     (io/file tmp-dir ".brainyard/agents/explore-agent/results")
              _       (.mkdirs dir)
              content (str "---\n"
                           "slug: block-list\n"
                           "question: \"q\"\n"
                           "created: 2026-06-29T00:00:00Z\n"
                           "agent: explore-agent\n"
                           "surfaces:\n  - filesystem\n  - mcp\n"
                           "entities:\n"
                           "  files:\n    - components/a.clj\n    - components/b.clj\n"
                           "  urls: []\n"
                           "  mcp_tools:\n    - linear:get-issue\n"
                           "  skills: []\n"
                           "related:\n  - .brainyard/agents/explore-agent/results/x.md\n"
                           "freshness: static\n"
                           "summary: >\n  one line summary\n"
                           "---\n# body\n")
              _       (spit (io/file dir "20260629-000000-block-list.md") content)
              r       (explore/explore$read-frontmatter
                       :path ".brainyard/agents/explore-agent/results/20260629-000000-block-list.md"
                       :base-dir tmp-dir)]
          (is (= ["filesystem" "mcp"] (:surfaces r)))
          (is (= ["components/a.clj" "components/b.clj"] (get-in r [:entities :files])))
          (is (= ["linear:get-issue"] (get-in r [:entities :mcp_tools])))
          (is (= [".brainyard/agents/explore-agent/results/x.md"] (:related r)))
          (is (= "static" (:freshness r)))
          (is (str/includes? (:summary r) "one line summary")))
        (finally
          (delete-recursive (io/file tmp-dir)))))))

;; ============================================================================
;; explore$reuse? — freshness rule (§7.4)
;; ============================================================================

(deftest reuse?-test
  (testing "static dossier with unchanged cited files → reuse"
    (let [tmp-dir (make-tmp-dir)]
      (try
        ;; Create the cited file first, then stamp the dossier `created` after.
        (let [cited (io/file tmp-dir "src/foo.clj")]
          (.mkdirs (.getParentFile cited))
          (spit cited "(ns foo)")
          (Thread/sleep 20)
          (let [path (write-dossier! tmp-dir "20260601-100000-static-fresh.md"
                                     {:slug "static-fresh" :surfaces ["filesystem"]
                                      :files ["src/foo.clj"] :freshness "static"
                                      :created (str (Instant/now))})
                r    (explore/explore$reuse? :path path :base-dir tmp-dir)]
            (is (true? (:reuse? r)))
            (is (= "static" (:freshness r)))
            (is (= [] (:changed r)))))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "static dossier with a changed cited file → stale"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (let [cited (io/file tmp-dir "src/foo.clj")]
          (.mkdirs (.getParentFile cited))
          (spit cited "(ns foo)")
          (let [created (Instant/now)
                path    (write-dossier! tmp-dir "20260601-100000-static-stale.md"
                                        {:slug "static-stale" :surfaces ["filesystem"]
                                         :files ["src/foo.clj"] :freshness "static"
                                         :created (str created)})]
            ;; Bump the cited file's mtime past `created`.
            (.setLastModified cited (+ (.toEpochMilli created) 600000))
            (let [r (explore/explore$reuse? :path path :base-dir tmp-dir)]
              (is (false? (:reuse? r)))
              (is (= ["src/foo.clj"] (:changed r))))))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "static dossier citing a now-missing file → stale"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (let [path (write-dossier! tmp-dir "20260601-100000-static-missing.md"
                                   {:slug "static-missing" :surfaces ["filesystem"]
                                    :files ["src/gone.clj"] :freshness "static"})
              r    (explore/explore$reuse? :path path :base-dir tmp-dir)]
          (is (false? (:reuse? r)))
          (is (= ["src/gone.clj"] (:changed r))))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "volatile dossier within the window → reuse"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (let [path (write-dossier! tmp-dir "20260601-100000-vol-fresh.md"
                                   {:slug "vol-fresh" :surfaces ["web"]
                                    :urls ["https://example.com"] :freshness "volatile"
                                    :created (str (Instant/now))})
              r    (explore/explore$reuse? :path path :base-dir tmp-dir)]
          (is (true? (:reuse? r)))
          (is (= "volatile" (:freshness r))))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "volatile dossier older than the window → stale"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (let [old  (.minusSeconds (Instant/now) (* 48 3600))
              path (write-dossier! tmp-dir "20260101-100000-vol-stale.md"
                                   {:slug "vol-stale" :surfaces ["web"]
                                    :urls ["https://example.com"] :freshness "volatile"
                                    :created (str old)})
              r    (explore/explore$reuse? :path path :base-dir tmp-dir)]
          (is (false? (:reuse? r)))
          (is (> (:age-hours r) 24.0)))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing ":volatile-hours override widens the window"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (let [old  (.minusSeconds (Instant/now) (* 48 3600))
              path (write-dossier! tmp-dir "20260101-100000-vol-override.md"
                                   {:slug "vol-override" :surfaces ["web"]
                                    :urls ["https://example.com"] :freshness "volatile"
                                    :created (str old)})
              r    (explore/explore$reuse? :path path :base-dir tmp-dir :volatile-hours 72)]
          (is (true? (:reuse? r))))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "missing dossier → :error"
    (is (contains? (explore/explore$reuse? :path "/no/such.md") :error)))

  (testing "validation"
    (is (contains? (explore/explore$reuse?) :error))))

;; ============================================================================
;; Auto-persist safety net (gated :agent.ask/finalize)
;;
;; Test the handler fn directly with a fake agent map satisfying the hook's
;; proto/defagent-type check. The handler now fills the §5 RESULT TEMPLATE,
;; spits ONE file + INDEX line, and returns a :replace decision injecting the
;; absent `Saved exploration:` handoff line.
;; ============================================================================

(defn- fake-explore-agent []
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

(deftest auto-persist-bolded-marker-idempotent-test
  (testing "a markdown-bolded **Saved exploration:** marker still counts as saved (no re-persist)"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (with-redefs [config/project-dir (constantly tmp-dir)]
          (let [answer (str "## Findings\n\nGuard is in `components/agent/src/ai/x.clj`.\n\n"
                            "**Saved exploration:** `.brainyard/agents/explore-agent/results/20260101-120000-x.md`\n")
                r      (explore/explore-auto-persist
                        {:agent (fake-explore-agent) :input "Q" :result {:answer answer}})]
            (is (nil? r) "bolded marker → already saved → no :replace decision")))
        (is (not (.exists (io/file tmp-dir ".brainyard/agents/explore-agent")))
            "must not re-persist when the LLM already emitted a (bolded) marker")
        (finally
          (delete-recursive (io/file tmp-dir)))))))

(deftest auto-persist-trivial-skip-test
  (testing "trivial answer (< 1000 chars, zero entities) is NOT persisted"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (with-redefs [config/project-dir (constantly tmp-dir)]
          (let [r (explore/explore-auto-persist
                   {:agent  (fake-explore-agent)
                    :input  "What's 2+2?"
                    :result {:answer "4"}})]
            (is (nil? r) "trivial answer → no :replace decision")))
        (is (not (.exists (io/file tmp-dir ".brainyard/agents/explore-agent/results")))
            "results dir should not exist for trivial answers")
        (finally
          (delete-recursive (io/file tmp-dir)))))))

(deftest auto-persist-non-trivial-test
  (testing "non-trivial answer fills the template, writes one file + INDEX, injects the handoff line"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (with-redefs [config/project-dir (constantly tmp-dir)]
          (let [answer (str "## Findings\n\n"
                            "The loop guard lives in `components/agent/src/ai/brainyard/agent/common/loop_guard_hook.clj`.\n\n"
                            "It blocks the 3rd consecutive identical tool call.\n")
                r      (explore/explore-auto-persist
                        {:agent  (fake-explore-agent)
                         :input  "Where is the loop guard implemented?"
                         :result {:answer answer}})]
            (testing "returns a :replace decision injecting Saved exploration:"
              (is (= :replace (:result r)))
              (is (str/includes? (:answer (:replacement r)) "Saved exploration: ")))))
        ;; Exactly one dossier written, with the template's new fields
        (let [results-dir (io/file tmp-dir ".brainyard/agents/explore-agent/results")
              files       (when (.isDirectory results-dir) (vec (.listFiles results-dir)))]
          (is (.isDirectory results-dir))
          (is (= 1 (count files)))
          (let [content (slurp (first files))]
            (is (str/starts-with? content "---\n"))
            (is (str/includes? content "agent: explore-agent"))
            (is (str/includes? content "loop_guard_hook.clj"))
            (is (str/includes? content "surfaces: [filesystem]"))
            (is (str/includes? content "related: []"))
            (is (str/includes? content "freshness: static"))
            (is (str/includes? content "## What was found"))
            (is (str/includes? content "## Builds on"))))
        ;; INDEX.md was written
        (let [idx (io/file tmp-dir ".brainyard/agents/explore-agent/INDEX.md")]
          (is (.isFile idx))
          (is (str/includes? (slurp idx) "loop")))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "long answer with no entity citations still persists by length, infers static"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (with-redefs [config/project-dir (constantly tmp-dir)]
          (let [answer (apply str (repeat 1500 "x "))]
            (explore/explore-auto-persist
             {:agent  (fake-explore-agent)
              :input  "Tell me about X"
              :result {:answer answer}})))
        (let [results-dir (io/file tmp-dir ".brainyard/agents/explore-agent/results")]
          (is (.isDirectory results-dir))
          (is (str/includes? (slurp (first (.listFiles results-dir))) "freshness: static")))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "web-cited answer infers freshness volatile"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (with-redefs [config/project-dir (constantly tmp-dir)]
          (let [answer (str "## Findings\n\nSee the docs at https://example.com/guide for details.\n"
                            (apply str (repeat 600 "y ")))]
            (explore/explore-auto-persist
             {:agent  (fake-explore-agent)
              :input  "What does the guide say?"
              :result {:answer answer}})))
        (let [results-dir (io/file tmp-dir ".brainyard/agents/explore-agent/results")]
          (is (str/includes? (slurp (first (.listFiles results-dir))) "freshness: volatile")))
        (finally
          (delete-recursive (io/file tmp-dir)))))))

(deftest auto-persist-idempotent-test
  (testing "answer already containing `Saved exploration:` line is NOT re-persisted"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (with-redefs [config/project-dir (constantly tmp-dir)]
          (let [answer (str "## Findings\n\n"
                            "The loop guard lives in `components/agent/src/ai/brainyard/agent/common/loop_guard_hook.clj`.\n\n"
                            "Saved exploration: .brainyard/agents/explore-agent/results/20260101-120000-loop-guard.md\n")
                r      (explore/explore-auto-persist
                        {:agent  (fake-explore-agent)
                         :input  "Q"
                         :result {:answer answer}})]
            (is (nil? r) "already-saved → no :replace decision")))
        (is (not (.exists (io/file tmp-dir ".brainyard/agents/explore-agent")))
            "should not create directory when LLM already persisted itself")
        (finally
          (delete-recursive (io/file tmp-dir)))))))

(deftest auto-persist-other-agent-skip-test
  (testing "non-explore-agent invocations are ignored"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (with-redefs [config/project-dir (constantly tmp-dir)]
          (let [answer (apply str (repeat 2000 "x "))
                r      (explore/explore-auto-persist
                        {:agent  (fake-other-agent)
                         :input  "Q"
                         :result {:answer answer}})]
            (is (nil? r))))
        (is (not (.exists (io/file tmp-dir ".brainyard/agents/explore-agent")))
            "other-agent answer must not be auto-persisted by explore-agent's hook")
        (finally
          (delete-recursive (io/file tmp-dir)))))))

(deftest auto-persist-defensive-test
  (testing "hook never throws — malformed inputs are swallowed"
    (is (nil? (explore/explore-auto-persist nil)))
    (is (nil? (explore/explore-auto-persist {})))
    (is (nil? (explore/explore-auto-persist {:agent nil})))
    (is (nil? (explore/explore-auto-persist {:agent (fake-explore-agent)
                                             :result :not-a-map})))
    (is (nil? (explore/explore-auto-persist {:agent (fake-explore-agent)
                                             :result {:answer 42}})))))

(deftest materialize-auto-dossier-direct-test
  (testing "materialize-auto-dossier! returns rel-path + slug and writes the file"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (let [answer (str "## Findings\n\nThe guard is in `components/agent/src/ai/x.clj`.\n")
              r      (explore/materialize-auto-dossier!
                      {:answer answer :question "Where is the guard?" :base-dir tmp-dir})]
          (is (string? (:rel-path r)))
          (is (string? (:slug r)))
          (is (.isFile (io/file (:path r)))))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "disabled? short-circuits"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (is (nil? (explore/materialize-auto-dossier!
                   {:answer (apply str (repeat 2000 "x "))
                    :question "Q" :base-dir tmp-dir :enabled? false})))
        (is (not (.exists (io/file tmp-dir ".brainyard/agents/explore-agent"))))
        (finally
          (delete-recursive (io/file tmp-dir)))))))

(deftest auto-persist-registration-test
  (testing "hook is registered for :agent.ask/finalize with :source :explore-agent"
    (require 'ai.brainyard.agent.core.hooks)
    (explore/install-auto-persist!)
    (let [hooks-var   (resolve 'ai.brainyard.agent.core.hooks/!hooks)
          hooks-state @@hooks-var
          entries     (get hooks-state :agent.ask/finalize [])
          ours        (first (filter #(= ::explore/explore-auto-persist (:id %)) entries))]
      (is (some? ours))
      (is (= :explore-agent (:source ours)))
      (is (fn? (:handler ours)))
      (is (fn? (:match ours))))))

;; ============================================================================
;; Integration — verbatim content block round-trips to a dossier via write-file
;;
;; Exercises the realistic authoring path: the LLM emits the whole dossier
;; (YAML frontmatter + markdown body, including a nested ``` code fence) as a
;; four-backtick `markdown` block instead of hand-escaping it, then promotes
;; that content to a durable dossier with a direct file write. Proves the
;; verbatim body rides back on the eval-result's `:code` so it's recoverable.
;; ============================================================================

(defn- minimal-sandbox []
  (clj-sandbox/create-sandbox :context {} :bindings {}))

(defn- st-with-code-blocks [sb code-blocks]
  (atom {:iteration-count 0
         :iterations []
         :answer ""
         :terminated false
         :tool-calls []
         :sandbox sb
         :code-blocks code-blocks}))

(deftest verbatim-block-generates-dossier-test
  (let [tmp-dir (make-tmp-dir)
        dossier (str "---\n"
                     "slug: verbatim-path\n"
                     "question: \"How does the verbatim eval path carry content?\"\n"
                     "created: 2026-06-07T00:00:00Z\n"
                     "agent: explore-agent\n"
                     "surfaces: [filesystem]\n"
                     "entities:\n"
                     "  files: [components/agent/.../coact_agent.clj]\n"
                     "  urls: []\n"
                     "  mcp_tools: []\n"
                     "  skills: []\n"
                     "related: []\n"
                     "freshness: static\n"
                     "summary: >\n  run-verbatim-block now returns the body on :code.\n"
                     "---\n"
                     "# What was found\n\n"
                     "The verbatim body survives nested fences verbatim:\n\n"
                     "```clojure\n"
                     "(run-verbatim-block lang content filename)\n"
                     "```\n\n"
                     "## Where\n\n"
                     "components/agent/src/ai/brainyard/agent/common/coact_agent.clj")
        code-blocks (str "````markdown explore-dossier.md\n" dossier "\n````")
        sb (minimal-sandbox)
        st (st-with-code-blocks sb code-blocks)]
    (try
      (rca/coact-code-eval-action {:st-memory st :agent nil})
      (let [entries (:last-code-results @st)
            e       (first entries)]
        (testing "the verbatim block produced exactly one eval entry"
          (is (= :code (:last-channel @st)))
          (is (= 1 (count entries)))
          (is (= "markdown" (:lang e)))
          (is (str/blank? (:error e))))

        (testing "the full body rides back on :code (the run-verbatim-block contract)"
          (is (= dossier (:code e))))

        (testing ":result is a scratch path holding the byte-for-byte body"
          (is (string? (:result e)))
          (is (.isFile (io/file (:result e))))
          (is (= dossier (slurp (io/file (:result e)))))
          (is (str/includes? (:output e) "Wrote ")))

        ;; --- Promote the recovered content into a dossier via a direct write ---
        ;; This is the write-file authoring path the redesign mandates (no
        ;; explore$write helper). We write the recovered :code to the results path.
        (let [recovered (:code e)
              rel       ".brainyard/agents/explore-agent/results/20260607-000000-verbatim-path.md"
              out       (io/file tmp-dir rel)]
          (.mkdirs (.getParentFile out))
          (spit out recovered)
          (testing "the persisted dossier is the verbatim content, intact"
            (let [written (slurp out)]
              (is (= recovered written))
              (is (str/starts-with? written "---\n"))
              (is (str/includes? written "agent: explore-agent"))
              (is (str/includes? written "related: []"))
              (is (str/includes? written "freshness: static"))
              (is (str/includes? written "```clojure"))
              (is (str/includes? written "(run-verbatim-block lang content filename)"))))

          (testing "the new dossier is discoverable + parseable by the readers"
            (let [found  (explore/explore$find :query "verbatim" :base-dir tmp-dir)
                  parsed (explore/explore$read-frontmatter :path rel :base-dir tmp-dir)]
              (is (= 1 (:n-matches found)))
              (is (= "verbatim-path" (:slug parsed)))
              (is (= [] (:related parsed)))
              (is (= "static" (:freshness parsed)))))))
      (finally
        (when-let [p (some-> (:last-code-results @st) first :result)]
          (.delete (io/file p)))
        (delete-recursive (io/file tmp-dir))))))
