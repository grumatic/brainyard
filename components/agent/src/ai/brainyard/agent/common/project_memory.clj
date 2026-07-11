;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.project-memory
  "Shared writer for project-scoped file memory
   (`<project-config-dir>/memory/<slug>.md` + an `index.md` pointer that the
   `## Project Memory` context section surfaces).

   Single source of truth for two callers that were about to diverge: the
   ask.sock `:op :inject :as :memory` verb (agent-tui base) and the event
   reactor's `:as :memory` action (docs/design/event-bus-and-reactor.md). Both
   go through `write-memory!` so the title/hook derivation and index upsert stay
   identical."
  (:require [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str])
  (:import [java.io File]))

(defn memory-title+hook
  "Derive an index pointer's title and one-line hook from slug content.
   Title: YAML frontmatter `title:`, else the first `# Heading`, else a
   humanized slug. Hook: frontmatter `description:`, else the first non-blank
   body line that isn't frontmatter/heading (collapsed + capped)."
  [safe-slug content]
  (let [lines     (str/split-lines (str content))
        fm-get    (fn [k]
                    (some (fn [l]
                            (let [m (re-matches (re-pattern (str "(?i)\\s*" k ":\\s*(.+?)\\s*")) l)]
                              (some-> (second m) (str/replace #"^[\"']|[\"']$" "") str/trim not-empty)))
                          (take-while #(not= (str/trim %) "---")
                                      (if (= (str/trim (or (first lines) "")) "---")
                                        (rest lines) lines))))
        heading   (some (fn [l] (some-> (re-matches #"#+\s+(.+?)\s*" l) second str/trim not-empty))
                        lines)
        body-line (some (fn [l]
                          (let [t (str/trim l)]
                            (when (and (not-empty t)
                                       (not= t "---")
                                       (not (str/starts-with? t "#"))
                                       (not (re-matches #"(?i)\w+:\s*.+" t)))
                              t)))
                        lines)
        title     (or (fm-get "title") heading
                      (->> (str/split safe-slug #"-")
                           (map str/capitalize) (str/join " ")))
        hook-raw  (or (fm-get "description") body-line "")
        hook      (let [h (str/replace hook-raw #"\s+" " ")]
                    (if (> (count h) 100) (str (subs h 0 99) "…") h))]
    [title hook]))

(defn upsert-memory-index!
  "Add or update the `index.md` pointer for `safe-slug`. If a line already links
   `(<slug>.md)`, its whole line is replaced with the freshly-derived pointer;
   otherwise the pointer is appended. Format matches the project-memory protocol:
   `- [Title](<slug>.md) — one-line hook`. Best-effort; never throws."
  [^File mem-dir safe-slug content]
  (let [[title hook] (memory-title+hook safe-slug content)
        pointer      (str "- [" title "](" safe-slug ".md)"
                          (when (not-empty hook) (str " — " hook)))
        idx          (File. mem-dir "index.md")
        existing     (when (.isFile idx) (slurp idx))
        link-token   (str "(" safe-slug ".md)")
        replaced?    (atom false)
        new-body     (if (str/blank? existing)
                       (str "# Project Memory Index\n\n" pointer "\n")
                       (let [lines  (str/split-lines existing)
                             lines' (mapv (fn [l]
                                            (if (str/includes? l link-token)
                                              (do (reset! replaced? true) pointer)
                                              l))
                                          lines)]
                         (str (str/join "\n" (if @replaced? lines' (conj lines' pointer)))
                              "\n")))]
    (spit idx new-body)))

(defn write-memory!
  "Write `content` as `<project-config-dir>/memory/<slug>.md` and add/update its
   `index.md` pointer so the item is discoverable in the `## Project Memory`
   context section. Returns `{:slug :path :indexed}` or `{:error …}`."
  [project-config-dir slug content]
  (cond
    (str/blank? (str project-config-dir)) {:error "no project config dir (project memory unavailable)"}
    (str/blank? (str slug))               {:error ":slug is required"}
    (str/blank? (str content))            {:error ":content is required"}
    :else
    (try
      (let [safe    (-> (str slug) str/trim str/lower-case
                        (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" ""))
            mem-dir (File. ^String (str project-config-dir) "memory")
            f       (File. mem-dir (str safe ".md"))]
        (.mkdirs mem-dir)
        (spit f content)
        (let [indexed? (try (upsert-memory-index! mem-dir safe content) true
                            (catch Throwable e
                              (mulog/log ::index-failed :slug safe :error (.getMessage e))
                              false))]
          {:slug safe :path (.getAbsolutePath f) :indexed indexed?}))
      (catch Throwable e
        {:error (str "memory write failed: " (.getMessage e))}))))
