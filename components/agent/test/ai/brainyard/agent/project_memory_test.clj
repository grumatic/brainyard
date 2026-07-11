;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.project-memory-test
  "Tests for the shared project-scoped file-memory writer (Phase 3 of
   docs/design/event-bus-and-reactor.md): slug write + index.md upsert + the
   title/hook derivation, behind the ask.sock :inject and reactor :memory paths."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.agent.common.project-memory :as pm]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- tmp []
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "pm-test-" (System/currentTimeMillis) "-" (rand-int 100000)))]
    (.mkdirs d) (.getPath d)))

(defn- cleanup [pcd]
  (doseq [^java.io.File f (reverse (file-seq (io/file pcd)))] (.delete f)))

(deftest write-and-index
  (let [pcd (tmp)]
    (try
      (let [r (pm/write-memory! pcd "Deploy Log"
                                "---\ntitle: Deploy Log\ndescription: prod deploy\n---\nbody")]
        (is (= "deploy-log" (:slug r)))
        (is (:indexed r))
        (is (.exists (io/file pcd "memory" "deploy-log.md")))
        (let [idx (slurp (io/file pcd "memory" "index.md"))]
          (is (str/includes? idx "(deploy-log.md)"))
          (is (str/includes? idx "Deploy Log"))
          (is (str/includes? idx "prod deploy") "frontmatter description → hook")))
      (finally (cleanup pcd)))))

(deftest index-upsert-replaces-not-duplicates
  (let [pcd (tmp)]
    (try
      (pm/write-memory! pcd "note" "# First\nalpha")
      (pm/write-memory! pcd "note" "# Second\nbeta")
      (let [idx (slurp (io/file pcd "memory" "index.md"))]
        (is (= 1 (count (re-seq #"\(note\.md\)" idx))) "one pointer, replaced in place")
        (is (str/includes? idx "Second")))
      (finally (cleanup pcd)))))

(deftest slug-sanitized
  (let [pcd (tmp)]
    (try
      (is (= "a-b-c" (:slug (pm/write-memory! pcd "  A/B  C! " "x"))))
      (finally (cleanup pcd)))))

(deftest validation-errors
  (testing "blank inputs surface :error, never throw"
    (is (:error (pm/write-memory! "" "s" "c")))
    (is (:error (pm/write-memory! (tmp) "" "c")))
    (is (:error (pm/write-memory! (tmp) "s" "")))))
