;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a-client.core.events
  "Pure translation: A2A stream payloads -> brainyard hook event descriptors.

   This namespace is **pure data**. It does not call
   `agent.core.hooks/fire!`, does not touch artifacts, and does not require
   `components/agent` at all — that would create an unwanted
   `a2a-client -> agent` dependency. Instead it returns descriptors, and
   the caller (the Phase-3 `RemoteAgent`) supplies an `:on-event` callback
   that fires real hooks.

   This is the same contract `acp-client/core/events.clj` enforces, for the
   same reason.

   ## Why translation is stateful, and how that stays pure

   A brainyard `:agent.dspy-action/chunk` carries BOTH the new delta and
   the accumulated text so far, but A2A status updates carry only the
   latest message. Accumulation therefore has to live somewhere. Rather
   than hide it in an atom — which would make this namespace impure and
   untestable in isolation — `translate` threads an explicit accumulator:

       (let [{:keys [acc events]} (translate acc payload)] …)

   `initial-acc` starts one.

   ## A2A has no tool-call vocabulary

   Worth stating because ACP does, and the two are easy to conflate: A2A's
   streaming vocabulary is exactly `status-update` and `artifact-update`.
   There is no `toolCall` frame, no plan/todo frame, and no thought/
   reasoning channel. We therefore do NOT synthesize
   `:agent.tool-use/pre|post` or `:todo/updated` events from A2A traffic —
   inventing them would put fabricated tool activity in the user's
   transcript. Remote work is opaque by design (see
   docs/design/a2a-design.md §2)."
  (:require [clojure.string :as str]
            [ai.brainyard.a2a.interface :as a2a]))

;; =============================================================================
;; Event keywords
;;
;; Duplicated as constants rather than required from agent.core.hooks, to
;; keep this namespace free of agent deps. `interface_test.clj` guards
;; against catalog drift — the same arrangement acp-client uses.
;; =============================================================================

(def ^:const event-dspy-chunk
  "Real brainyard hook — streamed text renders in the TUI with no new code."
  :agent.dspy-action/chunk)

;; The rest have no brainyard hook equivalent, because they describe things
;; only a REMOTE agent can report. They are namespaced under :a2a/ so the
;; agent layer can dispatch on them explicitly rather than pattern-matching
;; a payload shape.
(def ^:const event-artifact       :a2a/artifact)
(def ^:const event-task-state     :a2a/task-state)
(def ^:const event-input-required :a2a/input-required)
(def ^:const event-auth-required  :a2a/auth-required)
(def ^:const event-terminal       :a2a/task-terminal)

(def all-events
  #{event-dspy-chunk event-artifact event-task-state
    event-input-required event-auth-required event-terminal})

;; =============================================================================
;; Accumulator
;; =============================================================================

(defn initial-acc
  "A fresh translation accumulator."
  []
  {:text "" :task-id nil :context-id nil :state nil})

;; =============================================================================
;; Frame classification
;; =============================================================================

(defn frame-kind
  "Classify a StreamResponse frame: `:status-update` | `:artifact-update` |
   `:task` | `:message` | `:unknown`.

   Falls back to SHAPE when `:kind` is absent, because servers omit it —
   notably on the initial Task frame. Trusting `:kind` alone would drop
   those frames silently.

   A non-map frame is `:unknown`, not an exception. `contains?` throws on
   a string or number, and a peer that streams a bare JSON scalar would
   otherwise take down a live subscription mid-flight — one malformed
   frame killing an otherwise healthy stream."
  [frame]
  (if-not (map? frame)
    :unknown
    (let [k (:kind frame)]
      (cond
        (= "status-update" k)                           :status-update
        (= "artifact-update" k)                         :artifact-update
        (= "task" k)                                    :task
        (= "message" k)                                 :message
        (contains? frame :artifact)                     :artifact-update
        (and (contains? frame :status) (:taskId frame)) :status-update
        (contains? frame :status)                       :task
        (contains? frame :parts)                        :message
        :else                                           :unknown))))

;; =============================================================================
;; Translation
;; =============================================================================

