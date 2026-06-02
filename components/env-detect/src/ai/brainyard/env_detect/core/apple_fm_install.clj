;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.env-detect.core.apple-fm-install
  "Start / probe helpers for the `apfel` (Apple FM) server. Mirrors the
   shape of ollama-install: side-effecting, gated by an explicit caller
   confirmation. There is no install action here — apfel ships through
   a separate installer the wizard does not drive."
  (:require [clojure.string :as str])
  (:import [java.net HttpURLConnection URL]))

(def ^:private default-port 11435)

(defn- daemon-reachable? []
  (try
    (let [conn (doto ^HttpURLConnection
                (.openConnection (URL. (str "http://localhost:" default-port "/health")))
                 (.setConnectTimeout 500)
                 (.setReadTimeout 500)
                 (.setRequestMethod "GET"))
          code (.getResponseCode conn)]
      (.disconnect conn)
      (= 200 code))
    (catch Exception _ false)))

(defn start-daemon!
  "Start the apfel server in the background via `nohup apfel --serve --port 11435 &`
   and poll until /health answers. Returns {:ok? :elapsed-ms :detail}."
  []
  (let [start (System/currentTimeMillis)]
    (if (daemon-reachable?)
      {:ok? true :elapsed-ms 0 :detail "apfel daemon already running"}
      (try
        (let [cmd (format "nohup apfel --serve --port %d >/dev/null 2>&1 &" default-port)
              pb  (ProcessBuilder. ^"[Ljava.lang.String;"
                   (into-array String ["/bin/sh" "-c" cmd]))
              proc (.start pb)]
          (.waitFor proc))
        (loop [waited 0]
          (cond
            (daemon-reachable?)
            {:ok? true
             :elapsed-ms (- (System/currentTimeMillis) start)
             :detail "apfel daemon started"}

            (>= waited 10000)
            {:ok? false
             :elapsed-ms waited
             :detail "apfel daemon did not respond within 10s"}

            :else
            (do (Thread/sleep 500)
                (recur (+ waited 500)))))
        (catch Exception e
          {:ok?        false
           :elapsed-ms (- (System/currentTimeMillis) start)
           :detail     (str "failed to start apfel: " (.getMessage e))})))))
