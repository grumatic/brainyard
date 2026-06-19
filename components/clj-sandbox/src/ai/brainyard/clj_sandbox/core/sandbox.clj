;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-sandbox.core.sandbox
  "SCI-based sandbox for SCI code evaluation.

   Creates an isolated Clojure evaluation environment with:
   - Whitelisted pure functions (no I/O, no interop)
   - Persistent variables across eval calls
   - FINAL termination mechanism
   - Output capture via sci/out binding
   - Per-block eval timeout"
  (:require [sci.core :as sci]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.data]
            [clojure.data.json :as json]
            [clojure.pprint :as pprint]
            ;; Required at compile time so `sci/copy-ns` can snapshot their public
            ;; vars into the `:full` namespace surface (GraalVM-safe — copy-ns
            ;; expands at build time; both are Clojure core, always on classpath).
            [clojure.java.io]
            [clojure.java.shell]
            [ai.brainyard.mulog.interface :as mulog]
            [edamame.core :as edamame]
            [ai.brainyard.clj-sandbox.core.context-accessors :as ctx-acc])
  (:import [java.io StringWriter]
           [sci.lang IVar]))

;; ============================================================================
;; Termination
;; ============================================================================

(defn- make-final-fn
  "Create the FINAL function that terminates the SCI loop with an answer.
   Stringifies non-string answers with print semantics (NOT pr-str / toString)
   so newlines inside string values survive as REAL newlines in the answer
   text. Strings pass through unchanged."
  []
  (fn [answer]
    (let [answer-str (cond
                       (nil? answer)    ""
                       (string? answer) answer
                       :else            (with-out-str (print answer)))]
      ;; Guard against unbound SCI vars leaking as answers
      (when (or (nil? answer)
                (str/starts-with? answer-str "Unbound: "))
        (throw (ex-info (str "FINAL called with invalid value: " answer-str
                             ". Compute the answer as a string expression, e.g. (FINAL (str \"...\"))")
                        {})))
      (throw (ex-info "SCI termination"
                      {::termination true
                       :type :final
                       :value answer-str})))))

(defn termination?
  "Check if an exception is an SCI termination signal.
   Walks the cause chain because SCI may wrap the termination ExceptionInfo
   in its own ExceptionInfo with location metadata."
  [e]
  (loop [^Throwable t e]
    (when t
      (if (and (instance? clojure.lang.ExceptionInfo t)
               (::termination (ex-data t)))
        true
        (recur (.getCause t))))))

(defn termination-result
  "Extract the termination result from an SCI termination exception.
   Walks the cause chain to find the original termination data.
   Returns {:type :final :value str}."
  [e]
  (loop [^Throwable t e]
    (when t
      (if (and (instance? clojure.lang.ExceptionInfo t)
               (::termination (ex-data t)))
        (let [data (ex-data t)]
          {:type (:type data)
           :value (:value data)})
        (recur (.getCause t))))))

;; ============================================================================
;; Shared SCI Configuration
;; ============================================================================

