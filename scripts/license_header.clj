;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns license-header
  "License header management for Brainyard sources.

  Scans the workspace for in-scope source files (.clj / .cljc / .cljs / .bb)
  and either verifies (`check`) or installs (`add`) the project's standard
  MIT license header. The SPDX marker line acts as the detection
  sentinel, so re-running modifies nothing already conformant — both
  entry points are safe to invoke repeatedly.

  A one-time `migrate` entry point rewrites the legacy Apache-2.0 header
  block in place to the current MIT header.

  Invoked by `bb license:check` and `bb license:add` (see bb.edn)."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def header
  ";; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.")

(def sentinel
  ";; SPDX-License-Identifier: MIT")

(def legacy-apache-header
  "The exact 5-line Apache-2.0 header block this project shipped before the
  MIT relicense. `migrate` strips this block (and one trailing blank line)
  before prepending the current header."
  ";; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the \"License\"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.")

(def source-extensions
  #{"clj" "cljc" "cljs" "bb"})

(def excluded-dir-names
  "Path segments that disqualify a file from scanning. Matched against
  every segment of the path, so a hit at any depth excludes the file.
  `.brainyard` is project-local agent runtime state (plan-agent,
  todo-agent, skills storage) — out of scope for license tooling."
  #{".git" ".cpcache" ".lsp" ".clj-kondo" "target" "node_modules"
    "out" ".shadow-cljs" ".brainyard"})

(defn- excluded?
  [^java.io.File f]
  (->> (str/split (.getPath f) #"/")
       (some excluded-dir-names)
       boolean))

(defn- source-file?
  [^java.io.File f]
  (and (.isFile f)
       (let [n   (.getName f)
             dot (.lastIndexOf n ".")]
         (and (pos? dot)
              (contains? source-extensions (subs n (inc dot)))))))

(defn find-source-files
  ([] (find-source-files "."))
  ([root]
   (->> (file-seq (io/file root))
        (remove excluded?)
        (filter source-file?)
        (sort-by #(.getPath ^java.io.File %)))))

(defn header-present?
  "True if the SPDX sentinel appears in the first 15 lines of f."
  [^java.io.File f]
  (with-open [r (io/reader f)]
    (->> (line-seq r)
         (take 15)
         (some #(= sentinel %))
         boolean)))

(defn- insert-header
  "Prepend the standard header to content, respecting shebangs. A
  leading `#!` line stays on line 1; the header block follows it."
  [^String content]
  (if (str/starts-with? content "#!")
    (let [nl              (.indexOf content "\n")
          [shebang remain] (if (neg? nl)
                             [content ""]
                             [(subs content 0 nl) (subs content (inc nl))])]
      (str shebang "\n\n" header "\n\n" remain))
    (str header "\n\n" content)))

(defn add-header!
  [^java.io.File f]
  (spit f (insert-header (slurp f))))

;; ── public entry points ──────────────────────────────────────────────

(defn check
  "Return 0 if every in-scope file carries the header, 1 otherwise.
  Prints offenders to stdout. CI-friendly."
  [_args]
  (let [files   (find-source-files)
        missing (remove header-present? files)]
    (println (format "Scanned %d in-scope files." (count files)))
    (if (empty? missing)
      (do (println "All files carry the license header.")
          0)
      (do (println (format "Missing header in %d file(s):" (count missing)))
          (doseq [f missing]
            (println (str "  " (.getPath ^java.io.File f))))
          1))))

(defn add
  "Prepend the header to any in-scope file missing it. Returns 0.
  Pass --dry-run to list affected files without writing."
  [args]
  (let [dry?    (contains? (set args) "--dry-run")
        files   (find-source-files)
        missing (remove header-present? files)]
    (println (format "Scanned %d in-scope files; %d need a header."
                     (count files) (count missing)))
    (if (empty? missing)
      (println "Nothing to do.")
      (doseq [f missing]
        (let [path (.getPath ^java.io.File f)]
          (if dry?
            (println (str "  WOULD " path))
            (do (add-header! f)
                (println (str "  ADD   " path)))))))
    (when dry? (println "Dry run — no files written."))
    0))

(defn- migrate-content
  "Replace a leading legacy Apache header block (optionally preceded by a
  shebang) with the current MIT header. Returns nil if the file carries no
  legacy block, so callers can skip untouched files."
  [^String content]
  (let [[shebang body] (if (str/starts-with? content "#!")
                         (let [nl (.indexOf content "\n")]
                           (if (neg? nl)
                             [content ""]
                             [(subs content 0 nl) (subs content (inc nl))]))
                         [nil content])
        body* (str/triml body)]
    (when (str/starts-with? body* legacy-apache-header)
      (let [remain (-> body*
                       (subs (count legacy-apache-header))
                       str/triml)]
        (if shebang
          (str shebang "\n\n" header "\n\n" remain)
          (str header "\n\n" remain))))))

(defn migrate
  "One-time relicense migration: rewrite the legacy Apache-2.0 header block
  to the current MIT header. Files without the legacy block are left alone.
  Pass --dry-run to list affected files without writing. Returns 0."
  [args]
  (let [dry?    (contains? (set args) "--dry-run")
        files   (find-source-files)
        changes (keep (fn [f]
                        (when-let [c (migrate-content (slurp f))]
                          [f c]))
                      files)]
    (println (format "Scanned %d in-scope files; %d carry the legacy header."
                     (count files) (count changes)))
    (doseq [[^java.io.File f c] changes]
      (let [path (.getPath f)]
        (if dry?
          (println (str "  WOULD " path))
          (do (spit f c)
              (println (str "  MIGRATE " path))))))
    (when dry? (println "Dry run — no files written."))
    0))
