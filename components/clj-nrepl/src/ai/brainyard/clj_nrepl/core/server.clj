;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-nrepl.core.server
  "Loopback-only nREPL server, Integrant-managed.

   The server binds 127.0.0.1 only (non-loopback binds are rejected at
   start). The ephemeral port is optionally written to a 0600 file so
   external CIDER tooling can attach to the same image the LLM is using."
  (:require [nrepl.server :as nrepl.server]
            [integrant.core :as ig]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]))

(defonce ^:private !current-server (atom nil))

(def ^:private loopback-binds #{"127.0.0.1" "localhost" "::1" "0:0:0:0:0:0:0:1"})

(defn- write-port-file!
  "Write the bound port to `port-file`. Best-effort 0600 perms on POSIX."
  [port-file port]
  (try
    (io/make-parents (io/file port-file))
    (spit port-file (str port))
    (try
      (let [path (.toPath (io/file port-file))
            perms (PosixFilePermissions/fromString "rw-------")]
        (Files/setPosixFilePermissions path perms))
      (catch Exception _ nil))
    (mulog/info ::wrote-port-file :path (str port-file) :port port)
    (catch Exception e
      (mulog/warn ::write-port-file-failed
                  :path (str port-file)
                  :error (.getMessage e)))))

;; ============================================================================
;; Per-instance port files (replaces single ~/.brainyard/nrepl-port)
;;
;; Each running brainyard instance writes its bound port to
;;   ~/.brainyard/nrepl-ports/<base>-<pid>.port    (e.g. by-7492.port)
;; so simultaneous instances don't clobber each other. Callers should run
;; `cleanup-stale-ports!` at startup to remove files whose PID is gone.
;; ============================================================================

(defn default-port-dir
  "Returns ~/.brainyard/nrepl-ports/ — created on demand with 0700 perms."
  ^java.io.File []
  (let [d (java.io.File. (str (System/getProperty "user.home")
                              "/.brainyard/nrepl-ports"))]
    (when-not (.exists d)
      (.mkdirs d)
      (try
        (Files/setPosixFilePermissions
         (.toPath d)
         (PosixFilePermissions/fromString "rwx------"))
        (catch Throwable _ nil)))
    d))

(defn instance-port-file
  "Per-instance port file: <dir>/<base>-<pid>.port.
   `base` is a short identifier (\"by\", \"by-web\"); PID defaults to current."
  (^java.io.File [base]
   (instance-port-file (default-port-dir) base
                       (.pid (java.lang.ProcessHandle/current))))
  (^java.io.File [^java.io.File dir base]
   (instance-port-file dir base (.pid (java.lang.ProcessHandle/current))))
  (^java.io.File [^java.io.File dir base pid]
   (java.io.File. dir (str base "-" pid ".port"))))

(defn- pid-from-port-file
  "Parse PID from a filename like 'by-12345.port'. Returns Long or nil."
  [^java.io.File f]
  (when-let [m (re-find #"-(\d+)\.port$" (.getName f))]
    (Long/parseLong (second m))))

(defn- pid-alive? [pid]
  (.isPresent (java.lang.ProcessHandle/of (long pid))))

(defn cleanup-stale-ports!
  "Delete port files whose PID is no longer alive. Returns deleted filenames."
  ([] (cleanup-stale-ports! (default-port-dir)))
  ([^java.io.File dir]
   (let [deleted (atom [])]
     (try
       (doseq [f (.listFiles dir)
               :when (and f (re-find #"\.port$" (.getName f)))
               :let [pid (pid-from-port-file f)]
               :when pid
               :when (not (pid-alive? pid))]
         (try (.delete f) (swap! deleted conj (.getName f))
              (catch Throwable _ nil)))
       (catch Throwable _ nil))
     @deleted)))

(defn list-port-files
  "Inventory of known port files: seq of {:pid :port :file :alive?}."
  ([] (list-port-files (default-port-dir)))
  ([^java.io.File dir]
   (for [f (.listFiles dir)
         :when (and f (re-find #"\.port$" (.getName f)))
         :let [pid (pid-from-port-file f)]
         :when pid]
     {:pid    pid
      :port   (try (Long/parseLong (.trim (slurp f)))
                   (catch Throwable _ nil))
      :file   (.getName f)
      :alive? (pid-alive? pid)})))

(defn start-server!
  "Start a loopback-only nREPL server.

   Options:
     :bind       — defaults to \"127.0.0.1\"; non-loopback is rejected
     :port       — 0 (default) → ephemeral
     :port-file  — when set, write the bound port (0600). Default nil.
     :handler    — optional nREPL handler stack.

   Returns the server map. Throws if a server is already running."
  [& {:keys [bind port port-file handler]
      :or {bind "127.0.0.1" port 0}}]
  (when-not (loopback-binds bind)
    (throw (ex-info "clj-nrepl server may only bind to loopback"
                    {:bind bind})))
  (when @!current-server
    (throw (ex-info "clj-nrepl server already running"
                    {:port (:port @!current-server)})))
  (let [args (cond-> [:bind bind :port port]
               handler (into [:handler handler]))
        server (apply nrepl.server/start-server args)]
    (reset! !current-server server)
    (when port-file
      (write-port-file! port-file (:port server)))
    (mulog/info ::server-started :bind bind :port (:port server))
    server))

(defn stop-server!
  ([] (stop-server! @!current-server))
  ([server]
   (when server
     (try (nrepl.server/stop-server server)
          (catch Exception e
            (mulog/warn ::stop-server-failed :error (.getMessage e))))
     (when (identical? server @!current-server)
       (reset! !current-server nil))
     (mulog/info ::server-stopped :port (:port server)))))

(defn running? [] (some? @!current-server))
(defn server-port [] (some-> @!current-server :port))
(defn current-server [] @!current-server)

;; ============================================================================
;; Integrant lifecycle
;; ============================================================================

(defmethod ig/init-key :ai.brainyard.clj-nrepl/server
  [_ {:keys [enabled? bind port port-file handler]
      :or   {bind "127.0.0.1" port 0}}]
  (when enabled?
    (start-server! :bind bind :port port :port-file port-file :handler handler)))

(defmethod ig/halt-key! :ai.brainyard.clj-nrepl/server
  [_ server]
  (when server (stop-server! server)))
