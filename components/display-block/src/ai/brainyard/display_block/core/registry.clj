;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.display-block.core.registry
  "Default in-process block registry: id -> BlockProvider.

   Process-global by design — the rendering surface (TUI) needs to resolve
   ids it finds in scrollback to providers, which can be created by any
   producer in any namespace. Tests should call `clear!` between cases."
  (:require [ai.brainyard.display-block.interface.protocol :as p]))

(defonce ^:private !blocks (atom {}))

(defn- ^:private extract-id
  "Pull :id out of a provider's metadata."
  [provider]
  (or (:id (p/-meta provider))
      (throw (ex-info "BlockProvider -meta missing :id"
                      {:provider provider}))))

(defrecord AtomRegistry [!state]
  p/IBlockRegistry
  (-register! [_ provider]
    (let [id (extract-id provider)]
      (swap! !state assoc id provider)
      id))
  (-get [_ id]
    (get @!state id))
  (-unregister [_ id]
    (swap! !state dissoc id)
    nil)
  (-all [_] @!state)
  (-clear! [_]
    (reset! !state {})
    nil))

(def default-registry
  "Process-wide singleton used by the convenience fns in
   `display-block.interface`. Custom registries can be created and used
   directly via the `IBlockRegistry` protocol when isolation is needed
   (tests, parallel sessions)."
  (->AtomRegistry !blocks))

(defn register!
  ([provider]            (p/-register! default-registry provider))
  ([registry provider]   (p/-register! registry provider)))

(defn get-block
  ([id]                  (p/-get default-registry id))
  ([registry id]         (p/-get registry id)))

(defn unregister!
  ([id]                  (p/-unregister default-registry id))
  ([registry id]         (p/-unregister registry id)))

(defn dispose!
  "Look up `id`, call provider's -dispose!, then drop the registry entry.
   Returns true if a block was disposed, false if the id was not found."
  ([id] (dispose! default-registry id))
  ([registry id]
   (if-let [provider (p/-get registry id)]
     (do (try (p/-dispose! provider) (catch Exception _))
         (p/-unregister registry id)
         true)
     false)))

(defn all
  ([] (p/-all default-registry))
  ([registry] (p/-all registry)))

(defn clear!
  ([] (p/-clear! default-registry))
  ([registry] (p/-clear! registry)))
