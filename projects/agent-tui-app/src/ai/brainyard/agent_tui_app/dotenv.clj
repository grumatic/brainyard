;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-app.dotenv
  "Dotenv loader for the `by` native binary.

   `bb` tasks already `set -a && source .env` before launching the JVM, but
   the native `by` binary bypasses that wrapper — so users who keep API keys
   in a project-local `.env` see them in `bb tui …` and NOT in `by`. This ns
   bridges the gap by writing parsed keys to JVM System Properties.

   Callers that read API keys (`env-detect` provider detection, `clj-llm`
   provider config) check `(or (System/getenv k) (System/getProperty k))` so
   a property-backed key is indistinguishable from a real env var.

   Resolution: cwd/.env → each parent → ~/.brainyard/.env. First hit per key
   wins, and an existing env var always takes precedence."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- candidate-paths []
  (let [cwd  (System/getProperty "user.dir")
        home (System/getProperty "user.home")]
    (->> (concat (loop [d (io/file cwd) acc []]
                   (if (nil? d)
                     acc
                     (recur (.getParentFile d) (conj acc (io/file d ".env")))))
                 [(io/file home ".brainyard" ".env")])
         distinct)))

(defn- parse-line [^String line]
  (let [trimmed (str/trim line)]
    (when (and (not (str/blank? trimmed))
               (not (str/starts-with? trimmed "#")))
      (let [eq (.indexOf trimmed (int \=))]
        (when (pos? eq)
          (let [k (str/trim (subs trimmed 0 eq))
                v (str/trim (subs trimmed (inc eq)))
                v (cond
                    (and (>= (count v) 2)
                         (str/starts-with? v "\"")
                         (str/ends-with? v "\""))
                    (subs v 1 (dec (count v)))

                    (and (>= (count v) 2)
                         (str/starts-with? v "'")
                         (str/ends-with? v "'"))
                    (subs v 1 (dec (count v)))

                    :else v)]
            (when (seq k) [k v])))))))

(defn- parse-file [^java.io.File f]
  (when (.exists f)
    (try
      (into {} (keep parse-line (str/split-lines (slurp f))))
      (catch Exception _ {}))))

(defn load-from-dotenv!
  "Scan `.env` candidate paths and merge into JVM System Properties. Real env
   vars are never overridden. Returns {:paths [{:path :keys [str]}]
   :loaded-count int}."
  []
  (let [paths  (candidate-paths)
        merged (atom {})
        loaded (atom [])]
    (doseq [^java.io.File f paths]
      (when-let [m (parse-file f)]
        (let [new-keys (remove (fn [[k _]]
                                 (or (contains? @merged k)
                                     (System/getenv k)))
                               m)]
          (when (seq new-keys)
            (swap! merged into new-keys)
            (swap! loaded conj {:path (.getAbsolutePath f)
                                :keys (mapv first new-keys)})))))
    (doseq [[k v] @merged]
      (System/setProperty k v))
    {:paths        @loaded
     :loaded-count (count @merged)}))
