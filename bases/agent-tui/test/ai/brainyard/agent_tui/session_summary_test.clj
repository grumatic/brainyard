;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.session-summary-test
  (:require [ai.brainyard.agent-tui.session-summary :as ss]
            [ai.brainyard.agent-tui-persist.interface :as persist]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io File]
           [java.nio.file Files]))

(defn- tmp-root []
  (.toFile (Files/createTempDirectory
            "session-summary-test"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf [^File dir]
  (doseq [^File f (reverse (file-seq dir))] (.delete f)))

(deftest format-bytes-and-age
  (is (= "512 B" (ss/format-bytes 512)))
  (is (= "1.0 KB" (ss/format-bytes 1024)))
  (is (str/ends-with? (ss/format-bytes (* 3 1024 1024)) " MB"))
  (is (nil? (ss/format-age-millis nil)))
  (is (= "just now" (ss/format-age-millis (System/currentTimeMillis)))))

(deftest enriched-and-table
  (let [root (tmp-root)]
    (try
      (persist/with-root root
        (testing "empty root → empty enrichment + table"
          (is (= [] (ss/enriched-summaries)))
          (is (= [] (ss/format-table (ss/enriched-summaries) {}))))

        ;; Seed two sessions: a root and a child fork.
        (persist/save-meta! "agt-a" {:agent-id :coact-agent :defagent-id :coact-agent
                                     :model "claude-code:opus"
                                     :started-at 1000 :last-attached-at 3000
                                     :label "alpha"})
        (persist/append-event! "agt-a" {:kind :agent.ask/pre  :payload {:input "first prompt for A"}})
        (persist/append-event! "agt-a" {:kind :agent.ask/post :payload {:answer "answer A"}})
        (persist/save-meta! "agt-b" {:agent-id :coact-agent :started-at 2000
                                     :last-attached-at 5000 :parent-id "agt-a" :fork-point 1})

        (testing "enriched-summaries merges scan fields and sorts newest first"
          (let [rows (ss/enriched-summaries)]
            (is (= ["agt-b" "agt-a"] (mapv :session-id rows))) ; b attached later
            (let [a (first (filter #(= "agt-a" (:session-id %)) rows))]
              (is (= "first prompt for A" (:first-user-input a)))
              (is (= "answer A" (:last-answer a)))
              (is (= 2 (:event-count a)))
              (is (= "alpha" (:label a)))
              (is (= "claude-code:opus" (:model a))))))

        (testing "format-table renders 2 lines/session, fork marker, label, preview"
          (let [lines (ss/format-table (ss/enriched-summaries) {})]
            (is (= 4 (count lines)))                         ; 2 sessions × 2 lines
            (is (some #(str/includes? % "[alpha]") lines))   ; label
            (is (some #(str/includes? % "first prompt for A") lines)) ; preview
            (is (some #(str/starts-with? % "↳") lines))      ; child fork marker
            (is (some #(str/includes? % "(no messages yet)") lines)))) ; b has no ask/pre

        (testing "numbered? + active marker"
          (let [lines (ss/format-table (ss/enriched-summaries) {:numbered? true :active "agt-b"})]
            (is (some #(str/includes? % "  1 ▸") lines)))))   ; active b is row 1
      (finally (rm-rf root)))))

(deftest detail-and-tree
  (let [root (tmp-root)]
    (try
      (persist/with-root root
        (persist/save-meta! "agt-x" {:agent-id :coact-agent :model "m1"
                                     :working-dir "/tmp/proj" :started-at 1000
                                     :last-attached-at 2000 :label "ex"})
        (persist/append-event! "agt-x" {:kind :agent.ask/pre  :payload {:input "hello there"}})
        (persist/append-event! "agt-x" {:kind :agent.ask/post :payload {:answer "hi back"}})
        (let [row (first (ss/enriched-summaries))
              lines (ss/format-detail row)]
          (is (some #(str/includes? % "Session agt-x") lines))
          (is (some #(str/includes? % "/tmp/proj") lines))
          (is (some #(str/includes? % "hello there") lines))
          (is (some #(str/includes? % "hi back") lines)))
        (testing "tree renders the session"
          (is (some #(str/includes? % "agt-x") (ss/format-tree {})))))
      (finally (rm-rf root)))))
