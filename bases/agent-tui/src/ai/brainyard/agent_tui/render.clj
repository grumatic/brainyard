;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.render
  "Rendering helpers for the tmux-backed daemon (`by-host`).

   Two surfaces are addressed:

     :stream — append-only, normal scrollback.  Each event becomes one or
               two lines of nicely styled output.  Tool calls get a leading
               glyph + colored name + a one-line arg summary.  Agent answers
               get their own block separator.

     :status — single-row status pane.  We use a CR + erase-line prefix so
               every write overwrites the row in place; this gives the user
               a true 'status bar' rather than a spam of appended lines."
  (:require [clojure.string :as str]))

;; -- minimal ANSI helpers ----------------------------------------------------
;;
;; Inlined (rather than depending on agent.interface.tui.ansi) so this brick
;; tests cleanly without pulling in the agent component.

(def ^:private esc           "[")
(def ^:private reset-code    (str esc "0m"))
(def ^:private bold          (str esc "1m"))
(def ^:private italic        (str esc "3m"))
(def ^:private dim           (str esc "2m"))
(def ^:private cyan          (str esc "36m"))
(def ^:private white         (str esc "37m"))
(def ^:private bright-white  (str esc "97m"))
(def ^:private bright-red    (str esc "91m"))
(def ^:private bright-green  (str esc "92m"))
(def ^:private bright-yellow (str esc "93m"))
(def ^:private bright-cyan   (str esc "96m"))
(def ^:private bright-magenta (str esc "95m"))

(def ^:private erase-line (str esc "2K"))
(def ^:private h-line "─")

(defn- style [s & codes]
  (str (apply str codes) s reset-code))

(defn- muted   [s] (style s dim))
(defn- failure [s] (style s bold bright-red))
(defn- tname   [s] (style s bold cyan))

;; -- shared helpers -----------------------------------------------------------

(defn clamp
  "Clamp `s` to at most `max-len` visible characters.  When the input
   exceeds the budget, replace the trailing characters with `…` so the
   total visible width is exactly `max-len`.  Used by every block that
   truncates a single-line preview (tool name, error message, fact
   content, etc.) so the cutoff style is consistent across panes.

   This counts plain characters — it does NOT skip ANSI escape
   sequences.  Pass already-styled text in only when you've already
   stripped or accounted for the escapes; for raw user content the
   default is fine."
  [s max-len]
  (let [s (str s)]
    (cond
      (or (nil? max-len) (>= max-len (count s))) s
      (<= max-len 1)                              "…"
      :else                                       (str (subs s 0 (dec max-len)) "…"))))

