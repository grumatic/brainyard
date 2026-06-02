;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.permissions
  "File access permission and user feedback functions for TUI sessions."
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

(defn- mode-b-popup-feasible?
  "True when we should route a permission/feedback dialog through a tmux
   popup rather than the in-stream codepath.  Requires Mode B with the
   side-channel installed AND a popup-capable tmux server with a tall enough
   client (§11.4 — fall back to in-stream on small terminals)."
  []
  (and (= :B (:mode @tui-session/!tui-state))
       (tmux-side/installed?)
       (popup/feasible? (:tmux (tmux-side/state)))))

(defn make-permission-fn
  "Create a permission callback for file access prompts.
   In raw mode: blocks the calling thread until the user responds (y/n/a).
   In non-raw mode: auto-denies with an informative error.
   Maintains a session-level cache of approved directories.
   Uses a ReentrantLock to serialize concurrent permission requests
   (e.g. from parallel sandbox blocks)."
  [!input-reader-thread]
  (let [!session-allowed (atom #{})
        permission-lock (ReentrantLock.)]
    (fn [{:keys [tool path paths] :as _req}]
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

          ;; Mode B — drive the dialog as a tmux popup so the agent stream
          ;; pane keeps painting underneath.  Falls back to in-stream when
          ;; the terminal is too small or tmux is too old.
          (mode-b-popup-feasible?)
          (do (.lock permission-lock)
              (try
                (let [q (tmux-iface/permission-questionnaire
                         {:tool (or tool "tool") :path display-path})
                      reply (popup/show! (:tmux (tmux-side/state)) q {})]
                  (case (tmux-iface/permission-decision reply)
                    :yes    {:allowed true}
                    :always (do (doseq [d parent-dirs]
                                  (swap! !session-allowed conj d))
                                {:allowed true})
                    :no     {:denied true :reason "User denied file access"}
                    :never  {:denied true :reason "User denied file access (and asked not to ask again)"}
                    :cancel {:denied true :reason (case (:status reply)
                                                    :timeout   "Permission prompt timed out"
                                                    :cancelled "Permission prompt cancelled"
                                                    "Permission denied")}))
                (finally
                  (.unlock permission-lock))))

          ;; Raw mode available — prompt interactively
          @!input-reader-thread
          (do (.lock permission-lock)
              (try
                (let [p (promise)
                      lines [(ansi/warning (str "File access requested: " display-path))
                             (str "  " (ansi/muted "[y]es / [n]o / [a]lways (remember dir): "))]]
                  (reset! tui-session/!pending-permission p)
                  (show-user-feedback-block! lines)
                  (let [resp (deref p 30000 :timeout)]
                    (reset! tui-session/!pending-permission nil)
                    (hide-user-feedback-block!)
                    (case resp
                      :yes     {:allowed true}
                      :always  (do (doseq [d parent-dirs] (swap! !session-allowed conj d))
                                   {:allowed true})
                      :no      {:denied true :reason "User denied file access"}
                      :timeout {:denied true :reason "Permission prompt timed out (30s)"}
                      {:denied true :reason "Unknown response"})))
                (finally
                  (.unlock permission-lock))))

          ;; Non-raw mode (inline, piped) — auto-deny with hint
          :else
          {:denied true
           :reason (str "Access to " display-path " denied (non-interactive mode). "
                        "Use /allow-path " (or (first parent-dirs) display-path) " to grant access, then retry.")})))))

(defn format-feedback-lines
  "Build the user feedback prompt as a vector of ANSI-styled lines.
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
  "Format user feedback prompt as a single string (joined with newlines).
   Retained for the non-raw stdin-reader fallback path."
  [question options]
  (str "\n" (str/join "\n" (format-feedback-lines question options))))

(defn start-feedback-stdin-reader!
  "Start a temporary thread to read feedback selection from stdin (non-raw mode).
   Reads lines until a valid selection or the promise is already delivered.
   Supports :free-input options: selecting a free-input option reads another line."
  [^java.util.concurrent.CountDownLatch done-latch]
  (let [reader (BufferedReader. (InputStreamReader. System/in))
        t (Thread.
           (fn []
             (try
               (loop []
                 (when-let [{:keys [promise options]} @tui-session/!pending-feedback]
                   (when-not (realized? promise)
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
                               (recur))))))))
               (catch Exception _))
             (.countDown done-latch))
           "feedback-stdin-reader")]
    (.setDaemon t true)
    (.start t)
    t))

(defn make-user-feedback-fn
  "Create a user feedback callback for option selection prompts.
   Blocks calling thread until user selects an option (1-6) or timeout.
   In raw mode, the input reader thread intercepts keypresses.
   In non-raw mode, spawns a temporary stdin reader thread.
   Uses a ReentrantLock to serialize concurrent feedback requests
   (e.g. from parallel sandbox blocks) — only one prompt is active at a time."
  [!input-reader-thread]
  (let [feedback-lock (ReentrantLock.)]
    (fn [{:keys [question options timeout-ms]}]
      (let [timeout (or timeout-ms 60000)
            normalized (mapv (fn [opt]
                               (if (map? opt) opt {:label (str opt)}))
                             options)
            n (count normalized)]
        (cond
          (or (< n 2) (> n 6))
          {:error (str "Options must have 2-6 items, got " n)}

          ;; Mode B — drive the dialog as a tmux popup.  We carry the same
          ;; return shape as the in-stream path: `{:selected <label> :index
          ;; <int>}` on submit, `nil` on cancel, `{:timeout true …}` on
          ;; timeout.  Free-input options aren't supported here yet — they
          ;; fall back to the in-stream codepath via the `:else` branch.
          (and (mode-b-popup-feasible?)
               (not-any? :free-input normalized))
          (do (.lock feedback-lock)
              (try
                (let [opts (mapv (fn [i {:keys [label]}]
                                   {:value i :label (str label)})
                                 (range) normalized)
                      q (tmux-iface/feedback-questionnaire
                         {:question question :options opts})
                      reply (popup/show! (:tmux (tmux-side/state)) q
                                         {:height (max 16 (+ 6 (count normalized)))})]
                  (case (:status reply)
                    :submitted (let [idx (get-in reply [:answers :feedback :value])
                                     selected (when (and (integer? idx)
                                                         (< idx (count normalized)))
                                                (nth normalized idx))]
                                 (when selected
                                   {:selected (:label selected) :index idx}))
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
                      _  (reset! tui-session/!pending-feedback {:promise p :options normalized})
                      _  (if raw-mode?
                           (show-user-feedback-block! (format-feedback-lines question normalized))
                           (tui-session/emit! (format-feedback-prompt question normalized)))
                      ;; In non-raw mode (no input reader thread), spawn temporary stdin reader
                      done-latch (java.util.concurrent.CountDownLatch. 1)
                      stdin-thread (when-not raw-mode?
                                     (start-feedback-stdin-reader! done-latch))
                      resp (deref p timeout :timeout)]
                  (reset! tui-session/!pending-feedback nil)
                  (hide-user-feedback-block!)
                  ;; Clean up stdin reader thread if started
                  (when stdin-thread
                    (when-not (.await done-latch 100 java.util.concurrent.TimeUnit/MILLISECONDS)
                      (.interrupt ^Thread stdin-thread)))
                  (if (= resp :timeout)
                    {:timeout true :reason (str "User feedback timed out (" (/ timeout 1000) "s)")}
                    resp))
                (finally
                  (.unlock feedback-lock)))))))))

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
