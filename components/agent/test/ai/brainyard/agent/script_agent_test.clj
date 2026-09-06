;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.script-agent-test
  "Tests for script-agent and the script library.

   The properties under test are all CONTRACTS rather than behaviours: that the
   compiled signature, the rendered prompt and the eval dispatch agree about
   which fences exist, and that the library's PATH composition matches the
   index the prompt shows. A drift between any two of those is invisible at
   runtime until a turn is spent on it, which is exactly why they are pinned
   here rather than left to a live run.

   No LLM is involved. `run-single-block` is driven directly, which is enough
   because the language gate sits in it, above every executing arm."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.agent.common.coact-agent :as ca]
            [ai.brainyard.agent.common.script-agent]
            ;; The mode test instantiates these by id, so their defagents have
            ;; to be registered. In the dev REPL everything is loaded and this
            ;; passes without them; `bb test:ns` runs a clean JVM and does not.
            [ai.brainyard.agent.common.router-agent]
            [ai.brainyard.agent.common.react-agent]
            [ai.brainyard.agent.common.scripts :as scripts]
            [ai.brainyard.agent.core.agent :as agent]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.context-budget :as cb]
            [ai.brainyard.agent.core.context.section-assembler :as sa]
            [ai.brainyard.agent.core.tool :as tool])
  (:import [java.io File]))

(def ^:private script-langs #{:bash :python})

(defn- resolve-private [sym]
  @(resolve (symbol "ai.brainyard.agent.common.coact-agent" (name sym))))

(defn- tmp-dir!
  "A fresh scratch directory, removed by `delete-tree!`."
  [label]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "by-script-test-" label "-" (System/nanoTime)))]
    (.mkdirs d)
    d))

(defn- delete-tree! [^File f]
  (when (.isDirectory f) (run! delete-tree! (.listFiles f)))
  (.delete f))

(defn- write-script! [dir nm body]
  (let [d (io/file dir "bin")
        f (io/file d nm)]
    (.mkdirs d)
    (spit f body)
    (.setExecutable f true true)
    f))

;; ============================================================================
;; 1. Registration
;; ============================================================================

(deftest script-agent-registered-test
  (testing "script-agent self-registers as a defagent"
    (let [d (get @tool/!tool-defs :script-agent)]
      (is (some? d) "script-agent must be in !tool-defs")
      (is (= :agent (:type d)))))

  (testing "its config pins two channels and two languages"
    (let [m (:meta (get @tool/!tool-defs :script-agent))
          x (:config-extra m)]
      (is (true?  (:code-channel? x)))
      (is (false? (:tool-channel? x)))
      (is (= [:bash :python] (:code-langs x)))
      (is (= {:tools []} (:agent-tools m))
          "an explicit empty roster is what stops default-agent-roster being inherited"))))

;; ============================================================================
;; 2. The compiled output contract
;; ============================================================================

