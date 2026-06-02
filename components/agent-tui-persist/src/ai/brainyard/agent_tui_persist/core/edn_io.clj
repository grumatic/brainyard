;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-persist.core.edn-io
  "Atomic and append-only EDN I/O helpers.

   - `atomic-write!` writes to a sibling .tmp file and renames into place; a
     crash mid-write leaves the previous on-disk value intact.
   - `append-line!` appends one EDN form per line; readers can stream the file.
   - `read-edn` parses a single value, returning a fallback when the file is
     missing or empty."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io File PushbackReader PrintWriter FileOutputStream OutputStreamWriter]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]
           [java.nio.file CopyOption]))

(defn read-edn
  "Read a single EDN form from `f`. Returns `not-found` (default nil) when the
   file is missing or empty."
  ([f] (read-edn f nil))
  ([f not-found]
   (let [^File file (io/file f)]
     (if (and (.exists file) (pos? (.length file)))
       (with-open [r (PushbackReader. (io/reader file))]
         (edn/read {:eof not-found :readers *data-readers*} r))
       not-found))))

(defn atomic-write!
  "Serialise `value` to EDN and atomically replace `target`. Uses a sibling
   .tmp file so a partial write never overwrites the previous good value.

   Options:
     :prn-print? — when true, use prn (default true)."
  ([target value] (atomic-write! target value {}))
  ([target value _opts]
   (let [^File target (io/file target)
         parent       (.getParentFile target)
         _            (when (and parent (not (.exists parent))) (.mkdirs parent))
         tmp          (File/createTempFile (str (.getName target) ".") ".tmp" parent)]
     (try
       (with-open [w (-> (FileOutputStream. tmp)
                         (OutputStreamWriter. StandardCharsets/UTF_8)
                         (PrintWriter. true))]
         (binding [*out* w] (prn value)))
       (Files/move (.toPath tmp) (.toPath target)
                   (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                           StandardCopyOption/REPLACE_EXISTING]))
       target
       (catch Throwable t
         (.delete tmp)
         (throw t))))))

(defn append-line!
  "Append `value` (rendered with prn) as a single line to `target`. Creates the
   file if missing. Each line is one EDN form, suitable for streaming reads.
   Returns the size of the file after the append."
  [target value]
  (let [^File target (io/file target)
        parent       (.getParentFile target)
        _            (when (and parent (not (.exists parent))) (.mkdirs parent))]
    (with-open [w (-> (FileOutputStream. target true)
                      (OutputStreamWriter. StandardCharsets/UTF_8)
                      (PrintWriter. true))]
      (binding [*out* w] (prn value)))
    (.length target)))

(defn read-lines
  "Lazily read EDN forms from `f`, one per non-blank line. Materialise with
   `doall` if you intend to consume past the reader's lifetime."
  [f]
  (let [^File file (io/file f)]
    (when (.exists file)
      (with-open [r (io/reader file)]
        (doall
         (for [line (line-seq r)
               :let  [trimmed (clojure.string/trim line)]
               :when (seq trimmed)]
           (edn/read-string {:readers *data-readers*} trimmed)))))))
