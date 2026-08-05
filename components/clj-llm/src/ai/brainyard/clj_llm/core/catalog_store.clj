;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.catalog-store
  "Persist the refresh overlay under a cache root, and decide when it is stale.

   The root is INJECTED rather than resolved here. `clj-llm` sits below the
   agent component, so it cannot ask `core.config` where `~/.brainyard` is
   without inverting the dependency — the same reason `persist/set-root!`
   exists and is called by the app at startup. Until a root is set, every
   operation here is a no-op and the catalog is simply the baked one.

   One file per provider (`<root>/<provider>.edn`) rather than one combined
   file, for the same reason the project registry keys per slug: two `by`
   processes refreshing different providers never write the same file, and a
   single corrupt file costs one provider's overlay instead of all of them.

   Staleness is per provider, because their rates of change differ by orders
   of magnitude. A local Ollama server changes whenever the user runs
   `ollama pull`; a cloud provider's roster changes every few weeks. That is
   derived from the provider rather than configured separately — a second
   knob describing what the first already implies would only drift."
  (:require [ai.brainyard.clj-llm.core.catalog :as catalog]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.time Duration Instant]))

(defonce ^:private !cache-root (atom nil))

(defn set-cache-root!
  "Install the directory overlay files live in. Called once at startup by the
   app, which owns path policy."
  [path]
  (reset! !cache-root (when-not (str/blank? (str path)) (str path))))

(defn cache-root [] @!cache-root)

(def ^:private local-providers
  "Providers served from the user's own machine, where the model set is
   whatever they installed and can change at any moment."
  #{:ollama :apple-fm :free-llm})

(defn ttl-hours-for
  "Effective TTL for `provider`, derived from `base-ttl-hours`.

   A local server is polled far more eagerly — the round trip is a millisecond
   over loopback and costs nothing, and a baked list of someone else's models
   is exactly the thing that makes a local provider's catalog wrong."
  [provider base-ttl-hours]
  (if (contains? local-providers provider)
    (min 1 (or base-ttl-hours 24))
    (or base-ttl-hours 24)))

(defn- provider-file ^File [provider]
  (when-let [root (cache-root)]
    (io/file root (str (name provider) ".edn"))))

(defn- read-entry
  "Read one provider's overlay file. Returns nil when absent, unreadable, or
   written by a newer schema — a cache is disposable, so anything surprising
   is discarded rather than repaired."
  [provider]
  (when-let [^File f (provider-file provider)]
    (when (.isFile f)
      (try
        (let [m (edn/read-string (slurp f))]
          (when (and (map? m) (= catalog/schema-version (:schema-version m)))
            (-> m
                (update :models set)
                (dissoc :schema-version))))
        (catch Exception e
          (mulog/log ::catalog-cache-unreadable :provider provider
                     :error (.getMessage e))
          nil)))))

(defn save-entry!
  "Persist one provider's overlay entry. Returns the entry, or nil when no
   cache root is installed."
  [provider entry]
  (when-let [^File f (provider-file provider)]
    (try
      (.mkdirs (.getParentFile f))
      (spit f (pr-str (assoc entry
                             :schema-version catalog/schema-version
                             :provider provider
                             :models (vec (sort (:models entry))))))
      entry
      (catch Exception e
        (mulog/log ::catalog-cache-write-failed :provider provider
                   :error (.getMessage e))
        nil))))

(defn load-overlay!
  "Load every cached provider entry and install it as the overlay.

   Called at startup. Never throws and never blocks on the network: this is a
   handful of small file reads, and if the directory is absent — the common
   case on first run — the overlay stays empty and the catalog is the baked
   one."
  []
  (if-let [root (cache-root)]
    (let [files (or (.listFiles (io/file root)) (make-array File 0))
          entries (into {}
                        (keep (fn [^File f]
                                (let [n (.getName f)]
                                  (when (str/ends-with? n ".edn")
                                    (let [provider (keyword (subs n 0 (- (count n) 4)))]
                                      (when-let [e (read-entry provider)]
                                        [provider e]))))))
                        files)]
      (catalog/set-overlay! entries)
      (mulog/log ::catalog-overlay-loaded :providers (vec (sort (keys entries))))
      (catalog/overlay))
    {}))

(defn- age-hours [entry]
  (try
    (when-let [t (:fetched-at entry)]
      (/ (.toMinutes (Duration/between (Instant/parse (str t)) (Instant/now))) 60.0))
    (catch Exception _ nil)))

(defn stale?
  "True when `provider`'s overlay entry is missing or older than its TTL.

   A missing entry counts as stale, which is what makes the very first refresh
   happen; an unparseable timestamp also counts as stale rather than being
   treated as fresh forever."
  [provider base-ttl-hours]
  (let [entry (get (catalog/overlay) provider)]
    (if-not entry
      true
      (let [age (age-hours entry)]
        (or (nil? age)
            (>= age (ttl-hours-for provider base-ttl-hours)))))))