(deftest signature-narrows-to-the-enabled-languages-test
  (let [sigf (resolve-private 'think-act-code-signature)
        full (resolve-private 'ThinkActCode)
        ks   #(set (keys (:outputs %)))
        sig  (sigf true false script-langs)]

    (testing "the tool channel's output field is gone"
      (is (not (contains? (ks sig) :tool-calls)))
      (is (contains? (ks sig) :code-blocks))
      (is (contains? (ks sig) :answer)))

    (testing "the code-blocks description names bash and python and NOTHING else"
      (let [d (-> sig :outputs :code-blocks second :desc)]
        (is (str/includes? d "bash"))
        (is (str/includes? d "python"))
        (is (not (str/includes? d "clojure"))
            "a schema that still names clojure re-creates the contradiction")
        (is (not (str/includes? d "javascript")))
        (is (str/includes? d "refused")
            "the refusal is the contract; the model must be able to read it")))

    (testing "the JSON schema does not GROW — it rides the system message"
      (is (< (count (str (:output-json-schema sig)))
             (count (str (:output-json-schema full))))))

    (testing "the two-arity form is unchanged by the language gate"
      ;; Every pre-existing call site goes through it, so it must compile
      ;; exactly what it did before — identity, not equality.
      (is (identical? full (sigf true true)))
      (is (identical? (sigf true false) (sigf true false)))
      (is (= (:instructions (sigf true false))
             (:instructions (sigf true false ca/all-code-langs)))
          "the 2-arity form must mean 'all languages', not 'some default'"))))

(deftest instructions-drop-the-clojure-prose-test
  (let [ri     (resolve-private 'render-instructions)
        script (ri true false script-langs)
        clj    (ri true false)]

    (testing "no Selmer markup or HTML escaping survives"
      (is (not (str/includes? script "{%")))
      (is (not (str/includes? script "{{")))
      (is (not (str/includes? script "&quot;"))))

    (testing "clojure-specific prose is gone, script prose is kept"
      (is (not (str/includes? script "pmap")))
      (is (not (str/includes? script "clojure")))
      (is (not (str/includes? script "SCI")))
      (is (str/includes? script "ParallelBlock")
          "the marker DOES apply to bash/python and must survive")
      (is (str/includes? script "CODE CHANNEL"))
      (is (str/includes? script "ANSWER CHANNEL")))

    (testing "narrowing shrinks the block"
      (is (< (count script) (count clj))))

    (testing "heuristic numbering stays contiguous from 1"
      ;; The clojure rows are gated out, so the remaining rows must renumber.
      (let [rows (->> (str/split-lines script)
                      (drop-while #(not (str/includes? % "CHANNEL DECISION HEURISTICS")))
                      (keep #(second (re-find #"^(\d+)\. " %)))
                      (mapv parse-long))]
        (is (seq rows))
        (is (= rows (vec (range 1 (inc (count rows))))) (str "rows: " rows))))))

;; ============================================================================
;; 3. The rendered prompt
;; ============================================================================

(defn- script-sections []
  (sa/sections (resolve-private 'coact-assembler)
               {:agent-tools []
                :sandbox-bindings {}
                :code-channel? true
                :tool-channel? false
                :code-langs script-langs
                :scripts "## Scripts — your reusable tools\nfoo — does a thing."
                :execution-model nil}))

(deftest prompt-has-no-registry-surface-test
  (let [s (script-sections)
        all (str/join "\n" (vals s))]

    (testing "the tool surface is the Scripts section, not the registry"
      (is (contains? s :scripts))
      (is (not (contains? s :tools))
          "no bindings and no roster ⇒ no ## Tools section at all")
      (is (not (contains? s :tool-call-format))
          "there is no JSON channel to describe")
      (is (not (contains? s :sandbox-context-accessor))
          "context-get is an SCI construct and there is no SCI here")
      (is (not (contains? s :channel-routing))
          "'which channel' earns nothing with one action channel"))

    (testing "the five registry substrates are dropped"
      ;; Each one's every verb is a registered tool this agent cannot invoke.
      (doseq [k [:skill-substrate :mcp-substrate :todo-substrate
                 :exec-substrate :subagent-substrate]]
        (is (not (contains? s k)) (str k " describes tools with no channel"))))

    (testing "the rules and playbook are the script variants"
      (is (not (str/includes? (:critical-rules s) "usage$guide"))
          "usage$guide is called from a clojure fence that does not exist here")
      (is (not (str/includes? (:large-results-playbook s) "read-file"))
          "the recovery verbs must be shell ones, not the read-file TOOL")
      (is (str/includes? (:large-results-playbook s) "sed -n")))

    (testing "the role names the two real channels"
      (is (str/includes? (:role s) "bash / python"))
      (is (not (str/includes? (:role s) "SCI"))))

    (testing "nothing anywhere promises a clojure fence"
      (is (not (str/includes? all "```clojure"))))))

(deftest scripts-section-rides-the-session-zone-test
  (testing ":scripts is in the system order, in the :session-context zone"
    ;; Zone placement is not cosmetic: :agent-core is the largest cached prefix
    ;; and invalidates only on an agent/binary upgrade. The index changes every
    ;; time the model saves a script, so parking it there would bust the whole
    ;; static prefix on a `chmod +x`. :session-context is the zone whose stated
    ;; cadence is "one cache miss per on-disk edit".
    (let [asm    (resolve-private 'coact-assembler)
          order  (resolve-private 'coact-system-order)
          marker "zzz-unique-index-marker"
          secs   (sa/sections asm {:agent-tools [] :sandbox-bindings {}
                                   :code-channel? true :tool-channel? false
                                   :code-langs script-langs
                                   :scripts (str "## Scripts\n" marker)})
          zones  (into {} (map (fn [[z o]] [z (cb/compose secs o)])) (sa/system-zones asm))]
      (is (some #{:scripts} order)
          "a section missing from the order is silently dropped by compose")
      (is (str/includes? (str (:session-context zones)) marker))
      (is (not (str/includes? (str (:agent-core zones)) marker))
          ":scripts must not sit in the static zone — it changes on every save"))))

(deftest prompt-is-materially-smaller-test
  (testing "the script prompt is a fraction of the full CoAct one"
    ;; The claim this design is sold on. A regression here means a registry
    ;; section crept back in for an agent that cannot use it.
    (let [chars #(reduce + (map (comp count str) (vals %)))
          script (chars (script-sections))
          coact  (chars (sa/sections (resolve-private 'coact-assembler)
                                     {:agent-tools [] :sandbox-bindings {}
                                      :code-channel? true :tool-channel? true}))]
      (is (< script (* 0.7 coact))
          (str "script=" script " coact=" coact)))))

(deftest coact-prompt-is-untouched-test
  (testing "an ordinary agent still gets every section it did before"
    (let [s (sa/sections (resolve-private 'coact-assembler)
                         {:agent-tools [] :sandbox-bindings {}
                          :code-channel? true :tool-channel? true})]
      (doseq [k [:tool-call-format :channel-routing :sandbox-context-accessor
                 :skill-substrate :mcp-substrate :todo-substrate :exec-substrate
                 :subagent-substrate :critical-rules :large-results-playbook]]
        (is (contains? s k) (str k " must survive for a full-channel agent")))
      (is (str/includes? (:critical-rules s) "usage$guide")
          "the registry variant is what a registry agent gets"))))

;; ============================================================================
;; 4. Dispatch refuses rather than executes
;; ============================================================================

(deftest disabled-language-is-refused-not-run-test
  (let [rsb  (resolve-private 'run-single-block)
        opts {:auto-bg-ms 180000 :agent nil :code-langs script-langs}
        run  #(rsb nil {:lang %1 :code %2} opts)]

    (testing "a clojure fence never reaches the evaluator"
      ;; If it did, the nil sandbox would NPE rather than return a value —
      ;; so a clean :error map is itself evidence the gate ran first.
      (let [r (run "clojure" "(+ 1 1)")]
        (is (str/includes? (:error r) "not enabled"))
        (is (str/includes? (:error r) "bash, python")
            "the refusal must say what IS enabled, or it costs another iteration")
        (is (nil? (:result r)))))

    (testing "javascript is refused too"
      (is (str/includes? (:error (run "javascript" "1")) "not enabled")))

    (testing "the enabled languages run"
      (let [r (run "bash" "echo ok-bash")]
        (is (= "" (:error r)))
        (is (str/includes? (:output r) "ok-bash")))
      (let [r (run "python" "print('ok-py')")]
        (is (= "" (:error r)))
        (is (str/includes? (:output r) "ok-py"))))

    (testing "verbatim content fences are unaffected by the language gate"
      ;; They are not executed, so the gate must not reach them.
      (let [r (rsb nil {:lang "markdown" :code "# hi" :verbatim? true} opts)]
        (is (= "" (:error r)))
        (is (some? (:result r)))))

    (testing "no gate configured ⇒ historical behaviour"
      (let [r (rsb nil {:lang "javascript" :code "1"}
                   (dissoc opts :code-langs))]
        (is (not (str/includes? (str (:error r)) "not enabled"))
            "a nil :code-langs must not silently refuse everything")))))

;; ============================================================================
;; 5. No sandbox, no inherited roster
;; ============================================================================

(deftest explicit-empty-roster-is-not-inherited-test
  (testing "{:tools []} means none; nil means inherit"
    (let [merge-fn (resolve-private 'merge-derived-tools)
          coact    {:tools [:a :b]}]
      (is (= {:tools []} (merge-fn {:tools []} coact))
          "an explicit empty roster must survive the derived merge")
      (is (= coact (merge-fn nil coact))
          "nil still means 'unspecified — inherit'")
      (is (= {:tools [:x :a :b]} (merge-fn {:tools [:x]} coact))
          "a non-empty roster still concatenates"))))

(deftest script-agent-builds-no-sandbox-test
  (let [a (agent/setup-agent-by-id
           :script-agent {:agent-session {:user-id "test" :session-id "script-sbx"}})]
    (testing "config resolves to the pinned two-channel, two-language shape"
      (let [snap (config/get-config-snapshot a)]
        (is (= script-langs ((resolve-private 'resolve-code-langs) snap)))
        (is (false? (get snap :tool-channel?)))
        (is (true? ((resolve-private 'script-library-active?) snap))
            "the library is what replaces the roster")))

    (testing "an agent WITH a clojure fence gets no library"
      ;; Both halves — PATH and prompt section — must answer the same way, and
      ;; for a registry agent that answer is no.
      (let [c (agent/setup-agent-by-id
               :coact-agent {:agent-session {:user-id "test" :session-id "script-sbx-2"}})]
        (is (false? ((resolve-private 'script-library-active?)
                     (config/get-config-snapshot c))))))))

;; ============================================================================
;; 6. The library: precedence, PATH, index
;; ============================================================================

(deftest path-composition-and-shadowing-test
  (let [root (tmp-dir! "roots")
        p    (io/file root "proj")
        u    (io/file root "user")
        b    (io/file root "builtin")]
    (try
      (write-script! p "fetch" "#!/usr/bin/env bash\n# desc: Forked fetch.\n")
      (write-script! u "hi"    "#!/usr/bin/env bash\n# desc: Say hi.\n")
      (write-script! b "fetch" "#!/usr/bin/env bash\n# desc: Builtin fetch.\n")
      (write-script! b "plain" "#!/usr/bin/env bash\necho no header\n")
      (let [roots [{:scope :project :root (str p) :bin (str (io/file p "bin")) :lib (str (io/file p "lib"))}
                   {:scope :user    :root (str u) :bin (str (io/file u "bin")) :lib (str (io/file u "lib"))}
                   {:scope :builtin :root (str b) :bin (str (io/file b "bin")) :lib (str (io/file b "lib"))}]
            entries (scripts/list-scripts roots)
            by-name (group-by :name entries)
            prologue (scripts/env-prologue roots)]

        (testing "precedence: the project copy wins, the builtin is marked"
          (is (= 2 (count (by-name "fetch"))))
          (is (= [:project false] ((juxt :scope :shadowed?) (first (by-name "fetch")))))
          (is (= [:builtin true]  ((juxt :scope :shadowed?) (second (by-name "fetch")))))
          (is (false? (:shadowed? (first (by-name "hi"))))))

        (testing "a header-less script still lists, and still runs"
          (is (nil? (:desc (first (by-name "plain"))))))

        (testing "PATH is prepended in precedence order"
          (let [path (second (re-find #"PATH=\"([^\"]*)\"" prologue))]
            (is (str/ends-with? path ":$PATH") "the host PATH must survive")
            (is (< (.indexOf path (str p)) (.indexOf path (str u)))
                "project before user")
            (is (< (.indexOf path (str u)) (.indexOf path (str b)))
                "user before builtin")))

        (testing "the scopes are exported for scripts-ls, and the write target for scripts-new"
          (is (str/includes? prologue "BY_SCRIPT_ROOTS="))
          (is (str/includes? prologue (str "BY_SCRIPT_PROJECT_BIN=\"" (io/file p "bin") "\""))))

        (testing "a lib dir that does not exist contributes no empty PATH element"
          (is (not (re-find #"::" prologue)))
          (is (not (re-find #"PATH=\":" prologue)))))
      (finally (delete-tree! root)))))

(deftest env-prologue-quotes-hostile-paths-test
  (testing "a path containing shell metacharacters is data, never a substitution"
    (let [roots [{:scope :project
                  :root "/tmp/x $(touch /tmp/pwned) `id`"
                  :bin  (System/getProperty "java.io.tmpdir")
                  :lib  (System/getProperty "java.io.tmpdir")}]
          p (scripts/env-prologue roots)]
      (is (str/includes? p "\\$(touch") "the $ must be escaped")
      (is (str/includes? p "\\`id\\`") "the backticks must be escaped"))))

(deftest header-parsing-stops-at-the-header-test
  (let [root (tmp-dir! "hdr")]
    (try
      ;; The real failure this guards: `scripts-new` contains `# name: $name`
      ;; inside the heredoc it emits, so a whole-file scan indexed the
      ;; generator as a script called `$name`.
      (write-script! root "gen"
                     (str "#!/usr/bin/env bash\n"
                          "# name: gen\n"
                          "# desc: Emits another script.\n"
                          "set -e\n"
                          "cat > x <<EOF\n"
                          "# name: \\$other\n"
                          "# desc: The generated one.\n"
                          "EOF\n"))
      (let [e (first (scripts/list-scripts
                      [{:scope :project :root (str root)
                        :bin (str (io/file root "bin")) :lib (str (io/file root "lib"))}]))]
        (is (= "gen" (:name e)) "a nested header must not rename the script")
        (is (= "Emits another script." (:desc e))))
      (finally (delete-tree! root)))))

(deftest the-filename-is-the-name-test
  (let [root (tmp-dir! "naming")]
    (try
      ;; PATH resolves the FILENAME, so anything else is a promise the shell
      ;; will not keep. Found by copying `clj-count` to `fetch` without editing
      ;; its header: the listing showed a second `clj-count` shadowing the
      ;; first, while the project `fetch` — the file that actually shadows the
      ;; builtin on PATH — disappeared from it entirely.
      (write-script! root "fetch"
                     "#!/usr/bin/env bash\n# name: clj-count\n# desc: Copied, not edited.\n")
      (write-script! root "pdf-pages.py"
                     "#!/usr/bin/env python3\n# desc: Page count.\n")
      (let [es (scripts/list-scripts
                [{:scope :project :root (str root)
                  :bin (str (io/file root "bin")) :lib (str (io/file root "lib"))}])
            by (into {} (map (juxt :name identity)) es)]
        (is (contains? by "fetch")
            "the file on PATH is `fetch`; a stale header must not hide it")
        (is (not (contains? by "clj-count"))
            "…nor invent a command that does not exist under that name")
        (is (contains? by "pdf-pages.py")
            "the extension is part of what the shell resolves")
        (is (= "Copied, not edited." (:desc (by "fetch")))
            "the rest of the header is still documentation"))
      (finally (delete-tree! root)))))

(deftest scripts-section-rendering-test
  (let [mk (fn [n] {:name (str "s" n) :scope :project :desc (str "Does " n ".")
                    :path (str "/x/s" n) :shadowed? false})]

    (testing "one line per script, and the write instructions"
      (let [s (scripts/format-scripts-section (map mk (range 3)) 60 "/proj/.brainyard/scripts/bin")]
        (is (str/includes? s "## Scripts"))
        (is (str/includes? s "s0"))
        (is (str/includes? s "chmod +x"))
        (is (str/includes? s "scripts-new"))
        (is (not (str/includes? s "more —")))))

    (testing "overflow past the limit becomes a bounded pointer, not more lines"
      (let [s (scripts/format-scripts-section (map mk (range 100)) 10 "/proj/bin")]
        (is (str/includes? s "s9"))
        (is (not (str/includes? s "s99")))
        (is (str/includes? s "and 90 more"))
        (is (str/includes? s "scripts-ls"))))

    (testing "an empty library still explains how to start one"
      (let [s (scripts/format-scripts-section [] 60 "/proj/bin")]
        (is (str/includes? s "library is empty"))
        (is (str/includes? s "chmod +x"))))

    (testing "no library and nowhere to write ⇒ no section at all"
      (is (nil? (scripts/format-scripts-section [] 60 nil))))))

(deftest library-mode-splits-full-from-brief-test
  (let [mode (resolve-private 'script-library-mode)
        snap #(config/get-config-snapshot
               (agent/setup-agent-by-id
                % {:agent-session {:user-id "test" :session-id (str "mode-" (name %))}}))]

    (testing "no clojure fence ⇒ :full — the library IS the tool surface"
      (is (= :full (mode (snap :script-agent)))))

    (testing "a clojure fence ⇒ :brief — it has a registry, so names only"
      (is (= :brief (mode (snap :coact-agent))))
      (is (= :brief (mode (snap :router-agent)))
          "the router is the case this exists for — it dispatches script-agent"))

    (testing "no code channel ⇒ nothing"
      ;; :exec/script-library :requires :exec/code-channel, so react-agent gets
      ;; no mode at all — there is no block to run a script from.
      (is (nil? (mode (snap :react-agent)))))

    (testing "PATH is injected for :full ONLY"
      ;; :brief names a PATH, never a command, precisely so bare-name
      ;; resolution is unchanged for an agent that did not ask for it.
      (let [active? (resolve-private 'script-library-active?)]
        (is (true?  (active? (snap :script-agent))))
        (is (false? (active? (snap :router-agent))))))))

(deftest brief-index-is-names-only-and-free-when-empty-test
  (let [mk (fn [n scope] {:name n :scope scope :desc (str "Does " n ".")
                          :path (str "/x/" n) :shadowed? false})]

    (testing "builtins alone render NOTHING"
      ;; The pack is materialized on first use, so counting it would make every
      ;; repo look like it has a library before anyone wrote a script — and a
      ;; router will never run `scripts-new`. This is what makes :brief free.
      (is (nil? (scripts/format-scripts-brief
                 [(mk "scripts-ls" :builtin) (mk "fetch" :builtin)] 60))))

    (testing "an empty library renders nothing"
      (is (nil? (scripts/format-scripts-brief [] 60))))

    (testing "saved scripts render as bare names, with no authoring contract"
      (let [b (scripts/format-scripts-brief
               [(mk "clj-count" :project) (mk "hi" :user) (mk "fetch" :builtin)] 60)]
        (is (str/includes? b "clj-count"))
        (is (str/includes? b "hi"))
        (is (not (str/includes? b "fetch"))
            "a builtin is script-agent's own furniture, not library content")
        (is (not (str/includes? b "chmod"))
            "authoring belongs to :full — a registry agent does not write scripts")
        (is (not (str/includes? b "# desc:")))
        (is (str/includes? b "script-agent owns adding to the set")
            "it must say who DOES extend the set, or the names read as a dead end")))

    (testing "a shadowed duplicate is listed once"
      (let [b (scripts/format-scripts-brief
               [(mk "fetch" :project) (assoc (mk "fetch" :builtin) :shadowed? true)] 60)]
        (is (= 1 (count (re-seq #"fetch" b))))))

    (testing "it is a fraction of the full section, and bounded"
      (let [es (mapv #(mk (str "s" %) :project) (range 40))
            b  (scripts/format-scripts-brief es 60)
            f  (scripts/format-scripts-section es 60 "/p/bin")]
        (is (< (count b) (* 0.5 (count f))))
        (is (str/includes? (scripts/format-scripts-brief es 10) "…+30 more"))))))

(deftest resolve-invoked-reads-command-position-test
  (let [lib [{:name "clj-count"} {:name "design-docs-over-1000"} {:name "fetch"}]
        inv #(scripts/resolve-invoked % lib)]

    (testing "a bare name — the :full case, where the library is on PATH"
      (is (= ["clj-count"] (inv "clj-count components/agent")))
      (is (= ["clj-count"] (inv "THRESH=5 clj-count ."))
          "leading VAR=value assignments are not the command")
      (is (= ["clj-count"] (inv "./bin/clj-count ."))))

    (testing "a PATH — the :brief case, where an agent runs one by location"
      ;; Matching bare names only would measure the agent that needed the
      ;; measurement least, since :brief never puts the library on PATH.
      (is (= ["design-docs-over-1000"]
             (inv "bash .brainyard/scripts/bin/design-docs-over-1000 1200"))))

    (testing "command position in a pipeline, a list, a loop, a substitution"
      (is (= ["clj-count"] (inv "find . -name '*.md' | clj-count")))
      (is (= ["fetch"]     (inv "cd /tmp && fetch https://example.com")))
      (is (= ["clj-count"] (inv "for f in *; do clj-count $f; done")))
      (is (= ["design-docs-over-1000"] (inv "result=$(design-docs-over-1000)"))
          "the closing paren is shell syntax, not part of the name"))

    (testing "a NAME is not an INVOCATION — the false positives that matter"
      ;; Counting these would inflate the reuse rate, which is the one number
      ;; this exists to produce.
      (is (= [] (inv "echo clj-count is a script")))
      (is (= [] (inv "# clj-count in a comment")))
      (is (= [] (inv "grep -r fetch .")))
      (is (= [] (inv "python3 script.py"))))

    (testing "several distinct scripts in one block, deduped"
      (is (= ["clj-count" "fetch"]
             (inv "clj-count .\nfetch https://x\nclj-count src"))))

    (testing "no library, no matches"
      (is (= [] (scripts/resolve-invoked "clj-count ." []))))))

(deftest script-telemetry-emits-per-block-test
  (let [evs  (resolve-private 'script-block-events)
        emit (resolve-private 'emit-script-telemetry!)
        lib  [{:name "clj-count" :scope :project} {:name "fetch" :scope :builtin}]]

    (testing "one event per SCRIPT block, emitted even when nothing was reused"
      ;; A bare ::script-invoked would be a numerator with no denominator, and
      ;; the question is a ratio.
      (let [out (vec (evs lib [{:lang "bash"   :code "clj-count ." :result "0" :error "" :duration-ms 5}
                               {:lang "bash"   :code "ls -l"       :result "0" :error "" :duration-ms 3}
                               {:lang "python" :code "print(1)"    :result "0" :error "" :duration-ms 9}]))]
        (is (= 3 (count out)))
        (is (= [true false false] (mapv :reused? out)))
        (is (= [["clj-count"] [] []] (mapv :invoked out)))
        (is (= [[:project] [] []] (mapv :scopes out))
            ":scopes is what says whether the shipped builtins earn their slots")
        (is (= [5 3 9] (mapv :ms out)))
        (is (every? #(= 2 (:library %)) out))))

    (testing "clojure and verbatim blocks are not script blocks"
      (is (empty? (evs lib [{:lang "clojure"  :code "(clj-count)" :result "1"}
                            {:lang "markdown" :code "clj-count"   :result "/tmp/x.md"}]))))

    (testing "no library ⇒ no events — the ratio is not a question"
      (is (empty? (evs [] [{:lang "bash" :code "clj-count ."}]))))

    (testing "failure is recorded, not swallowed"
      (let [e (first (evs lib [{:lang "bash" :code "clj-count ." :result "2"
                                :error "Exit code: 2" :duration-ms 7}]))]
        (is (true? (:failed? e)))
        (is (= "2" (:exit e)))
        (is (true? (:reused? e)) "a failed run of a library script is still a reuse")))

    (testing "a builtin invocation is attributed to :builtin"
      (is (= [[:builtin]] (mapv :scopes (evs lib [{:lang "bash" :code "fetch https://x"}])))))

    (testing "telemetry can never fail a turn"
      ;; Assert it does not THROW, not that it returns nil — the catch arm
      ;; logs a warning, and mulog/log returns a value.
      (let [threw #(try (emit %) nil (catch Throwable t t))]
        (is (nil? (threw {:script-entries lib :blocks [{:lang "bash" :code nil}]})))
        (is (nil? (threw :not-a-map)))
        (is (nil? (threw {:script-entries lib :blocks :not-a-seq})))))))

(deftest builtin-pack-is-well-formed-test
  (testing "every builtin has a shebang and a desc header"
    (doseq [[nm body] scripts/builtin-scripts]
      (is (str/starts-with? body "#!") (str nm " needs a shebang"))
      (is (re-find #"(?m)^# desc: " body) (str nm " needs a # desc: line"))
      (is (re-find (re-pattern (str "(?m)^# name: " nm "$")) body)
          (str nm "'s header name must match its filename — `scripts-doctor`
               reports a mismatch, so the pack must not trip its own check"))))

  (testing "materialization is idempotent and marks them executable"
    (let [d (tmp-dir! "builtins")]
      (try
        (let [bin (str (io/file d "bin"))]
          (scripts/materialize-builtins! bin)
          (let [fs (into {} (for [[nm _] scripts/builtin-scripts]
                              [nm (io/file bin nm)]))]
            (doseq [[nm ^File f] fs]
              (is (.exists f) (str nm " was not written"))
              (is (.canExecute f) (str nm " is not executable"))
              (is (= (get scripts/builtin-scripts nm) (slurp f))))))
        (finally (delete-tree! d))))))
