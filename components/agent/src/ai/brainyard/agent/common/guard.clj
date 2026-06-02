;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.guard
  "Shared write-guard for agent artifact writers (plan / exec / eval / workflow
   dossiers, memory entries). Mirrors the secret-scan + size-cap the
   transactional siblings (init / config / update) already enforce, so a
   credential never lands in a committed `.brainyard/` artifact and a runaway
   body can't be written unbounded.

   Usage at a write site:

     (if-let [v (guard/content-violation body)]
       v                       ; {:stage :secret-detected|:budget-exceeded :error :hint …}
       (do (spit f body) ...)) ; safe to write")

(def ^:private secret-patterns
  "Cheap surface-level patterns — refuse the obvious paste-in mistake (not
   exhaustive). Same set as config-agent / init-agent."
  [#"sk-[A-Za-z0-9]{20,}"
   #"AKIA[0-9A-Z]{16}"
   #"ASIA[0-9A-Z]{16}"
   #"ghp_[A-Za-z0-9]{20,}"
   #"github_pat_[A-Za-z0-9_]{20,}"
   #"xox[bpoars]-[A-Za-z0-9-]{10,}"
   #"-----BEGIN [A-Z ]*PRIVATE KEY-----"
   #"AIza[0-9A-Za-z_-]{20,}"])

(defn scan-secrets
  "Return [match …] of secret-shaped substrings in `s` (empty when clean)."
  [^String s]
  (vec (mapcat (fn [pat]
                 (let [m (re-matcher pat (or s ""))]
                   (loop [hits []]
                     (if (.find m) (recur (conj hits (.group m))) hits))))
               secret-patterns)))

(def ^:const default-size-cap
  "Runaway guard for dossier/plan/verdict bodies (256 KB). Generous on purpose
   — unlike BRAINYARD.md these are not loaded into every prompt; the cap just
   stops a pathological write, not normal content."
  262144)

(defn content-violation
  "Return a violation map for `content`, or nil when it passes. The secret scan
   always runs; the size cap defaults to `default-size-cap` (override via :cap,
   pass :cap nil to disable). The returned map carries :error/:stage/:hint so a
   writer can return it directly to the caller."
  ([content] (content-violation content nil))
  ([^String content {:keys [cap] :or {cap default-size-cap}}]
   (let [secrets (scan-secrets content)]
     (cond
       (seq secrets)
       {:stage   :secret-detected
        :error   "Secret-shaped value detected; refusing to write it to a .brainyard/ artifact."
        :matches (mapv #(str (subs % 0 (min 8 (count %))) "…") secrets)
        :hint    "Reference the secret via an env var (put it in .env), not an inline literal."}

       (and cap (> (count (or content "")) cap))
       {:stage :budget-exceeded
        :error (str "Content exceeds the " cap "-byte write cap.")
        :size  (count (or content ""))
        :cap   cap
        :hint  "Summarize or split the artifact."}

       :else nil))))
