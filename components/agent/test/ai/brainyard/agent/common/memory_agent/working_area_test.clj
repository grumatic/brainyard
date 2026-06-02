;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.memory-agent.working-area-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [ai.brainyard.agent.common.memory-agent.working-area :as wa]
            [ai.brainyard.agent.core.config :as config])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:dynamic *tmp* nil)

(defn- make-tmp-dir []
  (-> (Files/createTempDirectory "memory-agent-wa-" (into-array FileAttribute []))
      .toFile .getAbsolutePath))

(defn- delete-recursive [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursive c)))
  (.delete f))

(defn- with-tmp [f]
  (let [tmp (make-tmp-dir)]
    (try
      (binding [*tmp* tmp]
        (with-redefs [config/project-dir (constantly tmp)]
          (f)))
      (finally (delete-recursive (io/file tmp))))))

(use-fixtures :each with-tmp)

;; ============================================================================
;; Path resolution
;; ============================================================================

(deftest user-root-test
  (testing "user-root composes <project-dir>/.brainyard/agents/memory-agent/<user-id>"
    (is (= (str *tmp* "/.brainyard/agents/memory-agent/jake") (wa/user-root "jake")))
    (is (= (str *tmp* "/.brainyard/agents/memory-agent/user-with-dashes")
           (wa/user-root "user-with-dashes")))))

(deftest allowed-slot-test
  (testing "whitelisted slots are accepted"
    (is (true? (wa/allowed-slot? "stats.edn")))
    (is (true? (wa/allowed-slot? "pending/verify-queue.edn")))
    (is (true? (wa/allowed-slot? "pending/consolidate-queue.edn"))))

  (testing "anything outside the whitelist is rejected"
    (is (false? (wa/allowed-slot? "essence.log"))
        "essence.log is appended via append-essence!, not state-write")
    (is (false? (wa/allowed-slot? "../../etc/passwd")))
    (is (false? (wa/allowed-slot? "consolidations/foo.md")))
    (is (false? (wa/allowed-slot? "")))
    (is (false? (wa/allowed-slot? nil)))))

(deftest slot-file-test
  (testing "slot-file returns an absolute path under the user root"
    (let [f (wa/slot-file "jake" "stats.edn")]
      (is (some? f))
      (is (str/starts-with? (.getAbsolutePath f) *tmp*))
      (is (str/ends-with? (.getAbsolutePath f) "/jake/stats.edn"))))

  (testing "slot-file returns nil for rejected slots"
    (is (nil? (wa/slot-file "jake" "../escape")))
    (is (nil? (wa/slot-file "jake" "essence.log")))))

;; ============================================================================
;; EDN slot read/write
;; ============================================================================

(deftest write-and-read-slot-test
  (testing "write-slot! creates parent dirs and returns absolute path"
    (let [path (wa/write-slot! "jake" "stats.edn" {:l2 {:total 42}})]
      (is (string? path))
      (is (.isFile (io/file path)))
      (is (str/ends-with? path "/jake/stats.edn"))))

  (testing "read-slot round-trips the EDN"
    (wa/write-slot! "jake" "stats.edn" {:l2 {:total 42}})
    (is (= {:l2 {:total 42}} (wa/read-slot "jake" "stats.edn"))))

  (testing "read-slot returns nil when the file is absent"
    (is (nil? (wa/read-slot "no-such-user" "stats.edn"))))

  (testing "read-slot returns nil for rejected slots"
    (is (nil? (wa/read-slot "jake" "essence.log"))))

  (testing "write-slot! throws IllegalArgumentException for rejected slots"
    (is (thrown? IllegalArgumentException
                 (wa/write-slot! "jake" "../escape" {:x 1})))))

(deftest pending-queues-roundtrip-test
  (testing "verify-queue and consolidate-queue both round-trip"
    (wa/write-slot! "jake" "pending/verify-queue.edn"  [{:fact-id "f1"}])
    (wa/write-slot! "jake" "pending/consolidate-queue.edn" [{:session-id "s1"}])
    (is (= [{:fact-id "f1"}]    (wa/read-slot "jake" "pending/verify-queue.edn")))
    (is (= [{:session-id "s1"}] (wa/read-slot "jake" "pending/consolidate-queue.edn")))))

;; ============================================================================
;; essence.log — NDJSON append
;; ============================================================================

(deftest append-essence-creates-ndjson-file-test
  (testing "first append creates the file and writes one NDJSON line"
    (let [path (wa/append-essence! "jake" {:turn-id 1 :essences []})]
      (is (string? path))
      (is (.isFile (io/file path)))
      (let [content (slurp path)
            lines   (remove str/blank? (str/split-lines content))]
        (is (= 1 (count lines)))
        (is (str/ends-with? content "\n"))
        (let [record (json/read-str (first lines) :key-fn keyword)]
          (is (= 1 (:turn-id record)))
          (is (= [] (:essences record))))))))

(deftest append-essence-multi-line-test
  (testing "N appends produce N NDJSON lines (one record per call)"
    (wa/append-essence! "jake" {:turn-id 1 :essences []})
    (wa/append-essence! "jake" {:turn-id 2 :essences [{:kind "fact" :content "x"}]})
    (wa/append-essence! "jake" {:turn-id 3 :essences []})
    (let [records (wa/read-essence-log "jake")]
      (is (= 3 (count records)))
      (is (= [1 2 3] (mapv :turn-id records)))
      (is (= 1 (count (:essences (nth records 1))))))))

(deftest read-essence-log-empty-test
  (testing "read-essence-log returns [] when the file is absent"
    (is (= [] (wa/read-essence-log "no-such-user")))))

(deftest read-essence-log-tolerates-bad-lines-test
  (testing "non-JSON lines are skipped, valid lines surface"
    (wa/append-essence! "jake" {:turn-id 1 :essences []})
    ;; Inject a malformed line (simulates a torn write).
    (let [f (wa/essence-log-file "jake")]
      (spit f "not-json\n" :append true))
    (wa/append-essence! "jake" {:turn-id 2 :essences []})
    (let [records (wa/read-essence-log "jake")]
      (is (= 2 (count records))
          "the torn line is dropped silently; the valid lines survive")
      (is (= [1 2] (mapv :turn-id records))))))

(deftest truthy-slot-list-test
  (testing "lists only existing whitelisted slots"
    (is (= #{} (wa/truthy-slot-list "jake")))
    (wa/write-slot! "jake" "stats.edn" {})
    (is (= #{"stats.edn"} (wa/truthy-slot-list "jake")))
    (wa/write-slot! "jake" "pending/verify-queue.edn" [])
    (is (= #{"stats.edn" "pending/verify-queue.edn"} (wa/truthy-slot-list "jake")))))
