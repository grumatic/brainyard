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
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.io BufferedReader InputStreamReader]
           [java.util.concurrent CountDownLatch TimeUnit]
           [java.util.concurrent.locks ReentrantLock]))

(def user-feedback-block-id
  "Stable id for the sticky-bottom live-block that renders the
   permission/feedback prompt below all other live blocks. Public so
   the input handler can dispose the block when transitioning a
   feedback prompt into free-input mode."
  :user-feedback)

(defn- show-user-feedback-block!
  "Render `lines` as the sticky-bottom user-feedback live-block.
   In fullscreen TUI, the block is anchored below iteration/think/todo
   blocks; in non-fullscreen mode, falls back to a plain emit."
  [lines]
  (if (layout/fullscreen?)
    (layout/update-live-block! user-feedback-block-id (vec lines)
                               {:sticky-bottom? true})
    (tui-session/emit! (str "\n" (str/join "\n" lines)))))

(defn- hide-user-feedback-block!
  "Dispose the user-feedback live-block (removes lines from scrollback).
   No-op in non-fullscreen mode."
  []
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
   Options with :free-input true show a '(free input)' hint."
  [question options]
  (let [hint (str "Select [1-" (count options) "]: ")]
    (-> [(ansi/style question ansi/bold ansi/bright-cyan)]
        (into (map-indexed
               (fn [i {:keys [label description free-input]}]
                 (str "  " (ansi/style (str "[" (inc i) "]") ansi/bold) " " label
                      (when description (str " — " (ansi/muted description)))
                      (when free-input (str " " (ansi/muted "(free input)")))))
               options))
        (conj (str "  " (ansi/muted hint))))))

(defn format-feedback-prompt
  "Format the :select prompt as a single string (joined with newlines).
   Retained for the non-raw stdin-reader fallback path."
  [question options]
  (str "\n" (str/join "\n" (format-feedback-lines question options))))

(defn format-confirm-lines
  "Build the :confirm prompt as ANSI lines: the question followed by a hint
   derived from `choices` (each `{:key char :label …}`). When the key is the
   label's first letter (yes/no/always) it renders inline — `[y]es`; otherwise
   the key is shown separately — `[d] never`."
  [question choices]
  (let [hint (->> choices
                  (map (fn [{:keys [key label]}]
                         (let [l (str label)]
                           (if (and (pos? (count l))
                                    (= (Character/toLowerCase ^char (first l))
                                       (Character/toLowerCase ^char key)))
                             (str "[" key "]" (subs l 1))
                             (str "[" key "] " l)))))
                  (str/join " / "))]
    [(ansi/warning question)
     (str "  " (ansi/muted (str hint ": ")))]))

(defn format-text-lines
  "Build the :text prompt as a vector of ANSI-styled lines for the sticky
   user-feedback block. The answer is typed into the main input line (whose
   prompt flips to an answer-mode indicator), so this is just the question."
  [question]
  [(ansi/style question ansi/bold ansi/bright-cyan)
   (str "  " (ansi/muted "Type your answer below, Enter to submit"))])

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
                       (show-user-feedback-block! (format-feedback-lines question normalized))
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
                _  (show-user-feedback-block! (format-text-lines question))
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
                  _ (show-user-feedback-block! (format-confirm-lines question choices))
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

(defn make-permission-fn
  "Create a file-access permission callback bound to a session as
   `:permission-fn`. A thin adapter over `feedback-fn`: keeps path
   normalization + a per-session approved-dir cache, and delegates the actual
   prompt to a :confirm request (so in-stream and tmux-popup rendering both live
   in the feedback primitive). Falls back to a non-interactive auto-deny + a
   `/allow-path` hint when no input channel is available.

   Request:  {:path <p> | :paths [<p>…] :action :read|:write|:bash …}
   Returns:  {:allowed true} | {:denied true :reason …}"
  [!input-reader-thread feedback-fn]
  (let [!session-allowed (atom #{})
        !session-denied  (atom #{})]
    (fn [{:keys [path paths] :as _req}]
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
          ;; without re-prompting (symmetric to the :always allow cache).
          (and (seq parent-dirs)
               (some #(contains? @!session-denied %) parent-dirs))
          {:denied true :reason "User denied file access (won't ask again this session)"}

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
                        "Use /allow-path " (or (first parent-dirs) display-path) " to grant access, then retry.")})))))

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
