;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.env-detect.core.providers
  "Detect available LLM providers by checking env vars, network, and PATH."
  (:require [clojure.string :as str])
  (:import [java.net HttpURLConnection URL]))

;; Hardcoded provider → env-var map (mirrors clj-llm providers registry).
;; Avoids compile-time dependency on clj-llm.
(def ^:private provider-env-vars
  {:openai     "OPENAI_API_KEY"
   :anthropic  "ANTHROPIC_API_KEY"
   :google     "GOOGLE_API_KEY"
   :azure      "AZURE_OPENAI_API_KEY"
   :groq       "GROQ_API_KEY"
   :together   "TOGETHER_API_KEY"
   :fireworks  "FIREWORKS_API_KEY"
   :openrouter "OPENROUTER_API_KEY"
   :mistral    "MISTRAL_API_KEY"
   :deepseek   "DEEPSEEK_API_KEY"})

(def provider-priority
  "Preference order when multiple API-key providers are reachable.
   Reflects (a) maturity of tool-use support, (b) agent-work quality,
   (c) brainyard's own tuning against Anthropic models. The bootstrap
   wizard uses this to preselect the highest-priority available provider;
   the user can override in interactive mode."
  [:anthropic :openai :google :azure :deepseek :groq :mistral
   :together :fireworks :openrouter])

(defn- mask-key
  "Show first 6 and last 4 chars of an API key, mask the rest."
  [key-str]
  (when (and key-str (> (count key-str) 12))
    (str (subs key-str 0 6) "...****" (subs key-str (- (count key-str) 4)))))

(defn- env-or-prop
  "Look up `name` in process env, falling back to JVM System Properties.
   The fallback lets a dotenv loader (see agent-tui-app/dotenv.clj) make
   keys visible without mutating the immutable JVM env map."
  [name]
  (or (System/getenv name) (System/getProperty name)))

(defn detect-api-key-providers
  "Check env vars for all API-key-based providers.
   Returns vec of {:provider kw :available? bool :env-var str :masked-key str-or-nil :method :api-key}"
  []
  (mapv (fn [[provider env-var]]
          (let [key-val (env-or-prop env-var)]
            {:provider   provider
             :available? (and (some? key-val) (not (str/blank? key-val)))
             :env-var    env-var
             :masked-key (mask-key key-val)
             :method     :api-key}))
        (sort-by key provider-env-vars)))

(defn detect-ollama
  "Check if Ollama is reachable at localhost:11434.
   Returns {:provider :ollama :available? bool :method :network :detail str}"
  []
  (let [url "http://localhost:11434"]
    (try
      (let [conn (doto ^HttpURLConnection (.openConnection (URL. url))
                   (.setConnectTimeout 2000)
                   (.setReadTimeout 2000)
                   (.setRequestMethod "GET"))
            code (.getResponseCode conn)]
        (.disconnect conn)
        {:provider   :ollama
         :available? (= 200 code)
         :method     :network
         :detail     (str url " reachable")})
      (catch Exception _
        {:provider   :ollama
         :available? false
         :method     :network
         :detail     (str url " not reachable")}))))

(defn detect-apple-fm
  "Check if Apfel (Apple FM server) is reachable at localhost:11435,
   or if `apfel` binary is on PATH.
   Returns {:provider :apple-fm :available? bool :method :network|:cli
            :installed? bool :binary-path str-or-nil :detail str}"
  []
  (let [url "http://localhost:11435/health"]
    (try
      (let [conn (doto ^HttpURLConnection (.openConnection (URL. url))
                   (.setConnectTimeout 2000)
                   (.setReadTimeout 2000)
                   (.setRequestMethod "GET"))
            code (.getResponseCode conn)]
        (.disconnect conn)
        {:provider    :apple-fm
         :available?  (= 200 code)
         :method      :network
         :installed?  true
         :binary-path nil
         :detail      (str url " reachable")})
      (catch Exception _
        ;; Network failed — check if binary is on PATH
        (try
          (let [proc (.start (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["which" "apfel"])))
                out  (str/trim (slurp (.getInputStream proc)))
                exit (.waitFor proc)]
            (if (and (zero? exit) (not (str/blank? out)))
              {:provider    :apple-fm
               :available?  false
               :method      :cli
               :installed?  true
               :binary-path out
               :detail      (str "apfel at " out " (not serving — run: apfel --serve --port 11435)")}
              {:provider    :apple-fm
               :available?  false
               :method      :cli
               :installed?  false
               :binary-path nil
               :detail      "apfel not found on PATH"}))
          (catch Exception _
            {:provider    :apple-fm
             :available?  false
             :method      :cli
             :installed?  false
             :binary-path nil
             :detail      "apfel not found"}))))))

