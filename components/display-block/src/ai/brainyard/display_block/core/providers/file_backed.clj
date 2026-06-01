;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.display-block.core.providers.file-backed
  "Provider that stores full content in a temp file under
   /tmp/{working-dir}/{class}/{id}.txt.

   Generalised over the older ad-hoc implementation that used to live
   in `clj-sandbox.core.truncation` and `agent-tui.display-block-ui`.

   Use `make` to construct one; the factory writes the temp file, then
   returns a provider record that satisfies `BlockProvider`."
  (:require [ai.brainyard.display-block.core.marker :as marker]
            [ai.brainyard.display-block.interface.protocol :as p]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

(def ^:dynamic *working-dir*
  "Working directory used to namespace temp files (e.g. so two brainyard
   workspaces don't collide in /tmp). Bind at the application entry point.
   Defaults to the JVM's `user.dir`."
  (System/getProperty "user.dir"))

(def ^:const default-max-expanded-lines
  "Cap applied by -expanded-lines when not overridden via :max-expanded-lines."
  200)

(defn- short-id []
  (subs (str (java.util.UUID/randomUUID)) 0 8))

(defn- ensure-dir [^String dir]
  (let [f (io/file dir)]
    (when-not (.exists f) (.mkdirs f))
    dir))

(defn- resolve-project-clj-sandbox-dir
  "Lazily resolve `<project>/.brainyard/temp/clj-sandbox/` via agent's interface
   when available. Returns nil on any failure so callers fall back."
  []
  (try
    (let [init-dirs!     (requiring-resolve 'ai.brainyard.agent.interface/init-dirs!)
          brainyard-sub! (requiring-resolve 'ai.brainyard.agent.interface/brainyard-subdir!)]
      (when (and init-dirs! brainyard-sub!)
        (brainyard-sub! (init-dirs!) "temp/clj-sandbox" :project)))
    (catch Throwable _ nil)))

(defn working-dir-subpath
  "Resolve a file-backed-provider cache path for `class`.
   Prefers `<project>/.brainyard/temp/clj-sandbox/file-backed/<class>` when
   the agent component is on classpath; falls back to the legacy
   `/tmp/<working-dir-mangled>/<class>` layout otherwise.

   Public so other code that knows the class can locate files
   (e.g. orphan scan)."
  [^String class]
  (if-let [base (resolve-project-clj-sandbox-dir)]
    (let [dir (str base "/file-backed/" class)]
      (ensure-dir dir)
      dir)
    (let [dir-name (-> (str *working-dir*)
                       (str/replace "/" "_")
                       (str/replace #"^_" ""))]
      (str "/tmp/" dir-name "/" class))))

(def ^:const default-hint-collapsed
  "Default marker hint shown when the block is in collapsed state."
  "Enter: expand, Ctrl-O: edit")

(def ^:const default-hint-expanded
  "Default marker hint shown when the block is in expanded state."
  "Enter: collapse, Ctrl-O: edit")

(defrecord FileBackedProvider [meta-map file-path]
  p/BlockProvider
  (-meta [_] meta-map)

  (-collapsed-marker-line [_]
    (let [{:keys [id hidden-lines hint-collapsed line-decorator]} meta-map
          summary  (str "+" (or hidden-lines 0) " lines")
          decorate (or line-decorator identity)
          hint     (or hint-collapsed default-hint-collapsed)]
      (decorate (marker/collapsed-line id summary :hint hint))))

  (-expanded-lines [_]
    (let [{:keys [id max-expanded-lines hint-expanded line-decorator
                  total-lines hidden-lines]} meta-map
          cap       (or max-expanded-lines default-max-expanded-lines)
          decorate  (or line-decorator identity)
          hint      (or hint-expanded default-hint-expanded)
          shown     (- (or total-lines 0) (or hidden-lines 0))
          file      (io/file ^String file-path)]
      (if (and file (.exists file))
        (let [content      (slurp file)
              lines        (str/split-lines content)
              hidden-tail  (drop shown lines)
              tail-count   (count hidden-tail)
              visible      (vec (take cap hidden-tail))
              overflow     (max 0 (- tail-count cap))
              trailer      (when (pos? overflow)
                             (str "  +" overflow " more lines in " file-path))
              tail-summary (if (pos? overflow)
                             (str (count visible) " of " tail-count " hidden lines")
                             (str tail-count " hidden lines"))
              expanded-marker (marker/expanded-line id tail-summary :hint hint)]
          (vec (concat (mapv decorate visible)
                       (when trailer [(decorate trailer)])
                       [(decorate expanded-marker)])))
        ;; File missing — surface a notice line that still parses as a
        ;; collapsed marker (so the user can see something is wrong but
        ;; the marker still survives a re-collapse).
        [(p/-collapsed-marker-line _)
         (decorate (str "(file missing for block " (:id meta-map) ": " file-path ")"))])))

  (-resource-path [_] file-path)

  (-dispose! [_]
    (try
      (let [f (io/file ^String file-path)]
        (when (.exists f) (.delete f)))
      (catch Exception _ nil))
    nil))

(defn make
  "Construct a FileBackedProvider for `content`.

   Writes the full content to /tmp/{working-dir}/{class-dir}/{id}.txt
   and returns a provider whose -collapsed-marker-line / -expanded-lines
   are derived from the file.

   Required:
     :class-dir          string, e.g. \"snippets\" — subdir name.

   Optional:
     :id                 supply your own id (else a random short id is generated)
     :class              semantic class keyword (e.g. :code) for theming hints
     :label              human label, e.g. \"Code\"
     :hint-collapsed     marker hint shown in collapsed state
                         (default: \"Enter: expand, Ctrl-O: edit\")
     :hint-expanded      marker hint shown in expanded state
                         (default: \"Enter: collapse, Ctrl-O: edit\")
     :line-decorator     fn `line -> styled-line` applied to every
                         line returned by `-expanded-lines` AND to the
                         collapsed marker line. Producers use it to
                         keep the box-drawing chrome / per-section
                         ANSI styling visually consistent across the
                         head (already in scrollback) and the tail
                         that gets spliced in on expand. Default: nil
                         (raw text).
     :max-expanded-lines cap on -expanded-lines tail (default 100)
     :total-lines        total line count of full content (else computed)
     :hidden-lines       lines hidden in collapsed form (else `total - max-expanded-lines`-ish; usually supplied by text-block)"
  [content {:keys [id class class-dir label hint-collapsed hint-expanded
                   line-decorator max-expanded-lines total-lines hidden-lines]}]
  (when-not class-dir
    (throw (ex-info "file-backed/make requires :class-dir" {})))
  (let [dir       (ensure-dir (working-dir-subpath class-dir))
        block-id  (or id (short-id))
        file      (io/file dir (str block-id ".txt"))
        _         (spit file (or content ""))
        lines-cnt (or total-lines (count (str/split-lines (str content))))
        meta-map  {:id                 block-id
                   :class              class
                   :class-dir          class-dir
                   :label              label
                   :hint-collapsed     hint-collapsed
                   :hint-expanded      hint-expanded
                   :line-decorator     line-decorator
                   :max-expanded-lines (or max-expanded-lines default-max-expanded-lines)
                   :total-lines        lines-cnt
                   :hidden-lines       (or hidden-lines 0)}]
    (->FileBackedProvider meta-map (.getPath ^File file))))

(defn from-existing-file
  "Wrap an already-written file as a FileBackedProvider. Used when the
   marker scanner discovers an orphan file (e.g. from a previous run)
   that has no live registry entry."
  [^String file-path {:keys [id class class-dir label hint-collapsed hint-expanded
                             line-decorator max-expanded-lines]}]
  (let [block-id (or id (short-id))
        f (io/file file-path)
        total (when (.exists f)
                (count (str/split-lines (slurp f))))
        meta-map {:id                 block-id
                  :class              class
                  :class-dir          class-dir
                  :label              label
                  :hint-collapsed     hint-collapsed
                  :hint-expanded      hint-expanded
                  :line-decorator     line-decorator
                  :max-expanded-lines (or max-expanded-lines default-max-expanded-lines)
                  :total-lines        (or total 0)
                  :hidden-lines       0}]
    (->FileBackedProvider meta-map file-path)))