(defn- chunk-event
  "Build a chunk descriptor when `text` adds something new.

   A2A status messages are not guaranteed to be deltas — some servers
   resend the full text each time. Emitting the raw message as a delta
   would duplicate it in the transcript, so we diff against what we have
   already accumulated and emit only the genuinely new suffix."
  [acc text]
  (when-not (str/blank? text)
    (let [prev (:text acc)
          delta (cond
                  (str/blank? prev)             text
                  (str/starts-with? text prev)  (subs text (count prev))
                  :else                         text)]
      (when-not (str/blank? delta)
        {:acc   (assoc acc :text (if (str/starts-with? text prev) text
                                     (str prev text)))
         :event {:event event-dspy-chunk
                 :data  {:chunk delta
                         :accumulated (if (str/starts-with? text prev) text
                                          (str prev text))}}}))))

(defn translate
  "Fold one A2A stream payload into `acc`, producing hook descriptors.

   `payload` is what `transport/open-sse!` delivers: `{:result frame}` or
   `{:error …}`. Returns `{:acc acc' :events [descriptor …]}`.

   Never throws — a malformed frame produces no events rather than
   derailing a live stream."
  [acc payload]
  (let [acc (or acc (initial-acc))]
    (cond
      (:error payload)
      {:acc acc :events [{:event event-task-state
                          :data  {:error (:error payload)}}]}

      (nil? (:result payload))
      {:acc acc :events []}

      :else
      (let [frame (:result payload)
            acc   (cond-> acc
                    (:taskId frame)    (assoc :task-id (:taskId frame))
                    (:id frame)        (update :task-id #(or % (:id frame)))
                    (:contextId frame) (assoc :context-id (:contextId frame)))]
        (case (frame-kind frame)

          :status-update
          (let [state  (a2a/state->kw (get-in frame [:status :state]))
                text   (some-> (get-in frame [:status :message]) a2a/message-text)
                chunk  (chunk-event acc (or text ""))
                acc'   (assoc (or (:acc chunk) acc) :state state)
                evs    (cond-> []
                         chunk (conj (:event chunk))
                         true  (conj {:event event-task-state
                                      :data  {:state   state
                                              :task-id (:task-id acc')
                                              :final   (boolean (:final frame))}}))
                evs    (cond-> evs
                         (= :input-required state)
                         (conj {:event event-input-required
                                :data  {:task-id (:task-id acc')
                                        :prompt  (or text "")}})

                         (= :auth-required state)
                         (conj {:event event-auth-required
                                :data  {:task-id (:task-id acc')
                                        :prompt  (or text "")}})

                         (a2a/terminal? state)
                         (conj {:event event-terminal
                                :data  {:task-id (:task-id acc')
                                        :state   state
                                        :answer  (:text acc')}}))]
            {:acc acc' :events evs})

          :artifact-update
          (let [artifact (:artifact frame)]
            {:acc acc
             :events [{:event event-artifact
                       :data  {:task-id     (:task-id acc)
                               :artifact-id (a2a/artifact-id artifact)
                               :name        (:name artifact)
                               :description (:description artifact)
                               :text        (a2a/parts-text (:parts artifact))
                               :append      (boolean (:append frame))
                               :last-chunk  (boolean (:lastChunk frame))
                               :artifact    artifact}}]})

          :task
          (let [state (a2a/state->kw (get-in frame [:status :state]))]
            {:acc    (assoc acc :state state :task-id (or (:id frame) (:task-id acc)))
             :events [{:event event-task-state
                       :data  {:state state :task-id (or (:id frame) (:task-id acc))
                               :final (a2a/terminal? state)}}]})

          :message
          (let [text  (a2a/message-text frame)
                chunk (chunk-event acc text)]
            {:acc    (or (:acc chunk) acc)
             :events (if chunk [(:event chunk)] [])})

          ;; :unknown — a frame kind from a newer minor version. Carrying on
          ;; is correct: the stream is still valid and later frames matter.
          {:acc acc :events []})))))

(defn translate-all
  "Fold a sequence of payloads. Returns `{:acc … :events [...]}` with every
   descriptor in order. Convenience for tests and batch replay."
  [payloads]
  (reduce (fn [{:keys [acc events]} p]
            (let [r (translate acc p)]
              {:acc (:acc r) :events (into events (:events r))}))
          {:acc (initial-acc) :events []}
          payloads))
