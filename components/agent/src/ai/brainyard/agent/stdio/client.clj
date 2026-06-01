;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.stdio.client
  "CLI client for programmatic interaction with stdio-based processes.

   Spawns a process via ProcessBuilder, reads stdout asynchronously via a
   background thread, and provides pattern-matching / idle-detection primitives
   to know when output is complete — replacing brittle sleep-based scripting.

   Follows the same ProcessBuilder pattern as mcp/client.clj but with plain
   text stdio (not JSON-RPC)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter Closeable]
           [java.util.regex Pattern]))

;; =============================================================================
;; CliClient Record
;; =============================================================================

(defrecord CliClient [process        ;; java.lang.Process
                      stdin          ;; OutputStreamWriter
                      !lines         ;; atom<vector<string>> — all stdout lines
                      !cursor        ;; atom<int> — read cursor for wait-for
                      !running       ;; atom<boolean>
                      reader-thread  ;; stdout reader daemon thread
                      stderr-thread] ;; stderr reader daemon thread
  Closeable
  (close [this]
    (reset! !running false)
    (when reader-thread
      (.interrupt ^Thread reader-thread))
    (when stderr-thread
      (.interrupt ^Thread stderr-thread))
    (when process
      (try
        (when (.isAlive ^Process process)
          (.destroy ^Process process))
        (catch Exception e
          (mulog/debug ::process-destroy-error :error (ex-message e)))))))

(defmethod print-method CliClient [client ^java.io.Writer w]
  (.write w (str "#CliClient{:running " @(:!running client)
                 ", :lines " (count @(:!lines client))
                 ", :cursor " @(:!cursor client) "}")))

;; =============================================================================
;; Internal Helpers
;; =============================================================================

(defn- ->pattern
  "Coerce string or regex to a java.util.regex.Pattern."
  [pattern]
  (cond
    (instance? Pattern pattern) pattern
    (string? pattern) (Pattern/compile (Pattern/quote pattern))
    :else (throw (ex-info "pattern must be a string or regex"
                          {:pattern pattern}))))

(defn- start-reader-thread
  "Start a daemon thread that reads lines from a BufferedReader and appends them
   to the !lines atom. Runs until the stream closes or !running becomes false."
  [^BufferedReader reader !lines !running thread-name]
  (let [t (Thread.
           (fn []
             (try
               (loop []
                 (when @!running
                   (let [line (.readLine reader)]
                     (if (nil? line)
                       (mulog/debug ::reader-stream-closed :thread thread-name)
                       (do
                         (swap! !lines conj line)
                         (recur))))))
               (catch InterruptedException _
                 (mulog/debug ::reader-interrupted :thread thread-name))
               (catch Exception e
                 (when @!running
                   (mulog/debug ::reader-error :thread thread-name :error (ex-message e)))))))]
    (.setDaemon t true)
    (.setName t thread-name)
    (.start t)
    t))

;; =============================================================================
;; Core API
;; =============================================================================

