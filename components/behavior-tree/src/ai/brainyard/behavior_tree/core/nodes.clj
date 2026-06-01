;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.behavior-tree.core.nodes
  (:require [ai.brainyard.behavior-tree.interface.protocol :as p :refer [opts+children]]))

(defmethod p/tick :default
  [node _context]
  (throw (ex-info "Node type not implemented" {:node node})))

;;
;; Sequence
;;

(defmethod p/tick :sequence
  [node context]
  (loop [[child-node :as children] (:children node)]
    (if-not child-node
      p/success
      (let [result (p/tick child-node context)]
        (case result
          :success (recur (rest children))
          :failure p/failure
          :running p/running)))))

(defmethod p/build :sequence
  [node-type args]
  (let [[opts children] (opts+children args)]
    (assoc opts
           :type node-type
           :children (mapv #(p/build (first %) (rest %)) children))))

;;
;; Fallback
;;

(defmethod p/tick :fallback
  [node context]
  (loop [[child-node :as children] (:children node)]
    (if-not child-node
      p/failure
      (let [result (p/tick child-node context)]
        (case result
          :success p/success
          :failure (recur (rest children))
          :running p/running)))))

(defmethod p/build :fallback
  [node-type args]
  (let [[opts children] (opts+children args)]
    (assoc opts
           :type node-type
           :children (mapv #(p/build (first %) (rest %)) children))))

;;
;; Parallel
;;

(defmethod p/tick :parallel
  [{:keys [success-threshold children] :as _node} context]
  (let [success-threshold (or success-threshold (count children))
        futures (mapv #(future (p/tick % context)) children)
        results (mapv deref futures)
        success-count (count (filter #(= % p/success) results))
        failure-count (count (filter #(= % p/failure) results))]
    (cond
      (>= success-count success-threshold) p/success
      (> failure-count (- (count children) success-threshold)) p/failure
      :else p/running)))

(defmethod p/build :parallel
  [node-type args]
  (let [[opts children] (opts+children args)]
    (assoc opts
           :type node-type
           :children (mapv #(p/build (first %) (rest %)) children))))

;;
;; Repeat decorator
;;

(defmethod p/tick :repeat
  [{:keys [max-n condition-fn child]
    :or {max-n 5 condition-fn (fn [_] true)} :as _node}
   context]
  (let [max-n (if (fn? max-n) (max-n context) max-n)]
    (if child
      (loop [n 0]
        (if (< n max-n)
        (let [child-result (p/tick child context)]
          (condp = child-result
            p/success (if (condition-fn context)
                        p/success
                        (recur (inc n)))
            p/failure p/failure
            (throw (ex-info "unknown child-result" {:child-result child-result}))))
        p/success))
      p/success)))

(defmethod p/build :repeat
  [node-type args]
  (let [[opts children] (opts+children args)]
    (when (not= (count children) 1)
      (throw (ex-info "only one child node is allowed for repeat decorator" {:children children})))
    (assoc opts
           :type node-type
           :child (let [child (first children)]
                    (p/build (first child) (rest child))))))

;;
;; Condition
;;

(defmethod p/tick :condition
  [{:keys [condition-fn opts] :as _node} context]
  (if (condition-fn (assoc context :opts opts))
    p/success
    p/failure))

(defmethod p/build :condition
  [node-type args]
  (let [[opts children] (opts+children args)]
    {:type node-type
     :opts opts
     :condition-fn (first children)}))

;;
;; Action
;;

(defmethod p/tick :action
  [{:keys [action-fn opts] :as _node} context]
  (action-fn (assoc context :opts opts)))

(defmethod p/build :action
  [node-type args]
  (let [[opts children] (opts+children args)]
    {:type node-type
     :opts opts
     :action-fn (first children)}))
