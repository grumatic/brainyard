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

   Resolution: `BY_ENV_FILE` if it names a readable file, else — at each level
   walking up from cwd — `<dir>/.brainyard/.env` then `<dir>/.env`, and finally
   `~/.brainyard/.env`. First hit per key wins, and an existing env var always
   takes precedence. `BY_NO_DOTENV` skips the whole thing.

   `.brainyard/.env` is where brainyard's own project-scoped credentials belong,
   beside the `config.edn`, `sessions/` and `agents/` that already live there;
   it was the one project-scoped thing with no home under `.brainyard/`. See
   docs/design/env-files-design.md.

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

(defn candidate-paths
  "Every `.env` this process should consider, most specific first.

   At each level of the walk `<dir>/.brainyard/.env` is offered BEFORE
   `<dir>/.env`, and the ordering is forced rather than aesthetic: the
   `.brainyard/` file is the one `by env set` writes, so a project that already
   has an application `.env` carrying the same name would otherwise shadow every
   write made through the tool — silently, with nothing to see.

   Walking for `.brainyard/.env` rather than resolving the project root is what
   keeps this correct at a moment when the project root is not yet known: this
   runs in `-dispatch`, before `install-working-dir!` and before any config
   loads. The git root is one of the ancestors, so the walk finds it anyway.
   `BY_PROJECT_DIR` is honored first when set — as a LEVEL, both of its files
   in the same order, because that is someone naming the root explicitly and a
   root that contributed only one of its two files would be a third rule.

   `~/.brainyard/.env` stays last. It is usually also produced by the walk (home
   is commonly an ancestor of cwd); `distinct` collapses the pair."
  []
  (let [cwd  (System/getProperty "user.dir")
        home (System/getProperty "user.home")
        ;; env only, in practice — the property table is written at the END of
        ;; `load-from-dotenv!`, so a `.env` cannot name its own project root.
        proj (util/resolve-var "BY_PROJECT_DIR")]
    (->> (concat (when proj [(io/file proj ".brainyard" ".env")
                             (io/file proj ".env")])
                 (loop [d (io/file cwd) acc []]
                   (if (nil? d)
                     acc
                     (recur (.getParentFile d)
                            (conj acc
                                  (io/file d ".brainyard" ".env")
                                  (io/file d ".env")))))
                 [(io/file home ".brainyard" ".env")])
         distinct)))

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
        (when-let [m (util/parse-env-file f)]
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
      ;; Tell the resolver WHICH properties are environment values. It cannot
      ;; work that out — the property table also holds ~60 standard JVM entries
      ;; — and `util/child-env` needs the answer to hand a spawned child the
      ;; `.env` layer it would otherwise never see.
      (util/register-dotenv-keys! (keys @merged))
      (cond-> {:paths        @loaded
               :loaded-count (count @merged)}
        missing (assoc :env-file-missing missing)))))
