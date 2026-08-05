;; Verify the providers.clj surgery in catalog_refresh.clj. Run by
;; `bb catalog:test`.
;;
;; The dangerous cases are the bracket ones: a provider's FIRST entry shares its
;; line with the opening `[`, its LAST carries the closing `]` (or `]))` at the
;; end of the whole form), and removing every entry of a provider must still
;; leave a vector behind. Each of those shipped broken at least once, so every
;; case here re-READS the rewritten text rather than eyeballing the diff.
;;
;; The cases are DERIVED from the catalog rather than naming model ids. Hard
;; coding ids looks fine and rots silently: once an id is removed from the
;; catalog — which `--write` exists to do — removing it again is a no-op that
;; still compares equal, so the case keeps reporting "ok" while testing
;; nothing. Deriving them also means the test cannot drift out of step with a
;; catalog it is supposed to be guarding.

(require '[clojure.string :as str])

(def src (slurp "src/ai/brainyard/clj_llm/core/providers.clj"))

(defn- catalog-form
  "Extract and read the `(def model-catalog …)` form from file text. Throws if
   the surgery produced something unparseable — which is the point."
  [text]
  (let [i (str/index-of text "(def model-catalog")
        r (java.io.PushbackReader. (java.io.StringReader. (subs text i)))]
    (read r)))

(defn- ids-of [form]
  (let [m (nth form 2)]                      ; (def model-catalog (array-map …))
    (into {} (map (fn [[p es]] [p (mapv :model es)]))
          (apply array-map (rest m)))))

(def baseline (ids-of (catalog-form src)))

(def failures (atom []))

(defn- fail! [label msg]
  (swap! failures conj label)
  (println (format "  %-38s %s" label msg)))

(defn- comment-count [text]
  (let [i (str/index-of text "(def model-catalog")]
    (count (re-seq #"(?m)^\s*;;" (subs text i (min (count text) (+ i 14000)))))))

(defn- check!
  "Apply `removals` to the source, re-read the result, and compare against the
   baseline minus those ids."
  [label removals]
  (cond
    ;; A case that removes nothing is testing nothing. Catching that here is
    ;; the whole reason the ids are derived.
    (and (seq removals) (every? empty? (vals removals)))
    (fail! label "EMPTY CASE — derived no ids to remove")

    (some (fn [[p ids]]
            (not-every? (set (get baseline p)) ids))
          removals)
    (fail! label (str "STALE CASE — ids not in the catalog: "
                      (pr-str (into {} (map (fn [[p ids]]
                                              [p (remove (set (get baseline p)) ids)]))
                                    removals))))

    :else
    (let [out  (try (#'user/apply-removals src removals)
                    (catch Throwable e
                      (fail! label (str "REWRITE THREW: " (.getMessage e)))
                      nil))
          got  (when out
                 (try (ids-of (catalog-form out))
                      (catch Throwable e
                        (fail! label (str "PARSE FAILED: " (.getMessage e)))
                        nil)))
          want (into {} (map (fn [[p ids]]
                               [p (vec (remove (get removals p #{}) ids))]))
                     baseline)]
      (when got
        (if (= want got)
          (if (= (comment-count src) (comment-count out))
            (println (format "  %-38s ok" label))
            (fail! label (format "COMMENTS LOST: %d -> %d"
                                 (comment-count src) (comment-count out))))
          (do (fail! label "WRONG DATA")
              (doseq [[p ids] got :when (not= ids (get want p))]
                (println "      " p "\n       want:" (get want p)
                         "\n       got: " ids))))))))

;; ---------------------------------------------------------------------------
;; Derive the edge cases from the catalog's actual shape
;; ---------------------------------------------------------------------------

(def ^:private big-provider
  "A provider with enough entries to have a distinct first / middle / last."
  (first (filter #(>= (count (get baseline %)) 3) (keys baseline))))

(def ^:private last-provider
  "The final provider in the array-map — its last entry carries the bracket run
   that closes the whole `(def model-catalog …)` form."
  (last (keys baseline)))

(def ^:private commented-provider
  "A provider whose block contains comment lines, so comment preservation is
   actually exercised."
  (let [body (subs src (str/index-of src "(def model-catalog"))]
    (first (filter (fn [p]
                     (let [i (str/index-of body (str "\n   " p "\n"))
                           j (when i (str/index-of body "\n   :" (inc i)))]
                       (and i (re-find #"(?m)^\s*;;" (subs body i (or j (count body)))))))
                   (keys baseline)))))

(defn- ids [provider & idxs]
  (let [v (get baseline provider)]
    (into #{} (keep #(nth v % nil)) idxs)))

(println (format "deriving cases from: big=%s last=%s commented=%s"
                 big-provider last-provider commented-provider))
(println "surgery cases:")

(check! "middle entry"             {big-provider (ids big-provider 1)})
(check! "FIRST entry (owns the [)" {big-provider (ids big-provider 0)})
(check! "LAST entry (owns the ])"  {big-provider (ids big-provider
                                                      (dec (count (get baseline big-provider))))})
(check! "last entry of whole form" {last-provider (ids last-provider
                                                       (dec (count (get baseline last-provider))))})
(check! "EVERY entry of a provider" {last-provider (set (get baseline last-provider))})
(check! "entry beside a comment"   {commented-provider (ids commented-provider 0)})
(check! "many across providers"    (into {} (map (fn [p] [p (ids p 0)]))
                                         (take 3 (keys baseline))))
(check! "no-op"                    {})

(let [n-fail (count @failures)]
  (println (str "\n" (- 8 n-fail) "/8 cases ok"))
  (when (pos? n-fail)
    (println (str "FAILED: " (str/join ", " @failures)))
    (System/exit 1)))
