;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.permissions
  "Unified TUI user-feedback mechanism + the file-access permission adapter
   that rides on top of it.

   `make-feedback-fn` is the single interactive-input primitive the TUI binds
   to a session as `:user-feedback-fn`. It dispatches on the request `:kind`:
     :select  — pick one of 2-6 options (the historical behavior; the default
                when :kind is absent, so existing callers are unchanged)
     :text    — free-form line of text
     :confirm — yes/no(/always …) from a set of single-key :choices
   Each kind renders through whichever backend is available — raw in-stream
   live-block, non-raw stdin, or (optionally, when feasible) a tmux popup. The
   tmux popup is just one optional backend; nothing is popup-only.

   `make-permission-fn` is a thin adapter: file-access permission is a :confirm
   request, so it keeps only the path normalization + per-session approved-dir
   cache and delegates all prompting to the feedback primitive."
  (:require [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.popup :as popup]
            [ai.brainyard.agent-tui.tmux-side :as tmux-side]
            [ai.brainyard.agent-tui-tmux.interface :as tmux-iface]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.io BufferedReader InputStreamReader]
           [java.util.concurrent CountDownLatch TimeUnit]
           [java.util.concurrent.locks ReentrantLock]))

(def user-feedback-block-id
  "Stable id for the sticky-bottom live-block that renders the
   permission/feedback prompt below all other live blocks. It stays up for the
   whole prompt — including after a `:free-input` option is picked and the
   answer is being typed, so the question the user is answering is still on
   screen — and is disposed once the promise is delivered."
  :user-feedback)

