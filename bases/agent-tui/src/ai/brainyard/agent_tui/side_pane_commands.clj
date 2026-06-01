;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui.side-pane-commands
  "Mode-B slash commands: `/activity`, `/log`, `/scrollback dump`, `/popup test`.
   In Mode A each handler emits a friendly note explaining how to enable side
   panes (start a tmux session, re-run with --with-tmux).

   The activity pane gets fed by hook handlers registered under source
   `:tui-side-activity` — show subscribes, hide unsubscribes. The log pane
   gets fed directly by `tail -F /tmp/agent-tui-app.log` (mulog already
   writes there); no producer wiring is needed."
  (:require [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.tmux-side :as tmux-side]
            [ai.brainyard.agent-tui.popup :as popup]
            [ai.brainyard.agent-tui-tmux.interface :as tmux-iface]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [clojure.string :as str]))

(def ^:private mode-a-note
  "Mode A — tmux side panes are unavailable. Run inside a tmux session and
   pass --with-tmux to enable activity/log side panes and popup dialogs.")

(defn- mode-b-or-note!
  "Run `body` if we're in Mode B with the side channel installed; otherwise
   emit a one-line note and return :continue."
  [body]
  (if (and (= :B (:mode @tui-session/!tui-state))
           (tmux-side/installed?))
    (body)
    (do (tui-session/emit! (ansi/muted mode-a-note))
        :continue)))

;; ----------------------------------------------------------------------------
;; Activity hook handlers
;; ----------------------------------------------------------------------------

(defn- ts [] (.format (java.time.LocalTime/now)
                      (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss")))

(defn- short-args [args]
  (let [s (try (pr-str args) (catch Throwable _ "?"))]
    (if (> (count s) 80) (str (subs s 0 77) "...") s)))

(defn- short-result [result]
  (cond
    (nil? result) "ok"
    (string? result) (let [s (str/replace result #"\s+" " ")]
                       (if (> (count s) 80) (str (subs s 0 77) "...") s))
    :else (let [s (try (pr-str result) (catch Throwable _ "?"))]
            (if (> (count s) 80) (str (subs s 0 77) "...") s))))

(defn- on-tool-pre [{:keys [tool-name args]}]
  (tmux-side/append-activity!
   (str (ts) " → " (some-> tool-name name) " " (short-args args))))

(defn- on-tool-post [{:keys [tool-name result]}]
  (tmux-side/append-activity!
   (str (ts)
        (if (and (map? result) (:error result)) " ✗ " " ✓ ")
        (some-> tool-name name)
        (when result (str "  " (short-result result))))))

(defn- on-ask-pre [{:keys [input]}]
  (tmux-side/append-activity! (str (ts) " ❯ " (short-result input))))

(defn- on-ask-post [{:keys [result]}]
  (tmux-side/append-activity!
   (str (ts) " ⏎ "
        (or (some-> result :answer short-result)
            (some-> result :result short-result)
            "(empty)"))))

(defn- on-task-created [{:keys [task]}]
  (tmux-side/append-activity!
   (str (ts) " ⊕ task " (or (:id task) "?")
        (when-let [name (:name task)] (str " " (short-result name))))))

(defn- on-task-completed [{:keys [task]}]
  (tmux-side/append-activity!
   (str (ts) " ⊜ task " (or (:id task) "?")
        (when-let [s (:status task)] (str " (" (name s) ")")))))

(defn- on-todo-updated [{:keys [items]}]
  (tmux-side/append-activity!
   (str (ts) " ⊟ todo updated (" (count items) " items)")))

(defn- register-activity-hooks! []
  (agent/register-hook! :agent.ask/pre        ::ask-pre        on-ask-pre        :source :tui-side-activity)
  (agent/register-hook! :agent.ask/post       ::ask-post       on-ask-post       :source :tui-side-activity)
  (agent/register-hook! :agent.tool-use/pre   ::tool-pre       on-tool-pre       :source :tui-side-activity)
  (agent/register-hook! :agent.tool-use/post  ::tool-post      on-tool-post      :source :tui-side-activity)
  (agent/register-hook! :task/created         ::task-created   on-task-created   :source :tui-side-activity)
  (agent/register-hook! :task/completed       ::task-completed on-task-completed :source :tui-side-activity)
  (agent/register-hook! :todo/updated         ::todo-updated   on-todo-updated   :source :tui-side-activity))

(defn- unregister-activity-hooks! []
  (try (agent/unregister-source! :tui-side-activity) (catch Throwable _)))

(defn- parse-direction
  "Pull a `:right | :left | :top | :bottom` direction out of `args`. Defaults
   to :right per design doc §5."
  [args]
  (let [tokens (->> (str/split (or args "") #"\s+") (remove str/blank?) set)]
    (cond
      (contains? tokens "left")   :left
      (contains? tokens "top")    :top
      (contains? tokens "bottom") :bottom
      :else                       :right)))

(defn- parse-percentage
  "Parse `-p N` / `--percentage N` out of `args`; defaults to 30."
  [args]
  (let [tokens (vec (str/split (or args "") #"\s+"))
        idx (some (fn [[i t]] (when (#{"-p" "--percentage"} t) i))
                  (map-indexed vector tokens))]
    (if-let [n (and idx (parse-long (str (get tokens (inc idx) ""))))]
      (max 1 (min 99 n))
      30)))

;; ----------------------------------------------------------------------------
;; /activity
;; ----------------------------------------------------------------------------

(defn handle-activity-command
  "`show [right|left|top|bottom] [-p N]` | `hide` | `toggle`."
  [args]
  (let [args (or args "")
        sub (or (first (str/split args #"\s+")) "")]
    (case sub
      "show" (mode-b-or-note!
              (fn []
                (let [ch (tmux-side/open-pane! :activity
                                               {:direction (parse-direction args)
                                                :percentage (parse-percentage args)})]
                  (register-activity-hooks!)
                  (tmux-side/append-activity!
                   (str (ts) " ▶ activity stream attached"))
                  (tui-session/emit! (ansi/success
                                      (str "Activity pane open: pane "
                                           (:pane-id ch) " ← " (:fifo-path ch))))
                  :continue)))
      "hide" (mode-b-or-note!
              (fn []
                (unregister-activity-hooks!)
                (if (tmux-side/close-pane! :activity)
                  (tui-session/emit! (ansi/success "Activity pane closed."))
                  (tui-session/emit! (ansi/muted "No activity pane open.")))
                :continue))
      "toggle" (mode-b-or-note!
                (fn []
                  (if (get (tmux-side/state) :activity)
                    (do (unregister-activity-hooks!)
                        (tmux-side/close-pane! :activity)
                        (tui-session/emit! (ansi/success "Activity pane closed."))
                        :continue)
                    (let [ch (tmux-side/open-pane! :activity {})]
                      (register-activity-hooks!)
                      (tmux-side/append-activity!
                       (str (ts) " ▶ activity stream attached"))
                      (tui-session/emit! (ansi/success
                                          (str "Activity pane open: pane " (:pane-id ch))))
                      :continue))))
      (do (tui-session/emit!
           (ansi/warning "Usage: /activity show [right|left|top|bottom] [-p N] | hide | toggle"))
          :continue))))

;; ----------------------------------------------------------------------------
;; /log
;; ----------------------------------------------------------------------------

(defn handle-log-command
  "`show [right|left|top|bottom] [-p N]` | `hide`."
  [args]
  (let [args (or args "")
        sub (or (first (str/split args #"\s+")) "")]
    (case sub
      "show" (mode-b-or-note!
              (fn []
                (let [ch (tmux-side/open-pane! :log
                                               {:direction (parse-direction args)
                                                :percentage (parse-percentage args)})]
                  (tui-session/emit! (ansi/success
                                      (str "Log pane open: pane " (:pane-id ch))))
                  :continue)))
      "hide" (mode-b-or-note!
              (fn []
                (if (tmux-side/close-pane! :log)
                  (tui-session/emit! (ansi/success "Log pane closed."))
                  (tui-session/emit! (ansi/muted "No log pane open.")))
                :continue))
      (do (tui-session/emit!
           (ansi/warning "Usage: /log show [right|left|top|bottom] [-p N] | hide"))
          :continue))))

;; ----------------------------------------------------------------------------
;; /scrollback
;; ----------------------------------------------------------------------------

(defn handle-scrollback-command
  "`dump` — capture the host pane's full scrollback (ANSI included) into the
   session dir and report the file path."
  [args]
  (let [sub (-> (or args "") str/trim)]
    (if (= sub "dump")
      (mode-b-or-note!
       (fn []
         (let [base (or (:session-dir (tmux-side/state))
                        (str (System/getProperty "java.io.tmpdir") "/by"))
               ts (System/currentTimeMillis)
               out (str base "/scrollback-" ts ".ans")]
           (if-let [path (tmux-side/capture-host-pane! out)]
             (tui-session/emit! (ansi/success (str "Scrollback dumped: " path)))
             (tui-session/emit! (ansi/failure "tmux capture-pane failed")))
           :continue)))
      (do (tui-session/emit! (ansi/warning "Usage: /scrollback dump"))
          :continue))))

;; ----------------------------------------------------------------------------
;; /popup test
;; ----------------------------------------------------------------------------

(defn handle-popup-command
  "`test` — open a no-op questionnaire popup (smoke test for the popup
   primitive). Reports the user's choice in-line."
  [args]
  (let [sub (-> (or args "") str/trim)]
    (if (= sub "test")
      (mode-b-or-note!
       (fn []
         (let [q (tmux-iface/feedback-questionnaire
                  {:question "Popup primitive smoke test — pick one:"
                   :options ["Apple" "Banana" "Cherry"]})
               reply (popup/show! (:tmux (tmux-side/state)) q {})]
           (tui-session/emit!
            (case (:status reply)
              :submitted (ansi/success
                          (str "You picked option index "
                               (get-in reply [:answers :feedback :value])))
              :cancelled (ansi/muted "Popup cancelled.")
              (ansi/muted (str "Popup status: " (:status reply)))))
           :continue)))
      (do (tui-session/emit! (ansi/warning "Usage: /popup test"))
          :continue))))
