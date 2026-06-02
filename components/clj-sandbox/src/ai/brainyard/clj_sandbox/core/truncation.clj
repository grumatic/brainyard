;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-sandbox.core.truncation
  "Safe truncation with temp-file recovery.

   When data exceeds a size limit, saves the original untruncated content
   to a temp file and returns truncated text with a recovery path.
   The LLM can call (read-file \"path\") to recover the full content."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:dynamic *working-dir*
  "Working directory used to namespace temp files.
   Bind this at the RLM entry point."
  (System/getProperty "user.dir"))

(defn short-id []
  (subs (str (java.util.UUID/randomUUID)) 0 8))

(defn ensure-dir [^String dir]
  (let [f (io/file dir)]
    (when-not (.exists f) (.mkdirs f))
    dir))

(defn- resolve-project-clj-sandbox-dir
  "Lazily resolve the project-scope `<project>/.brainyard/temp/clj-sandbox/`
   directory via the agent component's interface, if available.
   Returns nil on any failure (agent not on classpath, project-dir
   unresolvable, etc.) so callers can fall back."
  []
  (try
    (let [init-dirs!     (requiring-resolve 'ai.brainyard.agent.interface/init-dirs!)
          brainyard-sub! (requiring-resolve 'ai.brainyard.agent.interface/brainyard-subdir!)]
      (when (and init-dirs! brainyard-sub!)
        (brainyard-sub! (init-dirs!) "temp/clj-sandbox" :project)))
    (catch Throwable _ nil)))

(defn working-dir-subpath
  "Resolve the truncation cache path for a given content `class`.
   Prefers `<project>/.brainyard/temp/clj-sandbox/truncation/<class>` when the
   agent component is on classpath; falls back to the legacy
   `/tmp/<working-dir-mangled>/<class>` layout otherwise.

   Public so other namespaces can share it."
  [^String class]
  (if-let [base (resolve-project-clj-sandbox-dir)]
    (let [dir (str base "/truncation")]
      (.mkdirs (io/file dir))
      (str dir "/" class))
    (let [dir-name (-> (str *working-dir*)
                       (str/replace "/" "_")
                       (str/replace #"^_" ""))]
      (str "/tmp/" dir-name "/" class))))

(def ^:private truncation-file-re
  "Regex to extract temp file path from a previous truncation marker."
  #"--- Full content saved to: (.+?) ---")

(defn- recover-if-truncated
  "If text contains a truncation marker from a previous truncate-to-file call,
   recover the original content from the temp file. Returns [recovered-text existing-path]
   where existing-path is non-nil when recovery succeeded (for file reuse)."
  [text]
  (if-let [match (re-find truncation-file-re (str text))]
    (let [path (second match)
          f (io/file path)]
      (if (.exists f)
        [(slurp f) path]
        [text nil]))
    [text nil]))

(defn truncate-to-file
  "If text exceeds max-chars, save original to temp file and return truncated text
   with a recovery path. Otherwise return text unchanged.

   Idempotent: if text is already truncated (contains a TRUNCATED signature from
   a previous call), recovers the original from the temp file before re-truncating.
   This prevents cascading truncation of already-truncated text.

   Parameters:
     text       - String to potentially truncate
     max-chars  - Maximum chars before truncating
     class      - Category name for temp subdir (e.g. \"repl-output\", \"answer\")
     opts       - {:label          descriptive label for the notice (default: class)
                   :head-ratio     fraction of max-chars for head (default 0.7)
                   :tail-ratio     fraction of max-chars for tail (default 0.2)
                   :recovery-hint  custom recovery guidance string; %s is replaced with the
                                   temp file path (default: read-file chunk guidance)}

   Returns: String — original if under limit, or head + recovery notice + tail."
  [text max-chars class & {:keys [label head-ratio tail-ratio recovery-hint]
                           :or {label nil head-ratio 0.7 tail-ratio 0.2}}]
  (if (or (nil? text) (<= (count text) max-chars))
    text
    ;; Recover original from temp file if text is already truncated
    (let [[text existing-path] (recover-if-truncated text)]
      (if (<= (count text) max-chars)
        text
        (let [label (or label class)
              dir (ensure-dir (working-dir-subpath class))
              ;; Reuse existing temp file if we recovered from one, else create new
              file (if existing-path
                     (io/file existing-path)
                     (io/file dir (str (short-id) ".txt")))
              _ (when-not existing-path (spit file text))
              head-len (long (* max-chars head-ratio))
              tail-len (long (* max-chars tail-ratio))
              path (.getPath file)
              total-lines (count (str/split-lines text))
              total-chars (count text)
              safe-chars  (max 500 (long (* max-chars 0.6)))
              ;; `pr-str`'d structured data (maps / vectors whose string values
              ;; escape newlines to `\n`) collapses to a handful of "lines" even
              ;; when many kilobytes long — making `:lines` chunking useless.
              ;; For that shape, recommend char-based `:offset`/`:limit` slicing.
              single-blob? (and (> total-chars 1000) (<= total-lines 5))
              avg-line-len (max 1 (quot total-chars (max 1 total-lines)))
              safe-lines   (max 5 (quot safe-chars avg-line-len))]
          (str (subs text 0 head-len)
               "\n\n--- " label " TRUNCATED (original: " total-chars " chars, " total-lines " lines) ---"
               "\n--- Full content saved to: " path " ---"
               "\n--- Truncation limit: " max-chars " chars"
               (if single-blob?
                 (str " (single-line/structured content — use char-based :offset/:limit). ---")
                 (str " (~" safe-lines " lines). Keep read-file chunks within this limit. ---"))
               "\n--- " (if recovery-hint
                          (str/replace recovery-hint "%s" path)
                          (if single-blob?
                            (str "Recovery: (def data (:content (read-file \"" path "\" :offset 0 :limit " safe-chars "))) "
                                 "then increment :offset by " safe-chars " for the next chunk")
                            (str "Recovery: (def data (:content (read-file \"" path "\" :lines [1 " safe-lines "]))) then process with code")))
               " ---\n\n"
               (subs text (- total-chars tail-len))))))))

;; ----------------------------------------------------------------------
;; Note: line-based TUI truncation lived here historically. It has moved
;; to the `display-block` component (`ai.brainyard.display-block.interface/text-block`),
;; which provides a generalised provider/marker abstraction. Callers should
;; prefer `display-block/text-block` for any collapsible scrollback content.
;; ----------------------------------------------------------------------
