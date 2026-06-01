;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.mulog.core.slf4j-bridge
  "Routes SLF4J log events from Java libraries into mulog.
   Uses a custom logback appender that forwards events as structured mulog data."
  (:require [com.brunobonacci.mulog :as mu])
  (:import (ch.qos.logback.classic Level Logger)
           (ch.qos.logback.classic.spi ILoggingEvent ThrowableProxy)
           (ch.qos.logback.core AppenderBase)
           (org.slf4j LoggerFactory)))

(defn- new-slf4j-to-mulog-appender []
  (proxy [AppenderBase] []
    (append [^ILoggingEvent event]
      (mu/log ::slf4j
              :message (.getFormattedMessage event)
              :logger (.getLoggerName event)
              :level (keyword (.toLowerCase (str (.getLevel event))))
              :thread-name (.getThreadName event)
              :exception (when-let [ex-proxy (.getThrowableProxy event)]
                           (when (instance? ThrowableProxy ex-proxy)
                             (.getThrowable ^ThrowableProxy ex-proxy)))))))

(defonce ^:private !bridge-active (atom false))

(defn setup!
  "Set up SLF4J-to-mulog bridge. Replaces any existing logback appenders
   with a mulog-forwarding appender. Default level is WARN (Java lib noise).
   Options:
     :level - logback Level (default Level/WARN)"
  ([] (setup! {}))
  ([{:keys [level] :or {level Level/WARN}}]
   (let [^AppenderBase appender (new-slf4j-to-mulog-appender)
         logger-context (LoggerFactory/getILoggerFactory)
         ^Logger root-logger (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME)]
     (.detachAndStopAllAppenders root-logger)
     (.setLevel root-logger level)
     (.setContext appender logger-context)
     (.setName appender "mulog-bridge")
     (.start appender)
     (.addAppender root-logger appender)
     (reset! !bridge-active true)
     :ok)))

(defn stop!
  "Stop the SLF4J-to-mulog bridge."
  []
  (when @!bridge-active
    (let [^Logger root-logger (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME)]
      (.detachAndStopAllAppenders root-logger))
    (reset! !bridge-active false)
    :ok))