(def ^:private sci-classes
  "Java classes whitelisted for SCI sandbox access."
  {'Math java.lang.Math
   'Integer java.lang.Integer
   'Long java.lang.Long
   'Float java.lang.Float
   'Double java.lang.Double
   'Thread java.lang.Thread
   'java.time.Instant java.time.Instant
   'java.time.Duration java.time.Duration
   'java.time.LocalDate java.time.LocalDate
   'java.time.LocalDateTime java.time.LocalDateTime
   'java.time.LocalTime java.time.LocalTime
   'java.time.ZonedDateTime java.time.ZonedDateTime
   'java.time.ZoneId java.time.ZoneId
   'java.time.format.DateTimeFormatter java.time.format.DateTimeFormatter})

(def ^:private sci-deny
  "Symbols denied in SCI sandbox."
  ['System 'Runtime 'ProcessBuilder 'ClassLoader])

(def ^:private full-classes
  "Broad JDK class palette for the `:full` interop level. SCI resolves classes
   ONLY by the symbols enumerated in the `:classes` map — `:allow :all` lifts
   per-class allow-gating but does NOT enable resolution of un-enumerated
   classes or fully-qualified names. So `:full` is `sci-classes` (Math, numerics,
   Thread, java.time) plus the previously-denied capability classes
   (System/Runtime/ProcessBuilder/ClassLoader) and a curated set of common
   java.io / java.nio.file / java.net / java.util / java.security classes the
   agent is likely to reach for. `:allow :all` is added so instance interop on
   values of any returned type is not method-gated.

   Native-image caveat: SCI interop relies on reflection, so under the native
   `by` binary only classes already in the reflection config resolve at runtime;
   the `:full` palette is fully usable only under the JVM uberjar (`BY_JAR=1`)."
  (merge sci-classes
         {:allow :all
          'System          java.lang.System
          'Runtime         java.lang.Runtime
          'Process         java.lang.Process
          'ProcessBuilder  java.lang.ProcessBuilder
          'ClassLoader     java.lang.ClassLoader
          'String          java.lang.String
          'StringBuilder   java.lang.StringBuilder
          'Character       java.lang.Character
          'Boolean         java.lang.Boolean
          'Byte            java.lang.Byte
          'Short           java.lang.Short
          'Number          java.lang.Number
          'Object          java.lang.Object
          'Class           java.lang.Class
          'Exception       java.lang.Exception
          'Throwable       java.lang.Throwable
          'RuntimeException java.lang.RuntimeException
          'java.io.File                  java.io.File
          'java.io.BufferedReader        java.io.BufferedReader
          'java.io.InputStreamReader     java.io.InputStreamReader
          'java.io.ByteArrayOutputStream java.io.ByteArrayOutputStream
          'java.nio.file.Files java.nio.file.Files
          'java.nio.file.Paths java.nio.file.Paths
          'java.nio.file.Path  java.nio.file.Path
          'java.net.URL         java.net.URL
          'java.net.URI         java.net.URI
          'java.net.InetAddress java.net.InetAddress
          'java.util.UUID              java.util.UUID
          'java.security.MessageDigest java.security.MessageDigest}))

(defn- sci-init-opts
  "Build the `:classes`/`:deny` portion of `sci/init` opts for an interop level.

   - `:restricted` (default, also `nil`) — the whitelisted `sci-classes` plus
     the `sci-deny` denylist. Raw Java interop is confined to pure helpers
     (Math, numeric boxes, java.time); System/Runtime/ProcessBuilder/ClassLoader
     are denied. This is the only safe posture on a host.
   - `:full` — the broad `full-classes` palette with NO denylist, so the agent
     can do process exec, filesystem, network and system introspection via Java
     interop. Only appropriate inside a disposable container. See `full-classes`
     for the native-image caveat."
  [interop]
  (case interop
    :full {:classes full-classes}
    {:classes sci-classes :deny sci-deny}))

;; ============================================================================
;; SCI Namespace Configuration
;; ============================================================================

(def ^:private library-namespaces
  "SCI namespace entries for whitelisted library functions.

   Already bundled by sci/init (no entry needed): clojure.string,
   clojure.set, clojure.walk, clojure.edn, clojure.template, clojure.repl.

   Entries below add libraries SCI does not bundle by default so the LLM
   can call them with fully-qualified symbols, e.g. (clojure.pprint/pprint x)
   or (clojure.data.json/read-str s). The user namespace still exposes the
   short aliases pprint, parse-json, and to-json for convenience.

   clojure.core.protocols is needed so SCI can resolve protocol metadata
   on JDBC result rows (next.jdbc attaches datafy/nav metadata)."
  {'clojure.core.protocols {'datafy clojure.core.protocols/datafy
                            'nav    clojure.core.protocols/nav}
   'clojure.pprint         {'pprint      pprint/pprint
                            'print-table pprint/print-table
                            'cl-format   pprint/cl-format
                            'write       pprint/write}
   'clojure.data.json      {'read-str  json/read-str
                            'write-str json/write-str
                            'read      json/read
                            'write     json/write}
   'clojure.data           {'diff clojure.data/diff}})

(def ^:private full-namespaces
  "Additional library namespaces exposed ONLY at the `:full` interop level.
   `sci/copy-ns` snapshots the whole public surface of each (compile-time macro,
   so this is GraalVM-native-safe; both namespaces are Clojure core). These are
   I/O libraries deliberately withheld by SCI's defaults — exposing them is safe
   only alongside `:full`, which also supplies the class palette their return
   values (File, Process) need for interop (see `full-classes`)."
  {'clojure.java.io    (sci/copy-ns clojure.java.io    (sci/create-ns 'clojure.java.io))
   'clojure.java.shell (sci/copy-ns clojure.java.shell (sci/create-ns 'clojure.java.shell))})

