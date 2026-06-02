;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.capture.dispatcher
  "S0 — capture event dispatcher.

  Subscribes to lifecycle hooks emitted by the agent runtime
  (`ai.brainyard.agent.core.hooks`) and forwards normalized capture
  events onto two channels:

    :critical-ch — unbounded, undroppable. Carries `:agent.ask/pre`,
                   `:agent.ask/post`, and `:agent/exception` so a noisy run
                   can never lose the conversation skeleton.
    :events-ch   — bounded, sliding-buffer. Carries debounced
                   high-volume events (`:agent.tool-use/post`,
                   `:agent.code-eval/post`). Under back-pressure the oldest
                   non-critical events are dropped (a μ/log warn is
                   emitted).

  This namespace knows nothing about persistence — it only normalizes
  and routes. The sidecar worker (`sidecar.clj`) consumes from both
  channels, runs S1 (`parser.clj`), and writes to L2 via the unified
  store.

  Lifecycle:
    (start! cfg) → returns a dispatcher record holding both channels +
                   the registered hook ids. Idempotent per cfg.
    (stop! d)    → closes both channels and unregisters all hooks tagged
                   with the dispatcher's :source kw."
  (:require [clojure.core.async :as async]
            [ai.brainyard.mulog.interface :as mulog]))

;; ---------------------------------------------------------------------------
;; Soft dependency on the agent component's hooks system. The memory
;; component is a dependency of agent (not the other way around), so we
;; must not :require agent code at load time. `resolve-hook-fn` looks up
;; the hooks API lazily; capture is a no-op when agent isn't on the
;; classpath (e.g. memory component in isolation).
;; ---------------------------------------------------------------------------

(defn- resolve-hook-fn [sym]
  (try (requiring-resolve sym) (catch Exception _ nil)))

