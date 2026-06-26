;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.embed-static
  "Self-contained, in-process embeddings (CR-MEM-21) via Model2Vec static
  embeddings — a `token → vector` lookup table + mean pooling. No transformer
  inference, no native library, no JNI: pure JVM (ByteBuffer + WordPiece +
  array math), so it bundles into the GraalVM binary with zero native-image
  risk and needs no embedding server.

  Bundled model: `potion-base-8M` (256-dim, BERT-uncased WordPiece tokenizer).
  PCA + zipf weighting are baked into the stored matrix at distillation time,
  so inference is exactly: normalize → tokenize → look up rows → mean → L2
  normalize. Validated to reproduce the reference `model2vec` vectors exactly.

  Model files are fetched by `bb model2vec:fetch` into resources/model2vec/
  (gitignored, bundled as native-image resources). When absent, this provider
  is simply unavailable and recall falls back to FTS — non-regressing."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.text Normalizer Normalizer$Form]))

;; =====================================================
;; safetensors → float matrix
;; =====================================================

(defn- read-all-bytes ^bytes [in]
  (with-open [^java.io.InputStream is in
              baos (java.io.ByteArrayOutputStream.)]
    (io/copy is baos)
    (.toByteArray baos)))

(defn- parse-safetensors
  "Parse a safetensors byte array carrying a single `embeddings` F32 tensor.
  Returns {:rows :cols :data float-array}."
  [^bytes bytes]
  (let [bb   (doto (ByteBuffer/wrap bytes) (.order ByteOrder/LITTLE_ENDIAN))
        hlen (.getLong bb 0)
        hdr  (json/read-str (String. bytes 8 (int hlen) "UTF-8"))
        emb  (get hdr "embeddings")
        [rows cols] (get emb "shape")
        [start _]   (get emb "data_offsets")
        base (+ 8 hlen (long start))
        n    (* (long rows) (long cols))
        data (float-array n)]
    (dotimes [i n]
      (aset data i (.getFloat bb (int (+ base (* i 4))))))
    {:rows rows :cols cols :data data}))

;; =====================================================
;; BERT (uncased) WordPiece tokenizer
;; =====================================================

(defn- punctuation? [^long cp]
  (or (and (>= cp 33) (<= cp 47)) (and (>= cp 58) (<= cp 64))
      (and (>= cp 91) (<= cp 96)) (and (>= cp 123) (<= cp 126))
      (let [t (Character/getType (int cp))]
        (boolean (#{Character/CONNECTOR_PUNCTUATION Character/DASH_PUNCTUATION
                    Character/START_PUNCTUATION Character/END_PUNCTUATION
                    Character/INITIAL_QUOTE_PUNCTUATION Character/FINAL_QUOTE_PUNCTUATION
                    Character/OTHER_PUNCTUATION} (int t))))))

