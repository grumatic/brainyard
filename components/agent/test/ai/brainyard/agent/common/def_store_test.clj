;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.def-store-test
  "Tests for the metadata-.edn + verbatim-.clj sidecar persistence shared by
   user-tools and user-hooks."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.agent.common.def-store :as def-store]))

(def ^:private dir
  (str (System/getProperty "java.io.tmpdir") "/by-def-store-test"))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (rm-rf! c)))
  (.delete f))

(use-fixtures :each (fn [f] (rm-rf! (io/file dir)) (try (f) (finally (rm-rf! (io/file dir))))))

;; A body that exercises every character class that breaks naive serializers:
;; embedded double-quotes, a backslash regex, the #() reader macro, and newlines.
(def ^:private gnarly-body
  "(fn [args]
  (let [t (:text args)]
    (-> t
        (clojure.string/split #\"\\s+\")
        (->> (map #(count %)))
        (->> (reduce + 0))
        (#(str \"words: \" %)))))")

(deftest write-produces-two-files
  (testing "write-def! writes a metadata .edn (pretty, no :body) + a verbatim .clj"
    (let [{:keys [edn clj]} (def-store/write-def!
                             dir "gnarly"
                             {:name "gnarly"
                              :description "Count the whitespace-separated words in the supplied text and prefix the result"
                              :input-schema [:map [:text :string]]}
                             gnarly-body)
          edn-txt (slurp edn)
          clj-txt (slurp clj)]
      (is (str/ends-with? edn "/gnarly.edn"))
      (is (str/ends-with? clj "/gnarly.clj"))
      ;; metadata is pretty-printed: a wide map wraps keys onto separate lines,
      ;; and pprint emits a trailing newline (pr-str would not)
      (is (str/includes? edn-txt "{:name \"gnarly\","))
      (is (re-find #"\n :description" edn-txt))
      (is (str/ends-with? edn-txt "\n"))
      ;; the body is NOT in the .edn
      (is (not (str/includes? edn-txt ":body")))
      (is (not (str/includes? (pr-str (edn/read-string edn-txt)) ":body")))
      ;; the .clj is the verbatim source (real newlines, raw #() and #"...")
      (is (str/includes? clj-txt "#(count %)"))
      (is (str/includes? clj-txt "#\"\\s+\""))
      (is (str/includes? clj-txt "\n  (let [t (:text args)]")))))

(deftest read-round-trips-byte-for-byte
  (testing "read-def merges metadata + body, body identical to what was written"
    (def-store/write-def! dir "gnarly"
                          {:name "gnarly" :description "x" :input-schema [:map [:text :string]]}
                          gnarly-body)
    (let [rec (def-store/read-def dir "gnarly")]
      (is (= "gnarly" (:name rec)))
      (is (= [:map [:text :string]] (:input-schema rec)))
      (is (= gnarly-body (:body rec)) "body must round-trip exactly, escaping and all"))))

(deftest legacy-inline-edn-still-reads
  (testing "a pre-sidecar single .edn with inline :body (no .clj) is read as-is"
    (.mkdirs (io/file dir))
    (spit (str dir "/old.edn")
          (pr-str {:name "old" :description "legacy" :input-schema [:map] :body "(fn [a] 1)"}))
    (let [rec (def-store/read-def dir "old")]
      (is (= "old" (:name rec)))
      (is (= "(fn [a] 1)" (:body rec))))))

(deftest read-missing-returns-nil
  (is (nil? (def-store/read-def dir "does-not-exist"))))

(deftest delete-removes-both
  (testing "delete-def! removes the .edn and the .clj"
    (def-store/write-def! dir "gnarly" {:name "gnarly"} gnarly-body)
    (is (.exists (io/file (str dir "/gnarly.edn"))))
    (is (.exists (io/file (str dir "/gnarly.clj"))))
    (is (true? (def-store/delete-def! dir "gnarly")))
    (is (not (.exists (io/file (str dir "/gnarly.edn")))))
    (is (not (.exists (io/file (str dir "/gnarly.clj")))))
    (is (false? (def-store/delete-def! dir "gnarly")) "deleting absent returns false")))

(deftest delete-removes-legacy-single-edn
  (testing "delete-def! also removes a legacy inline .edn with no sidecar"
    (.mkdirs (io/file dir))
    (spit (str dir "/old.edn") (pr-str {:name "old" :body "(fn [a] 1)"}))
    (is (true? (def-store/delete-def! dir "old")))
    (is (not (.exists (io/file (str dir "/old.edn")))))))
