;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui-persist.core.scrollback
  "ANSI scrollback capture per pane, with size-bounded rotation.

   Per docs/tmux-based-agent-tui.md §11.3 — each session keeps two ANSI byte
   streams on disk: `scrollback.stream.txt` and `scrollback.activity.txt`.
   Writes are append-only.  When a stream exceeds `:max-bytes` it is rotated
   into numbered companions (`scrollback.stream.1.txt`, `.2.txt`, …) up to
   `:max-rotations`; the oldest is dropped.

   On replay, `tail-bytes` returns the last N bytes across rotated and live
   files, joined oldest→newest.  This is what `by-ui` re-feeds into the pane
   on attach to populate visible history."
  (:require [ai.brainyard.agent-tui-persist.core.paths :as paths]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File FileOutputStream]
           [java.nio.charset StandardCharsets]))

(def default-max-bytes
  "Default per-stream size cap before rotation kicks in (5 MiB)."
  (* 5 1024 1024))

(def default-max-rotations
  "Default number of rotated files to keep (.1.txt … .N.txt)."
  10)

(defn- stream-tag->filename [tag]
  (case tag
    :stream   "scrollback.stream.txt"
    :activity "scrollback.activity.txt"
    (throw (ex-info "Unknown scrollback stream" {:tag tag}))))

(defn- live-file
  ^File [session-id tag]
  (paths/session-file session-id (stream-tag->filename tag)))

(defn- rotated-file
  ^File [session-id tag n]
  (let [base   (stream-tag->filename tag)
        [stem ext] (let [dot (.lastIndexOf ^String base ".")]
                     [(subs base 0 dot) (subs base dot)])]
    (paths/session-file session-id (str stem "." n ext))))

(defn- rotate-once!
  "Shift .N → .N+1, evicting the oldest beyond `max-rotations`, then rename
   the live file to .1."
  [session-id tag max-rotations]
  (loop [n max-rotations]
    (if (zero? n)
      nil
      (let [src (rotated-file session-id tag n)]
        (when (.exists src)
          (if (= n max-rotations)
            (.delete src)
            (.renameTo src (rotated-file session-id tag (inc n)))))
        (recur (dec n)))))
  (let [^File live (live-file session-id tag)]
    (when (.exists live)
      (.renameTo live (rotated-file session-id tag 1)))))

(defn append!
  "Append `bytes-or-str` (String or byte[]) to the named scrollback stream.
   Rotates when the file would exceed `:max-bytes` (default `default-max-bytes`)
   to keep at most `:max-rotations` (default `default-max-rotations`) backups."
  ([session-id tag content] (append! session-id tag content {}))
  ([session-id tag content {:keys [max-bytes max-rotations]
                            :or {max-bytes default-max-bytes
                                 max-rotations default-max-rotations}}]
   (let [^File live (live-file session-id tag)
         payload (cond
                   (string? content) (.getBytes ^String content StandardCharsets/UTF_8)
                   (bytes? content)  content
                   :else (.getBytes (str content) StandardCharsets/UTF_8))
         current  (if (.exists live) (.length live) 0)]
     (when (and (pos? current) (> (+ current (alength ^bytes payload)) max-bytes))
       (rotate-once! session-id tag max-rotations))
     (with-open [out (FileOutputStream. live true)]
       (.write out ^bytes payload))
     (alength ^bytes payload))))

