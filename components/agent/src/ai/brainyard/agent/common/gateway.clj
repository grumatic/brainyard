;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.gateway
  "Messaging gateway core (R3 — docs/design/hermes-comparison.md).

   Maps an inbound platform message to a session turn and streams the reply
   back, so the agent is reachable from where the user lives (Telegram, etc.).
   This namespace is the **transport-agnostic core**: a `Transport` protocol, a
   pairing-code access gate, and a router. A concrete adapter (Telegram over
   `clj-http` getUpdates/sendMessage) is a thin `Transport` impl added later —
   the stub transport in the tests proves the contract today.

   Access control is **pairing-code** based: a new platform user is not served
   until they send a one-time code (minted in the `by` TUI via
   `gateway$pair-code`). Pairings persist under
   `<project>/.brainyard/gateway/`, resolving the 'remote user has no cwd'
   problem by carrying the minting session's `:user-id` + `:project-root` onto
   the pairing.

   The agent run is a pluggable seam (`*run-turn*`, default in-process
   `invoke-tool` with a per-user session for continuity), mirroring the
   scheduler's executor — so routing is fully testable without an LLM."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.tool :as tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.security SecureRandom]))

;; ============================================================================
;; Transport protocol
;; ============================================================================

(defprotocol Transport
  "A bidirectional messaging transport. Adapters (Telegram, …) implement this;
   the gateway router is written against it."
  (poll [t]
    "Return a seq of pending inbound messages, each
     {:platform :platform-user-id :chat-id :text}. May be empty.")
  (send-reply! [t chat-id text]
    "Deliver `text` to `chat-id`."))

;; ============================================================================
;; Store — <project>/.brainyard/gateway/{pairings.edn, codes.edn}
;; ============================================================================

(defn- ^File gateway-dir [project-dir]
  (io/file (str project-dir) ".brainyard" "gateway"))

(defn- ^File store-file [project-dir name] (io/file (gateway-dir project-dir) name))

(defn- read-edn [^File f fallback]
  (if (.exists f)
    (try (edn/read-string (slurp f)) (catch Exception _ fallback))
    fallback))

(defn- write-edn! [project-dir name data]
  (let [^File dir (gateway-dir project-dir)]
    (.mkdirs dir)
    (let [tmp (io/file dir (str name ".tmp"))
          dst (io/file dir name)]
      (spit tmp (pr-str data))
      (.renameTo tmp dst))
    data))

(defn- read-pairings [project-dir] (read-edn (store-file project-dir "pairings.edn") {}))
(defn- read-codes [project-dir] (read-edn (store-file project-dir "codes.edn") {}))

;; ============================================================================
;; Pairing codes
;; ============================================================================

(def ^:private code-alphabet "ABCDEFGHJKLMNPQRSTUVWXYZ23456789") ;; no ambiguous chars
(def ^:private ^SecureRandom secure-rng (SecureRandom.))

(defn- gen-code-str []
  (apply str (repeatedly 6 #(nth code-alphabet (.nextInt secure-rng (count code-alphabet))))))

(defn generate-code!
  "Mint a one-time pairing code carrying the minting session's identity. Opts:
   :user-id :project-root :ttl-ms (default 10 min) :now (testing seam).
   Returns {:code :expires-at}."
  [project-dir {:keys [user-id project-root ttl-ms now]}]
  (let [now    (or now (System/currentTimeMillis))
        ttl    (or ttl-ms 600000)
        code   (gen-code-str)
        entry  {:code code
                :user-id (or user-id "by-user")
                :project-root (or project-root (str project-dir))
                :expires-at (+ now ttl)}]
    (write-edn! project-dir "codes.edn" (assoc (read-codes project-dir) code entry))
    (mulog/log ::code-minted :user-id (:user-id entry))
    {:code code :expires-at (:expires-at entry)}))

(defn pair!
  "Pair `platform-user-id` using `code`. On a valid, unexpired code: create a
   persistent pairing (stable per-user session-id for continuity), consume the
   code. Returns the pairing map or {:error}. `now` is a testing seam."
  [project-dir platform-user-id code & {:keys [now]}]
  (let [now   (or now (System/currentTimeMillis))
        codes (read-codes project-dir)
        entry (get codes (some-> code str str/trim str/upper-case))]
    (cond
      (nil? entry) {:error "invalid pairing code"}
      (>= now (:expires-at entry)) {:error "pairing code expired"}
      :else
      (let [pairing {:platform-user-id platform-user-id
                     :user-id (:user-id entry)
                     :project-root (:project-root entry)
                     :session-id (str "gateway-" platform-user-id)
                     :paired-at now}]
        (write-edn! project-dir "pairings.edn"
                    (assoc (read-pairings project-dir) platform-user-id pairing))
        (write-edn! project-dir "codes.edn" (dissoc codes (:code entry)))
        (mulog/log ::paired :platform-user-id platform-user-id :user-id (:user-id entry))
        pairing))))

(defn resolve-user
  "The pairing for `platform-user-id`, or nil when unpaired."
  [project-dir platform-user-id]
  (get (read-pairings project-dir) platform-user-id))

(defn list-pairings [project-dir] (vec (vals (read-pairings project-dir))))

(defn unpair!
  "Remove a pairing. Returns true when one was removed."
  [project-dir platform-user-id]
  (let [ps (read-pairings project-dir)]
    (when (contains? ps platform-user-id)
      (write-edn! project-dir "pairings.edn" (dissoc ps platform-user-id))
      true)))

;; ============================================================================
;; Turn execution (pluggable seam)
;; ============================================================================

(defn default-run-turn
  "Run `text` through the agent for a paired user's session and return the reply
   text. Per-user `:session-id` gives cross-message continuity. Mirrors the
   scheduler executor: bare `invoke-tool` with an explicit `:agent-session`."
  [{:keys [user-id session-id]} text]
  (try
    (let [raw (tool/invoke-tool :coact-agent
                                :question (str text)
                                :agent-session {:user-id user-id :session-id session-id}
                                :auto-close? true)
          out (tool/resolve-agent-ref raw)]
      (cond (map? out)    (or (:answer out) (pr-str out))
            (string? out) out
            :else         (pr-str out)))
    (catch Exception e
      (mulog/warn ::run-turn-failed :session-id session-id :exception e)
      (str "⚠ error handling your message: " (.getMessage e)))))

(def ^:dynamic *run-turn*
  "Turn executor — `(fn [pairing text] -> reply-string)`. Rebind in tests."
  default-run-turn)

;; ============================================================================
;; Router
;; ============================================================================

(defn handle-message
  "Route one inbound message. Paired users get an agent turn + reply; unpaired
   users have their text treated as a pairing code (pair-or-prompt). Returns a
   keyword outcome (:answered | :paired | :unpaired) for logging/testing."
  [project-dir transport {:keys [platform-user-id chat-id text]}]
  (if-let [pairing (resolve-user project-dir platform-user-id)]
    (do (send-reply! transport chat-id (*run-turn* pairing text))
        :answered)
    (let [res (pair! project-dir platform-user-id text)]
      (if (:error res)
        (do (send-reply! transport chat-id
                         (str "🔒 You're not paired. Send your pairing code to connect "
                              "(mint one in the by TUI with `(gateway$pair-code)`)."))
            :unpaired)
        (do (send-reply! transport chat-id
                         "✅ Paired! Send a message and the agent will reply.")
            :paired)))))

(defn run-once!
  "Drain all currently-pending messages from `transport` through the router.
   Returns the count handled."
  [project-dir transport]
  (let [msgs (poll transport)]
    (doseq [m msgs]
      (try (handle-message project-dir transport m)
           (catch Exception e (mulog/warn ::handle-failed :msg m :exception e))))
    (count msgs)))

;; ============================================================================
;; Loop (in-process, daemon)
;; ============================================================================

(defonce ^:private !running (atom false))

(defn gateway-running? [] @!running)

(defn start-gateway!
  "Start a daemon loop that polls `transport` and routes messages until
   `stop-gateway!`. Returns true if started, false if one is already running.
   `transport`'s `poll` is expected to block/pace (e.g. Telegram long-poll); a
   2s backoff guards error spins."
  [project-dir transport]
  (if (compare-and-set! !running false true)
    (let [t (Thread.
             ^Runnable
             (fn []
               (loop []
                 (when @!running
                   (try (run-once! project-dir transport)
                        (catch Throwable e
                          (mulog/warn ::gateway-loop-error :exception e)
                          (try (Thread/sleep (long 2000)) (catch InterruptedException _ nil))))
                   (recur))))
             "by-gateway")]
      (.setDaemon t true)
      (.start t)
      (mulog/info ::gateway-started)
      true)
    false))

(defn stop-gateway!
  "Signal the gateway loop to stop. Returns true."
  []
  (reset! !running false)
  true)

;; ============================================================================
;; Commands — pairing management (the TUI side of the handshake)
;; ============================================================================

(defcommand gateway$pair-code
  "Mint a one-time pairing code a remote user sends to the gateway bot to connect."
  (fn [& {:keys [user-id project-root ttl-ms]}]
    (generate-code! (config/project-dir)
                    {:user-id user-id :project-root project-root
                     :ttl-ms (or ttl-ms (config/get-config :gateway-pair-code-ttl-ms))}))
  :input-schema  [:map
                  [:user-id      {:optional true} [:string {:desc "Identity to grant the paired user (default: current user)"}]]
                  [:project-root {:optional true} [:string {:desc "Project root the paired user operates in (default: current project)"}]]
                  [:ttl-ms       {:optional true} [:int {:desc "Code lifetime in ms (default from config)"}]]]
  :output-schema [:map
                  [:code       [:string {:desc "The pairing code to share"}]]
                  [:expires-at [:int {:desc "Expiry, epoch-ms"}]]])

(defcommand gateway$pairings
  "List paired messaging users."
  (fn [& _]
    {:pairings (mapv #(select-keys % [:platform-user-id :user-id :project-root :paired-at])
                     (list-pairings (config/project-dir)))})
  :input-schema  [:map]
  :output-schema [:map [:pairings [:vector {:desc "Pairing summaries"} :any]]])

(defcommand gateway$unpair
  "Remove a paired messaging user."
  (fn [& {:keys [platform-user-id]}]
    (if (unpair! (config/project-dir) platform-user-id)
      {:unpaired platform-user-id}
      {:error (str "no pairing for '" platform-user-id "'")}))
  :input-schema  [:map [:platform-user-id [:string {:desc "Platform user id to unpair"}]]]
  :output-schema [:map
                  [:unpaired {:optional true} [:string {:desc "Removed platform user id"}]]
                  [:error    {:optional true} [:string {:desc "Error if absent"}]]])

(def gateway-commands
  "Gateway pairing-management commands, bound into the common roster."
  [#'gateway$pair-code #'gateway$pairings #'gateway$unpair])