(defn collapse-ws
  "Collapse runs of whitespace in `s` to single spaces and trim ends.
   Returns \"\" for nil.  Used as the standard pre-clamp normalizer so
   multi-line tool output renders as a single readable line."
  [s]
  (-> (str s) (str/replace #"\s+" " ") str/trim))

;; -- ANSI-aware width + wrapping ---------------------------------------------
;;
;; Ported in slim form from `ai.brainyard.agent.tui.format` (legacy).
;; The legacy module also handles CJK / emoji double-width chars; we
;; skip that here to keep the port tractable — agent output is
;; overwhelmingly ASCII.  Re-add a `wide-codepoint?` guard if the user
;; reports wide-char misalignment.

(defn- skip-ansi-seq
  "Return the index just past the ANSI CSI escape that starts at
   index `pos` in `s` (caller has verified s[pos] = ESC).  An ANSI
   CSI runs from `\\u001b[` through the first letter byte (`m` for
   colors, `J`/`H`/`K` for cursor ops, etc.).  Non-CSI escapes (rare)
   fall back to a single-char advance."
  [^String s pos]
  (let [len (.length s)
        next-pos (inc pos)]
    (cond
      (>= next-pos len)                next-pos
      (not= (.charAt s next-pos) \[)   next-pos
      :else
      (loop [i (+ pos 2)]
        (cond
          (>= i len)                          i
          (Character/isLetter (.charAt s i))  (inc i)
          :else                               (recur (inc i)))))))

(defn display-width
  "Visible width of `s` in terminal cells, ignoring ANSI escape
   sequences.  Plain ASCII assumption — wide CJK / emoji code-points
   undercount by 1 each.  Returns 0 for nil."
  [^String s]
  (if (nil? s) 0
      (let [len (.length s)]
        (loop [i 0 w 0]
          (cond
            (>= i len)                  w
            (= (int (.charAt s i)) 27)    (recur (skip-ansi-seq s i) w)
            :else                       (recur (inc i) (inc w)))))))

(defn- char-index-at-width
  "Return the char index in `s` at which cumulative display width
   would EXCEED `limit`.  ANSI escapes count as 0 width.  Used as
   the candidate cut-point for word-wrap."
  [^String s limit]
  (let [len (.length s)]
    (loop [i 0 w 0]
      (cond
        (>= i len)                  i
        (= (int (.charAt s i)) 27)    (recur (skip-ansi-seq s i) w)
        (>= w limit)                i
        :else                       (recur (inc i) (inc w))))))

(defn- find-active-ansi-codes
  "Concatenate every ANSI CSI escape in `s[0..end-pos)` that hasn't
   been reset by a `\\u001b[0m`.  When wrap splits a styled span,
   the caller emits the closing reset on the first half and prepends
   this string to the second half so the style continues on the next
   line.  Without this, half a multi-line block would lose its color
   on every wrap boundary."
  [^String s end-pos]
  (let [prefix  (subs s 0 (min end-pos (count s)))
        matcher (re-matcher #"\[[0-9;]*m" prefix)]
    (loop [active []]
      (if (.find matcher)
        (let [code (.group matcher)]
          (recur (if (= code reset-code) [] (conj active code))))
        (apply str active)))))

(defn ansi-aware-word-wrap
  "Wrap `line` to at most `max-width` visible columns, breaking at
   word boundaries when possible.  ANSI escape sequences are not
   counted toward width and are preserved across wrap boundaries
   (closes active codes at the end of each emitted line, reopens
   them at the start of the next).  Returns a vector of lines."
  [line max-width]
  (let [line (str line)]
    (cond
      (<= (display-width line) max-width)
      [line]

      (or (nil? max-width) (<= max-width 0))
      [line]

      :else
      (loop [remaining line, result []]
        (if (<= (display-width remaining) max-width)
          (conj result remaining)
          (let [limit-idx (char-index-at-width remaining max-width)
                ;; Prefer breaking at the last space at-or-before
                ;; the limit; fall back to a hard cut if there's no
                ;; space (e.g. one very long token).
                break-at   (let [idx (str/last-index-of remaining " " limit-idx)]
                             (if (and idx (pos? idx)) idx limit-idx))
                first-part (subs remaining 0 break-at)
                rest-part  (str/trim (subs remaining break-at))
                active-codes (find-active-ansi-codes remaining break-at)
                first-closed (if (seq active-codes)
                               (str first-part reset-code)
                               first-part)
                rest-reopened (if (seq active-codes)
                                (str active-codes rest-part)
                                rest-part)]
            (recur rest-reopened (conj result first-closed))))))))

(defn- short-args
  "Compact, readable one-line view of a tool's args map.  Drops noisy keys
   like :agent / :session-store and quotes long string values."
  [args]
  (cond
    (nil? args)             ""
    (not (map? args))       (str args)
    (empty? args)           ""
    :else
    (let [omit?    #{:agent :session-store :st-memory :tool-defs :user-feedback-fn
                     :permission-fn :memory-manager :bt-config :_meta
                     ;; Suppress :input / :question keys: when a (sub-)agent
                     ;; tool call carries the user-typed text under either
                     ;; key, the daemon's :agent.ask/pre or :agent.tool-use/pre handler
                     ;; already emitted a `❯ <text>` line.  Re-emitting the
                     ;; same text here as `input=<text>` would duplicate it.
                     :input :question}
          interesting (into (sorted-map)
                            (remove (fn [[k _]] (omit? k)) args))
          fmt-v    (fn [v]
                     (cond
                       (string? v)             (clamp (collapse-ws v) 50)
                       (or (vector? v) (seq? v)) (clamp (pr-str (vec v)) 60)
                       (map? v) (str "{…" (count v) "k}")
                       :else    (pr-str v)))]
      (->> interesting
           (map (fn [[k v]] (str (name k) "=" (fmt-v v))))
           (str/join " ")))))

;; -- stream pane --------------------------------------------------------------

(defn ask-pre-line
  "Format the user-input echo for the stream pane.

   Two arities — the legacy `(ask-pre-line input)` form keeps callers
   that don't have access to pane width working; the new
   `(ask-pre-line input {:cols N})` form wraps long pasted prompts to
   the pane via `ansi-aware-word-wrap`.  Only the FIRST row carries
   the `❯ ` prompt glyph; continuation rows are indented to align
   with the wrapped text so they don't look like fresh prompts."
  ([input] (ask-pre-line input {}))
  ([input {:keys [cols]}]
   (let [text (str input)
         prefix (style "❯ " bold bright-cyan)
         indent "  "                   ; 2-space indent matching `❯ ` width
         render-row (fn [first? s]
                      (str (if first? prefix indent)
                           (style s bright-white)))
         rows (cond
                (and cols (> (display-width text) (max 1 (- cols 2))))
                (ansi-aware-word-wrap text (max 1 (- cols 2)))

                :else
                [text])]
     (str "\n"
          (str/join "\n"
                    (map-indexed (fn [i r] (render-row (zero? i) r)) rows))
          "\n"))))

(defn- apply-inline-md
  "Apply inline markdown styling to `s`: `**bold**`, `*italic*`,
   and `` `code` ``.  Order matters: bold first (so `**foo**` isn't
   eaten by the italic regex), then italic with a negative lookaround
   to avoid double-asterisks, then inline code.

   Each substitution wraps its capture group in the appropriate
   ANSI sequence + reset.  Plain text passes through.  Used by
   `markdown-line` after block-level decisions are made."
  [s]
  (-> s
      (str/replace #"\*\*([^*]+)\*\*"
                   (fn [[_ inner]] (style inner bold bright-white)))
      (str/replace #"(?<!\*)\*([^*]+)\*(?!\*)"
                   (fn [[_ inner]] (style inner italic)))
      (str/replace #"`([^`]+)`"
                   (fn [[_ inner]] (style inner cyan)))))

(defn- markdown-line
  "Apply lightweight markdown styling to a single line.  Stateless —
   `in-fence?` is the caller's running flag (true ⇒ we're inside a
   ```code``` block and should dim everything).  Returns the styled
   line.  Recognized constructs:

     # / ## / ### headings  → bold bright-white
     - / * / 1. list items   → cyan bullet + content (with inline md
                              applied to the rest of the line)
     ```                     → dim fence row
     **bold**                → bold bright-white inline
     *italic*                → italic inline
     `inline code`           → cyan span

   Anything else passes through `apply-inline-md` so inline styling
   still kicks in for plain paragraphs."
  [line in-fence?]
  (cond
    in-fence?
    (style line dim)

    (re-find #"^\s*```" line)
    (style line dim)

    ;; Headings: strip the `#`/`##`/`###` markers and bold the content
    ;; so the rendered output reads as a styled title rather than raw
    ;; markdown.  Indent (leading whitespace) is preserved.  Inline
    ;; styling is applied to the heading body too so a heading like
    ;; "## Use `foo`" still styles the inline code span.
    (re-find #"^(\s*)###\s+(.*)$" line)
    (let [[_ indent body] (re-find #"^(\s*)###\s+(.*)$" line)]
      (str indent (style (apply-inline-md body) bold bright-white)))

    (re-find #"^(\s*)##\s+(.*)$" line)
    (let [[_ indent body] (re-find #"^(\s*)##\s+(.*)$" line)]
      (str indent (style (apply-inline-md body) bold bright-white)))

    (re-find #"^(\s*)#\s+(.*)$" line)
    (let [[_ indent body] (re-find #"^(\s*)#\s+(.*)$" line)]
      (str indent (style (apply-inline-md body) bold bright-white)))

    (re-find #"^(\s*)[-*]\s" line)
    (let [[_ indent rest-line] (re-find #"^(\s*)[-*]\s(.*)$" line)]
      (str indent (style "•" cyan) " " (apply-inline-md rest-line)))

    (re-find #"^(\s*)\d+\.\s" line)
    (let [[_ indent num rest-line] (re-find #"^(\s*)(\d+)\.\s(.*)$" line)]
      (str indent (style (str num ".") cyan) " " (apply-inline-md rest-line)))

    :else
    (apply-inline-md line)))

(defn render-markdown
  "Apply line-by-line markdown styling to `s`.  Tracks ``` fence
   state across lines so multi-line code blocks dim consistently.
   Returns the rendered string (with newlines preserved).

   `:cols` (optional) — when set, each rendered line is word-wrapped
   to fit within the pane via `ansi-aware-word-wrap`.  Code fence
   rows are wrapped too so a long code line doesn't overflow.
   Without `:cols`, no wrapping is done (legacy behavior)."
  ([s] (render-markdown s nil))
  ([s {:keys [cols]}]
   (let [lines (str/split-lines (str s))]
     (loop [acc []
            in-fence? false
            remaining lines]
       (if (empty? remaining)
         (str/join "\n" acc)
         (let [line (first remaining)
               fence? (boolean (re-find #"^\s*```" line))
               ;; Inside-fence flag for THIS line: stays true through
               ;; the closing fence row so the closing ``` is also dim.
               styled (markdown-line line in-fence?)
               wrapped (if cols
                         (str/join "\n" (ansi-aware-word-wrap styled cols))
                         styled)
               next-in-fence? (if fence? (not in-fence?) in-fence?)]
           (recur (conj acc wrapped) next-in-fence? (rest remaining))))))))

(defn ask-post-line
  "Format the agent's final answer for the stream pane.  Adds a thin
   separator so consecutive turns are visually delimited.

   - `:markdown?` (default true) — run the answer through
     `render-markdown` so headings, code fences, and lists are
     styled.  Set false for raw rendering.
   - `:cols` (optional) — wrap rendered lines to fit the pane.  The
     trailing horizontal-rule separator is also drawn at this width
     so it stretches edge-to-edge instead of a fixed 60 cols.

   Either may be passed independently."
  [{:keys [answer error iteration-count markdown? cols]
    :or {markdown? true}}]
  (let [body (cond
               error  (failure (str "✗ " error))
               answer (let [s (str/trim-newline (str answer))]
                        (if markdown?
                          (render-markdown s {:cols cols})
                          ;; Even with markdown disabled, honor :cols
                          ;; so very long answer lines wrap.
                          (if cols
                            (->> (str/split-lines s)
                                 (mapcat #(ansi-aware-word-wrap % cols))
                                 (str/join "\n"))
                            s)))
               :else  "(no answer)")
        ;; Header marker: parallel to `[reasoning]`/`[observe]`/`• Code:`
        ;; section labels elsewhere in the stream.  Successes use a
        ;; green `▾ answer`, failures a red `▾ error` — same family,
        ;; different color so the reader immediately sees the verdict
        ;; before parsing the body.
        header (cond
                 error
                 (str (style "▾ error" bold bright-red)
                      (when iteration-count
                        (muted (str "  (" iteration-count " iters)")))
                      "\n")
                 answer
                 (str (style "▾ answer" bold bright-green)
                      (when iteration-count
                        (muted (str "  (" iteration-count " iters)")))
                      "\n"))
        rule-w (or cols 60)]
    (str (or header "")
         body
         "\n"
         (style (apply str (repeat rule-w h-line)) dim)
         "\n")))

(defn tool-pre-line
  "Format a `:agent.tool-use/pre` event.

   `:cols` (optional) is the live pane width — when set, a long
   args summary is clamped to fit on one line instead of wrapping.
   Falls back to the legacy unbounded `short-args` output when
   absent."
  [{:keys [tool-name args depth cols]}]
  (let [indent (apply str (repeat (* 2 (max 0 (or depth 0))) " "))
        arg-s  (short-args args)
        ;; Reserve room for indent + glyph + name + space.
        budget (when cols
                 (max 20 (- cols (count indent) 2
                            (count (str (name (or tool-name "")))) 1)))
        arg-s  (if budget (clamp arg-s budget) arg-s)]
    (str indent
         (style "⮕ " bright-yellow)
         (tname (str (name (or tool-name "tool"))))
         (when (seq arg-s)
           (str " " (muted arg-s)))
         "\n")))

(defn tool-post-line
  "Format a `:agent.tool-use/post` event.  Truncates the result preview so a multi-KB
   blob doesn't drown the pane.  When the tool result map carries any
   well-known diagnostic key (`:error`, `:hook-blocked`, `:error-message`,
   `:reason`, `:message`), surfaces that text in red so the user sees WHY
   without digging into a separate trace dump.

   Render shape:
     ✓ <tool-name> <preview>     when result is a string / `{:answer …}` map
     ✗ <tool-name> <error-msg>   when result map carries an error/diag key
     ✓ <tool-name>               when result has no useful preview (nil,
                                 boolean, empty map without diag keys)

   Note: tools that legitimately return `nil` are rendered as ✓ — the
   render layer cannot distinguish a successful no-result from a silent
   failure.  Upstream is responsible for converting silent failures into
   a diagnostic map.

   `:cols` (optional) is the live pane width — when set, the preview
   uses up to `(cols - reserve)` chars instead of a hardcoded cap.
   Falls back to the legacy 80/200-char limits when absent."
  [{:keys [tool-name result depth cols]}]
  (let [indent (apply str (repeat (* 2 (max 0 (or depth 0))) " "))
        err-text (when (map? result)
                   (or (:error result)
                       (:hook-blocked result)
                       ;; `invoke-tool` itself returns
                       ;; `{:error-message ...}` for unknown / undefined
                       ;; tools — surface that diagnostic too rather than
                       ;; rendering as a false ✓.
                       (:error-message result)
                       ;; Some hooks return `{:reason "..."}` or
                       ;; `{:message "..."}` — surface those too rather
                       ;; than swallowing the diagnostic.
                       (:reason result)
                       (:message result)))
        ok?    (not err-text)
        glyph  (if ok? (style "✓ " bright-green)
                   (style "✗ " bright-red))
        name-s (tname (str (name (or tool-name "tool"))))
        ;; Reserve space for indent + glyph + name + space.  The name
        ;; is styled with ANSI, but we only need a rough budget — when
        ;; cols is unknown, fall back to the legacy fixed limit.
        budget (when cols
                 (max 20 (- cols (count indent) 4 (count (str (name (or tool-name "")))) 1)))
        ok-cap   (or budget 80)
        err-cap  (or budget 200)
        preview (cond
                  err-text                       (let [s (collapse-ws err-text)]
                                                   (when (seq s) (clamp s err-cap)))
                  (string? result)               (let [s (collapse-ws result)]
                                                   (when (seq s) (clamp s ok-cap)))
                  (and (map? result) (:answer result))
                  (clamp (collapse-ws (:answer result)) ok-cap)
                  :else nil)]
    (str indent glyph name-s
         (when (seq preview)
           (str " "
                (if err-text
                  (failure preview)
                  (muted preview))))
         "\n")))

(defn todo-block
  "Render a multi-line block summarising the agent's current TODO list.
   Empty list → nil (caller should skip)."
  [{:keys [todo-list active-slug]}]
  (when (seq todo-list)
    (let [done-count (count (filter :done todo-list))
          total      (count todo-list)
          header     (str (style "▾ todo" bold bright-yellow)
                          (when active-slug
                            (str " " (muted (str "(" active-slug ")"))))
                          "  "
                          (muted (str done-count "/" total)))
          row        (fn [{:keys [description done]}]
                       (let [box (if done
                                   (style "[x]" bright-green)
                                   (style "[ ]" dim))]
                         (str "  " box " "
                              (if done (style description dim) description))))]
      (str header "\n"
           (str/join "\n" (map row todo-list))
           "\n"))))

(defn agent-created-line
  "Subtle one-liner for `:agent.instance/created`.  Only emitted for sub-agents
   (root agent's creation is implicit in the welcome banner)."
  [{:keys [agent-id parent-name]}]
  (str (style "» agent" bold bright-cyan) " "
       (style (str (some-> agent-id name)) bold cyan)
       (when parent-name
         (str "  " (muted (str "← " parent-name))))
       "\n"))

(defn agent-closed-line
  "One-liner for `:agent.instance/closed`."
  [{:keys [agent-id]}]
  (str (style "« agent closed" dim) " "
       (style (str (some-> agent-id name)) dim)
       "\n"))

(defn task-created-line
  "Single-line announcement for `:task/created`."
  [{:keys [id name]}]
  (str (style "▸ task" bold bright-cyan) " "
       (style (str id) dim)
       (when name (str " " name))
       "\n"))

(defn task-completed-line
  "Single-line announcement for `:task/completed`."
  [{:keys [id name status completed-at started-at]}]
  (let [glyph (case status
                :completed (style "✓ task" bold bright-green)
                :failed    (style "✗ task" bold bright-red)
                :cancelled (style "⊘ task" bold bright-yellow)
                (style "• task" dim))
        elapsed (when (and started-at completed-at)
                  (let [secs (/ (- completed-at started-at) 1000.0)]
                    (format "%.1fs" secs)))]
    (str glyph " "
         (style (str id) dim)
         (when name (str " " name))
         (when elapsed (str "  " (muted elapsed)))
         "\n")))

(defn help-block
  "Print a one-shot help block for the daemon's slash surface."
  []
  (let [bar  (apply str (repeat 60 h-line))
        row  (fn [cmd desc]
               ;; Pad up to 28 cells so the description column lines up
               ;; even for the longer commands like `/agent rename <s> <n>`.
               (let [pad (apply str (repeat (max 1 (- 28 (count cmd))) " "))]
                 (str "  " (style cmd bold bright-cyan) pad (muted desc))))
        section (fn [title rows]
                  (str (style title bold bright-white) "\n"
                       (str/join "\n" rows)))]
    (str (style bar dim) "\n"
         (section "Commands"
                  [(row "/help"                 "this list")
                   (row "/status"               "current agent state snapshot")
                   (row "/todo"                 "render the current TODO list")
                   (row "/queue"                "list pending input queue items")
                   (row "/usage [N] [--breakdown]" "totals + latest N calls (default 10)")
                   (row "/effort low|med|high"  "set effort preset (iterations/thinking)")
                   (row "/compact [ratio]"      "compact context to reduce tokens")
                   (row "/history [--input|--slash] [N]" "recent inputs (filterable, default 20)")
                   (row "/tasks"                "list active background tasks")
                   (row "/clear-tasks"          "remove completed/failed/cancelled tasks")
                   (row "/cancel"               "cancel the current iteration (Ctrl-C)")
                   (row "/sandbox"              "current clj-sandbox session info")
                   (row "/mcp"                  "list configured MCP servers + tool counts")
                   (row "/trace [N]"            "last N entries from the BT thinking trace")
                   (row "/capture <path>"       "snapshot scrollback to a file (ANSI stripped)")
                   (row "/allow-path <path>"    "add path to the agent's filesystem allow-list")
                   (row "/task list|detail|cancel|del|log <id>" "manage background tasks")
                   (row "/messages [N]"         "last N conversation messages (default 10)")
                   (row "/memory recall <q> [--tag a,b,c]" "search memory; optional tag filter")
                   (row "/memory remember <text> [--type kind] [--tag a,b,c]" "store a fact (optional tags)")
                   (row "/memory list [--tag a,b,c]" "browse stored entries (optional tag filter)")
                   (row "/memory forget <id>"   "delete an entry by id")
                   (row "/memory stats"         "memory layer counts + schema version")
                   (row "/context"              "snapshot of current st-memory + conversation")
                   (row "/lm list"              "show provider/model catalog + current LM")
                   (row "/lm switch <prov> [model]" "hot-swap the default LM")
                   (row "/lm test [<prompt>]"    "1-shot probe to verify the active LM")
                   (row "/lm models <prov> [page]" "paginated browse of a provider's models")
                   (row "/skills list"          "enumerate brainyard/claude/agents skills")
                   (row "/skills find <q>"      "search skills by name/description")
                   (row "/skills read <name>"   "show a skill's body (truncated)")
                   (row "/tools list [--type T]" "unified tool/command/skill/agent registry")
                   (row "/version"              "show app version + mode")
                   (row "/keys"                 "show keyboard shortcuts")
                   (row "/model [name]"         "show or hot-swap the active LM")
                   (row "/clear"                "restart session: clear history, scrollback, st-memory")
                   (row "/quit"                 "shut down the agent and exit")])
         "\n\n"
         (section "Sessions & agents"
                  [(row "/session new <type> [n]"  "create a new session, auto-open window")
                   (row "/session list"             "list persisted sessions on disk")
                   (row "/session attach <id>"      "switch to another session (auto-attaches)")
                   (row "/session rename <s> <n>"   "rename a session's tmux window")
                   (row "/session delete <id>"      "delete a persisted session (alias prune)")
                   (row "/agent list"               "list active agents (in-memory)")
                   (row "/agent close <sid>"        "tear down an agent + its window")])
         "\n\n"
         (section "Layout chrome"
                  [(row "/activity show|hide"   "split off a dedicated activity pane")
                   (row "/popup quiet on|off"   "render dialogs inline instead of popups")
                   (row "/layout default|compact|with-activity" "switch layout mode")])
         "\n\n"
         (section "Runtime toggles (colon prefix)"
                  [(row ":thinking on|off"      "show/hide LLM streaming text")
                   (row ":verbose normal|verbose|debug" "set output verbosity mode")
                   (row ":quiet on|off"         "suppress non-essential output")
                   (row ":debug on|off"         "alias for :verbose debug | :verbose normal")
                   (row ":model [name]"         "alias for /model")
                   (row ":usage"                "alias for /usage")])
         "\n\n"
         (section "Keys"
                  [(row "prefix d"        "detach (tmux); daemon keeps running")
                   (row "prefix n / p"    "next / previous agent (window)")
                   (row "prefix [N]"      "jump to agent N")
                   (row "Ctrl-C"          "cancel current iteration; double = exit")
                   (row "Ctrl-D (empty)"  "detach")
                   (row "Tab / ↑ ↓"       "autocomplete cycle")])
         "\n"
         (style bar dim) "\n")))

(defn model-block
  "Single-shot info block for `/model` (no args)."
  [{:keys [provider model]}]
  (str (style "Current Model" bold bright-white) "\n"
       "  provider : " (style (str (or (some-> provider name) "?")) bold cyan) "\n"
       "  model    : " (style (str (or model "?")) bold bright-magenta) "\n"
       (muted "  Pass `/model <name>` to hot-swap the active model.") "\n"))

(defn- segment [k v]
  (str "  " k " : " v))

(defn status-block-cmd
  "Multi-row block for `/status` — dump of the daemon's snapshot.
   Distinct from `status-block` (which paints the 2-row status pane)."
  [{:keys [state agent-id model iter max-iter tools-used queue-depth
           todo-progress mode version]
    :as _snapshot}]
  (let [state-color (case state
                      (:thinking :running) bright-green
                      :idle bright-yellow
                      (:cancelled :stopped) bright-red
                      white)]
    (str (style "Agent Status" bold bright-white) "\n"
         (segment "state    " (style (str (name (or state :idle))) state-color)) "\n"
         (segment "agent    " (style (str (some-> agent-id name)) bold cyan)) "\n"
         (segment "model    " (style (str (or model "?")) bright-magenta)) "\n"
         (segment "iter     " (str (or iter 0)
                                   (when max-iter (str " / " max-iter)))) "\n"
         (segment "tools    " (str (or tools-used 0))) "\n"
         (segment "queue    " (str (or queue-depth 0))) "\n"
         (when todo-progress
           (str (segment "todo     " (str (:done todo-progress) "/"
                                          (:total todo-progress))) "\n"))
         (segment "mode     " (str (some-> mode name (str)))) "\n"
         (segment "version  " (str "v" (or version "?"))) "\n")))

(defn queue-block
  "Render the daemon's pending input queue as a numbered list."
  [items]
  (if (empty? items)
    (str (muted "  (queue is empty)") "\n")
    (str (style "Pending Input Queue" bold bright-white) "\n"
         (str/join "\n"
                   (map-indexed
                    (fn [i {:keys [input status]}]
                      (str "  " (style (str (inc i) ".") dim) " "
                           (style (or input "(no text)") bright-white)
                           (when (and status (not= status :queued))
                             (str "  " (muted (str "[" (name status) "]"))))))
                    items))
         "\n")))

(defn- fmt-int
  "Right-align a non-negative integer for column display."
  [n width]
  (let [s (str (or n 0))]
    (str (apply str (repeat (max 0 (- width (count s))) " ")) s)))

(defn- fmt-ms
  "Format a latency in ms or `-` when missing.  Right-aligned to `width`."
  [ms width]
  (let [s (if ms (str ms "ms") "-")]
    (str (apply str (repeat (max 0 (- width (count s))) " ")) s)))

(defn usage-block
  "Render LLM usage stats (tokens + cost).  Accepts whichever of these
   keys are available; missing ones render as `?`.  When `:history` is
   passed (vector of usage records), renders an additional per-call
   table below the aggregate so the user sees where their tokens went —
   matching the legacy TUI's combined `format-usage-summary` +
   `format-token-breakdown`."
  [{:keys [provider model input-tokens output-tokens total-tokens
           cached-tokens cost-usd request-count history]}]
  (let [;; Shared column widths for the per-call table.
        w-i 3, w-in 8, w-out 8, w-cache 8, w-cost 9
        per-call-rows
        (when (seq history)
          (let [recent (vec (take-last 20 (reverse history)))
                cache-of (fn [c]
                           (+ (or (get-in c [:cache :read-tokens]) 0)
                              (or (get-in c [:cache :write-tokens]) 0)
                              (or (:cache-read-tokens c) 0)
                              (or (:cache-write-tokens c) 0)))
                cost-of (fn [c]
                          (or (get-in c [:cost :total-cost])
                              (:cost-usd c)
                              0.0))
                fmt-cost (fn [n]
                           (let [s (format "$%.4f" (double n))]
                             (str (apply str (repeat (max 0 (- w-cost (count s))) " ")) s)))
                hdr (str "  "
                         (style (fmt-int "#" w-i) dim) "  "
                         (style (fmt-int "In Tok" w-in) dim) "  "
                         (style (fmt-int "Out Tok" w-out) dim) "  "
                         (style (fmt-int "Cached" w-cache) dim) "  "
                         (style (apply str (repeat (max 0 (- w-cost 4)) " "))
                                dim)
                         (style "Cost" dim) "  "
                         (style "Model" dim))
                row (fn [i c]
                      (let [model-s (cond
                                      (and (:provider c) (:model c))
                                      (str (name (:provider c)) ":" (:model c))
                                      (:model c) (str (:model c))
                                      :else "?")
                            model-clamped (if (> (count model-s) 32)
                                            (subs model-s 0 32)
                                            model-s)]
                        (str "  "
                             (fmt-int (str (inc i)) w-i) "  "
                             (fmt-int (:input-tokens c) w-in) "  "
                             (fmt-int (:output-tokens c) w-out) "  "
                             (fmt-int (cache-of c) w-cache) "  "
                             (fmt-cost (cost-of c)) "  "
                             (style model-clamped bright-magenta))))]
            (str "\n" hdr "\n"
                 (str/join "\n" (map-indexed row recent))
                 "\n")))]
    (str (style "LLM Usage" bold bright-white) "\n"
         (segment "provider     " (str (some-> provider name)
                                       (when model (str " / " model)))) "\n"
         (segment "input tokens " (str (or input-tokens "?"))) "\n"
         (segment "output tokens" (str (or output-tokens "?"))) "\n"
         (when cached-tokens
           (str (segment "cached       " (str cached-tokens)) "\n"))
         (segment "total tokens " (str (or total-tokens "?"))) "\n"
         (when cost-usd
           (str (segment "cost (USD)   " (format "$%.4f" (double cost-usd))) "\n"))
         (when request-count
           (str (segment "requests     " (str request-count)) "\n"))
         per-call-rows)))

(defn iteration-header
  "One-line header announcing a new BT iteration.  Mirrors the legacy
   `format-iteration-header`."
  [iter max-iter]
  (str (style (str "[+] Iteration " iter
                   (when max-iter (str " / " max-iter)))
              bold bright-white)
       "\n"))

;; fmt-int / fmt-ms moved earlier in the file (above usage-block) so the
;; per-call table helpers are available to both /usage and /perf without
;; forward-reference gymnastics.

(defn perf-block
  "Per-call latency / token breakdown.  `history` is a vec of usage records
   carrying any subset of `:duration-ms :latency-ms :input-tokens
   :output-tokens :model :provider :tool`.

   Renders the aggregate summary FIRST, then the last 20 calls as a table —
   mirrors the legacy TUI's `format-perf-summary` so users can spot a slow
   call vs. just see averages."
  [history]
  (if (empty? history)
    (str (muted "  (no calls recorded yet)") "\n")
    (let [total (count history)
          ;; Some recorders use :duration-ms, others :latency-ms — accept both.
          dur-of (fn [c] (or (:duration-ms c) (:latency-ms c)))
          durations (keep dur-of history)
          total-ms (apply + 0 durations)
          avg-ms (when (seq durations) (long (/ total-ms (count durations))))
          input  (apply + 0 (keep :input-tokens history))
          output (apply + 0 (keep :output-tokens history))
          ;; Per-call rows — show the most recent 20 in chronological order
          ;; (oldest first so users can scan top→bottom as the session
          ;; progressed).  History is typically newest-first; reverse it.
          recent (vec (take-last 20 (reverse history)))
          ;; Column widths shared by header and data rows so they line up
          ;; visually.  Tweak either side and both update.
          w-i 3, w-lat 7, w-in 8, w-out 8
          header-row (str "  "
                          (style (fmt-int "#" w-i) dim) "  "
                          (style (fmt-int "Lat" w-lat) dim) "  "
                          (style (fmt-int "In Tok" w-in) dim) "  "
                          (style (fmt-int "Out Tok" w-out) dim) "  "
                          (style "Model" dim))
          row-of (fn [i c]
                   (let [model-s (cond
                                   (and (:provider c) (:model c))
                                   (str (name (:provider c)) ":" (:model c))
                                   (:model c) (str (:model c))
                                   :else "?")
                         model-clamped (if (> (count model-s) 32)
                                         (subs model-s 0 32)
                                         model-s)]
                     (str "  "
                          (fmt-int (str (inc i)) w-i) "  "
                          (fmt-ms (dur-of c) w-lat) "  "
                          (fmt-int (:input-tokens c) w-in) "  "
                          (fmt-int (:output-tokens c) w-out) "  "
                          (style model-clamped bright-magenta))))]
      (str (style "Performance" bold bright-white) "\n"
           "  calls         : " (style (str total) bold) "\n"
           "  total time    : " (style (str total-ms " ms") bold) "\n"
           (when avg-ms
             (str "  avg per call  : " (style (str avg-ms " ms") bold) "\n"))
           "  input tokens  : " (style (str input) bold) "\n"
           "  output tokens : " (style (str output) bold) "\n"
           "\n"
           header-row "\n"
           (str/join "\n" (map-indexed row-of recent))
           "\n"))))

(defn effort-block
  "Show or confirm the active effort preset (low | medium | high | custom)."
  [{:keys [level details]}]
  (str (style "Effort" bold bright-white) "\n"
       "  level   : " (style (str (or (some-> level name) "custom"))
                             bold cyan) "\n"
       (when (seq details)
         (apply str
                (for [[k v] (sort-by key details)]
                  (str "  " (name k) " : " (style (str v) bright-white) "\n"))))))

(defn history-block
  "Render the last `events` (most-recent first) of `:input`/`:slash` events
   from messages.log.  Each entry → `❯ <text>` row with the prompt dimmed
   so it reads as past history rather than the live prompt."
  [events]
  (if (empty? events)
    (str (muted "  (no history)") "\n")
    (str (style "Recent input" bold bright-white) "\n"
         (str/join "\n"
                   (for [{:keys [kind payload]} events]
                     (let [line (case kind
                                  :input (:line payload)
                                  :slash (str (:command payload)
                                              (when (seq (:args payload))
                                                (str " " (:args payload))))
                                  (str "?" (str payload)))]
                       (str "  " (style "❯" dim) " "
                            (style (str line) bright-white)))))
         "\n")))

(defn tasks-block
  "Render the active task list."
  [tasks]
  (if (empty? tasks)
    (str (muted "  (no active tasks)") "\n")
    (str (style "Active Tasks" bold bright-white) "\n"
         (str/join "\n"
                   (for [{:keys [id name status created-at]} tasks]
                     (let [glyph (case status
                                   :running (style "▸" bold bright-cyan)
                                   :pending (style "·" dim)
                                   :completed (style "✓" bold bright-green)
                                   :failed (style "✗" bold bright-red)
                                   :cancelled (style "⊘" bold bright-yellow)
                                   (style "?" dim))
                           age (when created-at
                                 (let [s (long (/ (- (System/currentTimeMillis)
                                                     created-at) 1000))]
                                   (str s "s")))]
                       (str "  " glyph " "
                            (style (str id) dim) " "
                            (or name "(unnamed)")
                            (when age (str "  " (muted age)))))))
         "\n")))

(defn version-block
  "One-line version output for `/version`."
  [{:keys [version mode]}]
  (str (style "Brainyard Agent TUI" bold bright-white)
       "  " (style (str "v" (or version "?")) bold bright-magenta)
       "  " (muted (str "(mode: " (some-> mode name) ")"))
       "\n"))

(defn keys-block
  "Lift the legacy /help keybindings into a focused block for /keys."
  []
  (let [row (fn [k desc]
              (let [pad (apply str (repeat (max 1 (- 22 (count k))) " "))]
                (str "  " (style k bold bright-cyan) pad (muted desc))))]
    (str (style "Keys" bold bright-white) "\n"
         (str/join "\n"
                   [(row "Tab"             "open / cycle autocomplete forward")
                    (row "↑ / ↓"           "history (idle) or popup nav (open)")
                    (row "Enter"           "submit input or accept popup item")
                    (row "Esc"             "clear buffer or dismiss popup")
                    (row "Ctrl-C"          "cancel current iteration; double = exit")
                    (row "Ctrl-D"          "detach (with empty buffer)")
                    (row "Ctrl-L"          "redraw all panes")
                    (row "prefix d"        "tmux detach; daemon keeps running")
                    (row "prefix n / p"    "next / previous agent (window)")
                    (row "prefix [N]"      "jump to agent N")])
         "\n")))

(defn sandbox-block
  "Render the clj-sandbox session summary (when present)."
  [info]
  (if (or (nil? info) (empty? info))
    (str (muted "  (sandbox not active)") "\n")
    (str (style "Sandbox" bold bright-white) "\n"
         (apply str
                (for [[k v] (sort-by key info)]
                  (str "  " (name k)
                       (apply str (repeat (max 1 (- 16 (count (name k)))) " "))
                       ": " (style (str v) bright-white) "\n"))))))

(defn mcp-block
  "Multi-row block for `/mcp` — summarise configured servers, connection
   status, and tool counts.  Input shape:

     {:total      <count of configured servers>
      :connected  <count of currently-connected servers>
      :servers    [{:name :enabled :connected :transport :tool-count} ...]}

   When `:servers` is empty (or the agent component isn't on the classpath),
   we render a `(no MCP servers configured)` marker."
  [{:keys [total connected servers] :as info}]
  (cond
    (nil? info)
    (str (muted "  (MCP not available)") "\n")

    (or (zero? (or total 0)) (empty? servers))
    (str (muted "  (no MCP servers configured)") "\n")

    :else
    (let [head (str (style "MCP Servers" bold bright-white) "  "
                    (muted (format "(%d configured, %d connected)"
                                   total connected))
                    "\n")
          rows (for [{:keys [name enabled connected transport tool-count]} servers
                     :let [glyph (cond
                                   connected   (style "●" bold bright-green)
                                   (false? enabled) (style "○" dim)
                                   :else       (style "○" bold bright-yellow))
                           pad   (apply str (repeat (max 1 (- 22 (count (str name)))) " "))
                           tt    (or transport "?")
                           tc    (or tool-count "—")]]
                 (str "  " glyph "  "
                      (style (str name) bold bright-cyan) pad
                      (muted (str "transport=" (clojure.core/name tt)
                                  "  tools=" tc))))]
      (str head (str/join "\n" rows) "\n"))))

(defn trace-block
  "Render the most recent BT trace entries from the agent's
   `[:data :traces]` vector.  Each trace is `{:agent-id :depth :content}`.
   `n` caps the number of rows shown.  Empty input renders a marker."
  [traces & {:keys [n] :or {n 30}}]
  (cond
    (or (nil? traces) (empty? traces))
    (str (muted "  (no traces yet — ask the agent something first)") "\n")

    :else
    (let [recent (vec (take-last n traces))
          row    (fn [{:keys [agent-id depth content]}]
                   (let [pre (apply str (repeat (* 2 (or depth 0)) " "))
                         id  (style (str (or agent-id "?")) cyan)
                         body (clamp (collapse-ws content) 100)]
                     (str pre (muted "▸ ") id " " (style body bright-white))))]
      (str (style "BT Trace" bold bright-white) "  "
           (muted (format "(last %d of %d)" (count recent) (count traces)))
           "\n"
           (str/join "\n" (map row recent))
           "\n"))))

(defn- wrap-block-row
  "Wrap one logical row of body text to fit the pane.  Body lines are
   indented by 2 spaces; when `:cols` is set, each line is word-wrapped
   to `(- cols 2)` so styled text stays within the pane.  Returns a
   single string with `\\n`-joined wrapped sub-rows, each prefixed with
   the 2-space indent."
  [^String row cols]
  (let [inner (when cols (max 1 (- cols 2)))
        parts (if inner
                (ansi-aware-word-wrap row inner)
                [row])]
    (str/join "\n" (map (fn [p] (str "  " (muted p))) parts))))

(defn reasoning-block
  "Render an LLM reasoning chain (chain-of-thought) as a soft block.

   `:cols` (optional) — when set, each body row word-wraps to fit the
   pane width.  Without it, long reasoning rows overflow the pane."
  ([text] (reasoning-block text {}))
  ([^String text {:keys [cols]}]
   (when (and text (seq (str/trim text)))
     (let [body (-> text str/trim)
           rows (str/split body #"\r?\n")]
       (str "\n"
            (style "[reasoning]" bold bright-magenta) "\n"
            (str/join "\n" (map #(wrap-block-row % cols) rows))
            "\n")))))

(defn observation-block
  "Render the agent's `:observation` for the iteration — what it
   noticed about the previous tool outputs.  Mirrors the legacy
   `format-observation`: muted body, leading marker so it stands
   apart from reasoning.

   `:cols` (optional) — when set, body rows word-wrap to fit the pane."
  ([text] (observation-block text {}))
  ([^String text {:keys [cols]}]
   (when (and text (seq (str/trim text)))
     (let [rows (str/split (str/trim text) #"\r?\n")]
       (str "\n"
            (style "[observe]" bold bright-cyan) "\n"
            (str/join "\n" (map #(wrap-block-row % cols) rows))
            "\n")))))

(defn goal-status-line
  "Render the iteration's goal-achieved verdict.  When `goal-achieved?`
   is truthy, show ✓ Goal achieved (with optional reasoning); else
   ✗ Goal not yet achieved.  Mirrors legacy `format-goal-status`."
  [{:keys [goal-achieved? goal-reasoning]}]
  (let [glyph (if goal-achieved?
                (style "✓ Goal achieved" bold bright-green)
                (style "✗ Goal not yet achieved" bold bright-yellow))
        reason (when (and goal-reasoning
                          (not (clojure.string/blank? (str goal-reasoning))))
                 (str " " (muted (str "(" (clamp (collapse-ws goal-reasoning) 200) ")"))))]
    ;; Leading "\n" so the verdict visually separates from whichever block
    ;; preceded it (reasoning, code-display, eval-result, or another emit
    ;; that ended with a single trailing newline).
    (str "\n  " glyph reason "\n")))

(defn- eval-section
  "Render one labeled section of an eval-display entry.  `body` is the
   raw text; `style-fn` applies to each rendered body line.  When
   `:cols` is supplied, lines wrap via `ansi-aware-word-wrap`.

   Visual style mirrors legacy `format-eval-section` but trimmed:

       • <label>:
         │ <styled body line>
         │ <styled body line>"
  [label body style-fn & {:keys [cols max-bytes]
                          :or {max-bytes 4000}}]
  (when (and body (not (str/blank? (str body))))
    (let [body (let [s (str body)]
                 (if (> (count s) max-bytes)
                   (str (subs s 0 max-bytes) "\n…")
                   s))
          inner-cols (when cols (max 20 (- cols 6)))
          row (fn [l]
                (let [parts (if inner-cols
                              (ansi-aware-word-wrap l inner-cols)
                              [l])]
                  (str/join "\n"
                            (map #(str "    " (muted "│ ")
                                       (if style-fn (style-fn %) %))
                                 parts))))]
      (str "  " (muted (str "• " label ":")) "\n"
           (str/join "\n" (map row (str/split-lines body)))))))

(defn code-display-block
  "Render the `:code-display` stage of coact / similar agents.
   `eval-display` is a vector of `{:code <src>}` maps — code that's
   about to run.  When there are multiple blocks, each is labeled
   `Code[1]`, `Code[2]`, …; a single block uses `Code`.

   `:cols` (optional) wraps long code lines."
  ([eval-display] (code-display-block eval-display {}))
  ([eval-display {:keys [cols]}]
   (when (seq eval-display)
     (let [multi? (> (count eval-display) 1)
           ;; Code stays uncolored — readability beats syntax highlight
           ;; for now.  Future: hook a real syntax highlighter per
           ;; language.  For now we just dim it slightly so it doesn't
           ;; visually compete with reasoning blocks.
           code-style (fn [s] (style s dim))
           sections (->> eval-display
                         (map-indexed
                          (fn [i {:keys [code]}]
                            (let [label (if multi? (str "Code[" (inc i) "]") "Code")]
                              (eval-section label code code-style :cols cols))))
                         (remove nil?)
                         (str/join "\n"))]
       (when (seq sections)
         (str "\n" sections "\n"))))))

(defn eval-result-block
  "Render the `:eval-result` stage of coact / similar agents.
   `eval-display` is a vector of `{:code :result? :output? :error?}`
   maps — code that already ran, with its captured outputs.  Per
   block, we emit Result / Output / Error sections (skipping Code
   since it was already shown in the prior `:code-display` stage).

   Blocks with `:error` show that section in red.  `:result \"nil\"`
   is suppressed because nil-returning side-effect blocks aren't
   interesting to look at.

   `:cols` (optional) wraps long output lines."
  ([eval-display] (eval-result-block eval-display {}))
  ([eval-display {:keys [cols]}]
   (when (seq eval-display)
     (let [multi? (> (count eval-display) 1)
           sections
           (->> eval-display
                (map-indexed
                 (fn [i {:keys [result output error]}]
                   (let [sfx (if multi? (str "[" (inc i) "]") "")
                         result-s (when (and result (not= "nil" (str result)))
                                    (eval-section (str "Result" sfx)
                                                  result nil :cols cols))
                         output-s (when (and output (not (str/blank? (str output))))
                                    (eval-section (str "Output" sfx)
                                                  output nil :cols cols))
                         error-s  (when (and error (not (str/blank? (str error))))
                                    (eval-section (str "Error" sfx)
                                                  error
                                                  (fn [s] (style s bright-red))
                                                  :cols cols))]
                     (->> [result-s output-s error-s]
                          (remove nil?)
                          (str/join "\n")))))
                (remove str/blank?)
                (str/join "\n\n"))]
       (when (seq sections)
         (str "\n" sections "\n"))))))

(defn agent-eval-line
  "One-line eval-agent verdict — `{:score :verdict :reason}` shape.
   Different from coact's `:eval-display` (vector of code-block
   results); this is for agents that wrap each iteration in an
   external evaluator.  Renamed from `eval-result-line` after the
   coact-shape vs. eval-agent-shape ambiguity surfaced."
  [{:keys [score verdict reason]}]
  (let [score-s (when score (format "score=%.2f" (double score)))
        verdict-s (when verdict
                    (let [v (clojure.string/lower-case (str (some-> verdict name)))]
                      (case v
                        ("pass" "ok" "good")    (style v bold bright-green)
                        ("fail" "bad" "error")  (style v bold bright-red)
                        (style v bold bright-yellow))))
        reason-s (when (and reason
                            (not (clojure.string/blank? (str reason))))
                   (muted (str "— " (clamp (collapse-ws reason) 160))))]
    (str "  " (style "[eval]" bold bright-magenta) " "
         (str/join " " (filter some? [verdict-s score-s reason-s]))
         "\n")))

(defn agents-block
  "Render the daemon's `/agent list` reply (a vector of `{:session-id
   :agent-id :name}` maps) as a styled table.  Empty input renders a
   `(no agents registered)` marker."
  [agents]
  (cond
    (or (nil? agents) (empty? agents))
    (str (muted "  (no agents registered)") "\n")

    :else
    (let [head (str (style "Active Agents" bold bright-white) "  "
                    (muted (format "(%d)" (count agents)))
                    "\n")
          row  (fn [{:keys [session-id agent-id name]}]
                 (let [sid  (str (or session-id "?"))
                       sid-pad (apply str (repeat (max 1 (- 18 (count sid))) " "))
                       aid  (str (some-> agent-id clojure.core/name) (when-not agent-id "?"))
                       aid-pad (apply str (repeat (max 1 (- 22 (count aid))) " "))]
                   (str "  "
                        (style sid bold bright-cyan) sid-pad
                        (style aid cyan) aid-pad
                        (when name (muted (str "“" name "”"))))))]
      (str head (str/join "\n" (map row agents)) "\n"))))

(defn sessions-block
  "Render the daemon's `/session list` reply (a vector of session summaries
   from `agent-tui-persist/summarise-sessions`) as a styled table."
  [sessions]
  (cond
    (or (nil? sessions) (empty? sessions))
    (str (muted "  (no persisted sessions)") "\n")

    :else
    (let [head (str (style "Persisted Sessions" bold bright-white) "  "
                    (muted (format "(%d)" (count sessions)))
                    "\n")
          row  (fn [{:keys [session-id agent-id locked? size-bytes age-ms]}]
                 (let [sid  (str (or session-id "?"))
                       pad  (apply str (repeat (max 1 (- 22 (count sid))) " "))
                       lock (if locked?
                              (style "● live" bold bright-green)
                              (style "○ idle" dim))
                       sz   (when size-bytes
                              (cond
                                (< size-bytes 1024)            (str size-bytes "B")
                                (< size-bytes 1048576)         (format "%.1fK" (/ size-bytes 1024.0))
                                :else                          (format "%.1fM" (/ size-bytes 1048576.0))))
                       age  (when age-ms
                              (cond
                                (< age-ms 60000)               (format "%ds" (long (/ age-ms 1000)))
                                (< age-ms 3600000)             (format "%dm" (long (/ age-ms 60000)))
                                (< age-ms 86400000)            (format "%dh" (long (/ age-ms 3600000)))
                                :else                          (format "%dd" (long (/ age-ms 86400000)))))]
                   (str "  "
                        (style sid bold bright-cyan) pad
                        lock "  "
                        (when agent-id (style (str (clojure.core/name agent-id)) cyan))
                        (when sz (muted (str "  " sz)))
                        (when age (muted (str "  age=" age))))))]
      (str head (str/join "\n" (map row sessions)) "\n"))))

(defn tool-activity-line
  "One-line summary of a tool-call/tool-result pair: ✓/✗ + tool name +
   short args + truncated result. Currently no production caller — kept
   as a small reusable formatter for ad-hoc tool-activity lines.

   Input: `{:tool-name :tool-args :tool-result}` map.  When `:tool-result`
   is a map containing `:error`, the glyph turns red."
  [{:keys [tool-name tool-args tool-result]}]
  (let [name-s   (style (str (or (some-> tool-name clojure.core/name) "?"))
                        bold cyan)
        args-s   (let [s (short-args (or tool-args {}))]
                   (when (seq s) (muted (str "  " s))))
        err?     (and (map? tool-result) (:error tool-result))
        glyph    (if err?
                   (style "✗" bold bright-red)
                   (style "✓" bold bright-green))
        body     (cond
                   err?
                   (failure (str "  " (or (:error tool-result) "")))

                   (string? tool-result)
                   (let [s (clamp (collapse-ws tool-result) 80)]
                     (when (seq s)
                       (style (str "  → " s) bright-white)))

                   (some? tool-result)
                   (style "  → ok" dim))]
    (str "  " glyph "  " name-s args-s body "\n")))

(defn capture-result-line
  "Single-line confirmation for `/capture <path>`.  Either reports the
   number of bytes written + the absolute path, or surfaces the error."
  [{:keys [path bytes-written error]}]
  (cond
    error
    (str (failure (str "✗ /capture — " error)) "\n")

    :else
    (str (style "✓ captured" bold bright-green) "  "
         (style (str bytes-written) bold bright-white) " bytes → "
         (style (str path) bright-cyan) "\n")))

(defn allow-path-block
  "Render the current allow-list (when called without args) or a one-line
   confirmation that `path` was added (when `:added` is truthy).  Errors
   surface as a single ✗ line."
  [{:keys [added removed dirs error]}]
  (cond
    error
    (str (failure (str "✗ /allow-path — " error)) "\n")

    added
    (str (style "✓ allowed" bold bright-green) " "
         (style (str added) bright-cyan) "\n")

    removed
    (str (style "− removed" bold bright-yellow) " "
         (style (str removed) bright-cyan) "\n")

    :else
    (str (style "Allowed paths" bold bright-white) "  "
         (muted (format "(%d)" (count dirs)))
         "\n"
         (str/join "\n"
                   (for [d dirs] (str "  " (style "•" dim) " "
                                      (style (str d) bright-cyan))))
         "\n")))

(defn task-detail-block
  "Render the full state of a single task for `/task detail <id>`.
   Accepts the Task record / map produced by agent.task.protocol."
  [task]
  (cond
    (nil? task)
    (str (failure "✗ task not found") "\n")

    :else
    (let [{:keys [id name status job-type job-config
                  created-at started-at completed-at error]} task
          row (fn [k v]
                (when v
                  (str "  " (style (clojure.core/name k) bold cyan)
                       (apply str (repeat (max 1 (- 14 (count (clojure.core/name k)))) " "))
                       ": " (style (str v) bright-white) "\n")))
          fmt-when (fn [t] (when t (java.time.Instant/ofEpochMilli (long t))))]
      (str (style "Task" bold bright-white) " "
           (style (str id) dim) "\n"
           (apply str
                  (filter some?
                          [(row :name      name)
                           (row :status    status)
                           (row :job-type  job-type)
                           (row :created   (fmt-when created-at))
                           (row :started   (fmt-when started-at))
                           (row :completed (fmt-when completed-at))
                           (when (seq job-config)
                             (row :config (pr-str job-config)))
                           (when error
                             (str "  " (failure (str "error: " error)) "\n"))]))))))

(defn task-log-block
  "Render the captured stdout/stderr lines of a task.  `lines` is a vector
   of strings; `n` caps the number of trailing lines shown.

   `:cols` (optional) sets the wrap budget — long lines word-wrap with
   the `│ ` indent prefix repeated on each continuation row, so a
   single 500-byte log line displays as a clean N-row paragraph
   instead of overflowing the pane."
  [task-id lines & {:keys [n cols] :or {n 50}}]
  (cond
    (or (nil? lines) (empty? lines))
    (str (muted (format "  (no output for task %s)" task-id)) "\n")

    :else
    (let [recent (vec (take-last n lines))
          ;; 4 chars consumed by "  │ " prefix.
          inner-cols (when cols (max 20 (- cols 4)))
          render-line (fn [l]
                        (let [s (str l)
                              parts (if inner-cols
                                      (ansi-aware-word-wrap s inner-cols)
                                      [s])]
                          (str/join "\n"
                                    (map #(str "  " (muted "│ ")
                                               (style % bright-white))
                                         parts))))]
      (str (style "Task log" bold bright-white) " "
           (style (str task-id) dim) "  "
           (muted (format "(last %d of %d)" (count recent) (count lines)))
           "\n"
           (str/join "\n" (map render-line recent))
           "\n"))))

(defn usage-by-iteration-block
  "Aggregate the usage tracker's `:history` by `:iteration` and render a
   one-row-per-iteration table.  Each entry in `history` is a map produced
   by `clj-llm.usage/record-usage!` and patched by the BT dspy-action
   with `:iteration` and `:turn-id`.  Entries without `:iteration` are
   bucketed under `:?`."
  [history]
  (cond
    (or (nil? history) (empty? history))
    (str (muted "  (no usage recorded yet)") "\n")

    :else
    (let [grouped (->> history
                       (group-by (fn [u] (or (:iteration u) :?)))
                       (sort-by (fn [[k _]] (if (= :? k) -1 k))))
          head    (str (style "Usage by Iteration" bold bright-white) "\n"
                       (muted "  iter  calls    in       out      cached    cost") "\n")
          row     (fn [iter rows]
                    (let [calls   (count rows)
                          in-t    (reduce + 0 (map #(or (:input-tokens %) 0) rows))
                          out-t   (reduce + 0 (map #(or (:output-tokens %) 0) rows))
                          cached  (reduce + 0 (map #(or (get-in % [:cache :read-tokens])
                                                        (:cache-read-tokens %) 0) rows))
                          cost    (reduce + 0.0 (map #(or (get-in % [:cost :total-cost])
                                                          (:cost-usd %) 0.0) rows))
                          cell    (fn [w v] (let [s (str v)
                                                  pad (max 1 (- w (count s)))]
                                              (str s (apply str (repeat pad " ")))))]
                      (str "  "
                           (cell 5 (if (keyword? iter) (clojure.core/name iter) iter))
                           (cell 8 calls)
                           (cell 9 in-t)
                           (cell 9 out-t)
                           (cell 10 cached)
                           (style (format "$%.4f" cost) bright-white))))]
      (str head
           (str/join "\n" (for [[iter rows] grouped] (row iter rows)))
           "\n"))))

(defn clear-tasks-line
  "One-line confirmation for `/clear-tasks` — reports how many entries
   were purged from the task registry."
  [n]
  (cond
    (zero? (or n 0))
    (str (muted "  (no completed/failed tasks to clear)") "\n")

    :else
    (str (style "✓ purged" bold bright-green) "  "
         (style (str n) bright-white) " "
         (muted "task(s) from the registry") "\n")))

(defn messages-block
  "Render the last `n` conversation messages from the agent's session.
   Each message is `{:role :content [:tool-name]}` (per agent component
   notes — tool-results carry `:role \"tool\"` with `:content` already
   pr-str'd).  Long content is wrapped/truncated to keep the block
   visually scannable; nothing here is meant to faithfully reconstruct
   the wire payload — `/capture` is the right tool for that."
  [messages]
  (cond
    (or (nil? messages) (empty? messages))
    (str (muted "  (no messages yet)") "\n")

    :else
    (let [head (str (style "Messages" bold bright-white) "  "
                    (muted (format "(%d shown)" (count messages)))
                    "\n")
          role-color (fn [r]
                       (case (some-> r clojure.core/name)
                         "user"      bright-cyan
                         "assistant" bright-magenta
                         "tool"      bright-yellow
                         "system"    dim
                         bright-white))
          row (fn [{:keys [role content tool-name]}]
                (let [role-s (or (some-> role clojure.core/name) "?")
                      pad    (apply str (repeat (max 1 (- 11 (count role-s))) " "))
                      body   (clamp (collapse-ws content) 200)]
                  (str "  " (style role-s bold (role-color role)) pad
                       (when tool-name
                         (str (muted "[") (style (str tool-name) cyan)
                              (muted "] ")))
                       (style body bright-white))))]
      (str head (str/join "\n" (map row messages)) "\n"))))

(defn memory-recall-block
  "Render results from `memory.interface/contextual-recall`.  Each entry
   carries `:_layer` (`:l1`/`:l2`/`:l3`), `:_rrf_score`, optional
   `:tags` (a set of strings), plus layer-specific shape (episode/fact/
   etc.) — we print the layer, score, tag chips, and a single text
   snippet that exists across most shapes.

   When `tag-filter` is provided as the third arg, the header surfaces
   it so the user remembers which filter is in effect."
  ([query results] (memory-recall-block query results nil))
  ([query results tag-filter]
   (cond
     (or (nil? results) (empty? results))
     (str (muted (format "  (no memory hits for %s%s)"
                         (pr-str query)
                         (if (seq tag-filter)
                           (str " #" (clojure.string/join " #"
                                                          (sort tag-filter)))
                           "")))
          "\n")

     :else
     (let [head (str (style "Memory recall" bold bright-white) "  "
                     (muted (format "%s · %d hits%s"
                                    (pr-str query)
                                    (count results)
                                    (if (seq tag-filter)
                                      (str " · filter " (clojure.string/join ","
                                                                             (sort tag-filter)))
                                      "")))
                     "\n")
           layer-color (fn [l]
                         (case l
                           :l1 bright-cyan
                           :l2 bright-magenta
                           :l3 bright-yellow
                           dim))
           row (fn [{:keys [_layer _rrf_score content text fact summary tags]}]
                 (let [text-s (clamp (collapse-ws (or content text fact summary "?")) 140)
                       layer-s (or (some-> _layer clojure.core/name) "?")
                       tag-chips (when (seq tags)
                                   (str (str/join " "
                                                  (for [t (sort tags)]
                                                    (style (str "#" t)
                                                           bright-yellow)))
                                        " "))]
                   (str "  "
                        (style (str "[" layer-s "]") bold (layer-color _layer))
                        " "
                        (when _rrf_score
                          (muted (format "%.3f " _rrf_score)))
                        tag-chips
                        (style text-s bright-white))))]
       (str head (str/join "\n" (map row results)) "\n")))))

(defn context-block
  "Snapshot of the agent's current working context.  Input map shape:

     {:instruction       <string>           ; system / agent prompt
      :tool-count        <int>
      :message-count     <int>
      :iteration         <int>
      :max-iter          <int>
      :todo-progress     {:done :total}
      :last-reasoning    <string>}

   Rendered as a multi-row block; absent keys are omitted rather than
   shown as `?` so the block stays clean."
  [{:keys [instruction tool-count message-count iteration max-iter
           todo-progress last-reasoning]}]
  (let [row (fn [k v]
              (when v
                (str "  " (style (clojure.core/name k) bold cyan)
                     (apply str (repeat (max 1 (- 16 (count (clojure.core/name k)))) " "))
                     ": " (style (str v) bright-white) "\n")))
        truncate (fn [s n]
                   (let [s (-> (str s) (str/replace #"\s+" " ") str/trim)]
                     (if (> (count s) n) (str (subs s 0 (- n 1)) "…") s)))]
    (str (style "Context" bold bright-white) "\n"
         (apply str
                (filter some?
                        [(when (seq instruction)
                           (row :instruction (truncate instruction 200)))
                         (when tool-count
                           (row :tools (str tool-count " bound")))
                         (when message-count
                           (row :messages (str message-count " in conversation")))
                         (when iteration
                           (row :iteration
                                (str iteration
                                     (when max-iter (str " / " max-iter)))))
                         (when todo-progress
                           (row :todo
                                (str (or (:done todo-progress) 0) "/"
                                     (or (:total todo-progress) 0))))
                         (when (seq last-reasoning)
                           (row :last-reasoning
                                (truncate last-reasoning 200)))])))))

(defn memory-remember-line
  "One-line confirmation for `/memory remember`.  `entry` is the persisted
   entry (carries `:id` once the store has stamped one) or nil on error.
   When the entry carries `:tags`, render them as `#tag` chips after the
   fact-type tag."
  [{:keys [entry fact-type error]}]
  (cond
    error
    (str (failure (str "✗ /memory remember — " error)) "\n")

    (nil? entry)
    (str (failure "✗ /memory remember — write returned nil") "\n")

    :else
    (str (style "✓ remembered" bold bright-green) "  "
         (style (str (:id entry)) dim) "  "
         (when fact-type (str (muted "[")
                              (style (clojure.core/name fact-type) cyan)
                              (muted "] ")))
         (when (seq (:tags entry))
           (str (str/join " "
                          (for [t (sort (:tags entry))]
                            (style (str "#" t) bright-yellow)))
                "  "))
         (style (clamp (collapse-ws (:content entry)) 80) bright-white)
         "\n")))

(defn memory-stats-block
  "Render counts returned by `memory.interface/get-stats`.  Shape:
   `{:episodes n :semantic-facts n :schema-version <string>}`.  Extra
   keys are tolerated and rendered alphabetically."
  [stats]
  (cond
    (or (nil? stats) (empty? stats))
    (str (muted "  (memory not initialized)") "\n")

    :else
    (let [head (str (style "Memory" bold bright-white) "\n")
          row  (fn [k v]
                 (str "  " (style (clojure.core/name k) bold cyan)
                      (apply str (repeat (max 1 (- 18 (count (clojure.core/name k)))) " "))
                      ": " (style (str v) bright-white)))]
      (str head
           (str/join "\n" (for [[k v] (sort-by (comp clojure.core/name key) stats)]
                            (row k v)))
           "\n"))))

(defn lm-list-block
  "Render the LM catalog: provider → models.  Input shape:

     {:current   {:provider :anthropic :model \"sonnet\"}
      :providers [{:provider :openai     :env-var \"OPENAI_API_KEY\"
                   :env-set? true        :models [\"gpt-5\" ...]}
                  ...]}

   Providers without an env var (e.g. `:claude-code`) render with `n/a`."
  [{:keys [current providers]}]
  (cond
    (or (nil? providers) (empty? providers))
    (str (muted "  (no providers registered)") "\n")

    :else
    (let [{cur-prov :provider cur-model :model} (or current {})
          head (str (style "LM Providers" bold bright-white) "  "
                    (when current
                      (muted (format "(current: %s / %s)"
                                     (some-> cur-prov clojure.core/name)
                                     (or cur-model "?"))))
                    "\n")
          row  (fn [{:keys [provider env-var env-set? models]}]
                 (let [glyph (cond
                               (= provider cur-prov) (style "●" bold bright-green)
                               env-set?              (style "○" bold bright-cyan)
                               (nil? env-var)        (style "•" dim)
                               :else                 (style "○" dim))
                       prov-s (str (clojure.core/name provider))
                       pad    (apply str (repeat (max 1 (- 14 (count prov-s))) " "))
                       sample (when (seq models)
                                (let [head (take 3 models)
                                      more (- (count models) 3)]
                                  (str (clojure.string/join ", " head)
                                       (when (pos? more)
                                         (muted (format "  +%d more" more))))))]
                   (str "  " glyph "  "
                        (style prov-s bold bright-cyan) pad
                        (when env-var
                          (str (muted "env=") (style env-var dim) "  "))
                        (when (seq models)
                          (style sample bright-white)))))]
      (str head (str/join "\n" (map row providers)) "\n"))))

(defn lm-switch-line
  "Confirmation line for `/lm switch`.  Shows old → new transition."
  [{:keys [old new error]}]
  (cond
    error
    (str (failure (str "✗ /lm switch — " error)) "\n")

    :else
    (let [fmt (fn [{:keys [provider model]}]
                (str (style (str (some-> provider clojure.core/name) "/"
                                 (or model "?"))
                            bold bright-cyan)))]
      (str (style "✓ switched" bold bright-green) "  "
           (when old (str (muted (fmt old)) " "
                          (style "→ " bold bright-yellow)))
           (fmt new) "\n"))))

(defn lm-test-block
  "Render the result of a `/lm test` probe.  Input shape:

     {:provider :model :latency-ms <int> :answer <string> :error <string>}"
  [{:keys [provider model latency-ms answer error]}]
  (cond
    error
    (str (failure (str "✗ /lm test — " error)) "\n")

    :else
    (let [head (str (style "LM probe" bold bright-white) "  "
                    (style (str (some-> provider clojure.core/name) "/"
                                (or model "?"))
                           bold bright-cyan)
                    (when latency-ms
                      (str "  " (muted (format "(%dms)" latency-ms))))
                    "\n")
          body (when (seq (some-> answer str/trim))
                 (let [s (clamp (collapse-ws answer) 200)]
                   (str "  " (muted "→ ") (style s bright-white) "\n")))]
      (str head (or body "")))))

(defn skills-list-block
  "Render the list returned by `agent.common.skills/list-skills`.  Each
   entry has `:name`, `:type` (`:brainyard|:claude|:agents`), `:scope`
   (`:project|:user|nil`), `:description` (optional)."
  [skills]
  (cond
    (or (nil? skills) (empty? skills))
    (str (muted "  (no skills available)") "\n")

    :else
    (let [head (str (style "Skills" bold bright-white) "  "
                    (muted (format "(%d)" (count skills)))
                    "\n")
          type-color (fn [t]
                       (case t
                         :brainyard bright-cyan
                         :claude    bright-magenta
                         :agents    bright-yellow
                         dim))
          row (fn [{:keys [name type scope description]}]
                (let [n     (str (or name "?"))
                      pad   (apply str (repeat (max 1 (- 28 (count n))) " "))
                      tag   (str "[" (clojure.core/name (or type :?)) "]")
                      scope (when scope
                              (str (muted "(") (style (clojure.core/name scope) dim)
                                   (muted ") ")))]
                  (str "  "
                       (style tag bold (type-color type)) " "
                       (style n bold bright-cyan) pad
                       scope
                       (when description
                         (muted (clamp (collapse-ws description) 80))))))]
      (str head (str/join "\n" (map row skills)) "\n"))))

(defn skill-read-block
  "Render the result of `skills/read-skill`.  When `result` carries
   `:error`, render a one-line failure.  Otherwise show a header
   (name + type + scope + path + size) and the body content (truncated
   to `:max-bytes` to stop a 50KB README from monopolising the pane).

   Default `:max-bytes` is 4096 (~50 lines).

   `:cols` (optional) — when set, long body lines wrap with the
   `│ ` indent prefix repeated, so a 200-char paragraph displays as
   a clean N-row block instead of overflowing the pane."
  ([result] (skill-read-block result {}))
  ([{:keys [error name type scope path size description content]}
    {:keys [max-bytes cols] :or {max-bytes 4096}}]
   (cond
     error
     (str (failure (str "✗ /skills read — " error)) "\n")

     :else
     (let [head (str (style "Skill" bold bright-white) " "
                     (style (str (or name "?")) bold bright-cyan)
                     "  "
                     (when type
                       (str (style (str "[" (clojure.core/name type) "]")
                                   bold (case type
                                          :brainyard bright-cyan
                                          :claude    bright-magenta
                                          :agents    bright-yellow
                                          dim))
                            " "))
                     (when scope
                       (str (muted (str "(" (clojure.core/name scope) ")")) " "))
                     (when size
                       (muted (format "%dB" size)))
                     "\n"
                     (when path
                       (str "  " (muted "path: ") (style (str path) dim) "\n"))
                     (when description
                       (str "  " (muted "desc: ")
                            (style (str description) bright-white) "\n"))
                     "\n")
           ;; 4 chars consumed by "  │ " prefix.
           inner-cols (when cols (max 20 (- cols 4)))
           render-line (fn [l]
                         (let [parts (if inner-cols
                                       (ansi-aware-word-wrap l inner-cols)
                                       [l])]
                           (str/join "\n"
                                     (map #(str "  " (muted "│ ")
                                                (style % bright-white))
                                          parts))))
           body (let [s (str (or content ""))
                      truncated? (> (count s) max-bytes)
                      shown (if truncated? (subs s 0 max-bytes) s)
                      lines (str/split-lines shown)]
                  (str (str/join "\n" (map render-line lines))
                       (when truncated?
                         (str "\n  " (muted (format "… (%dB more — use /capture for full body)"
                                                    (- (count s) max-bytes)))))
                       "\n"))]
       (str head body)))))

(defn tools-list-block
  "Render the unified `!tool-defs` registry.  Input is a map of
   `id → {:id :type :meta {:doc :description ...}}` (the same shape
   `agent.core.tool/get-tool-defs` returns).

   Optional `:type-filter` keeps only entries of the given type."
  ([tool-defs] (tools-list-block tool-defs {}))
  ([tool-defs {:keys [type-filter] :or {type-filter nil}}]
   (let [entries (cond->> (vals (or tool-defs {}))
                   type-filter (filter #(= type-filter (:type %))))
         entries (sort-by (juxt (comp str :type) (comp str :id)) entries)]
     (cond
       (empty? entries)
       (str (muted (if type-filter
                     (format "  (no %s tools registered)"
                             (clojure.core/name type-filter))
                     "  (no tools registered)"))
            "\n")

       :else
       (let [type-color (fn [t]
                          (case t
                            :command bright-cyan
                            :skill   bright-magenta
                            :agent   bright-yellow
                            :tool    bright-green
                            dim))
             head (str (style "Tools" bold bright-white) "  "
                       (muted (format "(%d)" (count entries)))
                       "\n")
             row (fn [{:keys [id type meta]}]
                   (let [id-s (str id)
                         pad  (apply str (repeat (max 1 (- 32 (count id-s))) " "))
                         tag  (str "[" (clojure.core/name (or type :?)) "]")
                         doc  (clamp (collapse-ws (or (:doc meta) (:description meta) ""))
                                     80)]
                     (str "  "
                          (style tag bold (type-color type)) " "
                          (style id-s bold bright-cyan) pad
                          (when (seq doc) (muted doc)))))]
         (str head (str/join "\n" (map row entries)) "\n"))))))

(defn activity-collapse-line
  "Replacement marker emitted to the stream pane when the dedicated
   activity pane is open and should own the live blocks (per docs §8.3).
   The same content still flows in full to the activity FIFO."
  [kind]
  (str (style "▸ " bold cyan)
       (style (str (or (some-> kind clojure.core/name) "?")) bold cyan)
       "  "
       (muted "(see /activity)")
       "\n"))

(defn activity-banner
  "Header banner written to the activity FIFO when `/activity show` runs.
   One short line telling the user what's coming + how to dismiss."
  []
  (let [bar (apply str (repeat 40 h-line))]
    (str (style bar bright-magenta) "\n"
         "  " (style "Agent Activity" bold bright-white)
         "  " (muted "tools · todos · tasks · sub-agents")
         "  " (muted "  /activity hide to close")
         "\n"
         (style bar bright-magenta) "\n\n")))

(defn- activity-timestamp
  "Short HH:MM:SS prefix for an activity-pane line, in dim style.
   Returns a styled string ending in a single space — concatenate
   directly in front of the rendered event line."
  []
  (let [fmt (java.text.SimpleDateFormat. "HH:mm:ss")
        now (.format fmt (java.util.Date.))]
    (str (muted now) " ")))

(defn with-activity-timestamp
  "Prepend the current `HH:MM:SS` to each non-blank line of `block`,
   so the activity pane carries a time column for free.  Blank lines
   stay blank to preserve visual grouping."
  [^String block]
  (when block
    (let [ts (activity-timestamp)]
      (->> (clojure.string/split-lines block)
           (map (fn [line] (if (clojure.string/blank? line)
                             line
                             (str ts line))))
           (clojure.string/join "\n")
           ;; split-lines drops a trailing empty string that came
           ;; from a final "\n"; restore it so block boundaries
           ;; stay intact in the FIFO stream.
           (#(if (.endsWith block "\n") (str % "\n") %))))))

;; -- Activity-pane lifecycle lines -------------------------------------------
;; These are compact one-liners for the per-session activity log so the
;; pane reads as a journal of what the agent + user did, in addition to
;; the per-tool lines that already land there via emit-split!.

(defn activity-ask-pre-line
  "Activity-pane line: 'ask: <truncated input>'."
  [input]
  (let [snippet (clamp (collapse-ws (str input)) 80)]
    (str (style "✦" bold bright-cyan)
         " "
         (style "ask" bold bright-cyan)
         (when (seq snippet) (str ": " (style snippet dim))))))

(defn activity-ask-post-line
  "Activity-pane line: '✓ answer (N iter)' or '✗ answer error'."
  [{:keys [answer error iteration-count]}]
  (cond
    error
    (str (failure (str "✗ answer  " (clamp (collapse-ws (str error)) 80))))

    :else
    (let [snippet (clamp (collapse-ws (str answer)) 80)]
      (str (style "✓" bold bright-green)
           " "
           (style "answer" bold bright-green)
           (when iteration-count
             (str (muted (format "  (%d iter)" iteration-count))))
           (when (seq snippet) (str " " (muted snippet)))))))

(defn- agent-type-and-suffix
  "Pull the defagent-type and instance suffix from a possibly-namespaced
   agent-id keyword. Returns `[type suffix]` strings; either may be nil
   when the input is sparse."
  [agent-id]
  (when agent-id
    (cond
      (and (keyword? agent-id) (namespace agent-id))
      [(namespace agent-id) (clojure.core/name agent-id)]

      (keyword? agent-id)
      [(clojure.core/name agent-id) nil]

      :else
      [(str agent-id) nil])))

(defn activity-agent-created-line
  "Activity-pane line: '+ agent <type> <suffix>  <session-id>'.

   `:agent-id` is the runtime instance-id (a possibly-namespaced
   keyword); we split it into type + suffix for readability so the
   journal reads like `+ agent react-agent teal-raccoon-998`."
  [{:keys [agent-id session-id]}]
  (let [[t suff] (agent-type-and-suffix agent-id)]
    (str (style "+" bold bright-yellow)
         " "
         (style "agent" bold bright-yellow)
         " "
         (style (or t "?") cyan)
         (when suff (str " " (muted suff)))
         (when (and session-id (not= session-id suff))
           (str "  " (muted session-id))))))

(defn activity-agent-closed-line
  "Activity-pane line: '- agent <type> <suffix>  <session-id>'."
  [{:keys [agent-id session-id]}]
  (let [[t suff] (agent-type-and-suffix agent-id)]
    (str (style "-" bold bright-red)
         " "
         (style "agent" bold bright-red)
         " "
         (style (or t "?") cyan)
         (when suff (str " " (muted suff)))
         (when (and session-id (not= session-id suff))
           (str "  " (muted session-id))))))

(defn activity-session-event-line
  "Activity-pane line for /session new / /session attach /
   /session delete events.  `verb` is one of \"new\", \"attached\",
   \"deleted\", \"renamed\".  `subject` is the session-id (or
   '<old> → <new>' for rename)."
  [verb subject & {:keys [detail]}]
  (str (style "◆" bold bright-magenta)
       " "
       (style "session" bold bright-magenta)
       " "
       (style verb cyan)
       " "
       (style (str subject) bright-white)
       (when detail (str " " (muted detail)))))

(defn lm-models-block
  "Paginated catalog for `/lm models <provider> [page]`.  Renders one
   page of `models` (a vector of strings) with a header showing
   provider, page index, and the next-page hint when there's more.

   Shape: `{:provider :openai :page 2 :per-page 20 :total 47 :models [...]}`."
  [{:keys [provider page per-page total models]}]
  (cond
    (or (nil? models) (empty? models))
    (str (muted (format "  (no models known for provider %s)"
                        (some-> provider clojure.core/name)))
         "\n")

    :else
    (let [page    (or page 1)
          per-page (or per-page 20)
          start   (inc (* (dec page) per-page))
          end     (min total (+ start (dec (count models))))
          head    (str (style "Models" bold bright-white) "  "
                       (style (str (some-> provider clojure.core/name))
                              bold bright-cyan)
                       "  "
                       (muted (format "(%d–%d of %d)" start end total))
                       "\n")
          rows    (for [[i m] (map-indexed vector models)]
                    (str "  "
                         (style (format "%3d." (+ start i)) dim)
                         " "
                         (style (str m) bright-white)))
          more?   (< end total)
          footer  (when more?
                    (str "\n  "
                         (muted (format "/lm models %s %d  →  next page"
                                        (some-> provider clojure.core/name)
                                        (inc page)))))]
      (str head (str/join "\n" rows) (or footer "") "\n"))))

(defn memory-forget-line
  "One-line confirmation for `/memory forget <id>`.  `existed?` reflects
   the truthy return of `forget-entry`."
  [{:keys [id existed? error]}]
  (cond
    error
    (str (failure (str "✗ /memory forget — " error)) "\n")

    (not existed?)
    (str (failure (str "✗ /memory forget — no entry with id " (pr-str id))) "\n")

    :else
    (str (style "✓ forgotten" bold bright-yellow) "  "
         (style (str id) dim) "\n")))

(defn memory-list-block
  "Read-only browse of L3 entries (`/memory list`).  Same row format as
   `memory-recall-block` but no `:_rrf_score` and no header query."
  [entries & {:keys [tag-filter]}]
  (cond
    (or (nil? entries) (empty? entries))
    (str (muted (format "  (no entries%s)"
                        (if (seq tag-filter)
                          (str " for #" (clojure.string/join " #"
                                                             (sort tag-filter)))
                          "")))
         "\n")

    :else
    (let [head (str (style "Memory" bold bright-white) "  "
                    (muted (format "%d entries%s"
                                   (count entries)
                                   (if (seq tag-filter)
                                     (str " · filter " (clojure.string/join ","
                                                                            (sort tag-filter)))
                                     "")))
                    "\n")
          row (fn [{:keys [id content text fact summary tags layer]}]
                (let [text-s (clamp (collapse-ws (or content text fact summary "?")) 120)
                      tag-chips (when (seq tags)
                                  (str (str/join " "
                                                 (for [t (sort tags)]
                                                   (style (str "#" t) bright-yellow)))
                                       " "))]
                  (str "  "
                       (style (str (or id "?")) dim)
                       (when layer
                         (str "  " (muted (str "[" (clojure.core/name layer) "]"))))
                       "\n      "
                       tag-chips
                       (style text-s bright-white))))]
      (str head (str/join "\n" (map row entries)) "\n"))))

(defn session-destroy-line
  "One-line confirmation for `/session destroy <id>` (alias `/session
   prune <id>`).  Reports either a successful delete or a not-found
   error.  Pass `:label` to override the leading command name shown
   in the error line (defaults to `/session destroy`) — used by
   sibling sub-commands (e.g. `/session prune`) that reuse this
   renderer for their error path."
  [{:keys [id deleted? error label]}]
  (let [lbl (or label "/session destroy")]
    (cond
      error
      (str (failure (str "✗ " lbl " — " error)) "\n")

      (not deleted?)
      (str (failure (str "✗ " lbl " — session not found: " id)) "\n")

      :else
      (str (style "✓ destroyed" bold bright-yellow) "  "
           (style (str id) dim) "\n"))))

(defn slash-error-line
  "Generic one-liner for slash sub-command errors that don't have
   their own renderer (e.g. `/agent new` with bad args). Mirrors the
   visual style of `session-destroy-line`'s error path."
  [label message]
  (str (failure (str "✗ " label " — " message)) "\n"))

(defn slash-ok-line
  "Generic one-liner for slash sub-command successes. Used by
   `/agent new`, `/agent rename`, `/agent close` so the user gets
   feedback in the stream pane instead of a silent dispatch."
  [label message]
  (str (style "✓" bold bright-green) " "
       (style label bold bright-cyan) " — "
       message "\n"))

(defn mcp-server-info-block
  "Render details for a single MCP server (`/mcp <server>` or `/mcp
   <server> info`).  Input shape:

     {:name        \"github\"
      :enabled     true
      :connected   true
      :transport   :stdio
      :tool-count  17
      :info        <server-info map from MCP, may be nil>
      :error       <string, optional>}"
  [{:keys [name enabled connected transport tool-count info error]}]
  (cond
    error
    (str (failure (str "✗ /mcp " name " — " error)) "\n")

    (nil? name)
    (str (failure "✗ /mcp — server name required") "\n")

    :else
    (let [glyph (cond
                  connected           (style "●" bold bright-green)
                  (false? enabled)    (style "○" dim)
                  :else               (style "○" bold bright-yellow))
          row   (fn [k v]
                  (when v
                    (str "  " (style (clojure.core/name k) bold cyan)
                         (apply str (repeat (max 1 (- 14 (count (clojure.core/name k)))) " "))
                         ": " (style (str v) bright-white) "\n")))]
      (str glyph " "
           (style "MCP Server" bold bright-white) "  "
           (style (str name) bold bright-cyan) "\n"
           (apply str
                  (filter some?
                          [(row :enabled    (if (false? enabled) "no" "yes"))
                           (row :connected  (if connected "yes" "no"))
                           (row :transport  (some-> transport clojure.core/name))
                           (row :tools      tool-count)
                           (when info
                             (row :info (clamp (collapse-ws (pr-str info)) 80)))]))))))

(defn mcp-tools-block
  "Render the tools advertised by one MCP server (`/mcp <server> tools`).
   `tools` is a vector of tool maps; we display name + (truncated)
   description."
  [server-name tools]
  (cond
    (or (nil? tools) (empty? tools))
    (str (muted (format "  (no tools advertised by MCP server %s)"
                        (pr-str server-name)))
         "\n")

    :else
    (let [head (str (style "MCP Tools" bold bright-white) "  "
                    (style (str server-name) bold bright-cyan) "  "
                    (muted (format "(%d)" (count tools)))
                    "\n")
          row (fn [t]
                (let [n    (or (:name t) (:tool-name t) "?")
                      desc (clamp (collapse-ws (or (:description t) (:doc t) "")) 70)
                      pad  (apply str (repeat (max 1 (- 28 (count (str n)))) " "))]
                  (str "  " (style (str n) bold bright-cyan) pad
                       (when (seq desc) (muted desc)))))]
      (str head (str/join "\n" (map row tools)) "\n"))))

(defn mcp-action-line
  "One-line confirmation for `/mcp <server> start|stop|restart|health`.
   `result` is the integration fn's return; we just acknowledge."
  [{:keys [server action result error]}]
  (cond
    error
    (str (failure (str "✗ /mcp " server " " action " — " error)) "\n")

    :else
    (str (style (str "✓ " action) bold bright-green) "  "
         (style (str server) bold bright-cyan)
         (when result
           (str "  " (muted (clamp (collapse-ws (pr-str result)) 80))))
         "\n")))

(defn cancelled-line
  "Confirmation that a cancel signal was emitted."
  []
  (str (style "⊘ cancel sent" bold bright-yellow) "\n"))

(defn paused-line
  "Confirmation that a pause request was emitted; lands at the next BT
   checkpoint (between iterations / before next condition/action)."
  []
  (str (style "⏸ paused" bold bright-yellow) "\n"))

(defn resumed-line
  "Confirmation that a paused agent was released."
  []
  (str (style "▶ resumed" bold bright-green) "\n"))

(defn compact-result-line
  "Single-line result for `/compact`."
  [{:keys [already-compact before-tokens after-tokens compacted-keys]}]
  (cond
    already-compact
    (str (muted (str "  context already within target ("
                     before-tokens " tokens)")) "\n")

    :else
    (str (style "compacted" bold bright-green) " : "
         before-tokens " → " after-tokens " est. tokens"
         (when (seq compacted-keys)
           (str " (" (clojure.string/join ", " (map name compacted-keys)) ")"))
         "\n")))

(defn config-line
  "Single-line confirmation for config toggles like `:thinking on`.
   Keyword values render by name (so `:verbose` → `verbose`, not
   `:verbose`); other values fall through `str`."
  [k v]
  (str (style "set" bold bright-yellow) " "
       (style (str k) bold cyan) " = "
       (style (cond
                (keyword? v) (name v)
                :else        (str v))
              bright-white)
       "\n"))

(defn unknown-command-line
  "One-line error returned when a slash command is not recognised."
  [cmd]
  (str (failure (str "✗ Unknown command: " cmd)) "  "
       (muted "Type /help for the slash surface.")
       "\n"))

(defn clear-screen-bytes
  "Bytes that visually clear the stream pane.  Tmux interprets these in the
   pane's local coordinate space."
  []
  (str esc "2J" esc "H"))

(defn welcome-banner
  "Multi-line greeting written once when the daemon attaches to a fresh
   stream pane.  Should fit in ~6 rows.

   `:cols` (optional) — bar width.  Defaults to 60 so legacy callers
   are unaffected; pass the live pane width so the bars stretch
   edge-to-edge."
  [{:keys [agent-id model session-id cols]}]
  (let [bar (apply str (repeat (or cols 60) h-line))]
    (str (style bar bright-cyan) "\n"
         "  " (style "Brainyard Agent TUI" bold bright-white)
         "  " (muted "(tmux mode)") "\n"
         "  agent      : " (style (name agent-id) bold cyan)
         (when model
           (str "    model      : "
                (style model bold bright-magenta))) "\n"
         "  session-id : " (style session-id dim) "\n"
         (style bar bright-cyan) "\n"
         (muted "  Type a message to begin.  /help for commands. Ctrl-C cancels, Ctrl-D detaches.")
         "\n\n")))

;; -- status pane --------------------------------------------------------------

(defn- status-content
  "Single-line status content matching the doc §8.1 layout:

     ● <state>  │  <agent-id>  │  <model>  │  iter N/M  │  ▸ <tool>  │  tools K  │  todo K/T  │  q D

   The `▸ <tool>` segment shows the most recently dispatched tool name
   while the agent is `:running`; it disappears once the agent goes idle.

   When the snapshot carries `:cols`, segments are joined left→right
   and any that would push the line past `:cols` are dropped from the
   right.  The leading `● state` + agent-id are always kept; on
   extremely narrow panes (< 20 cols) only those two survive.  Without
   `:cols`, all segments are joined (legacy behavior — long lines
   wrap)."
  [{:keys [state agent-id model iter max-iter tools-used queue-depth
           todo-progress last-tool cols tick]
    :as _snapshot}]
  (let [state-color (case state
                      (:thinking :running) bright-green
                      :idle                bright-yellow
                      (:cancelled :stopped) bright-red
                      white)
        ;; Active states show a braille spinner that cycles each repaint —
        ;; same 10-frame palette the legacy TUI uses (session.clj's
        ;; `think-spinner-frames`).  Idle/cancelled/stopped keep the static
        ;; `●` so a quiet pane reads as quiet.  `:tick` is normally derived
        ;; from wall-clock time at the call site (so consecutive paints
        ;; advance a frame); tests can inject a fixed tick.
        tick        (or tick (long (/ (System/currentTimeMillis) 300)))
        braille     ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"]
        dot         (if (#{:thinking :running} state)
                      (style (nth braille (mod tick 10)) state-color)
                      (style "●" state-color))
        sep         (style "│" dim)
        sep-w       (str " " sep " ")
        state-name  (style (str (name (or state :idle))) state-color)
        seg-agent   (style (str (name (or agent-id "?"))) bold cyan)
        seg-model   (when model
                      (style (str model) bright-magenta))
        seg-iter    (when iter
                      (str "iter " (style (str iter) bold)
                           (when max-iter (str "/" max-iter))))
        seg-tool    (when (and last-tool
                               (#{:running} state))
                      (str (style "▸ " bold cyan)
                           (style (str (cond-> last-tool
                                         (keyword? last-tool) name))
                                  bold cyan)))
        seg-tools   (when tools-used
                      (str "tools " (style (str tools-used) bold)))
        seg-todo    (when (and todo-progress
                               (:total todo-progress)
                               (pos? (:total todo-progress)))
                      (str "todo "
                           (style (str (:done todo-progress) "/"
                                       (:total todo-progress))
                                  bold bright-yellow)))
        seg-q       (when (and queue-depth (pos? queue-depth))
                      (str "q " (style (str queue-depth) bold bright-yellow)))
        ;; Priority-ordered: head segments are always kept; tail
        ;; segments get dropped right-to-left when the line overflows.
        head        [(str dot " " state-name) seg-agent]
        tail        (filter some? [seg-model seg-iter seg-tool
                                   seg-tools seg-todo seg-q])
        join-w      (fn [parts]
                      (str/join sep-w (filter some? parts)))]
    (cond
      ;; No budget — keep legacy "join everything" behavior.
      (nil? cols)
      (join-w (concat head tail))

      :else
      ;; Drop tail segments right-to-left until the joined line fits.
      (loop [keep-tail tail]
        (let [candidate (join-w (concat head keep-tail))]
          (cond
            (<= (display-width candidate) cols)  candidate
            (empty? keep-tail)                   (join-w head)
            :else                                (recur (butlast keep-tail))))))))

(defn status-line
  "Backward-compatible single-row status writer.  Overwrites the current
   row using CR + erase-line.  Prefer `status-block` for the 2-row pane
   layout introduced in docs §8.1."
  [snapshot]
  (str "\r" erase-line " " (status-content snapshot)))

(def ^:private default-status-pane-cols
  "Fallback width used when the live pane width isn't known (e.g. very
   first repaint before the status writer queries tmux).  80 is a safe
   minimum — wider panes get a flush right-edge once the live width is
   plumbed in by the status writer; narrower panes will wrap until the
   first resize tick."
  80)

(defn- chrome-row
  "Render the lower status row: layout mode on the left, version on the
   right, padded with spaces so the version sits flush at column `cols`.
   Both segments are dim so they don't compete with the live state row
   above them.

   `cols` is the pane's live visible width — passed in by the status
   writer after it queries `tmux display-message #{pane_width}`.  When
   absent (very first repaint, or when no status sink is available),
   falls back to `default-status-pane-cols`."
  [{:keys [mode version cols]} _snapshot]
  (let [;; `:or` only kicks in when the key is ABSENT — explicit `nil`
        ;; (snapshot has the key but it's nil before the first resize)
        ;; bypasses it, so guard manually.
        cols (or cols default-status-pane-cols)
        left-text (str "mode " (or (some-> mode name) "repl"))
        right-text (str "v" (or version "?"))
        ;; Pad the gap so right-text ends at column `cols`.  Account for
        ;; the leading space and a 1-cell margin.
        gap (max 1 (- cols 1 (count left-text) (count right-text) 1))]
    (str " "
         (style left-text dim)
         (apply str (repeat gap " "))
         (style right-text dim))))

(defn status-block
  "Two-row status pane render (per docs §8.1):

     row 1 — single-line status content (state, agent, model, iter, …)
     row 2 — mode (left) + version (right), both dim chrome

   Uses cursor positioning so each repaint exactly overwrites both rows
   in place rather than appending — important because the status pane
   is fed via a FIFO that an outer reader process keeps open and tmux
   would otherwise scroll the pane on each newline.

   `snapshot` may carry `:mode` (e.g. `:repl|:three-pane`) and `:version`
   (a string).  Missing fields render as `mode repl` / `v?`."
  [snapshot]
  (let [content (status-content snapshot)
        chrome  (chrome-row {:mode (:mode snapshot)
                             :version (:version snapshot)
                             ;; Pass through the live `:cols` if the
                             ;; status writer captured it from tmux;
                             ;; chrome-row falls back to the safe
                             ;; default when nil.
                             :cols (:cols snapshot)}
                            snapshot)
        home (str esc "H")]
    (str home erase-line " " content
         "\r\n" erase-line chrome)))

(defn empty-status-line
  "Erase the entire status pane (both rows) on clean shutdown."
  []
  (str (str esc "H") erase-line "\r\n" erase-line))
