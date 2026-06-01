;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.acp.core.transport.stdio
  "Stdio transport for ACP — JSON-RPC 2.0 over NDJSON on a subprocess's
   stdin/stdout.

   Lifecycle pattern mirrors `agent.stdio.client` (ProcessBuilder + a
   daemon reader thread that pushes to an atom), but with two
   differences required for JSON-RPC multiplexing:

     1. The reader thread parses each line as JSON-RPC and pushes the
        resulting map onto a `LinkedBlockingQueue`. Consumers block on
        `read-message!` instead of polling an atom.

     2. Writes are serialized through a write lock so concurrent
        `write-message!` calls don't interleave bytes on stdin.

   Stderr is drained on a separate daemon thread and surfaced through
   mulog (debug level) — losing it would cause subprocesses with full
   pipe buffers to deadlock."
  (:require [ai.brainyard.acp.core.jsonrpc :as jsonrpc]
            [ai.brainyard.acp.core.transport :as transport]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.java.io :as io])
  (:import [ai.brainyard.acp.core.transport ITransport]
           [java.io BufferedReader InputStreamReader OutputStreamWriter Closeable]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;; =============================================================================
;; Sentinel — placed on the inbox queue when the reader stream closes,
;; so `read-message!` callers wake up and return `nil` rather than block
;; forever.
;; =============================================================================

(def ^:private EOF-SENTINEL ::eof)

;; =============================================================================
;; Reader thread
;; =============================================================================

(defn- start-reader-thread
  "Spawn a daemon thread that reads lines from `reader`, parses each as
   JSON-RPC, and pushes the resulting map onto `inbox`. On EOF or close
   it places `EOF-SENTINEL` on `inbox` and exits.

   Parse errors are pushed as ExceptionInfo objects so the dispatcher
   can decide whether to log + continue or close the transport."
  [^BufferedReader reader ^LinkedBlockingQueue inbox !running thread-name]
  (let [t (Thread.
           (fn []
             (try
               (loop []
                 (when @!running
                   (let [line (.readLine reader)]
                     (cond
                       (nil? line)
                       (do
                         (mulog/debug ::reader-eof :thread thread-name)
                         (.put inbox EOF-SENTINEL))

                       (clojure.string/blank? line)
                       (recur)

                       :else
                       (do
                         (try
                           (.put inbox (jsonrpc/decode line))
                           (catch Exception e
                             (mulog/warn ::parse-error
                                         :thread thread-name
                                         :line line
                                         :error (ex-message e))
                             (.put inbox e)))
                         (recur))))))
               (catch InterruptedException _
                 (mulog/debug ::reader-interrupted :thread thread-name)
                 (.put inbox EOF-SENTINEL))
               (catch Exception e
                 (when @!running
                   (mulog/warn ::reader-error
                               :thread thread-name
                               :error (ex-message e)))
                 (.put inbox EOF-SENTINEL)))))]
    (.setDaemon t true)
    (.setName t thread-name)
    (.start t)
    t))

(defn- start-stderr-drain
  "Drain stderr on a daemon thread to prevent pipe-full deadlock.
   Each line is logged at debug level."
  [^BufferedReader reader !running thread-name]
  (let [t (Thread.
           (fn []
             (try
               (loop []
                 (when @!running
                   (let [line (.readLine reader)]
                     (when line
                       (mulog/debug ::subprocess-stderr
                                    :thread thread-name
                                    :line line)
                       (recur)))))
               (catch InterruptedException _ nil)
               (catch Exception _ nil))))]
    (.setDaemon t true)
    (.setName t thread-name)
    (.start t)
    t))

;; =============================================================================
;; StdioTransport
;; =============================================================================

(defrecord StdioTransport [command         ;; vector<string> — process command
                           working-dir     ;; string?         — cwd for spawned process
                           env             ;; map?            — extra env vars
                           !process        ;; atom<Process?>
                           !stdin          ;; atom<OutputStreamWriter?>
                           !inbox          ;; atom<LinkedBlockingQueue?>
                           !running        ;; atom<boolean>
                           !reader-thread  ;; atom<Thread?>
                           !stderr-thread  ;; atom<Thread?>
                           write-lock]     ;; Object — held while writing

  ITransport
  (open! [this]
    (when @!running
      (throw (ex-info "transport already open" {:command command})))
    (when-not (seq command)
      (throw (ex-info "command is required" {:command command})))
    (let [pb (ProcessBuilder. ^java.util.List (vec command))]
      (when working-dir
        (.directory pb (io/file working-dir)))
      (when env
        (let [env-map (.environment pb)]
          (doseq [[k v] env]
            (.put env-map (str k) (str v)))))
      (let [proc (.start pb)
            stdin (OutputStreamWriter. (.getOutputStream proc))
            stdout (BufferedReader. (InputStreamReader. (.getInputStream proc)))
            stderr (BufferedReader. (InputStreamReader. (.getErrorStream proc)))
            inbox (LinkedBlockingQueue.)]
        (reset! !process proc)
        (reset! !stdin stdin)
        (reset! !inbox inbox)
        (reset! !running true)
        (reset! !reader-thread
                (start-reader-thread stdout inbox !running
                                     (str "acp-stdio-reader[" (first command) "]")))
        (reset! !stderr-thread
                (start-stderr-drain stderr !running
                                    (str "acp-stdio-stderr[" (first command) "]")))
        (mulog/info ::stdio-transport-opened
                    :command command :working-dir working-dir)
        this)))

  (read-message! [this]
    (transport/read-message! this nil))

  (read-message! [_this timeout-ms]
    (when-let [^LinkedBlockingQueue inbox @!inbox]
      (let [msg (if timeout-ms
                  (.poll inbox (long timeout-ms) TimeUnit/MILLISECONDS)
                  (.take inbox))]
        (cond
          (nil? msg)              nil      ;; timeout
          (= EOF-SENTINEL msg)    (do
                                    ;; Re-deposit so subsequent reads also see EOF
                                    (.put inbox EOF-SENTINEL)
                                    nil)
          (instance? Throwable msg) (throw msg)
          :else                   msg))))

  (write-message! [_this msg]
    (when-not @!running
      (throw (ex-info "transport is closed" {:msg msg})))
    (let [^OutputStreamWriter stdin @!stdin]
      (when-not stdin
        (throw (ex-info "transport not open" {:msg msg})))
      (let [line (jsonrpc/encode msg)]
        (locking write-lock
          (.write stdin line)
          (.write stdin "\n")
          (.flush stdin)))))

  (open? [_this]
    (and @!running
         (when-let [^Process p @!process]
           (.isAlive p))))

  (close! [_this]
    (when @!running
      (reset! !running false)
      (mulog/info ::closing-stdio-transport :command command)
      (when-let [^Thread t @!reader-thread]
        (.interrupt t))
      (when-let [^Thread t @!stderr-thread]
        (.interrupt t))
      (when-let [^OutputStreamWriter w @!stdin]
        (try (.close w) (catch Exception _ nil)))
      (when-let [^Process p @!process]
        (try
          (when (.isAlive p)
            (.destroy p)
            (when-not (.waitFor p 2 TimeUnit/SECONDS)
              (.destroyForcibly p)))
          (catch Exception e
            (mulog/debug ::process-destroy-error :error (ex-message e)))))
      (when-let [^LinkedBlockingQueue inbox @!inbox]
        (.put inbox EOF-SENTINEL)))
    nil)

  Closeable
  (close [this] (transport/close! this)))

(defmethod print-method StdioTransport [t ^java.io.Writer w]
  (.write w (str "#StdioTransport{:command " (pr-str (:command t))
                 ", :open? " (boolean (transport/open? t)) "}")))

;; =============================================================================
;; Factory
;; =============================================================================

(defn create
  "Create a new (unopened) StdioTransport.

   Options:
     :command     — vector<string>, required. e.g. [\"node\" \"-e\" \"…\"]
     :working-dir — cwd for the spawned process (string)
     :env         — map<string,string> of additional env vars

   Call `open!` to spawn the process and start I/O threads."
  [{:keys [command working-dir env]}]
  (->StdioTransport command working-dir env
                    (atom nil) (atom nil) (atom nil)
                    (atom false) (atom nil) (atom nil)
                    (Object.)))