(defn- register-hook!*
  [event-key handler-id handler & opts]
  (when-let [f (resolve-hook-fn 'ai.brainyard.agent.core.hooks/register-hook!)]
    (apply f event-key handler-id handler opts)))

(defn- unregister-source!*
  [source-kw]
  (when-let [f (resolve-hook-fn 'ai.brainyard.agent.core.hooks/unregister-source!)]
    (f source-kw)))

;; =====================================================
;; Tunables
;; =====================================================

(def ^:private default-channel-size 1024)

(def ^:private critical-events
  "Events that must never be dropped: the conversation skeleton + audit
  trail."
  #{:agent.ask/pre :agent.ask/post :agent/exception})

(def ^:private subscribed-events
  "Hooks the dispatcher subscribes to."
  [:agent.ask/pre :agent.ask/post :agent.tool-use/post :agent.code-eval/post :agent/exception])

(def ^:private debounce-events
  "Events whose dedup keys collapse identical entries within a window."
  #{:agent.tool-use/post :agent.code-eval/post})

;; =====================================================
;; Helpers
;; =====================================================

(defn- agent-of [event-map]
  (or (:agent event-map) (:stage-agent event-map)))

(defn- session-id-of [event-map]
  (or (:session-id event-map)
      (when-let [a (agent-of event-map)]
        (try
          ((requiring-resolve 'ai.brainyard.agent.core.protocol/session-id) a)
          (catch Exception _ nil)))))

(defn- user-id-of [event-map]
  (or (:user-id event-map)
      (when-let [a (agent-of event-map)]
        (try
          ((requiring-resolve 'ai.brainyard.agent.core.protocol/user-id) a)
          (catch Exception _ nil)))))

(defn- digest
  "Cheap signature for dedup. Includes event-key + a content fingerprint
  so identical retries collapse but distinct events don't."
  [event]
  (let [k (:event-key event)
        body (case k
               :agent.tool-use/post       (str (:tool-name event) "|" (hash (:args event)))
               :agent.code-eval/post (str (hash (:code event)))
               (str (hash event)))]
    (str (name k) "|" body)))

(defn- normalize
  "Build a capture event from a raw hook event-map."
  [event-key event-map]
  (let [now    (System/currentTimeMillis)
        sid    (session-id-of event-map)
        uid    (user-id-of event-map)]
    (-> event-map
        (dissoc :agent :stage-agent)
        (assoc  :event-key   event-key
                :captured-at now
                :event-id    (str (random-uuid))
                :session-id  sid
                :user-id     uid))))

;; =====================================================
;; Dedup transducer
;; =====================================================

(defn- dedup-xform
  "Stateful transducer: collapses consecutive duplicates within a
  sliding window of `window` recent digests, restricted to
  debounce-events."
  [window]
  (fn [rf]
    (let [recent (volatile! clojure.lang.PersistentQueue/EMPTY)
          seen   (volatile! #{})]
      (fn
        ([] (rf))
        ([result] (rf result))
        ([result event]
         (if (contains? debounce-events (:event-key event))
           (let [d (digest event)]
             (if (contains? @seen d)
               result ; drop dup
               (do
                 (vswap! recent conj d)
                 (vswap! seen conj d)
                 (when (> (count @recent) window)
                   (let [evicted (peek @recent)]
                     (vswap! recent pop)
                     (vswap! seen disj evicted)))
                 (rf result event))))
           (rf result event)))))))

;; =====================================================
;; Dispatcher record
;; =====================================================

(defrecord Dispatcher [config critical-ch events-ch source-kw stopped?])

(defn start!
  "Build and register a dispatcher.

  Options:
    :channel-size   — non-critical channel capacity (default 1024)
    :debounce-window — recent digests retained for dedup (default 30)
    :source         — keyword tag for hook teardown (default
                      ::dispatcher-<uuid>)
    :match          — optional (fn [event-map]) -> boolean to scope
                      capture (e.g. only one user, only root agents)

  Returns a `Dispatcher` record. The caller should pass it to a
  `sidecar` to actually drain the channels."
  [& {:keys [channel-size debounce-window source match]
      :or   {channel-size    default-channel-size
             debounce-window 30}}]
  (let [src        (or source (keyword "ai.brainyard.memory.capture"
                                       (str "dispatcher-" (random-uuid))))
        ;; Critical channel uses a large fixed buffer so offer! never
        ;; blocks the agent loop. The sidecar consumes in priority
        ;; order; under sustained pressure this buffer can grow but
        ;; is bounded by `channel-size * 8` — far above realistic
        ;; per-turn event counts. We do NOT use a sliding-buffer here
        ;; because critical events must never be dropped.
        critical-ch (async/chan (* channel-size 8))
        events-ch   (async/chan (async/sliding-buffer channel-size)
                                (dedup-xform debounce-window))
        match-pred  (or match (constantly true))
        push!       (fn [event-key event-map]
                      ;; Drop cached replays (M8b dup-storage cleanup).
                      ;; When a :pre hook replaced the tool call with a
                      ;; cached result, dispatch-with-hooks still fires
                      ;; :agent.tool-use/post with `:hook-replaced true`.
                      ;; Capturing it would double-write the same result
                      ;; to L2 every time the cache hits. The original
                      ;; (real) call is already in L2 from when it was
                      ;; first executed, so dropping the replay keeps
                      ;; the audit trail honest.
                      (when (and (match-pred event-map)
                                 (not (:hook-replaced event-map)))
                        (let [event (normalize event-key event-map)
                              ch (if (contains? critical-events event-key)
                                   critical-ch
                                   events-ch)
                              ok? (async/offer! ch event)]
                          (when (and (not ok?)
                                     (not (contains? critical-events event-key)))
                            (mulog/warn ::capture-event-dropped
                                        :event-key event-key
                                        :reason :channel-full)))))]
    (doseq [ev subscribed-events]
      (register-hook!*
       ev
       (keyword (str (name src) "-" (name ev)))
       (fn [event-map] (push! ev event-map))
       :source   src
       :priority -100  ; run last, after observability hooks
       :on-error :log))
    (mulog/info ::capture-dispatcher-started :source src)
    (->Dispatcher {:channel-size channel-size
                   :debounce-window debounce-window}
                  critical-ch events-ch src (atom false))))

(defn stop!
  "Tear down a dispatcher. Idempotent."
  [^Dispatcher d]
  (when (and d (not @(:stopped? d)))
    (reset! (:stopped? d) true)
    (unregister-source!* (:source-kw d))
    (async/close! (:critical-ch d))
    (async/close! (:events-ch d))
    (mulog/info ::capture-dispatcher-stopped :source (:source-kw d))
    nil))

(defn channels
  "Return [critical-ch events-ch] for the sidecar to consume."
  [^Dispatcher d]
  [(:critical-ch d) (:events-ch d)])
