;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-app.dotenv
  "Dotenv loader for the `by` native binary.

   `bb` tasks already `set -a && source .env` before launching the JVM, but
   the native `by` binary bypasses that wrapper — so users who keep API keys
   in a project-local `.env` see them in `bb tui …` and NOT in `by`. This ns
   bridges the gap by writing parsed keys to JVM System Properties.

   Callers read keys through `util/resolve-var`, which checks the environment
   and then this property table, so a property-backed key is indistinguishable
   from a real env var. (Before Phase 0 of
   docs/design/environment-scoping-design.md each layer had its own copy of
   that lookup and several readers had neither.)

   Resolution: `BY_ENV_FILE` if it names a readable file, else cwd/.env → each
   parent → ~/.brainyard/.env. First hit per key wins, and an existing env var
   always takes precedence. `BY_NO_DOTENV` skips the whole thing.

   **Both control flags are read from the environment (or a `-D` property),
   never from a `.env`** — which is what keeps them non-circular: the property
   table is written only at the very end of `load-from-dotenv!`, so a `.env`
   cannot switch off its own loader. They were wrapper-only knobs until Phase 1;
   `BY_NO_DOTENV=1` demonstrably did not suppress this loader, which is the
   sort of flag people set once and then trust."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.util.interface :as util]))

(defn- explicit-env-file
  "The file named by `BY_ENV_FILE`, when it names one that exists.

   Set-but-missing falls through to the walk rather than failing, matching
   `by-wrapper.sh`'s `[ -n \"$BY_ENV_FILE\" ] && [ -f \"$BY_ENV_FILE\" ]` —
   but the caller is told, because a typo'd path that silently loads a
   different file is worse than one that loads nothing."
  []
  (when-let [p (util/resolve-var "BY_ENV_FILE")]
    (let [f (io/file p)]
      (if (.isFile f) f {:missing p}))))

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
   vars are never overridden.

   Returns `{:paths [{:path :keys [str]}] :loaded-count int}`, plus
   `:skipped :by-no-dotenv` when `BY_NO_DOTENV` suppressed the load, and
   `:env-file-missing <path>` when `BY_ENV_FILE` named a file that is not
   there (the walk still ran)."
  []
  (if (util/resolve-var "BY_NO_DOTENV")
    {:paths [] :loaded-count 0 :skipped :by-no-dotenv}
    (let [explicit (explicit-env-file)
          missing  (:missing explicit)
          paths    (if (and explicit (not missing)) [explicit] (candidate-paths))
          merged (atom {})
          loaded (atom [])]
      (doseq [^java.io.File f paths]
        (when-let [m (parse-file f)]
          (let [new-keys (remove (fn [[k _]]
                                   (or (contains? @merged k)
                                       ;; a REAL env var wins; the property
                                       ;; table is what we are about to write
                                       (System/getenv k)))
                                 m)]
            (when (seq new-keys)
              (swap! merged into new-keys)
              (swap! loaded conj {:path (.getAbsolutePath f)
                                  :keys (mapv first new-keys)})))))
      (doseq [[k v] @merged]
        (System/setProperty k v))
      (cond-> {:paths        @loaded
               :loaded-count (count @merged)}
        missing (assoc :env-file-missing missing)))))
