;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.def-store
  "Persist an LLM-authored definition (user tool / user hook) as a
   human-reviewable PAIR of companion files sharing a basename:

     <base>.edn  — the metadata map, pretty-printed (clojure.pprint)
     <base>.clj  — the body, raw Clojure source written VERBATIM (no escaping)

   Keeping the body in its own `.clj` file means the persisted source is exactly
   what the author wrote — `#(…)`, `#\"…\"`, quotes and all — opens in an editor
   with paren-matching/highlighting, and the `.edn` stays a small tidy metadata
   map (no multi-line escaped string). The `.clj` is only ever read back as TEXT
   (and handed to the SCI sandbox for eval, as before) — it is never `load`ed or
   `eval`ed off disk here, so reading stays on the safe `clojure.edn` reader.

   `read-def` merges the two and falls back to a LEGACY single `.edn` whose
   `:body` is inline (pre-sidecar files), so old definitions keep working and
   migrate to the two-file form on their next overwrite."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(defn- edn-file ^java.io.File [dir base] (io/file (str dir "/" base ".edn")))
(defn- clj-file ^java.io.File [dir base] (io/file (str dir "/" base ".clj")))

(defn write-def!
  "Write `meta-map` to `<dir>/<base>.edn` (pretty-printed) and `body` to
   `<dir>/<base>.clj` (verbatim source). Creates `dir` as needed. Returns
   `{:edn <path> :clj <path>}`."
  [dir base meta-map body]
  (.mkdirs (io/file dir))
  (let [ef (edn-file dir base)
        cf (clj-file dir base)
        b  (str body)]
    (spit ef (binding [*print-length* nil *print-level* nil]
               (with-out-str (pp/pprint meta-map))))
    (spit cf (cond-> b (not (str/ends-with? b "\n")) (str "\n")))
    {:edn (str ef) :clj (str cf)}))

(defn read-def
  "Return the metadata map for `<dir>/<base>` with `:body` assoc'd from the
   `.clj` sidecar. Falls back to a legacy single `.edn` whose `:body` is inline
   when there is no sidecar. Returns nil when no `.edn` exists."
  [dir base]
  (let [ef (edn-file dir base)
        cf (clj-file dir base)]
    (when (.exists ef)
      (let [m (edn/read-string (slurp ef))]
        (if (.exists cf)
          (assoc m :body (let [s (slurp cf)]
                           (cond-> s (str/ends-with? s "\n")
                                   (subs 0 (dec (count s))))))
          m)))))                                ;; legacy: :body already inline (or absent)

(defn delete-def!
  "Delete `<dir>/<base>.edn` and `<dir>/<base>.clj` (either may be absent).
   Returns true if anything was removed."
  [dir base]
  (let [ef  (edn-file dir base)
        cf  (clj-file dir base)
        had (or (.exists ef) (.exists cf))]
    (when (.exists ef) (.delete ef))
    (when (.exists cf) (.delete cf))
    had))
