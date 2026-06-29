;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.exec
  "Exec-agent dossier helpers + auto-persist hook.

   A *dossier* is the per-turn execution record exec-agent emits — pre-flight
   verdict (incl. consumed plan-agent + todo-agent dossier references),
   structured per-item evidence (with :ok? + :evidence per idx), post-flight
   rubric (incl. acceptance_progress map), recommended next agent. Lives
   under `.brainyard/agents/exec-agent/dossiers/<yyyyMMdd-HHmmss>-<slug>.md` per
   docs/exec-agent-design.md §7.3.

   Mirrors the plan-agent / todo-agent dossier helper patterns with
   exec-specific schema additions: `execute` block (items_advanced,
   items_pending_after, evidence map) and `post.acceptance_progress`
   (criterion → status map: evidence-recorded | partial | pending |
   contradicted).

   v1 narrowings (matching plan/todo): no separate verbose `runs/` dir
   (single dossier per turn, body carries detail inline), no
   `exec$preflight`/`exec$postflight`/`exec$item-route` mega-helpers,
   `:max-items-per-turn` default 5."
  (:require [ai.brainyard.agent.common.guard :as guard]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.text SimpleDateFormat]
           [java.time Instant]
           [java.util Date]))

(def ^:private dossiers-subdir "dossiers")
(def ^:private dossiers-base ".brainyard/agents/exec-agent")
(def ^:private dossiers-dir-rel (str dossiers-base "/" dossiers-subdir))
(def ^:private dossiers-index-rel (str dossiers-base "/INDEX.md"))

(def ^:private slug-stopwords
  #{"a" "an" "the" "is" "are" "and" "or" "of" "in" "on" "at" "to" "for"
    "by" "with" "from" "as" "but" "if" "then" "than" "so"
    "what" "where" "when" "who" "whom" "why" "how" "which"
    "do" "does" "did" "can" "could" "would" "should" "shall" "will"
    "this" "that" "these" "those" "it" "its" "we" "they" "you" "i"
    "be" "been" "being" "was" "were" "have" "has" "had"
    "exec" "execute" "drive" "advance"})