(defn- ordered-files
  "Return all rotation files for `tag`, oldest first (highest index → live)."
  [session-id tag]
  (let [files (concat
               (for [n (reverse (range 1 (inc default-max-rotations)))
                     :let [^File f (rotated-file session-id tag n)]
                     :when (.exists f)]
                 f)
               [(live-file session-id tag)])]
    (filter #(.exists ^File %) files)))

(defn read-all
  "Read the entire scrollback for `tag` (oldest first → newest), returning a
   single string.  Useful for replay after re-attach."
  [session-id tag]
  (let [files (ordered-files session-id tag)]
    (apply str (map slurp files))))

(defn tail-bytes
  "Return the last `n` bytes of the scrollback as a UTF-8 string, walking
   rotated files as needed.  Used to seed a freshly attached pane's history."
  [session-id tag n]
  (when (pos? n)
    (let [files (vec (ordered-files session-id tag))]
      (loop [i (dec (count files))
             remaining (long n)
             chunks (transient [])]
        (if (or (neg? i) (zero? remaining))
          (apply str (persistent! chunks))
          (let [^File f (nth files i)
                len (.length f)
                start (max 0 (- len remaining))
                read-bytes (- len start)
                buf (byte-array read-bytes)]
            (with-open [in (java.io.RandomAccessFile. f "r")]
              (.seek in start)
              (.readFully in buf))
            (recur (dec i)
                   (- remaining read-bytes)
                   (conj! chunks (String. buf StandardCharsets/UTF_8)))))))))

(defn truncate!
  "Delete every rotation + live file for `tag`."
  [session-id tag]
  (doseq [^File f (ordered-files session-id tag)]
    (.delete f)))

(defn total-bytes
  "Sum of bytes across all rotation files for `tag`."
  [session-id tag]
  (reduce + 0 (map #(.length ^File %) (ordered-files session-id tag))))

;; ============================================================================
;; One-time repair: split fused emits
;; ============================================================================

(defn repair-concat!
  "Heuristically split fused emits in `(session-id, tag)`'s scrollback
   stream files. Earlier versions of `tee-scrollback!` appended each
   emit's bytes verbatim; when an emit lacked a trailing `\\n`, the
   next emit's bytes were concatenated on the same disk line and would
   re-fuse into `!scrollback` on every resume.

   Walks all rotation files and inserts a `\\n` at every
   `\\033[0m\\033[` boundary (style-reset immediately followed by a new
   style sequence) — the shape every TUI emit ends with, so the
   boundary maps reliably to an emit junction.

   Idempotent — once newlines are inserted, the pattern no longer
   matches at those positions. May over-split a single emit whose
   content legitimately contains multiple `<style>…<reset>` segments
   (e.g. the welcome banner) into more lines than the original
   `!scrollback` had; that's cosmetic and preferable to leaving fused
   lines that keep replaying on every resume.

   Returns the number of newlines inserted across this session's
   rotation files."
  [session-id tag]
  (let [pattern     "[0m["
        replacement "[0m\n["]
    (reduce + 0
            (for [^File f (ordered-files session-id tag)
                  :let [original (slurp f)
                        ;; Pass 1: the typical fusion shape
                        ;; `<style><text><reset><style>` collapses two
                        ;; emits onto one disk line.
                        pass-1  (clojure.string/replace original pattern replacement)
                        ;; Pass 2: emits that end in unstyled text
                        ;; (no terminating reset) still escape pass 1.
                        ;; The only emit shape we know fuses INTO such
                        ;; a tail is the `Press Ctrl-C` hint, which
                        ;; always begins with `<ESC>[2mPress Ctrl-C`.
                        ;; Inserting `\n` before that literal prefix
                        ;; un-fuses it without over-splitting in-emit
                        ;; dim spans.
                        repaired (clojure.string/replace pass-1
                                                         "[2mPress Ctrl-C"
                                                         "\n[2mPress Ctrl-C")
                        diff     (- (count repaired) (count original))]
                  :when (pos? diff)]
              (do (spit f repaired) diff)))))

(defn repair-all!
  "Walk every persisted session under the configured root and run
   `repair-concat!` on its `tag` stream. Returns a sorted map of
   `session-id → newlines-inserted`, omitting sessions where the
   repair made no changes.

   One-shot utility — once every existing file has been repaired and
   `tee-scrollback!` now terminates each chunk, subsequent runs are
   no-ops."
  ([] (repair-all! :stream))
  ([tag]
   (into (sorted-map)
         (for [sid (paths/list-sessions)
               :let [n (repair-concat! sid tag)]
               :when (pos? n)]
           [sid n]))))
