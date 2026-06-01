;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.task.format
  "Pure formatting functions for task management TUI output.
   Uses tui/ansi.clj helpers for color-coded terminal display."
  (:require [ai.brainyard.agent.tui.ansi :as ansi]
            [clojure.string :as str]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- elapsed-str
  "Format elapsed time in human-readable form."
  [started-at completed-at]
  (when started-at
    (let [end (or completed-at (System/currentTimeMillis))
          ms (- end started-at)]
      (cond
        (< ms 1000)    (str ms "ms")
        (< ms 60000)   (format "%.1fs" (/ ms 1000.0))
        (< ms 3600000) (format "%.1fm" (/ ms 60000.0))
        :else          (format "%.1fh" (/ ms 3600000.0))))))

(defn- status-str
  "Color-coded status string."
  [status]
  (case status
    :pending   (ansi/muted "pending")
    :running   (ansi/success "running")
    :completed (ansi/style "completed" ansi/bright-cyan)
    :failed    (ansi/failure "failed")
    :cancelled (ansi/style "cancelled" ansi/bright-yellow)
    (ansi/muted (str status))))

(defn- pad-right
  "Pad string to width with spaces (based on visible length, ignoring ANSI)."
  [s width]
  (let [visible-len (count (str/replace s #"\033\[[0-9;]*m" ""))
        padding (max 0 (- width visible-len))]
    (str s (apply str (repeat padding " ")))))

;; ============================================================================
;; Public Formatting Functions
;; ============================================================================

(defn format-task-list
  "Format tasks as a table.
   Columns: ID | Status | Type | Name | Elapsed
   Status is color-coded."
  [tasks]
  (if (empty? tasks)
    (str (ansi/header "Tasks") "\n  " (ansi/muted "No tasks."))
    (let [sorted (sort-by :created-at tasks)
          rows (map (fn [task]
                      [(name (:id task))
                       (status-str (:status task))
                       (name (:job-type task))
                       (let [n (:name task)]
                         (if (> (count n) 35)
                           (str (subs n 0 32) "...")
                           n))
                       (or (elapsed-str (:started-at task) (:completed-at task)) "-")])
                    sorted)]
      (str (ansi/header "Tasks") "\n"
           "  " (pad-right (ansi/style "ID" ansi/bold) 16)
           (pad-right (ansi/style "Status" ansi/bold) 18)
           (pad-right (ansi/style "Type" ansi/bold) 14)
           (pad-right (ansi/style "Name" ansi/bold) 38)
           (ansi/style "Elapsed" ansi/bold) "\n"
           (->> rows
                (map (fn [[id status type task-name elapsed]]
                       (str "  " (pad-right id 10)
                            (pad-right status 18)
                            (pad-right type 14)
                            (pad-right task-name 38)
                            elapsed)))
                (str/join "\n"))))))

(defn format-task-detail
  "Format detailed view for a single task."
  [task]
  (let [fmt-time (fn [ms] (when ms (str (java.util.Date. (long ms)))))
        lines [(str (ansi/header (str "Task: " (name (:id task)))))
               (str "  " (ansi/style "Name:" ansi/bold) "     " (:name task))
               (str "  " (ansi/style "Type:" ansi/bold) "     " (name (:job-type task)))
               (str "  " (ansi/style "Status:" ansi/bold) "   " (status-str (:status task)))
               (str "  " (ansi/style "Created:" ansi/bold) "  " (fmt-time (:created-at task)))
               (when (:started-at task)
                 (str "  " (ansi/style "Started:" ansi/bold) "  " (fmt-time (:started-at task))))
               (when (:completed-at task)
                 (str "  " (ansi/style "Finished:" ansi/bold) " " (fmt-time (:completed-at task))))
               (when (:started-at task)
                 (str "  " (ansi/style "Elapsed:" ansi/bold) "  "
                      (elapsed-str (:started-at task) (:completed-at task))))
               (when (:result task)
                 (str "  " (ansi/style "Result:" ansi/bold) "   " (pr-str (:result task))))
               (when (seq (:metadata task))
                 (str "  " (ansi/style "Metadata:" ansi/bold) " " (pr-str (:metadata task))))
               (let [output @(:output-lines task)]
                 (when (seq output)
                   (str "  " (ansi/style "Output:" ansi/bold) "   " (count output) " lines")))]]
    (str/join "\n" (remove nil? lines))))

(defn format-task-output
  "Format captured output lines for :task-log command.
   Supports last-N lines. Shows line count header."
  [task last-n]
  (let [all-lines @(:output-lines task)
        lines (if (and last-n (pos? last-n))
                (take-last last-n all-lines)
                all-lines)
        total (count all-lines)
        showing (count lines)]
    (str (ansi/header (str "Output: " (name (:id task))
                           " (" (if (= total showing)
                                  (str total " lines")
                                  (str "last " showing " of " total " lines"))
                           ")"))
         "\n"
         (if (empty? lines)
           (str "  " (ansi/muted "No output."))
           (->> lines
                (map #(str "  " %))
                (str/join "\n"))))))

(defn format-task-notification
  "Format inline notification for task state transitions.
   Returns nil if no notification should be shown."
  [task prev-status]
  (let [new-status (:status task)
        task-name (:name task)
        id-str (name (:id task))]
    (when (not= prev-status new-status)
      (case new-status
        :running   (ansi/muted (str "  [task] " id-str " started: " task-name))
        :completed (let [elapsed (elapsed-str (:started-at task) (:completed-at task))]
                     (ansi/success (str "  \u2713 [task] " id-str " completed: " task-name
                                        (when elapsed (str " (" elapsed ")")))))
        :failed    (let [err (get-in task [:result :error])]
                     (ansi/failure (str "  \u2717 [task] " id-str " failed: " task-name
                                        (when err (str " \u2014 " err)))))
        :cancelled (ansi/warning (str "  [task] " id-str " cancelled: " task-name))
        nil))))

;; ============================================================================
;; Task Activity Display Formatting
;; ============================================================================

(defn format-task-activity-line
  "Format a task header line for the sticky task activity area.
   bullet-char is the spinner character (○ or ● for running, ✓/✗ for terminal).
   Returns: 'bullet task-id: task-name' truncated to cols."
  [bullet-char task-id task-name cols]
  (let [id-str (if (keyword? task-id) (name task-id) (str task-id))
        prefix (str bullet-char " " id-str ": ")
        max-name (max 1 (- cols (count prefix)))
        truncated-name (if (> (count task-name) max-name)
                         (str (subs task-name 0 (max 1 (- max-name 3))) "...")
                         task-name)]
    (str prefix truncated-name)))

(defn format-task-output-line
  "Format a single task output line with indent and dim styling.
   Returns: '  │ output text' truncated to cols."
  [line cols]
  (let [prefix "  │ "
        max-text (max 1 (- cols (count prefix)))
        truncated (if (> (count (str line)) max-text)
                    (str (subs (str line) 0 (max 1 (- max-text 3))) "...")
                    (str line))]
    (str (ansi/muted prefix) (ansi/muted truncated))))

(defn format-task-status-bar
  "Format compact task notification for status bar left side.
   Shorter than format-task-notification to fit status bar width."
  [task prev-status]
  (let [new-status (:status task)
        id-str (name (:id task))
        task-name (let [n (:name task)]
                    (if (> (count n) 50) (str (subs n 0 47) "...") n))]
    (when (not= prev-status new-status)
      (case new-status
        :running   (ansi/muted (str id-str ": " task-name))
        :completed (let [elapsed (elapsed-str (:started-at task) (:completed-at task))]
                     (ansi/success (str "\u2713 " id-str (when elapsed (str " (" elapsed ")")))))
        :failed    (ansi/failure (str "\u2717 " id-str " failed"))
        :cancelled (ansi/warning (str id-str " cancelled"))
        nil))))
