;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;;
;; Maintainer tool behind `bb catalog:refresh`.
;;
;; Asks every reachable provider which models it serves and compares that to
;; the BAKED catalog in providers.clj — the one shipped in the binary, which
;; users get before any runtime refresh has happened.
;;
;; Reports by default. `--write` applies REMOVALS ONLY: entries the provider no
;; longer serves are deleted from the source. Additions are reported but never
;; written, because an entry is only worth adding once a human has given it a
;; :curated-rank and a :description — auto-inserting bare {:model "…"} lines
;; would just be work to redo, and the same "curation stays human" split the
;; runtime overlay uses.
;;
;; The rewrite is surgical rather than a regenerate. providers.clj carries
;; comments that encode probe-derived knowledge — which OpenAI ids are served
;; only by /v1/responses, why Opus 4.6 keeps sampling params — and a
;; pretty-printed regenerate would delete them, after which someone re-adds the
;; models they warn about.

(require '[ai.brainyard.clj-llm.core.catalog :as catalog]
         '[ai.brainyard.clj-llm.core.catalog-fetch :as fetch]
         '[ai.brainyard.clj-llm.core.providers :as providers]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def providers-file "src/ai/brainyard/clj_llm/core/providers.clj")

(def write? (some #{"--write"} *command-line-args*))

;; ---------------------------------------------------------------------------
;; Source surgery
;; ---------------------------------------------------------------------------

(defn- entry-id
  "The :model id on a catalog entry line, or nil when the line has none."
  [line]
  (second (re-find #"\{:model\s+\"([^\"]+)\"" line)))

(defn- catalog-bounds
  "[start end) line indices of the `(def model-catalog …)` form."
  [lines]
  (let [start (first (keep-indexed #(when (str/starts-with? %2 "(def model-catalog") %1) lines))
        end   (first (keep-indexed #(when (and (> %1 start)
                                               (re-find #"^(\(|;; =====)" %2))
                                      %1)
                                   lines))]
    [start (or end (count lines))]))

(defn- strip-brackets
  "Split a catalog line into [indent leading-bracket entry-text trailing]. The
   entry text is returned verbatim so curation and spacing survive untouched;
   only the bracket decoration is ours to move."
  [line]
  (let [[_ indent lead body] (re-find #"^(\s*)(\[?)(\{.*)$" line)]
    (when body
      ;; Trailing `]`, `]))`, `])` etc. after the entry's closing brace.
      (let [[_ entry trail] (re-find #"^(\{.*\})(.*)$" body)]
        [indent lead entry (or trail "")]))))

(defn- rewrite-provider-block
  "Re-emit one provider's lines with `remove-ids` dropped.

   Brackets are recomputed rather than carried, because the entry that opens or
   closes a vector may be the one being removed. Comment lines pass through in
   place — they are the whole reason this is surgery and not a regenerate."
  [block remove-ids]
  (let [decorated (mapv (fn [line]
                          (if-let [id (entry-id line)]
                            (assoc (zipmap [:indent :lead :entry :trail]
                                           (strip-brackets line))
                                   :id id :kind :entry)
                            {:kind :other :line line}))
                        block)
        entries   (filterv #(= :entry (:kind %)) decorated)
        ;; The closing bracket run of the LAST entry (e.g. "]" or "]))") has to
        ;; be carried to whichever entry ends up last.
        last-trail (:trail (peek entries))
        keep?     (fn [d] (or (not= :entry (:kind d))
                              (not (contains? remove-ids (:id d)))))
        kept      (filterv keep? decorated)
        kept-idx  (vec (keep-indexed #(when (= :entry (:kind %2)) %1) kept))
        first-e   (first kept-idx)
        last-e    (peek kept-idx)
        rewritten (vec
                   (keep-indexed
                    (fn [i d]
                      (if (= :entry (:kind d))
                        (str (:indent d)
                             (if (= i first-e) "[" "")
                             (:entry d)
                             (if (= i last-e) last-trail ""))
                        (:line d)))
                    kept))]
    (if (seq kept-idx)
      rewritten
      ;; Every entry removed. The vector must still exist and still carry the
      ;; bracket run that closes it — and, for the last provider in the map,
      ;; closes the whole `(def model-catalog (array-map …))` form. Dropping
      ;; the lines outright leaves the key with no value and the file
      ;; unparseable, which is what the "first AND last" case caught.
      ;; `[]` supplies the vector's own closing bracket, so drop the leading
      ;; `]` from the carried trail — for the final provider that trail is
      ;; `]))`, and emitting it whole would close the vector twice.
      (conj rewritten (str (:indent (first entries))
                           "[]"
                           (if (str/starts-with? last-trail "]")
                             (subs last-trail 1)
                             last-trail))))))

(defn- apply-removals
  "Rewrite providers.clj with `removals` (provider -> #{ids}) deleted.
   Returns the new file text."
  [text removals]
  (let [lines (str/split-lines text)
        [start end] (catalog-bounds lines)
        ;; Provider keys sit on their own line, e.g. "   :openai".
        provider-of (fn [line] (second (re-find #"^\s{2,4}(:[a-z0-9-]+)$" line)))
        body  (subvec (vec lines) start end)
        ;; Split the form into [header, (provider, block)…]
        idxs  (vec (keep-indexed #(when (provider-of %2) %1) body))
        out   (atom (subvec body 0 (or (first idxs) (count body))))]
    (doseq [[i nxt] (map vector idxs (concat (rest idxs) [(count body)]))]
      (let [pkey  (keyword (subs (provider-of (nth body i)) 1))
            block (subvec body (inc i) nxt)
            ids   (get removals pkey #{})]
        (swap! out conj (nth body i))
        (swap! out into (if (seq ids)
                          (rewrite-provider-block block ids)
                          block))))
    (str (str/join "\n"
                   (concat (subvec (vec lines) 0 start)
                           @out
                           (subvec (vec lines) end)))
         ;; `split-lines` discards the trailing newline and `join` does not put
         ;; it back, so without this every --write leaves the file with
         ;; "\\ No newline at end of file" — a spurious one-line diff on a file
         ;; nobody edited by hand.
         (when (str/ends-with? text "\n") "\n"))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(defn main! []
  (let [candidates (fetch/refreshable-providers)]
    (if (empty? candidates)
      (println "[catalog:refresh] No reachable providers — nothing to compare against.")
      (do
        (println (str "[catalog:refresh] Querying: " (str/join ", " (map name candidates))))
        (let [overlay (fetch/refresh-all)
              drift   (catalog/drift providers/model-catalog overlay)]
          (if (empty? drift)
            (println "[catalog:refresh] Baked catalog matches every reachable provider.")
            (do
              (doseq [[provider {:keys [retired discovered]}] drift]
                (println (str "\n" (name provider)))
                (doseq [r retired]
                  (println (format "  - retired    %-46s %s" (:model r)
                                   (if (:curated-rank r) "[curated — was in the picker]" ""))))
                (doseq [d discovered]
                  (println (format "  + discovered %s" d))))
              (println)
              (let [removals (into {} (keep (fn [[p {:keys [retired]}]]
                                              (when (seq retired)
                                                [p (set (map :model retired))]))
                                            drift))
                    n-rm  (reduce + 0 (map count (vals removals)))
                    n-add (reduce + 0 (map (comp count :discovered) (vals drift)))]
                (cond
                  (not write?)
                  (println (str "[catalog:refresh] " n-rm " removable, " n-add " new. "
                                "Re-run with --write to delete the retired entries "
                                "(additions are never written — they need a rank and a description)."))

                  (zero? n-rm)
                  (println (str "[catalog:refresh] Nothing to remove. "
                                n-add " new model(s) above need curating by hand."))

                  :else
                  (let [text (slurp providers-file)
                        out  (apply-removals text removals)]
                    (spit providers-file out)
                    (println (str "[catalog:refresh] Removed " n-rm
                                  " retired entr" (if (= 1 n-rm) "y" "ies")
                                  " from " providers-file "."))
                    (println "[catalog:refresh] Review `git diff`, then re-check curated-rank contiguity.")
                    (when (pos? n-add)
                      (println (str "[catalog:refresh] " n-add
                                    " new model(s) listed above are NOT written — add them with a rank and description if they belong in the picker.")))))))))))))

;; Guarded so the surgery fns above can be loaded and tested without
;; contacting any provider (see scripts/catalog_refresh_test.clj).
(when-not (System/getenv "CATALOG_REFRESH_NO_RUN")
  (main!))