(defn start!
  "Spawn a CLI process and return a connected CliClient.

   Options:
     :command     — command vector, e.g. [\"bb\" \"tui\" \"react-agent\"]
     :working-dir — working directory for the process
     :env         — map of additional environment variables"
  [& {:keys [command working-dir env]}]
  (when-not (seq command)
    (throw (ex-info "command is required" {:command command})))
  (let [process-builder (ProcessBuilder. ^java.util.List (vec command))
        _ (when working-dir
            (.directory process-builder (io/file working-dir)))
        _ (when env
            (let [env-map (.environment process-builder)]
              (doseq [[k v] env]
                (.put env-map (str k) (str v)))))
        proc (.start process-builder)
        stdin (OutputStreamWriter. (.getOutputStream proc))
        stdout-reader (BufferedReader. (InputStreamReader. (.getInputStream proc)))
        stderr-reader (BufferedReader. (InputStreamReader. (.getErrorStream proc)))
        !lines (atom [])
        !stderr (atom [])
        !cursor (atom 0)
        !running (atom true)
        reader-t (start-reader-thread stdout-reader !lines !running "cli-stdout-reader")
        stderr-t (start-reader-thread stderr-reader !stderr !running "cli-stderr-reader")]
    (mulog/info ::cli-process-started :command command :working-dir working-dir)
    (->CliClient proc stdin !lines !cursor !running reader-t stderr-t)))

(defn send-line!
  "Write a line of text to the process stdin and flush."
  [^CliClient client text]
  (let [^java.io.Writer w (:stdin client)]
    (.write w (str text "\n"))
    (.flush w)))

(defn wait-for
  "Wait until a stdout line matches `pattern` (string or regex).

   Scans lines from the current cursor position. Returns all lines from the
   old cursor position through the matching line (inclusive). Advances the
   cursor past the match.

   Options:
     :timeout-ms — max wait in milliseconds (default 30000)

   Throws ExceptionInfo on timeout."
  [^CliClient client pattern & {:keys [timeout-ms] :or {timeout-ms 30000}}]
  (let [pat (->pattern pattern)
        start-time (System/currentTimeMillis)
        deadline (+ start-time timeout-ms)]
    (loop []
      (let [lines @(:!lines client)
            cursor @(:!cursor client)
            n (count lines)]
        ;; Scan from cursor to end for a match
        (if-let [match-idx (loop [i cursor]
                             (when (< i n)
                               (if (re-find pat (nth lines i))
                                 i
                                 (recur (inc i)))))]
          ;; Found — advance cursor and return lines
          (let [result (subvec lines cursor (inc match-idx))]
            (reset! (:!cursor client) (inc match-idx))
            result)
          ;; Not found — check timeout
          (if (>= (System/currentTimeMillis) deadline)
            (throw (ex-info "wait-for timed out"
                            {:pattern (str pattern)
                             :timeout-ms timeout-ms
                             :lines-checked (- n cursor)
                             :last-lines (vec (take-last 5 lines))}))
            (do
              (Thread/sleep (long 50))
              (recur))))))))

(defn wait-for-idle
  "Wait until no new output has arrived for `idle-ms` milliseconds.

   Returns all new lines since the current cursor position. Advances cursor.

   Options:
     :idle-ms    — idle threshold in milliseconds (default 2000)
     :timeout-ms — max total wait in milliseconds (default 60000)

   Throws ExceptionInfo on timeout."
  [^CliClient client & {:keys [idle-ms timeout-ms] :or {idle-ms 2000 timeout-ms 60000}}]
  (let [start-time (System/currentTimeMillis)
        deadline (+ start-time timeout-ms)]
    (loop [last-count (count @(:!lines client))
           last-change (System/currentTimeMillis)]
      (let [now (System/currentTimeMillis)
            current-count (count @(:!lines client))]
        (cond
          ;; Timed out
          (>= now deadline)
          (throw (ex-info "wait-for-idle timed out"
                          {:idle-ms idle-ms
                           :timeout-ms timeout-ms
                           :lines-seen current-count}))

          ;; New output arrived — reset idle timer
          (not= current-count last-count)
          (do
            (Thread/sleep (long 50))
            (recur current-count now))

          ;; Idle long enough
          (>= (- now last-change) idle-ms)
          (let [cursor @(:!cursor client)
                lines @(:!lines client)
                result (subvec lines cursor)]
            (reset! (:!cursor client) (count lines))
            result)

          ;; Still waiting
          :else
          (do
            (Thread/sleep (long 50))
            (recur last-count last-change)))))))

(defn get-output
  "Return all lines from the current cursor to end. Advances cursor."
  [^CliClient client]
  (let [lines @(:!lines client)
        cursor @(:!cursor client)
        result (subvec lines cursor)]
    (reset! (:!cursor client) (count lines))
    result))

(defn get-all-output
  "Return all captured lines. Does NOT advance cursor."
  [^CliClient client]
  @(:!lines client))

(defn clear-output!
  "Advance cursor to current end of output buffer (skip everything so far)."
  [^CliClient client]
  (reset! (:!cursor client) (count @(:!lines client))))

(defn shutdown!
  "Send /quit to the process, wait for exit, return exit code.

   Options:
     :timeout-ms — max wait for process exit (default 10000)"
  [^CliClient client & {:keys [timeout-ms] :or {timeout-ms 10000}}]
  (try
    (send-line! client "/quit")
    (catch Exception _
      (mulog/debug ::quit-send-failed :message "stdin may be closed")))
  (let [^Process proc (:process client)]
    ;; (long ...) cast required for native-image — see Process.waitFor
    ;; overload note in components/agent/src/.../aws_commands.clj.
    (if (.waitFor proc (long timeout-ms) java.util.concurrent.TimeUnit/MILLISECONDS)
      (let [code (.exitValue proc)]
        (reset! (:!running client) false)
        code)
      (do
        (mulog/warn ::process-exit-timeout :timeout-ms timeout-ms)
        (.destroyForcibly proc)
        (reset! (:!running client) false)
        -1))))

(defn alive?
  "Return true if the underlying process is still running."
  [^CliClient client]
  (and (:process client)
       (.isAlive ^Process (:process client))))