(defn current-dirs
  "Resolve dirs from the current agent session, falling back to init-dirs!.
   Public so the auto-persist hook + tests can `with-redefs` it cleanly."
  []
  (or (when-let [a (some-> (requiring-resolve 'ai.brainyard.agent.core.protocol/*current-agent*)
                           deref)]
        (some-> (:!session a) deref
                ((or (requiring-resolve 'ai.brainyard.agent.core.session/get-session-config)
                     (constantly nil))
                 :dirs)))
      ((or (requiring-resolve 'ai.brainyard.agent.core.config/init-dirs!)
           (constantly {})))))

(defn dossier-default-base-dir
  "Project root for exec-agent dossiers. Public so tests can `with-redefs`
   it without reaching at internals. Delegates to `agent.core.config/
   project-dir` — the single source of truth shared by every functional
   agent and the LLM tool channel."
  []
  (config/project-dir))

(defn- now-ts []
  (.format (SimpleDateFormat. "yyyyMMdd-HHmmss") (Date.)))

(defn- now-iso []
  (str (Instant/now)))

(defn- now-yyyy-mm-dd-hh-mm []
  (-> (subs (now-iso) 0 16)
      (str/replace "T" " ")))

(defn- exec-slugify
  [request max-chars]
  (let [tokens (-> (str request)
                   str/lower-case
                   (str/replace #"[^a-z0-9]+" "-")
                   (str/split #"-"))
        kept   (->> tokens
                    (remove #(or (str/blank? %) (slug-stopwords %)))
                    seq)
        joined (if kept (str/join "-" kept) "exec")]
    (subs joined 0 (min max-chars (count joined)))))

;; ---------------------------------------------------------------------------
;; YAML emission — same shape as plan/todo's, with string-keyed flow-map
;; support (for post.acceptance_progress with criterion-string keys).
;; ---------------------------------------------------------------------------

(def ^:private bareword-re #"^[A-Za-z0-9_./:-]+$")

(defn- yaml-string
  [s]
  (str "\"" (-> (str s)
                (str/replace "\\" "\\\\")
                (str/replace "\"" "\\\""))
       "\""))

(defn- kw-full-name
  "Return the full keyword name, including namespace when present.
   `:n/a` → `\"n/a\"`. (Plain `(name :n/a)` would return `\"a\"`.)"
  [k]
  (if-let [ns (namespace k)]
    (str ns "/" (name k))
    (name k)))

(defn- yaml-scalar
  [v]
  (cond
    (nil? v)        "null"
    (boolean? v)    (if v "true" "false")
    (keyword? v)    (kw-full-name v)
    (number? v)     (str v)
    (string? v)     (if (and (seq v) (re-matches bareword-re v))
                      v
                      (yaml-string v))
    :else           (yaml-string (str v))))

(defn- yaml-flow-vector
  [xs]
  (str "[" (str/join ", " (map yaml-scalar xs)) "]"))

(defn- yaml-flow-key
  [k]
  (cond
    (keyword? k) (kw-full-name k)
    (string? k)  (if (and (seq k) (re-matches bareword-re k))
                   k
                   (yaml-string k))
    :else        (yaml-string (str k))))

(defn- yaml-flow-map
  "Single-line flow-style flat map. Used for evidence sub-maps,
   post.acceptance_progress (string keys, scalar/keyword values),
   and post.rubric (keyword keys, scalar values)."
  [m]
  (str "{"
       (str/join ", "
                 (map (fn [[k v]]
                        (str (yaml-flow-key k) ": "
                             (cond
                               (sequential? v) (yaml-flow-vector v)
                               (map? v)        ;; one-level nested flow-map (e.g. evidence per idx)
                               (str "{"
                                    (str/join ", "
                                              (map (fn [[k2 v2]]
                                                     (str (yaml-flow-key k2) ": "
                                                          (cond
                                                            (sequential? v2) (yaml-flow-vector v2)
                                                            :else            (yaml-scalar v2))))
                                                   v))
                                    "}")
                               :else           (yaml-scalar v))))
                      m))
       "}"))

(defn- yaml-value
  [v]
  (cond
    (sequential? v) (yaml-flow-vector v)
    (map? v)        (yaml-flow-map v)
    :else           (yaml-scalar v)))

(defn- yaml-block
  [block-key entries]
  (apply str
         (name block-key) ":\n"
         (mapv (fn [[k v]]
                 (str "  " (name k) ": " (yaml-value v) "\n"))
               entries)))

(defn- ordered-pairs
  [m preferred]
  (let [in-pref  (filter #(contains? m %) preferred)
        leftover (remove (set preferred) (keys m))]
    (mapv (fn [k] [k (get m k)]) (concat in-pref leftover))))

(def ^:private pre-key-order
  [:verdict :checks :acceptance :acceptance_coverage :resume_from
   :gather_question :refuse_reason])

(def ^:private execute-key-order
  [:budget :items_advanced :items_pending_after :items_failed_this_turn
   :evidence])

(def ^:private post-key-order
  [:verdict :rubric :revision_applied :revision_summary :holds
   :acceptance_progress])

(def ^:private handoff-key-order
  [:next_agent :next_call])

(defn- build-exec-dossier-frontmatter*
  [{:keys [slug todo_path plan_path todo_dossier plan_dossier
           created turn-id session-id pre execute post handoff]}]
  (str "---\n"
       "slug: " (yaml-scalar slug) "\n"
       "agent: exec-agent\n"
       "created: " (or created (now-iso)) "\n"
       (when todo_path    (str "todo_path: "    (yaml-value todo_path) "\n"))
       (when plan_path    (str "plan_path: "    (yaml-value plan_path) "\n"))
       (when todo_dossier (str "todo_dossier: " (yaml-value todo_dossier) "\n"))
       (when plan_dossier (str "plan_dossier: " (yaml-value plan_dossier) "\n"))
       (when turn-id      (str "turn_id: "      (yaml-string turn-id) "\n"))
       (when session-id   (str "session_id: "   (yaml-string session-id) "\n"))
       "\n"
       (yaml-block :pre     (ordered-pairs (or pre {})     pre-key-order))
       "\n"
       (yaml-block :execute (ordered-pairs (or execute {}) execute-key-order))
       "\n"
       (yaml-block :post    (ordered-pairs (or post {})    post-key-order))
       "\n"
       (yaml-block :handoff (ordered-pairs (or handoff {}) handoff-key-order))
       "---\n"))

;; ---------------------------------------------------------------------------
;; YAML parsing — flat scalars + named nested blocks; flow-style flat maps
;; preserved as raw strings.
;; ---------------------------------------------------------------------------

(defn- read-frontmatter-lines
  [^java.io.File file]
  (with-open [r (io/reader file)]
    (let [reader (java.io.BufferedReader. r)
          first-line (.readLine reader)]
      (when (= "---" first-line)
        (loop [acc []]
          (let [ln (.readLine reader)]
            (cond
              (nil? ln)        nil
              (= "---" ln)     acc
              :else            (recur (conj acc ln)))))))))

(defn- parse-flow-vector
  "Parse `[a, \"b c\", 42, true]` into a vector. Quoted strings drop
   their quotes; bare tokens are coerced (numbers → Long, true/false →
   bool, null → nil; otherwise the trimmed string)."
  [s]
  (let [trimmed (str/trim (str s))]
    (if (and (str/starts-with? trimmed "[") (str/ends-with? trimmed "]"))
      (let [inner (subs trimmed 1 (dec (count trimmed)))]
        (if (str/blank? inner)
          []
          (->> (str/split inner #",")
               (map str/trim)
               (map (fn [tok]
                      (if (and (str/starts-with? tok "\"") (str/ends-with? tok "\""))
                        (-> tok
                            (subs 1 (dec (count tok)))
                            (str/replace "\\\"" "\"")
                            (str/replace "\\\\" "\\"))
                        ;; Bare token: coerce numbers / booleans / null;
                        ;; otherwise leave as a trimmed string.
                        (let [t (str/trim tok)]
                          (cond
                            (= t "true")  true
                            (= t "false") false
                            (= t "null")  nil
                            (re-matches #"-?\d+" t)
                            (try (Long/parseLong t) (catch Exception _ t))
                            :else t)))))
               vec)))
      [])))

(defn- coerce-bare-value
  [s]
  (let [trimmed (str/trim s)]
    (cond
      (= trimmed "true")  true
      (= trimmed "false") false
      (= trimmed "null")  nil
      (re-matches #"-?\d+" trimmed)
      (try (Long/parseLong trimmed) (catch Exception _ trimmed))
      :else trimmed)))

(defn- parse-scalar
  [v]
  (let [v (str/trim v)]
    (cond
      (re-matches #"^\[.*\]$" v) (parse-flow-vector v)
      (re-matches #"^\{.*\}$" v) v ; flow-map kept as raw string for v1
      (and (str/starts-with? v "\"") (str/ends-with? v "\""))
      (-> v
          (subs 1 (dec (count v)))
          (str/replace "\\\"" "\"")
          (str/replace "\\\\" "\\"))
      :else (coerce-bare-value v))))

(def ^:private block-keys
  #{"pre" "execute" "post" "handoff"})

(defn- parse-exec-dossier-yaml
  [lines]
  (loop [ls    lines
         acc   {}
         block nil]
    (if (empty? ls)
      acc
      (let [ln    (first ls)
            rest* (rest ls)]
        (cond
          (and (vector? block) (= :flat-block (first block))
               (re-matches #"^\s{2,}\S.*" ln))
          (if-let [[_ k v] (re-matches #"^\s+([\w_-]+):\s*(.*)$" ln)]
            (recur rest*
                   (assoc-in acc [(second block) (keyword k)] (parse-scalar v))
                   block)
            (recur rest* acc nil))

          (re-matches #"^([\w_-]+):\s*$" ln)
          (let [[_ k] (re-matches #"^([\w_-]+):\s*$" ln)]
            (if (block-keys k)
              (recur rest* (assoc acc (keyword k) {}) [:flat-block (keyword k)])
              (recur rest* acc nil)))

          (re-matches #"^([\w_-]+):\s*(.*)$" ln)
          (let [[_ k v] (re-matches #"^([\w_-]+):\s*(.*)$" ln)]
            (recur rest* (assoc acc (keyword k) (parse-scalar v)) nil))

          :else
          (recur rest* acc block))))))

;; ---------------------------------------------------------------------------
;; Retired write-side dossier helpers (exec$dossier-slug / -frontmatter /
;; -write / -index-append / exec$next-handoff) — the LLM now authors the
;; evidence dossier as markdown directly via write-file from the §7 template;
;; the auto-persist hook fills render-exec-dossier-md and spits it. The private
;; emitters/parsers below back the hook + the read seams.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Dossier write internals (kept — used by the auto-persist hook)
;; ---------------------------------------------------------------------------

(defn- existing-slug-count
  [base-dir slug]
  (let [^java.io.File dir (io/file base-dir dossiers-dir-rel)]
    (if (.isDirectory dir)
      (let [slug-re (re-pattern (str "(?i)-" (java.util.regex.Pattern/quote slug)
                                     "(-\\d+)?\\.md$"))]
        (->> (.listFiles dir)
             (filter (fn [^java.io.File f] (.isFile f)))
             (map (fn [^java.io.File f] (.getName f)))
             (filter #(re-find slug-re %))
             count))
      0)))

(defn- final-slug-with-suffix
  [base-dir base-slug]
  (let [n (existing-slug-count base-dir base-slug)]
    (if (zero? n)
      base-slug
      (str base-slug "-" (inc n)))))

(defn- index-line
  [{:keys [path slug pre-verdict post-verdict next-agent advanced pending]}]
  (let [filename (-> path (str/split #"/") last)
        pre-tok  (str "pre:" (name pre-verdict))
        post-tok (str "post:" (if (nil? post-verdict) "n/a" (name post-verdict)))
        progress (cond
                   (and (some? advanced) (some? pending))
                   (str "[+" advanced " / -" pending "]")
                   (some? advanced) (str "[+" advanced "]"))
        next-tok (str "→ " (name (or next-agent :unspecified)))]
    (str "- " (now-yyyy-mm-dd-hh-mm)
         " [" slug "](" dossiers-subdir "/" filename ") — "
         pre-tok " · " post-tok
         (when progress (str " · " progress))
         " · " next-tok "\n")))

(defn- append-index!
  "Append one INDEX.md line for a freshly written dossier (append-only; the
   auto-persist hook uses this). Verdicts coerced to keywords."
  [base-dir {:keys [path slug pre-verdict post-verdict next-agent advanced pending]}]
  (let [pre-kw  (if (keyword? pre-verdict) pre-verdict (keyword (str pre-verdict)))
        post-kw (cond (keyword? post-verdict) post-verdict
                      (string? post-verdict)  (keyword post-verdict)
                      :else                   nil)
        next-kw (cond (keyword? next-agent) next-agent
                      (string? next-agent)  (keyword next-agent)
                      :else                 :unspecified)
        line    (index-line {:path path :slug slug :pre-verdict pre-kw
                             :post-verdict post-kw :next-agent next-kw
                             :advanced advanced :pending pending})
        file    (io/file base-dir dossiers-index-rel)]
    (.mkdirs (.getParentFile file))
    (spit file line :append true)
    (mulog/log ::exec.dossier-index :slug slug :path path
               :pre-verdict pre-kw :post-verdict post-kw :next-agent next-kw)
    line))

;; ---------------------------------------------------------------------------
;; exec$read-dossier
;; ---------------------------------------------------------------------------

(defcommand exec$read-dossier
  "Read and parse the YAML frontmatter from an exec-agent dossier."
  (fn [& {:keys [path base-dir]
          :or   {base-dir (dossier-default-base-dir)}}]
    (cond
      (not (string? path))
      {:error ":path is required (string)"}

      :else
      (let [file (if (.isAbsolute (io/file path))
                   (io/file path)
                   (io/file base-dir path))]
        (cond
          (not (.isFile file))
          {:error (str "File not found: " path)}

          :else
          (if-let [lines (read-frontmatter-lines file)]
            (parse-exec-dossier-yaml lines)
            {:error (str "No frontmatter block at " path " (file did not start with ---)")})))))
  :input-schema  [:map
                  [:path [:string {:desc "Repo-relative or absolute path to an exec-agent dossier"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:slug {:optional true} [:string {:desc "Todo slug"}]]
                  [:todo_path {:optional true} [:string {:desc "Repo-relative path to the todo body"}]]
                  [:plan_path {:optional true} [:string {:desc "Repo-relative path to the plan body"}]]
                  [:todo_dossier {:optional true} [:string {:desc "Path to the consumed todo-agent dossier"}]]
                  [:plan_dossier {:optional true} [:string {:desc "Path to the source plan-agent dossier"}]]
                  [:pre {:optional true} [:map {:desc "Pre-flight sub-block"}]]
                  [:execute {:optional true} [:map {:desc "Execute sub-block"}]]
                  [:post {:optional true} [:map {:desc "Post-flight sub-block"}]]
                  [:handoff {:optional true} [:map {:desc "Handoff sub-block"}]]
                  [:error {:optional true} [:string {:desc "Error if file missing or no frontmatter"}]]])

;; ---------------------------------------------------------------------------
;; exec$find — search prior dossiers (resume support)
;; ---------------------------------------------------------------------------

(defcommand exec$find
  "Find prior exec-agent dossiers for a slug, newest-first (for resume detection)."
  (fn [& {:keys [slug base-dir]
          :or   {base-dir (dossier-default-base-dir)}}]
    (cond
      (not (string? slug))
      {:error ":slug is required (string)"}

      :else
      (let [dir (io/file base-dir dossiers-dir-rel)]
        (if-not (.isDirectory dir)
          {:matches [] :n-matches 0}
          (let [slug-re (re-pattern (str "(?i)-" (java.util.regex.Pattern/quote slug)
                                         "(-\\d+)?\\.md$"))
                matches
                (->> (.listFiles dir)
                     (filter (fn [^java.io.File f]
                               (and (.isFile f)
                                    (re-find slug-re (.getName f)))))
                     (sort-by #(.getName ^java.io.File %))
                     reverse
                     (keep (fn [^java.io.File f]
                             (when-let [lines (read-frontmatter-lines f)]
                               (let [fm (parse-exec-dossier-yaml lines)]
                                 {:path        (str dossiers-dir-rel "/" (.getName f))
                                  :slug        (:slug fm)
                                  :created     (:created fm)
                                  :pre_verdict (get-in fm [:pre :verdict])
                                  :post_verdict (get-in fm [:post :verdict])
                                  :advanced    (get-in fm [:execute :items_advanced])
                                  :pending     (get-in fm [:execute :items_pending_after])}))))
                     vec)]
            (mulog/log ::exec.find :slug slug :n-matches (count matches))
            {:matches matches :n-matches (count matches)})))))
  :input-schema  [:map
                  [:slug [:string {:desc "Todo slug to search for"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:matches [:vector {:desc "Matching prior dossiers, newest-first"} :map]]
                  [:n-matches [:int {:desc "Number of matches"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ---------------------------------------------------------------------------
;; exec$next-handoff
;; ---------------------------------------------------------------------------

(defn- handoff-from-state
  [pre-verdict post-verdict items-pending-after slug dossier-path]
  (let [ctx (cond-> "" dossier-path (str " :agent-context \"" dossier-path "\""))]
    (case (or post-verdict :n-a)
      :pass
      (cond
        ;; All items done — recommend eval-agent
        (or (nil? items-pending-after) (empty? items-pending-after))
        {:next-agent "eval-agent"
         :next-call (str "(eval-agent {:question \"Score this todo against its plan.\""
                         ctx "})")}

        ;; Items remain — recommend continuing exec-agent
        :else
        {:next-agent "exec-agent"
         :next-call (str "(exec-agent {:question \"Continue.\""
                         ctx "})")})

      :hold
      {:next-agent "user"
       :next-call (str "Resolve holds, then re-call exec-agent: "
                       "(exec-agent {:question \"<refined request>\""
                       ctx "})")}

      :n-a
      (case pre-verdict
        :gather
        {:next-agent "user"
         :next-call (str "Provide the missing input named in dossier.gather_question; "
                         "typically run plan-agent / todo-agent first.")}

        :refuse
        {:next-agent "none"
         :next-call (str "See dossier.refuse_reason for the redirect (typically "
                         "plan-agent or todo-agent re-run).")}

        {:next-agent "user"
         :next-call (str "Inspect the dossier and decide next step.")}))))

;; exec$next-handoff (the command) is retired — the LLM writes the handoff block
;; + Next: line from the rule table in exec_agent.clj. The pure fn
;; `handoff-from-state` (above) is kept and used by the auto-persist hook.

;; ---------------------------------------------------------------------------
;; Public roster
;; ---------------------------------------------------------------------------

(def exec-dossier-helpers
  "The surviving exec READ seams — the dossier frontmatter reader and the
   resume search seam (exec$find). exec-agent + downstream consumers (eval/
   main) append these so the SCI sandbox auto-binds them. The write-side dossier
   helper chain is retired (dossiers authored via write-file)."
  [#'exec$read-dossier
   #'exec$find])

;; ============================================================================
;; Auto-persist hook
;;
;; Same proven shape as plan-auto-persist / todo-auto-persist: the LLM can
;; forget the helpers or hallucinate the `Saved dossier:` path. Hook
;; reconstructs a minimal dossier from answer text. Idempotency is on-disk-
;; existence check (catches hallucinated paths).
;; ============================================================================

(def saved-run-prefix
  "Stable prefix the agent emits when a verbose run record was written
   this turn. v1 narrowing: the run record is OPTIONAL (single dossier
   suffices). The hook still recognizes the prefix for parity with the
   design schema."
  "Saved run: ")

(def saved-dossier-prefix
  "Stable prefix the agent emits when a dossier was persisted this turn."
  "Saved dossier: ")

(defn- exec-agent? [agent]
  (try
    (= :exec-agent (proto/defagent-type agent))
    (catch Throwable _ false)))

(defn- extract-line-after
  [^String answer ^String prefix]
  (when (and (string? answer) (string? prefix))
    (when-let [start (str/index-of answer prefix)]
      (let [after (subs answer (+ start (count prefix)))
            end   (or (str/index-of after "\n") (count after))]
        (-> (subs after 0 end)
            (str/replace #"^[`'\"\s]+" "")
            (str/replace #"[`'\"\.,\s]+$" "")
            str/trim)))))

(defn- absolute->repo-relative
  [base-dir ^String path]
  (cond
    (or (nil? path) (str/blank? path))
    nil

    (and base-dir (str/starts-with? path (str base-dir "/")))
    (subs path (inc (count base-dir)))

    :else path))

(defn- dossier-already-saved?
  [^String answer base-dir]
  (when-let [claimed (extract-line-after answer saved-dossier-prefix)]
    (let [file (if (.isAbsolute (io/file claimed))
                 (io/file claimed)
                 (io/file base-dir claimed))]
      (.isFile file))))

(defn- detect-pre-verdict [^String answer]
  (cond
    (re-find #"(?im)^\s*Refused:\s*\S" answer) :refuse
    (re-find #"(?im)^\s*Need:\s*\S"    answer) :gather
    :else                                       :go))

(defn- detect-post-verdict [^String answer pre-verdict]
  (cond
    (not= :go pre-verdict)                  nil
    (re-find #"(?im)^\s*Hold:\s*\S" answer) :hold
    :else                                   :pass))

(defn- detect-done?
  "Truthy when the answer carries a `Done:` line — signals that all items
   were advanced and the next handoff is eval-agent."
  [^String answer]
  (boolean (re-find #"(?im)^\s*Done:\s*\S" answer)))

(defn- detect-saved-todo-path [^String answer]
  (extract-line-after answer "Saved todo: "))

(defn- one-line-summary
  [^String answer max-chars]
  (let [paragraphs (->> (str/split (or answer "") #"\n\n")
                        (map str/trim)
                        (remove str/blank?))
        prose? (fn [^String p]
                 (and (not (str/starts-with? p "#"))
                      (not (str/starts-with? p "|"))
                      (not (str/starts-with? p "```"))
                      (not (str/starts-with? p "Saved "))
                      (not (str/starts-with? p "Need:"))
                      (not (str/starts-with? p "Refused:"))
                      (not (str/starts-with? p "Hold:"))
                      (not (str/starts-with? p "Done:"))
                      (not (str/starts-with? p "Manual:"))
                      (not (str/starts-with? p "Suggested:"))
                      (not (str/starts-with? p "Next:"))))
        chosen (or (first (filter prose? paragraphs))
                   (first paragraphs)
                   "")
        flat   (-> chosen
                   (str/replace #"\s+" " ")
                   (str/replace #"^#+\s*" "")
                   str/trim)]
    (subs flat 0 (min max-chars (count flat)))))

(defn- render-exec-dossier-md
  "Fill the §7 exec-dossier template: frontmatter (via the kept private emitter,
   byte-compatible with the old helper) + body sections. Backs the auto-persist
   hook; the happy-path LLM writes the equivalent markdown itself."
  [{:keys [slug todo-path pre execute post handoff title summary answer]}]
  (str (build-exec-dossier-frontmatter*
        {:slug slug :todo_path todo-path :pre pre :execute execute
         :post post :handoff handoff})
       "\n# Exec dossier — " (or title slug) "\n\n"
       "## Pre-flight summary\n"
       "*Reconstructed from the agent's answer text — the LLM did not author the "
       "dossier itself this turn.*\n\n"
       "## Execution log\n" (or summary "") "\n\n"
       "## Post-flight notes\n" (if post (name (:verdict post)) "n/a") "\n\n"
       "## Handoff\n" (:next_call handoff) "\n\n"
       "## Original answer\n" answer "\n"))

(defn materialize-auto-dossier!
  "Core of the exec auto-persist safety net — agent-state-free. Reconstruct a
   §7-template dossier from the answer text, `spit` it under dossiers/
   (timestamp-prefixed), and append one INDEX line. Returns
   `{:path :rel-path :slug :pre-verdict :post-verdict}` or nil when skipped.
   Does the SAME thing the happy path does (write one templated file)."
  [{:keys [answer question base-dir]}]
  (cond
    (or (not (string? answer)) (str/blank? answer))
    nil

    (dossier-already-saved? answer base-dir)
    nil

    :else
    (let [pre-verdict   (detect-pre-verdict answer)
          post-verdict  (detect-post-verdict answer pre-verdict)
          done?         (detect-done? answer)
          todo-path-raw (detect-saved-todo-path answer)
          todo-path     (when todo-path-raw
                          (absolute->repo-relative base-dir todo-path-raw))
          slug          (or (when todo-path-raw
                              (-> todo-path-raw (str/split #"/") last
                                  (str/replace #"\.md$" "")))
                            (exec-slugify (or question "auto-persisted") 60)
                            "exec")
          summary       (one-line-summary answer 200)
          ;; PASS + Done → all items advanced → eval-agent; PASS w/o Done →
          ;; items remain → exec-agent (non-empty sentinel drives the branch).
          pending-after (when (and (= :pass post-verdict) (not done?))
                          [:still-pending])
          handoff-kebab (handoff-from-state pre-verdict post-verdict pending-after slug nil)
          handoff       {:next_agent (:next-agent handoff-kebab)
                         :next_call  (:next-call  handoff-kebab)}
          ts            (now-ts)
          final-slug    (final-slug-with-suffix base-dir slug)
          rel-path      (str dossiers-dir-rel "/" ts "-" final-slug ".md")
          file          (io/file base-dir rel-path)
          content       (render-exec-dossier-md
                         {:slug      final-slug
                          :todo-path todo-path
                          :pre       {:verdict pre-verdict}
                          :execute   (cond-> {} todo-path-raw (assoc :budget {:max_items_per_turn 5}))
                          :post      (when post-verdict {:verdict post-verdict})
                          :handoff   handoff
                          :title     (str final-slug " (auto-persisted)")
                          :summary   summary
                          :answer    answer})]
      (.mkdirs (.getParentFile file))
      (spit file content)
      (append-index! base-dir
                     {:path rel-path :slug final-slug
                      :pre-verdict pre-verdict :post-verdict post-verdict
                      :next-agent (:next-agent handoff-kebab)})
      (mulog/log ::exec.auto-persist
                 :slug final-slug :path rel-path
                 :pre-verdict pre-verdict :post-verdict post-verdict
                 :answer-chars (count answer) :had-todo-path? (boolean todo-path-raw)
                 :done? done?)
      {:path         (.getAbsolutePath file)
       :rel-path     rel-path
       :slug         final-slug
       :pre-verdict  pre-verdict
       :post-verdict post-verdict})))

(defn exec-auto-persist
  "Gated handler for `:agent.ask/finalize`. Materializes the dossier when the
   LLM skipped the FINAL-STEP checklist AND returns a `:replace` decision
   injecting the absent `Saved dossier: <path>` handoff line into the answer.
   Idempotent — no-op when the line is present or nothing was persisted.
   Defensive — failures logged, never re-thrown."
  [{:keys [agent input result]}]
  (try
    (when (and (exec-agent? agent) (map? result))
      (let [question  (or (when (string? input) input)
                          (some-> input :question str)
                          "(question not captured)")
            answer    (str (:answer result))
            persisted (materialize-auto-dossier!
                       {:answer   (:answer result)
                        :question question
                        :base-dir (dossier-default-base-dir)})
            path      (:path persisted)]
        (when (and path (not (str/includes? answer "Saved dossier:")))
          {:result      :replace
           :reason      "injected absent Saved-dossier handoff line"
           :replacement (assoc result :answer (str answer "\n\nSaved dossier: " path))})))
    (catch Throwable t
      (mulog/error ::exec.auto-persist-failed
                   :exception t
                   :agent-id  (try (proto/agent-id agent)
                                   (catch Throwable _ "unknown")))
      nil)))

(defn install-auto-persist!
  "Register the auto-persist hook on the gated `:agent.ask/finalize` event.
   Idempotent — `register-hook!` replaces by id. The :match predicate scopes
   the hook to exec-agent instances only."
  []
  (hooks/register-hook!
   :agent.ask/finalize
   ::exec-auto-persist
   exec-auto-persist
   :source   :exec-agent
   :match    (fn [{:keys [agent]}] (exec-agent? agent))
   :priority 50))

;; Self-install at namespace load.
(install-auto-persist!)
