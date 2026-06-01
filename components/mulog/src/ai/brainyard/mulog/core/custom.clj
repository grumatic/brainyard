;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.mulog.core.custom
  "Helpers for creating custom μ/log publishers."
  (:require [com.brunobonacci.mulog.buffer :as rb]))

(defn agent-buffer
  "Create an agent-buffer for a custom publisher.
   Capacity should be sized based on event flow rate."
  ([] (agent-buffer 10000))
  ([capacity] (rb/agent-buffer capacity)))

(defn items
  "Get items from a ring buffer as a sequence of [offset value] pairs."
  [buffer]
  (rb/items buffer))

(defn clear
  "Clear all items from the buffer."
  [buffer]
  (rb/clear buffer))

(defn dequeue
  "Remove items up to and including the given offset."
  [buffer offset]
  (rb/dequeue buffer offset))

(defn simple-publisher
  "Create a simple custom publisher from a publish function.

   Args:
   - publish-fn: Function that receives a seq of events
   - opts: {:delay 500 :buffer-size 10000}

   Returns a publisher config map for start-publisher!"
  [publish-fn & {:keys [_delay _buffer-size]
                 :or {_delay 500 _buffer-size 10000}}]
  {:type :inline
   :publisher publish-fn})