(defonce ^{:private true
           :doc "The open feedback block's `(fn [cols] -> lines)`, or nil.

  Kept so the block can be re-rendered against a CHANGED prompt state without
  the caller having to hold the question and options. Only one feedback prompt
  is ever open at a time (they take `feedback-lock`), so one slot is enough."}
  !feedback-block-render
  (atom nil))

(defn- show-user-feedback-block!
  "Render the sticky-bottom user-feedback live-block from `render-fn`, a
   `(fn [cols] -> lines)`.
   In fullscreen TUI, the block is anchored below iteration/think/todo
   blocks; in non-fullscreen mode, falls back to a plain emit.

   Takes a thunk rather than finished lines so the prompt re-wraps if the
   terminal is resized while it is open. That matters more here than anywhere
   else on screen: a permission question names a path or a command, and rows
   too wide for the pane are clipped at paint time — which would ask the user
   to approve something they cannot fully read."
  [render-fn]
  (reset! !feedback-block-render render-fn)
  (if (layout/fullscreen?)
    (layout/update-live-block! user-feedback-block-id (vec (render-fn nil))
                               {:sticky-bottom? true
                                :render (fn [c] (vec (render-fn c)))})
    (tui-session/emit! (str "\n" (str/join "\n" (render-fn nil))))))

(defn refresh-user-feedback-block!
  "Re-render the open feedback block. The thunk reads the pending state, so
   this is how a prompt whose SUB-state changed — a :select whose free-input
   option was just picked, which is no longer asking for an option number —
   stops telling the user to do something that no longer works. No-op when no
   block is open or outside fullscreen. Public because the transition is
   detected in `input`, which does not own the question text."
  []
  (when-let [render-fn @!feedback-block-render]
    (when (layout/fullscreen?)
      (layout/update-live-block! user-feedback-block-id (vec (render-fn nil))
                                 {:render (fn [c] (vec (render-fn c)))}))))

(defn- wrap-prompt-text
  "Word-wrap one prompt row to the pane, indenting continuations by `indent`.
   `nil` cols means \"ask the layout\" — the tick path passes nothing, the
   reflow passes the width it is rendering for."
  ([s cols] (wrap-prompt-text s cols ""))
  ([s cols indent]
   (let [w (max 20 (- (or cols (:cols @layout/!layout) 80)
                      2 (count indent)))]
     (map-indexed (fn [i r] (str indent (when (pos? i) "  ") r))
                  (fmt/ansi-aware-word-wrap s w)))))

(defn- hide-user-feedback-block!
  "Dispose the user-feedback live-block (removes lines from scrollback).
   No-op in non-fullscreen mode."
  []
  (reset! !feedback-block-render nil)
  (when (layout/fullscreen?)
    (layout/dispose-live-block! user-feedback-block-id)))

(defn- refresh-feedback-prompt!
  "Repaint the input prompt to reflect (`fb-kind`) or clear (nil) answer-mode.
   The editor is parked in read-key! while a prompt is open, so it does not
   repaint the prompt itself on open/close — do-select/confirm/text call this so
   the yellow '? ' indicator appears immediately, not only once the user types.
   Safe because the input buffer is empty while a prompt is open (the user has
   already submitted their turn). No-op outside fullscreen."
  [fb-kind]
  (when (layout/fullscreen?)
    (let [{:keys [prompt placeholder]} (tui-session/feedback-prompt-parts fb-kind)]
      (layout/draw-input-prompt! (str prompt (ansi/muted placeholder))))))

(defn- mode-b-popup-feasible?
  "True when we should route a permission/feedback dialog through a tmux
   popup rather than the in-stream codepath.  Requires the `enable-tmux-popup`
   config toggle (default true), Mode B with the side-channel installed, AND a
   popup-capable tmux server with a tall enough client (§11.4 — fall back to
   in-stream on small terminals or when the toggle is off)."
  []
  (and (= :B (:mode @tui-session/!tui-state))
       (agent/get-config (tui-session/get-active-agent) :enable-tmux-popup)
       (tmux-side/installed?)
       (popup/feasible? (:tmux (tmux-side/state)))))

;; ============================================================================
;; Prompt formatting
;; ============================================================================

(defn format-feedback-lines
  "Build the :select prompt as a vector of ANSI-styled lines.
   Options with :free-input true show a '(free input)' hint.

   The 3-arity wraps to `cols`; the 2-arity asks the layout. Both the question
   and the option rows are wrapped — an option's `:description` is free text and
   a question routinely carries a path, so neither fits a narrow pane.

   `picked-idx` (4-arity) is the free-input option the user already chose: the
   question and the options stay on screen as context, that option is marked,
   and the trailer stops saying 'Select [1-N]' — by then a digit is text being
   typed into the answer, not an option number."
  ([question options] (format-feedback-lines question options nil))
  ([question options cols] (format-feedback-lines question options cols nil))
  ([question options cols picked-idx]
   (let [hint (if picked-idx
                "Type your response below, Enter to submit"
                (str "Select [1-" (count options) "]: "))]
     (-> (vec (wrap-prompt-text (ansi/style question ansi/bold ansi/bright-cyan) cols))
         (into (mapcat
                (fn [i {:keys [label description free-input]}]
                  (wrap-prompt-text
                   (str (ansi/style (str "[" (inc i) "]") ansi/bold) " " label
                        (when description (str " — " (ansi/muted description)))
                        (when (and free-input (not picked-idx))
                          (str " " (ansi/muted "(free input)")))
                        (when (= i picked-idx) (str " " (ansi/success "✓ selected"))))
                   cols "  "))
                (range) options))
         (into (wrap-prompt-text (ansi/muted hint) cols "  "))))))

(defn format-feedback-prompt
  "Format the :select prompt as a single string (joined with newlines).
   Retained for the non-raw stdin-reader fallback path."
  [question options]
  (str "\n" (str/join "\n" (format-feedback-lines question options))))

(defn format-confirm-lines
  "Build the :confirm prompt as ANSI lines: the question followed by a hint
   derived from `choices` (each `{:key char :label …}`). When the key is the
   label's first letter (yes/no/always) it renders inline — `[y]es`; otherwise
   the key is shown separately — `[d] never`.

   The 3-arity wraps the question to `cols`; the 2-arity asks the layout."
  ([question choices] (format-confirm-lines question choices nil))
  ([question choices cols]
   (let [hint (->> choices
                   (map (fn [{:keys [key label]}]
                          (let [l (str label)]
                            (if (and (pos? (count l))
                                     (= (Character/toLowerCase ^char (first l))
                                        (Character/toLowerCase ^char key)))
                              (str "[" key "]" (subs l 1))
                              (str "[" key "] " l)))))
                   (str/join " / "))]
     (into (vec (wrap-prompt-text (ansi/warning question) cols))
           (wrap-prompt-text (ansi/muted (str hint ": ")) cols "  ")))))

(defn format-text-lines
  "Build the :text prompt as a vector of ANSI-styled lines for the sticky
   user-feedback block. The answer is typed into the main input line (whose
   prompt flips to an answer-mode indicator), so this is just the question.

   The 2-arity wraps to `cols`; the 1-arity asks the layout."
  ([question] (format-text-lines question nil))
  ([question cols]
   (into (vec (wrap-prompt-text (ansi/style question ansi/bold ansi/bright-cyan) cols))
         (wrap-prompt-text (ansi/muted "Type your answer below, Enter to submit")
                           cols "  "))))

;; ============================================================================
;; Non-raw stdin reader (one temporary thread per prompt)
;; ============================================================================

(defn start-feedback-stdin-reader!
  "Start a temporary thread to read feedback input from stdin (non-raw mode).
   Branches on the pending feedback `:kind`:
     :select  — read a number 1-N (a :free-input option reads a follow-up line)
     :text    — read one line → {:input line :index 0}
     :confirm — read one line, match its first char against the :choices keys."
  [^CountDownLatch done-latch]
  (let [reader (BufferedReader. (InputStreamReader. System/in))
        t (Thread.
           (fn []
             (try
               (loop []
                 (when-let [{:keys [promise kind options choices]} @tui-session/!pending-feedback]
                   (when-not (realized? promise)
                     (case (or kind :select)
                       :text
                       (when-let [line (.readLine reader)]
                         (deliver promise {:input (str/trim line) :index 0}))

                       :confirm
                       (when-let [line (.readLine reader)]
                         (let [c  (str/trim line)
                               ch (when (seq c) (Character/toLowerCase (.charAt c 0)))
                               hit (some #(when (and ch (= ch (Character/toLowerCase ^char (:key %)))) %)
                                         choices)]
                           (if hit
                             (deliver promise {:value (:value hit) :key (:key hit)})
                             (do (tui-session/emit! (ansi/warning "Enter one of the listed keys."))
                                 (recur)))))

                       ;; :select (default)
                       (when-let [line (.readLine reader)]
                         (let [input (str/trim line)]
                           (if-let [n (parse-long input)]
                             (if (and (>= n 1) (<= n (count options)))
                               (let [idx (dec n)
                                     selected (nth options idx)]
                                 (if (:free-input selected)
                                   ;; Free-input option selected — read another line
                                   (do (tui-session/emit! (str "\n  " (ansi/muted "Type your response: ")))
                                       (if-let [text-line (.readLine reader)]
                                         (deliver promise {:selected (:label selected) :index idx
                                                           :input (str/trim text-line)})
                                         (deliver promise {:selected (:label selected) :index idx :input ""})))
                                   ;; Normal selection
                                   (deliver promise {:selected (:label selected) :index idx})))
                               (do (tui-session/emit! (ansi/warning (str "Invalid. Enter 1-" (count options) ".")))
                                   (recur)))
                             (do (tui-session/emit! (ansi/warning "Enter a number to select an option."))
                                 (recur)))))))))
               (catch Exception _))
             (.countDown done-latch))
           "feedback-stdin-reader")]
    (.setDaemon t true)
    (.start t)
    t))

;; ============================================================================
;; Per-kind handlers
;; ============================================================================

(def default-confirm-choices
  "Default :choices for a :confirm request — yes / no / always."
  [{:key \y :label "yes"    :value :yes}
   {:key \n :label "no"     :value :no}
   {:key \a :label "always" :value :always}])

(defn- do-select
  "Handle a :select request — pick one of 2-6 options. Optional Mode-B popup
   backend (a :free-input pick opens a follow-up free-text popup), else raw
   in-stream live-block, else non-raw stdin. Returns {:selected <label> :index
   <int>} (+ :input for a free-input option), {:timeout true …}, {:error …},
   or nil (Mode-B cancel)."
  [{:keys [question options timeout-ms]} ^ReentrantLock feedback-lock !input-reader-thread]
  (let [timeout (or timeout-ms 60000)
        normalized (mapv (fn [opt]
                           (if (map? opt) opt {:label (str opt)}))
                         options)
        n (count normalized)]
    (cond
      (or (< n 2) (> n 6))
      {:error (str "Options must have 2-6 items, got " n)}

      ;; Optional Mode-B popup backend. A :free-input pick opens a follow-up
      ;; free-text popup for the typed answer.
      (mode-b-popup-feasible?)
      (do (.lock feedback-lock)
          (try
            (let [opts (mapv (fn [i {:keys [label]}]
                               {:value i :label (str label)})
                             (range) normalized)
                  q (tmux-iface/feedback-questionnaire
                     {:question question :options opts})
                  reply (popup/show! (:tmux (tmux-side/state)) q
                                     {:height (max 16 (+ 6 (count normalized)))
                                      :timeout-ms timeout})]
              (case (:status reply)
                :submitted (let [idx (get-in reply [:answers :feedback :value])
                                 selected (when (and (integer? idx)
                                                     (< idx (count normalized)))
                                            (nth normalized idx))]
                             (when selected
                               (if (:free-input selected)
                                 ;; Follow-up free-text popup for the typed answer.
                                 (let [tq (tmux-iface/text-questionnaire
                                           {:question (str (:label selected)
                                                           " — type your response")})
                                       treply (popup/show! (:tmux (tmux-side/state)) tq
                                                           {:height 10 :timeout-ms timeout})]
                                   (case (:status treply)
                                     :submitted {:selected (:label selected) :index idx
                                                 :input (or (get-in treply [:answers :answer :input]) "")}
                                     :timeout {:timeout true
                                               :reason (str "User feedback timed out ("
                                                            (/ timeout 1000) "s)")}
                                     nil))
                                 {:selected (:label selected) :index idx})))
                :timeout   {:timeout true
                            :reason (str "User feedback timed out ("
                                         (/ timeout 1000) "s)")}
                nil))
            (finally
              (.unlock feedback-lock))))

      :else
      (do (.lock feedback-lock)
          (try
            (let [p (promise)
                  raw-mode? (boolean @!input-reader-thread)
                  _  (reset! tui-session/!pending-feedback
                             {:promise p :kind :select :options normalized})
                  _  (if raw-mode?
                       ;; The thunk reads :free-idx at RENDER time, so the same
                       ;; renderer covers both sub-states — the pick re-renders
                       ;; through it, and so does a resize afterwards.
                       (show-user-feedback-block!
                        (fn [c] (format-feedback-lines
                                 question normalized c
                                 (:free-idx @tui-session/!pending-feedback))))
                       (tui-session/emit! (format-feedback-prompt question normalized)))
                  _  (refresh-feedback-prompt! :select)
                  done-latch (CountDownLatch. 1)
                  stdin-thread (when-not raw-mode?
                                 (start-feedback-stdin-reader! done-latch))
                  resp (deref p timeout :timeout)]
              (reset! tui-session/!pending-feedback nil)
              (hide-user-feedback-block!)
              (refresh-feedback-prompt! nil)
              (when stdin-thread
                (when-not (.await done-latch 100 TimeUnit/MILLISECONDS)
                  (.interrupt ^Thread stdin-thread)))
              (if (= resp :timeout)
                {:timeout true :reason (str "User feedback timed out (" (/ timeout 1000) "s)")}
                resp))
            (finally
              (.unlock feedback-lock)))))))

(defn- do-text
  "Handle a :text request — read a free-form line. Optional Mode-B popup
   backend (a free-text entry field), else in-stream. In raw mode the answer
   is typed into the normal sticky input line: the readline editor
   (`autocomplete/read-line-raw!`) sees the pending :text request and delivers
   the typed line on Enter, so the question shows as a sticky-bottom block and
   the input prompt flips to its answer-mode indicator. In non-raw mode a
   temporary stdin reader reads one line. Returns {:input <text> :index 0},
   {:timeout true …}, or nil (Mode-B cancel)."
  [{:keys [question timeout-ms]} ^ReentrantLock feedback-lock !input-reader-thread]
  (let [timeout (or timeout-ms 60000)]
    (cond
      ;; Optional Mode-B popup backend — a free-text entry field.
      (mode-b-popup-feasible?)
      (do (.lock feedback-lock)
          (try
            (let [q     (tmux-iface/text-questionnaire {:question question})
                  reply (popup/show! (:tmux (tmux-side/state)) q
                                     {:height 10 :timeout-ms timeout})]
              (case (:status reply)
                :submitted {:input (or (get-in reply [:answers :answer :input]) "")
                            :index 0}
                :timeout   {:timeout true
                            :reason (str "User feedback timed out ("
                                         (/ timeout 1000) "s)")}
                ;; cancelled (Esc/Ctrl-C) → nil, no answer
                nil))
            (finally
              (.unlock feedback-lock))))

      :else
      (do
        (.lock feedback-lock)
        (try
          (let [p (promise)
                raw-mode? (boolean @!input-reader-thread)
                ;; Minimal shape: the readline editor only needs :promise + :kind.
                ;; No :buf/:mode — the editor owns the line buffer and echo.
                _  (reset! tui-session/!pending-feedback {:promise p :kind :text})
                ;; Show the question as a sticky-bottom block above the input and
                ;; flip the input prompt to answer-mode immediately.
                _  (show-user-feedback-block! (fn [c] (format-text-lines question c)))
                _  (refresh-feedback-prompt! :text)
                done-latch (CountDownLatch. 1)
                stdin-thread (when-not raw-mode?
                               (start-feedback-stdin-reader! done-latch))
                resp (deref p timeout :timeout)]
            (reset! tui-session/!pending-feedback nil)
            (hide-user-feedback-block!)
            (refresh-feedback-prompt! nil)
            (when stdin-thread
              (when-not (.await done-latch 100 TimeUnit/MILLISECONDS)
                (.interrupt ^Thread stdin-thread)))
            (if (= resp :timeout)
              {:timeout true :reason (str "User feedback timed out (" (/ timeout 1000) "s)")}
              resp))
          (finally
            (.unlock feedback-lock)))))))

(defn- do-confirm
  "Handle a :confirm request — single-key choice from `:choices` (default
   yes/no/always). Optional Mode-B popup backend, else raw in-stream
   live-block, else non-raw stdin. Returns {:value <choice-value> :key <char>}
   or {:timeout true …}."
  [{:keys [question choices timeout-ms]} ^ReentrantLock feedback-lock !input-reader-thread]
  (let [timeout (or timeout-ms 30000)
        choices (vec (or (seq choices) default-confirm-choices))]
    (cond
      ;; Optional Mode-B popup backend (reuses the generic feedback popup).
      (mode-b-popup-feasible?)
      (do (.lock feedback-lock)
          (try
            (let [opts (mapv (fn [{:keys [key label value]}]
                               {:value value :label (str label) :shortcut key})
                             choices)
                  q (tmux-iface/feedback-questionnaire
                     {:question question :options opts})
                  reply (popup/show! (:tmux (tmux-side/state)) q
                                     {:height (max 12 (+ 6 (count choices)))
                                      :timeout-ms timeout})]
              (case (:status reply)
                :submitted (let [v (get-in reply [:answers :feedback :value])
                                 hit (some #(when (= v (:value %)) %) choices)]
                             (when hit {:value (:value hit) :key (:key hit)}))
                :timeout   {:timeout true
                            :reason (str "Confirm timed out (" (/ timeout 1000) "s)")}
                nil))
            (finally
              (.unlock feedback-lock))))

      ;; Raw in-stream live-block.
      @!input-reader-thread
      (do (.lock feedback-lock)
          (try
            (let [p (promise)
                  _ (reset! tui-session/!pending-feedback
                            {:promise p :kind :confirm :choices choices})
                  _ (show-user-feedback-block!
                     (fn [c] (format-confirm-lines question choices c)))
                  _ (refresh-feedback-prompt! :confirm)
                  resp (deref p timeout :timeout)]
              (reset! tui-session/!pending-feedback nil)
              (hide-user-feedback-block!)
              (refresh-feedback-prompt! nil)
              (if (= resp :timeout)
                {:timeout true :reason (str "Confirm timed out (" (/ timeout 1000) "s)")}
                resp))
            (finally
              (.unlock feedback-lock))))

      ;; Non-raw stdin.
      :else
      (do (.lock feedback-lock)
          (try
            (let [p (promise)
                  _ (reset! tui-session/!pending-feedback
                            {:promise p :kind :confirm :choices choices})
                  _ (tui-session/emit! (str "\n" (str/join "\n" (format-confirm-lines question choices))))
                  done-latch (CountDownLatch. 1)
                  stdin-thread (start-feedback-stdin-reader! done-latch)
                  resp (deref p timeout :timeout)]
              (reset! tui-session/!pending-feedback nil)
              (when stdin-thread
                (when-not (.await done-latch 100 TimeUnit/MILLISECONDS)
                  (.interrupt ^Thread stdin-thread)))
              (if (= resp :timeout)
                {:timeout true :reason (str "Confirm timed out (" (/ timeout 1000) "s)")}
                resp))
            (finally
              (.unlock feedback-lock)))))))

;; ============================================================================
;; Public factories
;; ============================================================================

(defn make-feedback-fn
  "Create the unified user-feedback callback bound to a session as
   `:user-feedback-fn`. Dispatches on the request `:kind` (:select | :text |
   :confirm); a missing :kind means :select, so historical `{:question :options
   :timeout-ms}` calls behave exactly as before. A single ReentrantLock
   serializes every prompt across all kinds (including the permission adapter,
   which calls back through here) — only one prompt is active at a time."
  [!input-reader-thread]
  (let [feedback-lock (ReentrantLock.)]
    (fn [{:keys [kind] :as req}]
      (case (or kind :select)
        :select  (do-select  req feedback-lock !input-reader-thread)
        :text    (do-text    req feedback-lock !input-reader-thread)
        :confirm (do-confirm req feedback-lock !input-reader-thread)
        ;; Unknown kind — treat as select for forward-compat.
        (do-select req feedback-lock !input-reader-thread)))))

(defn- mcp-permission-confirm
  "MCP-tool branch of `make-permission-fn`'s callback. Handles a
   `{:type :mcp-tool :servers […] :tools [\"s/t\"…] :display …}` request from the
   fail-closed MCP permission gate (mcp/permission.clj): prompts via `feedback-fn`
   (in-stream or tmux popup), caching always/never per *server* name, and
   auto-denies with a hint when there is no interactive channel."
  [!mcp-allowed !mcp-denied !input-reader-thread feedback-fn req]
  (let [servers (vec (distinct (or (:servers req) [])))
        display (or (:display req)
                    (when (seq (:tools req)) (str/join ", " (:tools req)))
                    "MCP tool")]
    (cond
      ;; every server in this call already trusted this session
      (and (seq servers) (every? #(contains? @!mcp-allowed %) servers))
      {:allowed true}

      ;; a server was denied with :never earlier — don't re-prompt
      (and (seq servers) (some #(contains? @!mcp-denied %) servers))
      {:denied true :reason "User denied MCP access (won't ask again this session)"}

      ;; interactive — in-stream OR tmux popup
      (or @!input-reader-thread (mode-b-popup-feasible?))
      (let [resp (feedback-fn
                  {:kind :confirm
                   :question (str "MCP tool call requested: " display)
                   :choices [{:key \y :label "yes"    :value :yes}
                             {:key \n :label "no"     :value :no}
                             {:key \a :label "always (remember server)" :value :always}
                             {:key \d :label "never (deny, don't ask again)" :value :never}]
                   :timeout-ms 30000})]
        (case (:value resp)
          :yes    {:allowed true}
          :always (do (doseq [s servers] (swap! !mcp-allowed conj s))
                      {:allowed true})
          :no     {:denied true :reason "User denied MCP access"}
          :never  (do (doseq [s servers] (swap! !mcp-denied conj s))
                      {:denied true :reason "User denied MCP access (won't ask again this session)"})
          (if (:timeout resp)
            {:denied true :reason "Permission prompt timed out (30s)"}
            {:denied true :reason "User denied MCP access"})))

      ;; non-interactive — auto-deny with a hint
      :else
      {:denied true
       :reason (str "MCP tool call (" display ") denied (non-interactive mode). "
                    "Allowlist it via :mcp-allow-tools or set [:permissions :mode] :auto-approve.")})))

(defn- within-allowed-dir?
  "True when every path in `paths` canonicalizes to a location inside some entry
   of `allowed-dirs` (also canonicalized, so the macOS /var→/private/var symlink
   resolves). Mirrors reference/resolve-allowed-path — used to let a persisted
   allowed-dir silence the write prompt the same way it already silences reads."
  [allowed-dirs paths]
  (let [canon (fn [p] (try (.getPath (.getCanonicalFile (io/file (str p))))
                           (catch Exception _ nil)))
        dirs  (keep canon allowed-dirs)]
    (and (seq paths)
         (seq dirs)
         (every? (fn [p]
                   (when-let [tp (canon p)]
                     (some (fn [d] (or (= tp d) (str/starts-with? tp (str d "/"))))
                           dirs)))
                 paths))))

(defn make-permission-fn
  "Create a permission callback bound to a session as `:permission-fn`. A thin
   adapter over `feedback-fn`: keeps path normalization + a per-session approved
   cache, and delegates the actual prompt to a :confirm request (so in-stream and
   tmux-popup rendering both live in the feedback primitive). Falls back to a
   non-interactive auto-deny + a `/allow-path` hint when no input channel is
   available.

   Requests:
     file access — {:path <p> | :paths [<p>…] :action :read|:write|:bash …}
     MCP call    — {:type :mcp-tool :servers [<s>…] :tools [\"s/t\"…] :display <s>}
                   (the fail-closed MCP permission gate; see mcp/permission.clj)
   Returns:  {:allowed true} | {:denied true :reason …}"
  [!input-reader-thread feedback-fn]
  (let [!session-allowed (atom #{})
        !session-denied  (atom #{})
        ;; Separate caches for MCP-tool approvals, keyed by server name.
        !mcp-allowed     (atom #{})
        !mcp-denied      (atom #{})]
    (fn [{:keys [path paths type] :as req}]
      (if (= :mcp-tool type)
        (mcp-permission-confirm !mcp-allowed !mcp-denied !input-reader-thread feedback-fn req)
        ;; ---- file-access permission ----
        ;; Support both :path (single) and :paths (vector from bash security check)
        (let [all-paths (or (when paths (seq paths)) (when path [path]))
              display-path (if (and all-paths (> (count all-paths) 1))
                             (str/join ", " all-paths)
                             (first all-paths))
              parent-dirs (keep #(when % (.getParent (io/file %))) all-paths)]
          (cond
            ;; Already approved all directories in this session
            (and (seq parent-dirs)
                 (every? #(contains? @!session-allowed %) parent-dirs))
            {:allowed true}

            ;; A directory was denied with :never earlier this session — deny
            ;; without re-prompting (symmetric to the :always allow cache). A
            ;; recent explicit :never wins over the persisted allow-list below.
            (and (seq parent-dirs)
                 (some #(contains? @!session-denied %) parent-dirs))
            {:denied true :reason "User denied file access (won't ask again this session)"}

            ;; Within a persisted allowed-dir (config [:permissions :allowed-dirs],
            ;; which includes the default project-dir and any /allow-path
            ;; additions) — allow WITHOUT prompting, mirroring the read gate.
            ;; Permission-mode still governs: :deny-by-default / :auto-approve use
            ;; their own fns (deny-all / allow-all) and never reach this branch.
            (within-allowed-dir? (try (agent/allowed-dirs) (catch Throwable _ nil))
                                 all-paths)
            {:allowed true}

            ;; Interactive — raw in-stream OR tmux popup. Delegate the prompt to
            ;; the unified feedback primitive as a :confirm request.
            (or @!input-reader-thread (mode-b-popup-feasible?))
            (let [resp (feedback-fn
                        {:kind :confirm
                         :question (str "File access requested: " display-path)
                         :choices [{:key \y :label "yes"    :value :yes}
                                   {:key \n :label "no"     :value :no}
                                   {:key \a :label "always (remember dir)" :value :always}
                                   {:key \d :label "never (deny, don't ask again)" :value :never}]
                         :timeout-ms 30000})]
              (case (:value resp)
                :yes    {:allowed true}
                :always (do (doseq [d parent-dirs] (swap! !session-allowed conj d))
                            {:allowed true})
                :no     {:denied true :reason "User denied file access"}
                :never  (do (doseq [d parent-dirs] (swap! !session-denied conj d))
                            {:denied true :reason "User denied file access (won't ask again this session)"})
                (if (:timeout resp)
                  {:denied true :reason "Permission prompt timed out (30s)"}
                  {:denied true :reason "User denied file access"})))

            ;; Non-raw mode (inline, piped) — auto-deny with hint
            :else
            {:denied true
             :reason (str "Access to " display-path " denied (non-interactive mode). "
                          "Use /allow-path " (or (first parent-dirs) display-path) " to grant access, then retry.")}))))))

(defn handle-allow-path-command
  "Handle /allow-path <dir> command. Adds directory to agent's allowed-dirs config."
  [args]
  (if (str/blank? args)
    (tui-session/emit! (ansi/warning "Usage: /allow-path <directory>"))
    (let [dir (str/trim args)
          ag  (tui-session/get-active-agent)]
      (if-not ag
        (tui-session/emit! (ansi/warning "No TUI agent running."))
        (let [current (agent/allowed-dirs ag)
              updated (vec (distinct (conj current dir)))]
          (agent/set-allowed-dirs! ag updated)
          (tui-session/emit!
           (ansi/success (str "Added " dir " to allowed directories: " (pr-str updated)))))))))
