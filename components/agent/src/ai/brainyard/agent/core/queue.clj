;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.queue
  "Input queue for serialized agent ask processing.

   Allows users to submit inputs while the agent is processing.
   Inputs are queued (FIFO, max 10) and processed sequentially.
   Both TUI and Web inject their own process-fn and notify-fn."
  (:import [java.time Instant]
           [java.util UUID]))

(def ^:private default-max-size 10)

(defn create-queue
  "Create a new input queue.

   process-fn: (fn [input]) — called for each item, should call agent/ask etc.
   notify-fn:  (fn [event item queue-info]) — called on state changes.
     event is one of :enqueued, :processing, :completed, :error, :cancelled, :queue-empty
     item is the queue item map (nil for :queue-empty)
     queue-info is {:items [...] :queue-length N :processing-id uuid|nil}

   Returns an atom holding the queue state."
  [process-fn notify-fn]
  (atom {:items          []
         :processing-id  nil
         :worker-future  nil
         :max-size       default-max-size
         :process-fn     process-fn
         :notify-fn      notify-fn}))

(defn get-queue-info
  "Return queue state summary for UI display."
  [!queue]
  (let [{:keys [items processing-id]} @!queue]
    {:items        (mapv #(select-keys % [:id :input :status :queued-at]) items)
     :queue-length (count (filter #(= :queued (:status %)) items))
     :processing-id processing-id}))

(defn- pop-next-item!
  "Atomically find and mark the next :queued item as :processing.
   Returns the item, or nil if none."
  [!queue]
  (let [result (atom nil)]
    (swap! !queue
           (fn [q]
             (let [idx (some (fn [[i item]]
                               (when (= :queued (:status item)) i))
                             (map-indexed vector (:items q)))]
               (if idx
                 (let [item (nth (:items q) idx)
                       updated (assoc item :status :processing)]
                   (reset! result updated)
                   (-> q
                       (assoc-in [:items idx] updated)
                       (assoc :processing-id (:id updated))))
                 q))))
    @result))

(defn- remove-item!
  "Remove a completed/errored item from the queue."
  [!queue item-id]
  (swap! !queue
         (fn [q]
           (-> q
               (update :items (fn [items] (vec (remove #(= item-id (:id %)) items))))
               (cond-> (= item-id (:processing-id q))
                 (assoc :processing-id nil))))))

(defn- run-processing-loop!
  "Start a processing loop in a future. Processes items sequentially.

   Self-healing: EVERY per-item side effect — including `notify-fn :processing`,
   which the notify-fn may implement with terminal/layout code that is not yet
   ready on a freshly-launched TUI — runs inside a per-item `try` that catches
   `Throwable`, and `remove-item!` runs in a per-item `finally` so a poison item
   can never strand the queue. The outer `finally` ALWAYS clears `:worker-future`
   so `ensure-worker!` can respawn. Without this, a single unexpected throw would
   kill the future while leaving `:worker-future` pointing at the dead future,
   permanently wedging the queue (asks enqueue forever but never drain)."
  [!queue]
  (future
    (try
      (loop []
        (when-let [item (pop-next-item! !queue)]
          (let [{:keys [process-fn notify-fn]} @!queue]
            (try
              (notify-fn :processing item (get-queue-info !queue))
              ;; opts forwarded as an optional 2nd arg ONLY when present, so
              ;; 1-arity process-fns (e.g. agent-web) are unaffected.
              (if-some [o (:opts item)]
                (process-fn (:input item) o)
                (process-fn (:input item)))
              (notify-fn :completed item (get-queue-info !queue))
              (catch InterruptedException _
                (notify-fn :cancelled item (get-queue-info !queue)))
              (catch Throwable e
                ;; Report the failure, but never let a notify-fn throw of its own
                ;; escape and kill the worker.
                (try (notify-fn :error item (assoc (get-queue-info !queue) :error e))
                     (catch Throwable _ nil)))
              (finally
                (remove-item! !queue (:id item)))))
          ;; Continue if more items
          (recur)))
      ;; Normal drain: queue emptied.
      (let [{:keys [notify-fn]} @!queue]
        (try (notify-fn :queue-empty nil (get-queue-info !queue))
             (catch Throwable _ nil)))
      (catch InterruptedException _ nil)
      (finally
        ;; ALWAYS clear so `ensure-worker!` can respawn the worker — this, plus
        ;; the per-item Throwable guard above, is the crux of the self-heal.
        (swap! !queue assoc :worker-future nil)))))

(defn- ensure-worker!
  "Start the worker future if not running. Also respawns when the previous
   worker future has died/completed (`future-done?`) — a bare `(nil? …)` guard
   would never restart a crashed worker, so any worker death (see the self-heal
   note on `run-processing-loop!`) would permanently wedge the queue."
  [!queue]
  (let [f (:worker-future @!queue)]
    (when (or (nil? f) (future-done? f))
      (swap! !queue assoc :worker-future (run-processing-loop! !queue)))))

(defn enqueue!
  "Add an input to the queue. Returns {:id uuid :position N} on success,
   or {:error :queue-full} if the queue has reached max-size.

   `opts` (3-arity, default nil) is stored on the item and forwarded to
   process-fn as an optional 2nd arg when non-nil — used to tag auto-asks
   (e.g. {:source :wakeup}). 2-arity callers are unchanged (opts nil)."
  ([!queue input] (enqueue! !queue input nil))
  ([!queue input opts]
   (let [{:keys [items max-size]} @!queue
         queued-count (count (filter #(= :queued (:status %)) items))]
     (if (>= queued-count max-size)
       {:error :queue-full}
       (let [id   (UUID/randomUUID)
             item {:id id :input input :status :queued :queued-at (Instant/now)
                   :opts opts}]
         (swap! !queue update :items conj item)
         (let [{:keys [notify-fn]} @!queue
               info (get-queue-info !queue)]
           (notify-fn :enqueued item info)
           (ensure-worker! !queue)
           {:id id :position (:queue-length info)}))))))

(defn cancel-item!
  "Cancel a queued item by ID. Only removes :queued items (not :processing).
   Returns true if item was found and removed."
  [!queue item-id]
  (let [removed? (atom false)]
    (swap! !queue
           (fn [q]
             (let [items (:items q)
                   match (some #(when (and (= item-id (:id %)) (= :queued (:status %))) %) items)]
               (if match
                 (do (reset! removed? true)
                     (update q :items (fn [its] (vec (remove #(= item-id (:id %)) its)))))
                 q))))
    (when @removed?
      (let [{:keys [notify-fn]} @!queue]
        (notify-fn :cancelled {:id item-id} (get-queue-info !queue))))
    @removed?))

(defn cancel-all-queued!
  "Remove all :queued items (not the currently :processing one).
   Returns the number of items removed."
  [!queue]
  (let [removed-count (atom 0)]
    (swap! !queue
           (fn [q]
             (let [items (:items q)
                   queued (filter #(= :queued (:status %)) items)
                   remaining (vec (remove #(= :queued (:status %)) items))]
               (reset! removed-count (count queued))
               (assoc q :items remaining))))
    (when (pos? @removed-count)
      (let [{:keys [notify-fn]} @!queue]
        (notify-fn :cancelled nil (get-queue-info !queue))))
    @removed-count))

(defn stop-queue!
  "Stop the queue — cancel worker future and clear all items."
  [!queue]
  (let [{:keys [worker-future]} @!queue]
    (when (and worker-future (not (future-cancelled? worker-future)))
      (future-cancel worker-future)))
  (swap! !queue assoc :items [] :processing-id nil :worker-future nil))