(defn detect-claude-code
  "Check if `claude` CLI is on PATH.
   Returns {:provider :claude-code :available? bool :method :cli :detail str}"
  []
  (try
    (let [proc (.start (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["which" "claude"])))
          out  (str/trim (slurp (.getInputStream proc)))
          exit (.waitFor proc)]
      (if (and (zero? exit) (not (str/blank? out)))
        {:provider   :claude-code
         :available? true
         :method     :cli
         :detail     (str "claude CLI at " out)}
        {:provider   :claude-code
         :available? false
         :method     :cli
         :detail     "claude CLI not found on PATH"}))
    (catch Exception _
      {:provider   :claude-code
       :available? false
       :method     :cli
       :detail     "claude CLI not found"})))

(defn detect-all-providers
  "Run all provider detections.
   Returns vec of {:provider kw :available? bool :method :api-key|:network|:cli :detail str}"
  []
  (let [api-key-results (detect-api-key-providers)
        ollama-result   (detect-ollama)
        apple-fm-result (detect-apple-fm)
        claude-result   (detect-claude-code)]
    ;; Return api-key providers + special providers, normalizing :detail
    (into (mapv (fn [{:keys [env-var masked-key] :as m}]
                  (assoc m :detail
                         (if (:available? m)
                           (str env-var " (" masked-key ")")
                           (str env-var " not set"))))
                api-key-results)
          [ollama-result apple-fm-result claude-result])))

(defn- which
  "Return the absolute path of `binary` on PATH, or nil."
  [binary]
  (try
    (let [proc (.start (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["which" binary])))
          out  (str/trim (slurp (.getInputStream proc)))
          exit (.waitFor proc)]
      (when (and (zero? exit) (not (str/blank? out)))
        out))
    (catch Exception _ nil)))

(defn- run-cmd-output
  "Run a command, return trimmed stdout (or nil on error / non-zero exit)."
  [cmd-vec]
  (try
    (let [proc (.start (ProcessBuilder. ^java.util.List cmd-vec))
          out  (str/trim (slurp (.getInputStream proc)))
          exit (.waitFor proc)]
      (when (zero? exit) (not-empty out)))
    (catch Exception _ nil)))

(defn- fetch-ollama-tags
  "GET http://localhost:11434/api/tags. Returns body string or nil."
  []
  (try
    (let [url  "http://localhost:11434/api/tags"
          conn (doto ^HttpURLConnection (.openConnection (URL. url))
                 (.setConnectTimeout 2000)
                 (.setReadTimeout 2000)
                 (.setRequestMethod "GET"))
          code (.getResponseCode conn)
          body (when (= 200 code) (slurp (.getInputStream conn)))]
      (.disconnect conn)
      body)
    (catch Exception _ nil)))

(defn- parse-model-names
  "Extract \"name\":\"<model>\" strings from an Ollama /api/tags JSON body.
   Regex-only; no JSON dep. Returns vec of strings."
  [body]
  (when body
    (mapv second (re-seq #"\"name\"\s*:\s*\"([^\"]+)\"" body))))

(defn detect-ollama-installation
  "Distinct from `detect-ollama` (daemon reachability). Reports binary
   presence, version string, daemon state, and pulled models.
   Returns {:installed? :binary-path :version :daemon-running?
            :pulled-models [str] :detail str}"
  []
  (let [binary-path     (which "ollama")
        installed?      (some? binary-path)
        version         (when installed?
                          (run-cmd-output ["ollama" "--version"]))
        daemon-running? (:available? (detect-ollama))
        pulled-models   (if daemon-running?
                          (or (parse-model-names (fetch-ollama-tags)) [])
                          [])]
    {:installed?      installed?
     :binary-path     binary-path
     :version         version
     :daemon-running? daemon-running?
     :pulled-models   (vec pulled-models)
     :detail          (cond
                        (not installed?)
                        "ollama binary not on PATH"
                        (not daemon-running?)
                        (str "ollama installed at " binary-path " (daemon not running)")
                        :else
                        (str "ollama"
                             (when version (str " " version))
                             " — " (count pulled-models) " model(s) pulled"))}))

(defn- reachable?
  "HEAD `url` with 2s timeout. Returns true if any HTTP response received
   (i.e. host resolved and responded, even with 4xx)."
  [url]
  (try
    (let [conn (doto ^HttpURLConnection (.openConnection (URL. url))
                 (.setConnectTimeout 2000)
                 (.setReadTimeout 2000)
                 (.setInstanceFollowRedirects false)
                 (.setRequestMethod "HEAD"))
          code (.getResponseCode conn)]
      (.disconnect conn)
      (< code 500))
    (catch Exception _ false)))

(defn detect-network-egress
  "Quick HEAD probes to the hosts the bootstrap ladder might depend on.
   Returns {:huggingface? :ollama? :detail str}.
   Used by the ladder to decide whether the install/pull rung is viable."
  []
  (let [hf? (reachable? "https://huggingface.co")
        ol? (reachable? "https://ollama.com")]
    {:huggingface? hf?
     :ollama?      ol?
     :detail       (str "huggingface.co: " (if hf? "ok" "unreachable")
                        ", ollama.com: "   (if ol? "ok" "unreachable"))}))
