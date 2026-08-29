;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.behavior-tree.core.nodes-task
  "SPIKE — the BT tick as an effect (docs/design/functional-effect-system.md §15).

   Every node type from `core.nodes`, translated. The translation is
   mechanical: wrap the body in `m/sp`, `m/?` each recursive tick. Nothing else
   about a node changes — same dispatch, same statuses, same `:children`.

   This ns exists ALONGSIDE `core.nodes`, implementing `p/tick-task` while
   `p/tick` stays exactly as it was. Both dispatch on the same built tree, so
   the spike's test runs identical trees through both engines and compares. A
   translation that merely compiles proves nothing; one that agrees with the
   synchronous engine on every tree in the existing suite proves something.

   Two things it is trying to answer, which reasoning could not settle:

     1. Does `:running` survive? Under effects, 'the node is still going' and
        'the Task has not settled yet' are different ideas, and conflating them
        would be a silent semantic change.
     2. Does the Q4 binding hazard really reduce to one site? See `leaf-task`."
  (:require [ai.brainyard.behavior-tree.interface.protocol :as p]
            [ai.brainyard.effect.interface :as fx]
            [missionary.core :as m]))

;; ============================================================================
;; The leaf seam — where Q4 is answered
;; ============================================================================

(defn- leaf-task
  "Run `thunk` as a Task, through the context's `:leaf-wrap` if it supplies one.

   This is the whole answer to Q4 (a dynamic var reverts at the first park).
   The run path reads `*current-agent*` in 123 places; auditing them would be
   the project. Unnecessary, because a leaf can re-establish the binding and
   every leaf goes through here — one site, not 123.

   `:leaf-wrap` rather than binding `*current-agent*` directly: `behavior-tree`
   sits BELOW `agent` and cannot see `agent.core.protocol`. Inverting that
   dependency to grab one var would be far worse than letting the caller pass
   `(fn [thunk] (binding [proto/*current-agent* agent] (thunk)))`. The engine
   stays agnostic about what a leaf needs in scope.

   `fx/task-of`, so the leaf runs on `m/blk` — leaves block (LLM calls, tool
   dispatch, code eval) and must not occupy the scheduler thread."
  [context thunk]
  (let [wrap (or (:leaf-wrap context) (fn [t] (t)))]
    (fx/task-of #(wrap thunk))))

(defn- lift
  "A leaf may return a status keyword (today's contract) or a Task (once it has
   been converted). Accept both, so leaves migrate one at a time instead of in
   a flag day — the same coexistence that carried Phases 1–3.

   A missionary Task is a fn of two callbacks, so `fn?` discriminates. Statuses
   are keywords, and no node returns a bare function for any other reason."
  [r]
  (if (fn? r) r (fx/success r)))

;; ============================================================================
;; Sequence
;; ============================================================================

(defmethod p/tick-task :sequence
  [node context]
  (m/sp
   (loop [[child-node :as children] (:children node)]
     (if-not child-node
       p/success
       (case (m/? (p/tick-task child-node context))
         :success (recur (rest children))
         :failure p/failure
         :running p/running)))))

;; ============================================================================
;; Fallback
;; ============================================================================

(defmethod p/tick-task :fallback
  [node context]
  (m/sp
   (loop [[child-node :as children] (:children node)]
     (if-not child-node
       p/failure
       (case (m/? (p/tick-task child-node context))
         :success p/success
         :failure (recur (rest children))
         :running p/running)))))

;; ============================================================================
;; Parallel
;; ============================================================================

(defmethod p/tick-task :parallel
  [{:keys [success-threshold children] :as _node} context]
  ;; The synchronous version is `(mapv #(future (p/tick % ctx)) children)` then
  ;; `(mapv deref …)`: one unbounded future per child, no canceller for the
  ;; fan-out, and a child that THROWS leaves its siblings running to
  ;; completion. `fx/all` (m/join) fixes all three.
  ;;
  ;; Note precisely what does and does not change. A child returning `:failure`
  ;; is a VALUE, not an error, so siblings are not cancelled by it and the
  ;; threshold arithmetic below is untouched — semantics preserved. What the
  ;; join adds is error propagation and a single canceller.
  (m/sp
   (let [threshold (or success-threshold (count children))
         results   (m/? (fx/all (mapv #(p/tick-task % context) children)))
         successes (count (filter #(= % p/success) results))
         failures  (count (filter #(= % p/failure) results))]
     (cond
       (>= successes threshold) p/success
       (> failures (- (count children) threshold)) p/failure
       :else p/running))))

;; ============================================================================
;; Repeat decorator
;; ============================================================================

(defmethod p/tick-task :repeat
  [{:keys [max-n condition-fn child]
    :or {max-n 5 condition-fn (fn [_] true)} :as _node}
   context]
  (m/sp
   (let [max-n (if (fn? max-n) (max-n context) max-n)]
     (if child
       (loop [n 0]
         (if (< n max-n)
           (let [child-result (m/? (p/tick-task child context))]
             (condp = child-result
               p/success (if (condition-fn context)
                           p/success
                           (recur (inc n)))
               p/failure p/failure
               (throw (ex-info "unknown child-result" {:child-result child-result}))))
           p/success))
       p/success))))

;; ============================================================================
;; Condition
;; ============================================================================

(defmethod p/tick-task :condition
  [{:keys [condition-fn opts] :as _node} context]
  (m/sp
   (if (m/? (leaf-task context #(condition-fn (assoc context :opts opts))))
     p/success
     p/failure)))

;; ============================================================================
;; Action
;; ============================================================================

(defmethod p/tick-task :action
  [{:keys [action-fn opts] :as _node} context]
  (m/sp
   (m/? (lift (m/? (leaf-task context #(action-fn (assoc context :opts opts))))))))

(defmethod p/tick-task :default
  [node _context]
  (fx/failure (ex-info "Node type not implemented (task engine)" {:node node})))