(defn- control? [^long cp]
  (and (not (#{9 10 13} cp))
       (let [t (Character/getType (int cp))]
         (or (= t Character/CONTROL) (= t Character/FORMAT)))))

(defn- clean-text [^String s]
  (apply str (for [cp (seq (.toArray (.codePoints s)))
                   :when (not (or (= cp 0) (= cp 0xFFFD) (control? cp)))]
               (if (Character/isWhitespace (int cp)) " " (String. (Character/toChars (int cp)))))))

(defn- strip-accents [^String s]
  (-> (Normalizer/normalize s Normalizer$Form/NFD)
      (->> (filter #(not= (Character/getType (int %)) Character/NON_SPACING_MARK))
           (apply str))))

(defn- normalize-text [^String s]
  (-> s clean-text strip-accents str/lower-case))

(defn- pre-tokenize
  "Whitespace-split, then isolate punctuation chars as standalone tokens
  (BertPreTokenizer)."
  [s]
  (->> (str/split (str/trim s) #"\s+")
       (remove str/blank?)
       (mapcat (fn [w]
                 (loop [chars (seq w) cur [] out []]
                   (if-let [c (first chars)]
                     (if (punctuation? (int c))
                       (recur (rest chars) [] (cond-> out (seq cur) (conj (apply str cur)) :always (conj (str c))))
                       (recur (rest chars) (conj cur c) out))
                     (cond-> out (seq cur) (conj (apply str cur)))))))))

(defn- wordpiece
  "Greedy longest-match WordPiece. Unmatchable word ⇒ [UNK]."
  [vocab ^String word]
  (let [n (count word)]
    (if (> n 100)
      ["[UNK]"]
      (loop [start 0 out []]
        (if (>= start n)
          out
          (let [piece (loop [end n]
                        (when (> end start)
                          (let [sub (cond->> (subs word start end) (pos? start) (str "##"))]
                            (if (contains? vocab sub) [sub end] (recur (dec end))))))]
            (if piece
              (recur (long (second piece)) (conj out (first piece)))
              ["[UNK]"])))))))

(defn- encode-ids [vocab unk-id s]
  (->> (pre-tokenize (normalize-text s))
       (mapcat #(wordpiece vocab %))
       (mapv #(get vocab % unk-id))))

;; =====================================================
;; Model load (memoized) + embed
;; =====================================================

(defonce ^:private !model (atom ::unresolved))

(defn- resolve-paths
  "Resolve {:matrix <input-stream-fn> :tokenizer <input-stream-fn>} or nil.
  Order: BY_MODEL2VEC_PATH (a dir) > bundled classpath resources."
  []
  (let [open (fn [f] (fn [] (io/input-stream f)))]
    (or (when-let [d (System/getenv "BY_MODEL2VEC_PATH")]
          (let [m (io/file d "model.safetensors") t (io/file d "tokenizer.json")]
            (when (and (.exists m) (.exists t)) {:matrix (open m) :tokenizer (open t)})))
        (when-let [m (io/resource "model2vec/model.safetensors")]
          (when-let [t (io/resource "model2vec/tokenizer.json")]
            {:matrix (open m) :tokenizer (open t)})))))

(defn- load-model []
  (when-let [{:keys [matrix tokenizer]} (resolve-paths)]
    (let [{:keys [rows cols data]} (parse-safetensors (read-all-bytes (matrix)))
          tok   (json/read-str (slurp (tokenizer)))
          vocab (get-in tok ["model" "vocab"])
          unk   (get vocab "[UNK]" 0)]
      (mulog/info ::model2vec-loaded :rows rows :dim cols :vocab (count vocab))
      {:rows rows :cols cols :data data :vocab vocab :unk-id unk})))

(defn model
  "The loaded Model2Vec model, or nil when unavailable. Memoized."
  []
  (let [m @!model]
    (if (not= m ::unresolved) m (reset! !model (load-model)))))

(defn available? [] (some? (model)))

(defn dimensions
  "Output dimensionality of the bundled model, or nil when unavailable."
  []
  (:cols (model)))

(defn- embed-one [{:keys [^floats data cols vocab unk-id]} s]
  (let [ids (encode-ids vocab unk-id s)
        acc (double-array cols)]
    (if (empty? ids)
      (vec acc)
      (do
        (doseq [id ids]
          (let [off (* (long id) (long cols))]
            (dotimes [j cols] (aset acc j (+ (aget acc j) (aget data (int (+ off j))))))))
        (let [n (count ids)]
          (dotimes [j cols] (aset acc j (/ (aget acc j) n)))
          (let [norm (Math/sqrt (areduce acc i s 0.0 (+ s (* (aget acc i) (aget acc i)))))]
            (when (pos? norm) (dotimes [j cols] (aset acc j (/ (aget acc j) norm))))
            (vec acc)))))))

(defn static-embed-fn
  "Return an `embed-fn` `(fn [texts] -> [[float…] …])` backed by the bundled
  Model2Vec model, or nil when the model is unavailable (recall then falls
  back to FTS). Use as `:embed-fn` for `create-memory-manager`, paired with
  `:embed-dims` = `(dimensions)`."
  []
  (when-let [m (model)]
    (fn [texts] (mapv #(embed-one m %) texts))))
