;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.helpers
  "Helper functions extracted from core: LM setup, usage tracking, JUL suppression."
  (:require [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [clojure.string :as str])
  (:import [java.util.logging Logger Level]))

;; ============================================================================
;; Internal Helpers
;; ============================================================================

(defn suppress-jul-cookie-warnings!
  "Suppress Apache HttpClient cookie warnings (JUL, bypasses Timbre).
   Returns the logger instance — MUST be held as a strong reference
   to prevent GC (JUL uses weak refs internally)."
  []
  (doto (Logger/getLogger "org.apache.http.client.protocol.ResponseProcessCookies")
    (.setLevel Level/SEVERE)))

;; Suppress JUL cookie warnings at namespace load time.
;; Strong ref prevents GC from resetting the logger level.
(defonce ^:private _jul-cookie-logger (suppress-jul-cookie-warnings!))

(def ^:private user-id-fallback
  "Last-resort user-id when neither a CLI flag, BY_USER_ID, nor the
   `user.name` system property yields a usable value."
  "by-user")

(defn resolve-user-id
  "Resolve the effective user-id at startup, in precedence order:

     1. `explicit`           — typically a `--user-id`/`-u` CLI flag (nil when unset)
     2. `BY_USER_ID`         — real env var, or the same key bridged from a
                               project `.env` into a System Property (see
                               agent-tui-app.dotenv); env wins over property
     3. `user.name`          — JVM system property holding the OS login name
     4. \"by-user\"           — hardcoded fallback

   Blank/whitespace-only values are treated as absent at every tier, so an
   empty flag or `BY_USER_ID=` never shadows a lower tier. The result is a
   non-blank string. Resolution reads process-fixed sources, so calling this
   as a default at session-creation time yields a value that is stable for
   the life of the process — i.e. 'determined at start time'."
  ([] (resolve-user-id nil))
  ([explicit]
   (or (some-> explicit str/trim not-empty)
       (some-> (or (System/getenv "BY_USER_ID")
                   (System/getProperty "BY_USER_ID"))
               str/trim
               not-empty)
       (some-> (System/getProperty "user.name") str/trim not-empty)
       user-id-fallback)))

(def provider-key-env
  "Providers whose auto-setup requires an API key, mapped to the env var that
   supplies it. Providers absent from this map need no key (claude-code, ollama,
   apple-fm) or resolve credentials through clj-llm's catalog at `create-lm`."
  {:openai    "OPENAI_API_KEY"
   :anthropic "ANTHROPIC_API_KEY"})

(defn- credential
  "Resolve a credential env var the way the rest of `by` does: real env var
   first, then a JVM system property (the dotenv loader bridges `.env` keys into
   properties, not the environment — see dotenv.clj). Returns nil when neither
   is set to a non-blank value."
  [k]
  (let [v (or (System/getenv k) (System/getProperty k))]
    (when-not (str/blank? v) v)))

(defn missing-provider-key
  "When `provider` requires an API key that isn't present (neither as an env var
   nor a `.env`-bridged system property), return that env-var name; otherwise
   nil. Non-throwing companion to `setup-lm!` — a pre-flight can call this to
   notify the user instead of letting setup throw. Blank values count as absent."
  [provider]
  (when-let [env-var (get provider-key-env provider)]
    (when-not (credential env-var)
      env-var)))

(defn no-provider-message
  "A user-facing, actionable message when `provider` has no usable API key.
   Shared by the `by run` pre-flight (notify + graceful exit) and `setup-lm!`
   (the exception message), so both read identically."
  [provider]
  (let [env-var (get provider-key-env provider)]
    (str "No LLM provider available: the '" (name provider) "' provider needs an "
         "API key, but " env-var " is not set.\n"
         "  Fix one of:\n"
         "    • set " env-var " (export it, or add it to your .env), or\n"
         "    • choose a provider you have credentials for (`by run -p <provider>`), or\n"
         "    • run `by config` to configure a provider interactively.")))

(defn setup-lm!
  "Auto-setup LM with provider defaults.

   Throws `ex-info` with `{:provider … ::no-provider true}` when the provider
   requires an API key that is absent — callers that can present a friendlier
   surface should pre-flight with `missing-provider-key` instead of catching
   this. The message is `no-provider-message`."
  [provider & {:keys [model]}]
  (let [default-models {:openai      "gpt-4.1-mini"
                        :anthropic   "claude-opus-4-7"
                        :claude-code "opus"
                        :ollama      "glm-5:cloud"
                        :apple-fm    "apple-foundationmodel"}
        resolved-model (or model (get default-models provider))
        resolved-key   (when-let [env-var (get provider-key-env provider)]
                         (credential env-var))
        ;; Prompt-cache TTL for the stable prompt zones ("5m" default | "1h").
        ;; Env/dotenv opt-in — "1h" keeps the cross-turn prefix cached across
        ;; human-paced turn gaps (anthropic/anthropic-max beta header, bedrock
        ;; Converse cachePoint ttl for Claude models; 2x write premium, paid
        ;; once per stable zone per session). See
        ;; docs/design/prompt-cache-arrangement.md Phase 4.
        cache-ttl      (let [v (or (System/getenv "BY_CACHE_TTL")
                                   (System/getProperty "BY_CACHE_TTL"))]
                         (when-not (str/blank? v) v))]
    (when (missing-provider-key provider)
      (throw (ex-info (no-provider-message provider)
                      {:provider provider ::no-provider true})))
    (let [lm (clj-llm/create-lm (cond-> {:model resolved-model :provider provider}
                                  resolved-key (assoc :api-key resolved-key)
                                  cache-ttl    (assoc :cache-ttl cache-ttl)))]
      (clj-llm/configure-default-lm! lm)
      (tui-session/emit! (str (ansi/muted (str "LM configured: " (name provider) " / " resolved-model))))
      lm)))

(defn get-usage
  "Get usage summary from session's tracker."
  [agent]
  (when-let [tracker (agent/get-session-config @(:!session agent) :usage-tracker)]
    (clj-llm/get-usage-summary tracker)))

(defn get-usage-totals
  "Extract flat totals {:calls N :tokens N :cost F} from usage summary."
  [agent]
  (when-let [usage (get-usage agent)]
    (let [totals (or (:totals usage) usage)]
      {:calls  (or (:call-count totals) (:total-calls totals) 0)
       :tokens (or (:total-tokens totals)
                   (+ (or (:input-tokens totals) (:total-input-tokens totals) 0)
                      (or (:output-tokens totals) (:total-output-tokens totals) 0)))
       :cost   (or (:total-cost totals) 0.0)})))

(defn usage-diff
  "Compute the difference between two usage snapshots."
  [before after]
  (if (and before after)
    {:calls  (- (:calls after 0) (:calls before 0))
     :tokens (- (:tokens after 0) (:tokens before 0))
     :cost   (- (:cost after 0.0) (:cost before 0.0))}
    after))