(def ^:private full-user-aliases
  "Short `user`-namespace aliases for common I/O fns, merged in ONLY at the
   `:full` interop level. SCI omits slurp/spit from its defaults (I/O boundary);
   these expose them plus a `sh` shorthand for clojure.java.shell/sh."
  {'slurp (with-meta slurp
            {:doc "Read a file/URL/stream into a string. (:full interop only.)"
             :arglists '([f] [f & opts]) :category :io})
   'spit  (with-meta spit
            {:doc "Write content to a file (overwrites). Options: :append true. (:full interop only.)"
             :arglists '([f content] [f content & opts]) :category :io})
   'sh    (with-meta clojure.java.shell/sh
            {:doc "Run a shell command, return {:exit :out :err}. e.g. (sh \"ls\" \"-l\"). (:full interop only.)"
             :arglists '([& args]) :category :io})})

(defn- build-sci-namespaces
  "Build the full SCI namespace map: whitelisted library namespaces plus the
   user namespace with core bindings (FINAL, parse-json, to-json, pprint)
   merged with caller-supplied extra-bindings (e.g. llm-query, tool closures,
   context accessors). The short aliases (pprint, parse-json, to-json) coexist
   with the fully-qualified forms exposed via library-namespaces.

   At the `:full` interop level the namespace map is enriched with
   `full-namespaces` (clojure.java.io, clojure.java.shell) and the `user` ns
   gains `full-user-aliases` (slurp/spit/sh) — these are withheld at
   `:restricted` because they do I/O and need the `:full` class palette.

   Output capture is handled by binding both Clojure *out* and SCI sci/out
   to the sandbox output-writer in eval-code."
  [final-fn extra-bindings interop]
  (let [parse-json (fn [s & {:keys [key-fn] :or {key-fn str}}]
                     (json/read-str s :key-fn key-fn))
        to-json    (fn [v & {:keys [escape-slash] :or {escape-slash true}}]
                     (json/write-str v :escape-slash escape-slash))
        user-bindings {'FINAL (with-meta final-fn
                                {:doc "Return your final answer string. Must be called alone — no other code alongside."
                                 :arglists '([answer])
                                 :category :core})
                       'parse-json (with-meta parse-json
                                     {:doc "Parse JSON string to Clojure data (string keys by default). Options: :key-fn (default str, use keyword for keyword keys)"
                                      :arglists '([s] [s & {:keys [key-fn]}])
                                      :category :core})
                       'to-json (with-meta to-json
                                  {:doc "Convert Clojure data to JSON string. Options: :escape-slash"
                                   :arglists '([v] [v & {:keys [escape-slash]}])
                                   :category :core})
                       'pprint (with-meta pprint/pprint
                                 {:doc "Pretty-print any value to stdout"
                                  :arglists '([object])
                                  :category :core})}
        full? (= interop :full)
        base-namespaces (cond-> library-namespaces
                          full? (merge full-namespaces))
        user-ns (cond-> (merge user-bindings extra-bindings)
                  full? (merge full-user-aliases))]
    (assoc base-namespaces 'user user-ns)))

;; ============================================================================
;; Sandbox Creation
;; ============================================================================

(defn- resolve-sci-val
  "Resolve a value from ns-publics. SCI vars (def'd) need deref, injected
   bindings are raw values."
  [v]
  (if (instance? IVar v)
    @v
    v))

(defn- extract-user-vars-from-ctx
  "Implementation: pull user vars from a live SCI ctx, filtering against the
   `initial` symbol set captured at sandbox creation. Returns the same shape
   as `extract-user-vars`."
  [ctx initial]
  (let [env (sci/eval-string* ctx "(ns-publics 'user)")]
    (->> env
         (remove (fn [[k _]] (contains? initial k)))
         (reduce (fn [m [k v]]
                   (let [val (try (resolve-sci-val v) (catch Exception _ ::skip))]
                     (if (= val ::skip)
                       m
                       (let [val-str (try (pr-str val) (catch Exception _ nil))]
                         (if (and val-str
                                  (< (count val-str) 2000)
                                  ;; Verify round-trip: read-string must succeed
                                  (try (read-string val-str) true
                                       (catch Exception _ false)))
                           (assoc m (name k) {:value val-str :type "inferred"})
                           m)))))
                 {}))))

(defn- extract-user-vars-with-survival-from-ctx
  "Like `extract-user-vars-from-ctx` but also returns the names of vars that
   were pruned and the reason (`:non-edn`, `:too-large`, `:resolve-failed`).
   Returns `{:kept {name {:value :type}} :lost [{:name :reason} ...]}`."
  [ctx initial]
  (let [env (sci/eval-string* ctx "(ns-publics 'user)")]
    (reduce (fn [acc [k v]]
              (if (contains? initial k)
                acc
                (let [name* (name k)
                      val   (try (resolve-sci-val v) (catch Exception _ ::skip))]
                  (cond
                    (= val ::skip)
                    (update acc :lost conj {:name name* :reason :resolve-failed})

                    :else
                    (let [val-str (try (pr-str val) (catch Exception _ nil))]
                      (cond
                        (nil? val-str)
                        (update acc :lost conj {:name name* :reason :non-edn})

                        (>= (count val-str) 2000)
                        (update acc :lost conj {:name name* :reason :too-large})

                        (try (read-string val-str) false
                             (catch Exception _ true))
                        (update acc :lost conj {:name name* :reason :non-edn})

                        :else
                        (assoc-in acc [:kept name*] {:value val-str :type "inferred"})))))))
            {:kept {} :lost []}
            env)))

(defn- strip-protocol-metadata
  "Recursively remove metadata from all IMeta values.
   Prevents SCI analyzer errors from JDBC result rows that carry
   clojure.core.protocols/datafy and /nav metadata."
  [x]
  (walk/postwalk
   (fn [v]
     (if (and (instance? clojure.lang.IMeta v) (meta v))
       (with-meta v nil)
       v))
   x))

(defn create-sandbox
  "Create a new SCI sandbox with context loaded as a variable.

   Options:
     :context         - Map of data the LLM should explore via context-get,
                        context-keys, context-sample, context-search. Path-based
                        access requires a map shape; pass `nil` (or omit) when
                        there is no input data. Non-map values throw.
     :bindings        - Additional vars to expose {symbol value}. Supply sub-LLM
                        callables (llm-query, llm-query-batched, rlm-query) here
                        when needed.
     :synthetic-keys  - Caller-supplied {keyword (fn [] ...)} thunks merged
                        with the built-in `:user-vars` thunk. Each thunk is
                        called lazily by context-accessors to expose live
                        derived data (e.g. notes, runtime stats).
     :interop         - SCI interop level: `:restricted` (default) keeps the
                        whitelist + denylist; `:full` permits arbitrary Java
                        interop (only safe inside a container). See
                        `sci-init-opts`. The resolved level is recorded in the
                        returned map so `fork-sandbox` inherits it.

   Returns:
     {:sci-ctx <atom wrapping SCI context>
      :output  <StringWriter, retained for backward compat — each
                eval-code call now uses its own per-eval writer>
      :history <atom of [{:code :result :output :error}]>}

   Note: pre-Step-G this fn also accepted `:max-pending` and built a
   `:pending-evals` registry powering eval-code's soft-timeout-survives
   branch. That registry was unified into the agent task manager (see
   ai.brainyard.agent.task.commands/await-task); eval-code is now
   hard-only and the sandbox no longer owns any pending-eval state."
  [& {:keys [context bindings synthetic-keys interop] :or {interop :restricted}}]
  (when (and (some? context) (not (map? context)))
    (throw (ex-info "create-sandbox :context must be a map (or nil) — path-based context-accessors require a map shape"
                    {:context-type (str (type context))})))
  (let [output (StringWriter.)
        sci-ctx-atom (atom nil)
        initial-vars-atom (atom #{})
        final-fn (make-final-fn)
        ;; Strip protocol metadata (JDBC rows carry clojure.core.protocols/datafy)
        ;; once, then reuse for accessors
        clean-context (when (some? context)
                        (strip-protocol-metadata context))
        ;; Lazy synthetic key: live snapshot of user-defined vars in the
        ;; sandbox. Computed on each accessor call so the LLM always sees
        ;; current state (after later iterations add more `def`s).
        user-vars-fn (fn []
                       (when-some [ctx @sci-ctx-atom]
                         (extract-user-vars-from-ctx ctx @initial-vars-atom)))
        synthetic-keys (merge {:user-vars user-vars-fn}
                              (or synthetic-keys {}))
        ;; Build context accessor functions for selective retrieval
        context-accessors (when clean-context
                            (ctx-acc/make-context-accessors
                             clean-context
                             :synthetic-keys synthetic-keys))
        extra-bindings (cond-> (or bindings {})
                         ;; Merge accessor functions (context-index, context-get, etc.)
                         (some? context-accessors) (merge context-accessors))
        namespaces (build-sci-namespaces final-fn extra-bindings interop)
        sci-ctx (sci/init (merge {:namespaces namespaces}
                                 (sci-init-opts interop)))
        ;; Capture initial user-namespace symbols for extract-user-vars filtering
        initial-vars (set (keys (sci/eval-string* sci-ctx "(ns-publics 'user)")))]
    (reset! sci-ctx-atom sci-ctx)
    (reset! initial-vars-atom initial-vars)
    {:sci-ctx sci-ctx-atom
     :output output
     :history (atom [])
     :initial-vars initial-vars
     :synthetic-keys synthetic-keys
     :interop interop}))

(defn- sci-internal-error?
  "Check if ex-data is from SCI internals (not user-thrown)."
  [data]
  (when data
    (some #(contains? data %) [:type :phase :edamame/expected-delimiter])))

(defn format-error
  "Format an exception into a clear error message for the LLM.
   Extracts line/column from SCI exceptions to help locate the error.
   Includes ex-info data for user-thrown exceptions, simplifies Java-internal messages."
  ([^Throwable e] (format-error e nil))
  ([^Throwable e code-str]
   (let [msg (.getMessage e)
         data (when (instance? clojure.lang.ExceptionInfo e)
                (ex-data e))
         error-type (-> (class e) .getSimpleName)
         ;; Extract SCI location metadata — walk full cause chain
         ;; SCI wraps runtime exceptions in ExceptionInfo with :line/:column,
         ;; but the future may catch the unwrapped inner exception
         [sci-line sci-col] (loop [^Throwable t e]
                              (if (nil? t)
                                [nil nil]
                                (if-let [d (when (instance? clojure.lang.ExceptionInfo t)
                                             (ex-data t))]
                                  (if (:line d)
                                    [(:line d) (:column d)]
                                    (recur (.getCause t)))
                                  (recur (.getCause t)))))
         ;; Build a readable message
         base (cond
                ;; NPE with unhelpful Java message
                (and (instance? NullPointerException e)
                     (or (nil? msg) (str/includes? (str msg) "instance_class")))
                "NullPointerException: called a method on nil"

                ;; Empty message
                (str/blank? msg)
                (str error-type " (no message)")

                :else msg)
         ;; Include ex-data only for user-thrown ex-info, not SCI internals
         base (if (and data (seq data) (not (sci-internal-error? data)))
                (str base " " (pr-str data))
                base)
         ;; Prepend line/column location
         location (when sci-line
                    (if sci-col
                      (str " (line " sci-line ", col " sci-col ")")
                      (str " (line " sci-line ")")))
         ;; Extract the offending code line for context
         code-context (when (and sci-line code-str)
                        (let [lines (str/split-lines code-str)]
                          (when (and (pos? sci-line) (<= sci-line (count lines)))
                            (let [line-text (str/trimr (nth lines (dec sci-line)))]
                              (when-not (str/blank? line-text)
                                (str "\n  " sci-line " | " line-text))))))
         ;; Detect escape-related errors and append actionable fix guidance
         escape-hint (when (and msg (or (str/includes? msg "Unsupported escape character")
                                        (str/includes? msg "Invalid escape sequence")
                                        (str/includes? msg "Unexpected escape")))
                       (str "\n\nFIX: In SCI strings, only \\n \\t \\r \\\" \\\\ are valid escapes. "
                            "For regex/shell patterns, double the backslash: "
                            "\\\\d not \\d, \\\\s not \\s, \\\\w not \\w. "
                            "For complex scripts: write the script to /tmp/foo.sh via (write-file ...) "
                            "then run (bash \"bash /tmp/foo.sh\")."))]
     (str base location code-context escape-hint))))

(defn split-code-at-final
  "Split a code string into pre-FINAL code and the rest.
   When the LLM writes expressions before FINAL in one block, this allows
   executing only the pre-FINAL code so results can be verified first.

   Returns {:pre-final <string-or-nil> :has-final? bool}
   - pre-final is non-nil only when there are expressions BEFORE FINAL
   - If FINAL is the first/only form, pre-final is nil (execute normally)"
  [code-str]
  (if-let [m (re-matcher #"(?m)^\s*\(FINAL[\s(]" code-str)]
    (if (.find m)
      (let [start (.start m)
            pre (subs code-str 0 start)]
        (if (str/blank? pre)
          {:pre-final nil :has-final? true}
          {:pre-final (str/trim pre) :has-final? true}))
      {:pre-final nil :has-final? false})
    {:pre-final nil :has-final? false}))

(def ^:private valid-escape-chars
  "Characters that form valid escape sequences in Clojure/SCI string literals.
   \\n \\t \\r \\b \\f \\\" \\\\ \\uXXXX \\0-7 (octal)"
  #{\n \t \r \b \f \" \\ \u})

(defn try-repair-escapes
  "Attempt to repair invalid escape sequences in string literals within code.
   LLMs commonly generate code like (bash \"grep '\\d+' file\") where \\d is
   invalid in SCI. This function doubles the backslash so \\d becomes \\\\d
   (a literal backslash-d), which is what the LLM intended for the shell.

   Walks the code character-by-character, tracking parse state:
   - :code     — normal code (outside strings/regex/comments)
   - :string   — inside a double-quoted string literal (repair target)
   - :regex    — inside a regex literal #\"...\" (escapes valid, skip)
   - :comment  — inside a ; line comment (skip to newline)

   Inside :string state, replaces \\X (where X is not a valid escape char)
   with \\\\X. Also handles backslash at end-of-line (shell continuation).

   Returns repaired code string, or nil if no repairs were needed."
  [^String code-str]
  (let [len (count code-str)
        sb (StringBuilder. len)
        repaired? (volatile! false)]
    (loop [i 0, state :code]
      (if (>= i len)
        (when @repaired? (.toString sb))
        (let [c (.charAt code-str i)]
          (case state
            ;; === NORMAL CODE ===
            :code
            (cond
              ;; Line comment — skip to end of line
              (= c \;)
              (do (.append sb c)
                  (recur (inc i) :comment))

              ;; Regex literal #"..." — enter regex mode (don't repair escapes)
              (and (= c \#) (< (inc i) len) (= (.charAt code-str (inc i)) \"))
              (do (.append sb c)
                  (.append sb \")
                  (recur (+ i 2) :regex))

              ;; String literal "..." — enter string mode (repair escapes)
              (= c \")
              (do (.append sb c)
                  (recur (inc i) :string))

              ;; Everything else — pass through
              :else
              (do (.append sb c)
                  (recur (inc i) :code)))

            ;; === INSIDE STRING LITERAL (repair target) ===
            :string
            (cond
              ;; End of string
              (= c \")
              (do (.append sb c)
                  (recur (inc i) :code))

              ;; Backslash — check escape validity
              (and (= c \\) (< (inc i) len))
              (let [next-c (.charAt code-str (inc i))]
                (cond
                  ;; Valid escape characters — pass through
                  (contains? valid-escape-chars next-c)
                  (do (.append sb c)
                      (.append sb next-c)
                      (recur (+ i 2) :string))

                  ;; Octal escape (0-7)
                  (and (>= (int next-c) (int \0)) (<= (int next-c) (int \7)))
                  (do (.append sb c)
                      (.append sb next-c)
                      (recur (+ i 2) :string))

                  ;; Backslash at end of line (shell continuation) — double it
                  (or (= next-c \newline) (= next-c \return))
                  (do (.append sb \\)
                      (.append sb \\)
                      (.append sb next-c)
                      (vreset! repaired? true)
                      (recur (+ i 2) :string))

                  ;; Invalid escape — double the backslash to make it literal
                  :else
                  (do (.append sb \\)
                      (.append sb \\)
                      (.append sb next-c)
                      (vreset! repaired? true)
                      (recur (+ i 2) :string))))

              ;; Backslash at end of code (malformed) — double it
              (and (= c \\) (= (inc i) len))
              (do (.append sb \\)
                  (.append sb \\)
                  (vreset! repaired? true)
                  (recur (inc i) :string))

              ;; Normal character inside string
              :else
              (do (.append sb c)
                  (recur (inc i) :string)))

            ;; === INSIDE REGEX LITERAL (pass through unchanged) ===
            :regex
            (cond
              ;; End of regex
              (= c \")
              (do (.append sb c)
                  (recur (inc i) :code))

              ;; Escaped character inside regex — skip both chars unchanged
              (and (= c \\) (< (inc i) len))
              (do (.append sb c)
                  (.append sb (.charAt code-str (inc i)))
                  (recur (+ i 2) :regex))

              ;; Normal character inside regex
              :else
              (do (.append sb c)
                  (recur (inc i) :regex)))

            ;; === INSIDE LINE COMMENT (skip to newline) ===
            :comment
            (do (.append sb c)
                (if (= c \newline)
                  (recur (inc i) :code)
                  (recur (inc i) :comment)))))))))

(defn- try-repair-eof
  "Attempt to repair code with unclosed delimiters (EOF parse errors).
   Recursively appends expected closing delimiters until the code parses.
   Uses parse-only (no eval) to avoid side effects during repair attempts.
   Skips repair if code contains FINAL to prevent truncated answers.
   Returns repaired code string, or nil if repair fails or is not applicable."
  [code-str]
  (when-not (re-find #"\(FINAL[\s(]" code-str)
    (loop [code code-str, n 0]
      (when (< n 10)
        (let [result (try
                       (edamame/parse-string-all code {:all true})
                       :ok
                       (catch clojure.lang.ExceptionInfo e
                         (let [data (ex-data e)]
                           (if (:edamame/expected-delimiter data)
                             {:append (:edamame/expected-delimiter data)}
                             :fail)))
                       (catch Exception _ :fail))]
          (cond
            (= result :ok)   (when (not= code code-str) code)
            (= result :fail) nil
            :else (recur (str code (:append result)) (inc n))))))))

(defn eval-code
  "Evaluate a Clojure code string in the sandbox.

   Options:
     :timeout-ms — Timeout in milliseconds (default 30000). On expiry the
                   future is cancelled (best-effort; SCI loops ignore
                   Thread.interrupt) and the result carries
                   `:status :timeout`. Pre-Step-G this fn had a
                   soft-timeout-survives branch that stashed long-running
                   evals in the sandbox's `:pending-evals` registry; that
                   capability is now unified into the agent task manager
                   (see ai.brainyard.agent.task.commands/await-task and
                   the :clj-sandbox-eval job type).

   Returns one of:
     {:code :result :output :error}                 ;; sync completion
     {:code :result nil :output <partial>
      :error \"Evaluation timed out\"
      :status :timeout :timeout-ms <ms>}            ;; hard-cancel

   Throws ex-info with ::termination for FINAL calls.

   Note: for in-process callers that want to own the future + timeout (e.g.
   the agent task manager's :clj-sandbox-eval executor), prefer
   `eval-sandbox-thunk` — it returns a `[thunk eval-output]` pair so the
   caller can wrap it however they like."
  [sandbox code-str & {:keys [timeout-ms] :or {timeout-ms 30000}}]
  (let [{:keys [sci-ctx history]} sandbox
        ;; Layer 1: repair invalid escape sequences (e.g. \d → \\d) before SCI parsing
        escape-repaired (or (try-repair-escapes code-str) code-str)
        escape-fixed? (not= escape-repaired code-str)
        _ (when escape-fixed?
            (mulog/debug ::repaired-escape-sequences :original-len (count code-str)
                         :repaired-len (count escape-repaired)))
        ;; Layer 2: repair unclosed delimiters
        effective-code (or (try-repair-eof escape-repaired) escape-repaired)
        repaired? (not= effective-code escape-repaired)
        _ (when repaired?
            (mulog/debug ::repaired-unclosed-delimiters :original-len (count escape-repaired)
                         :repaired-len (count effective-code)))
        eval-output (StringWriter.)]
    ;; Evaluate in a daemon thread with timeout via deref.
    ;; SCI's tight loops (loop/recur) ignore Thread.interrupt(), so a
    ;; watchdog-based approach on the calling thread hangs forever.
    ;; Using a future + deref-with-timeout lets us abandon stuck evals.
    ;; Trade-off: SCI stacktrace (thread-local) is lost for timed-out evals,
    ;; but we return a generic timeout error anyway so this is acceptable.
    (let [eval-ctx @sci-ctx
          eval-future (future
                        (try
                          {:result (binding [*out* eval-output]
                                     (sci/binding [sci/out eval-output]
                                       (sci/eval-string* eval-ctx effective-code)))}
                          (catch clojure.lang.ExceptionInfo e
                            (if (termination? e)
                              {::termination e}
                              {:error (format-error e effective-code)}))
                          (catch Exception e
                            {:error (format-error e effective-code)})))
          r (deref eval-future timeout-ms ::timeout)]
      (if (not= r ::timeout)
        ;; --- Synchronous completion --------------------------------------
        (if-let [ex (::termination r)]
          (throw ex)
          (let [captured (.toString eval-output)
                final-result (assoc r :code code-str :output captured)]
            (swap! history conj final-result)
            final-result))
        ;; --- Hard cancel --------------------------------------------------
        (do
          (future-cancel eval-future)
          (let [captured (.toString eval-output)
                final-result {:code code-str :result nil :output captured
                              :error "Evaluation timed out"
                              :status :timeout
                              :timeout-ms timeout-ms}]
            (swap! history conj final-result)
            final-result))))))

(defn eval-sandbox-thunk
  "Build a zero-arg thunk that evaluates `code-str` in `sandbox` synchronously
   on whatever thread invokes it. Caller owns the future + timeout — useful
   when the surrounding system already manages task lifecycle (e.g. the agent
   task manager's executor pool + detach watcher), so this avoids the double
   future / dual registry stacking that `eval-code` does internally.

   Returns a 2-vector `[thunk eval-output]`:
     - thunk       — `(fn [] result-map)`. result-map has the same shape as
                     the sync-completion branch of `eval-code` (plus the
                     FINAL projection below):
                       {:result <val> :code <code> :output <captured>}
                     or {:error <msg> :code <code> :output <captured>}
                     Success results are appended to the sandbox's `:history`;
                     errors and FINAL are not.

                     FINAL termination is projected as an error map with
                     :final-value carrying the termination value, so callers
                     don't need to catch sandbox-internal exception types:
                       {:error \"FINAL termination\"
                        :final-value <value>
                        :code <code> :output <captured>}
     - eval-output — the per-eval StringWriter. Caller can sample its current
                     contents (`(.toString w)`) at any time for partial-
                     progress display before the thunk returns.

   The thunk applies the same code repair (try-repair-escapes →
   try-repair-eof) and SCI/Clojure stdout bindings as `eval-code`, so its
   sync semantics are identical for non-FINAL paths."
  [sandbox code-str]
  (let [{:keys [sci-ctx history]} sandbox
        escape-repaired (or (try-repair-escapes code-str) code-str)
        effective-code  (or (try-repair-eof escape-repaired) escape-repaired)
        eval-output     (StringWriter.)]
    [(fn []
       (let [eval-ctx @sci-ctx
             raw (try
                   {:result (binding [*out* eval-output]
                              (sci/binding [sci/out eval-output]
                                (sci/eval-string* eval-ctx effective-code)))}
                   (catch clojure.lang.ExceptionInfo e
                     (if (termination? e)
                       (let [t (termination-result e)]
                         {:error       "FINAL termination"
                          :final-value (:value t)})
                       {:error (format-error e effective-code)}))
                   (catch Exception e
                     {:error (format-error e effective-code)}))
             captured (.toString eval-output)
             final (assoc raw :code code-str :output captured)]
         (when (and history (not (:error raw)))
           (swap! history conj final))
         final))
     eval-output]))

;; ============================================================================
;; Step G removed `poll-pending-evals!` and `cancel-pending-evals!` along with
;; the sandbox's `:pending-evals` registry. The soft-timeout-survives capability
;; lives in the agent task manager now (see
;; ai.brainyard.agent.task.commands/await-task with :on-timeout :detach, and
;; ai.brainyard.agent.task.executor/ClojureSandboxJobExecutor). External
;; callers that want the same semantics should drive a task via
;; ai.brainyard.agent.task.protocol/create-task with :job-type :clj-sandbox-eval.
;; ============================================================================

(defn get-var
  "Get the value of a variable in the sandbox."
  [sandbox var-name]
  (let [sym (symbol (name var-name))]
    (sci/eval-form @(:sci-ctx sandbox) sym)))

(defn set-var!
  "Set a variable in the sandbox."
  [sandbox var-name value]
  (let [sym (symbol (name var-name))]
    (sci/eval-form @(:sci-ctx sandbox) (list 'def sym value))
    value))

(defn extract-user-vars
  "Extract user-defined variables from a live sandbox as a serializable snapshot.
   Must be called while the sandbox is still alive.

   Returns map of {\"var-name\" {:value \"pr-str'd-value\" :type \"inferred\"}}
   suitable for persistence via sandbox-state. Skips non-serializable values
   and values exceeding 2000 chars when serialized."
  [sandbox]
  (extract-user-vars-from-ctx @(:sci-ctx sandbox)
                              (or (:initial-vars sandbox) #{})))

(defn extract-user-vars-with-survival
  "Like `extract-user-vars` but additionally reports which vars were pruned
   and why. Returns `{:kept {name {:value :type}} :lost [{:name :reason} ...]}`
   where `:reason` is `:non-edn` (functions, opaque types), `:too-large`
   (>= 2000 chars serialized), or `:resolve-failed`. Used by the TUI resume
   path so the banner can say \"N defs restored, M lost (functions)\"."
  [sandbox]
  (extract-user-vars-with-survival-from-ctx @(:sci-ctx sandbox)
                                            (or (:initial-vars sandbox) #{})))

(defn get-history
  "Get the evaluation history from the sandbox."
  [sandbox]
  @(:history sandbox))

;; ============================================================================
;; Sandbox Mutation (for persistent sandbox across turns)
;; ============================================================================

(defn- sci-set-var!
  "Set a var value in SCI sandbox directly, bypassing the analyzer.
   Uses direct env atom swap instead of (sci/eval-form ctx (list 'def sym val))
   to avoid SCI's analyzer walking the value's metadata — which fails when
   values carry protocol metadata (e.g., JDBC result rows with
   clojure.core.protocols/datafy)."
  [sci-ctx sym val]
  (swap! (:env sci-ctx) assoc-in [:namespaces 'user sym]
         (sci/new-var sym val)))

(defn update-context!
  "Update the context variable and rebuild context-accessor bindings in a live sandbox.
   Used when reusing a sandbox across turns with new context data.
   Preserves all user-defined variables and the synthetic-keys wiring from
   sandbox creation (so `(context-get [:user-vars])` keeps working).
   `new-context` must be a map (or nil)."
  [sandbox new-context]
  (when (and (some? new-context) (not (map? new-context)))
    (throw (ex-info "update-context! new-context must be a map (or nil)"
                    {:context-type (str (type new-context))})))
  (let [ctx @(:sci-ctx sandbox)
        ;; Strip protocol metadata (JDBC rows carry clojure.core.protocols/datafy)
        ;; to prevent SCI analyzer errors when LLM code does (def x context)
        clean-context (strip-protocol-metadata new-context)
        ;; Rebuild context accessor functions from the clean context, carrying
        ;; over the synthetic-keys (user-vars thunk, etc.) installed at create.
        accessors (ctx-acc/make-context-accessors
                   clean-context
                   :synthetic-keys (:synthetic-keys sandbox))]
    ;; Update each accessor binding in the user namespace
    (doseq [[sym f] accessors]
      (sci-set-var! ctx sym f))
    sandbox))

(defn update-bindings!
  "Add or update bindings in a live sandbox without destroying existing user vars.
   Used to refresh tool closures, agent state accessors, etc. between turns."
  [sandbox bindings]
  (let [ctx @(:sci-ctx sandbox)]
    (doseq [[sym val] bindings]
      (sci-set-var! ctx sym val))
    sandbox))

(defn clear-history!
  "Clear evaluation history, optionally keeping the last N entries.
   Use between turns to prevent unbounded growth in persistent sandboxes."
  [sandbox & {:keys [keep-last] :or {keep-last 0}}]
  (swap! (:history sandbox) (fn [h] (vec (take-last keep-last h))))
  sandbox)

;; ============================================================================
;; Sandbox Forking (for parallel execution)
;; ============================================================================

(defn fork-sandbox
  "Create an independent copy of a sandbox for parallel evaluation.
   The forked sandbox starts with the same namespace bindings (functions, tool
   closures, user-defined vars) but has its own output buffer and history.
   Each fork gets its own env atom for isolation during execution.
   After all forks complete, eval-code-blocks-parallel merges new defs back
   into the parent sandbox (last-block-wins for conflicts).

   Used by eval-code-blocks-parallel to run independent code blocks concurrently."
  [sandbox]
  (let [parent-ctx @(:sci-ctx sandbox)
        parent-env-snapshot @(:env parent-ctx)
        interop (:interop sandbox :restricted)
        ;; Create a fresh SCI context with same base config, then replace its
        ;; env with a snapshot of the parent. This preserves the full namespace
        ;; structure including :aliases, :obj, and SCI internals. The interop
        ;; level must match the parent so forked blocks have the same Java
        ;; interop surface.
        forked-ctx (sci/init (merge {:namespaces library-namespaces}
                                    (sci-init-opts interop)))]
    (reset! (:env forked-ctx) parent-env-snapshot)
    {:sci-ctx (atom forked-ctx)
     :output (StringWriter.)
     :history (atom [])
     :initial-vars (:initial-vars sandbox)
     :interop interop}))

(def ^:private max-parallel-blocks
  "Maximum number of parallel code blocks allowed."
  10)

(defn- merge-fork-vars!
  "Merge new user-defined vars from forked sandboxes back into the parent.
   Iterates forks in block order — last-block-wins for conflicts.
   Skips vars that already existed in the parent before forking and
   vars whose values cannot be resolved."
  [parent-ctx forks parent-user-keys]
  (reduce (fn [seen fork]
            (let [fork-ctx @(:sci-ctx fork)
                  fork-user-ns (get-in @(:env fork-ctx) [:namespaces 'user])]
              (reduce (fn [seen' [sym v]]
                        (if (contains? parent-user-keys sym)
                          seen'
                          (let [val (try (resolve-sci-val v) (catch Exception _ ::skip))]
                            (when (not= val ::skip)
                              (sci-set-var! parent-ctx sym val))
                            (conj seen' sym))))
                      seen
                      fork-user-ns)))
          parent-user-keys
          forks))

(defn eval-code-blocks-parallel
  "Evaluate multiple code blocks concurrently in isolated sandbox forks.

   Each block runs in its own forked sandbox context. Results are collected
   as a vector in the same order as the input blocks. After execution, new
   variables defined in the forks are merged back into the parent sandbox
   in block order (last-block-wins for conflicts).

   FINAL is NOT allowed in parallel blocks — parallel execution is for data
   gathering and decomposable sub-tasks, not for finalizing answers. If a block
   calls FINAL, it is caught and returned as an error.

   Options:
     :timeout-ms - Per-block timeout (default 30000). Parallel SCI forks run
                   in-process and cannot detach into the background; the
                   deadline is enforced as a hard cancel.

   Returns:
     {:eval-results [result-map ...]}  — one result per block"
  [sandbox code-blocks & {:keys [timeout-ms]
                          :or {timeout-ms 30000}}]
  (cond
    ;; Single block: use existing sequential path (no fork overhead)
    (<= (count code-blocks) 1)
    (let [code (first code-blocks)]
      (if (nil? code)
        {:eval-results []}
        (try
          {:eval-results [(eval-code sandbox code :timeout-ms timeout-ms)]}
          (catch clojure.lang.ExceptionInfo e
            (if (termination? e)
              {:eval-results [{:code code :result nil :output ""
                               :error "FINAL cannot be called in parallel blocks. Use sequential code to finalize."}]}
              {:eval-results [{:code code :result nil :output ""
                               :error (format-error e code)}]})))))

    ;; Too many blocks
    (> (count code-blocks) max-parallel-blocks)
    {:eval-results [{:code "" :result nil :output ""
                     :error (str "Too many parallel blocks (" (count code-blocks)
                                 "). Maximum is " max-parallel-blocks ".")}]}

    ;; Multiple blocks: fork and execute in parallel
    :else
    (let [parent-ctx @(:sci-ctx sandbox)
          parent-user-keys (set (keys (get-in @(:env parent-ctx) [:namespaces 'user])))
          forks (mapv (fn [_] (fork-sandbox sandbox)) code-blocks)
          futures (mapv (fn [fork code]
                          (future
                            (try
                              (eval-code fork code :timeout-ms timeout-ms)
                              (catch clojure.lang.ExceptionInfo e
                                (if (termination? e)
                                  {:code code :result nil :output ""
                                   :error "FINAL cannot be called in parallel blocks. Use sequential code to finalize."}
                                  {:code code :result nil :output ""
                                   :error (format-error e code)}))
                              (catch Exception e
                                {:code code :result nil :output ""
                                 :error (format-error e code)}))))
                        forks code-blocks)
          results (mapv (fn [f code]
                          (let [r (deref f (* 2 timeout-ms) ::timeout)]
                            (if (= r ::timeout)
                              {:code code :result nil :output "" :error "Parallel eval timed out"}
                              r)))
                        futures code-blocks)]
      ;; Merge new defs from forks back into parent sandbox
      (merge-fork-vars! parent-ctx forks parent-user-keys)
      {:eval-results results})))
