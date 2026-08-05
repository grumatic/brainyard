;; Verify the providers.clj surgery in catalog_refresh.clj.
;;
;; The dangerous cases are the bracket ones: a provider's FIRST entry shares its
;; line with the opening `[`, and its LAST carries the closing `]` (or `]))` at
;; the end of the whole form). Deleting either naively produces a file that does
;; not parse — so every case is exercised here and the result is re-read.

(require '[clojure.string :as str] '[clojure.edn :as edn])

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

(defn- check! [label removals]
  (let [out  (#'user/apply-removals src removals)
        got  (try (ids-of (catalog-form out))
                  (catch Throwable e
                    (println (format "  %-38s PARSE FAILED: %s" label (.getMessage e)))
                    (throw e)))
        want (into {} (map (fn [[p ids]]
                             [p (vec (remove (get removals p #{}) ids))]))
                   baseline)
        ok   (= want got)]
    (println (format "  %-38s %s" label (if ok "ok" "WRONG DATA")))
    (when-not ok
      (doseq [[p ids] got
              :when (not= ids (get want p))]
        (println "     " p "\n      want:" (get want p) "\n      got: " ids)))
    ;; comments inside the catalog must survive
    (let [c-before (count (re-seq #"(?m)^\s*;;" (subs src (str/index-of src "(def model-catalog")
                                                      (+ (str/index-of src "(def model-catalog") 12000))))
          c-after  (count (re-seq #"(?m)^\s*;;" (subs out (str/index-of out "(def model-catalog")
                                                      (+ (str/index-of out "(def model-catalog") 12000))))]
      (when (not= c-before c-after)
        (println (format "     COMMENTS LOST: %d -> %d" c-before c-after))))
    ok))

(println "surgery cases:")
(def results
  [(check! "middle entry"            {:openai #{"gpt-5.2"}})
   (check! "FIRST entry (owns the [)" {:openai #{"gpt-5.6-luna"}})
   (check! "LAST entry (owns the ])"  {:openai #{"o1"}})
   (check! "last entry of whole form" {:deepseek #{"deepseek-reasoner"}})
   (check! "first AND last"           {:deepseek #{"deepseek-chat" "deepseek-reasoner"}})
   (check! "entry next to a comment"  {:bedrock #{"global.anthropic.claude-opus-5"}})
   (check! "many across providers"    {:openai  #{"gpt-4" "gpt-3.5-turbo" "o3-mini"}
                                       :ollama  #{"gemma3:12b"}
                                       :bedrock #{"zai.glm-4.7" "cohere.command-r-v1:0"}})
   (check! "no-op"                    {})])

(println (str "\n" (count (filter true? results)) "/" (count results) " cases ok"))
(when-not (every? true? results) (System/exit 1))
