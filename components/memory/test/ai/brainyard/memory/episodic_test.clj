;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.episodic-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.memory.core.episodic :as episodic]))

(def ^:dynamic *ds* nil)

(defn with-test-db [f]
  (let [conn (sqlite/create-datasource ":memory:?cache=shared")]
    (sqlite/init-schema! conn)
    (try
      (binding [*ds* conn]
        (f))
      (finally
        (.close conn)))))

(use-fixtures :each with-test-db)

(deftest upsert-by-entry-id-dedup-test
  (testing "an entry_id-bearing episode upserts on (user_id, entry_id)"
    (let [rec (fn [eid content]
                {:session_id "s1" :user_id "u1" :episode_type "episode" :role nil
                 :content content :tags nil :sources nil :entry_id eid
                 :keep_flag 0 :archived_flag 0 :tombstoned_flag 0 :metadata nil})]
      (episodic/append-episode! *ds* (rec "qa/s1/abc" "Q: x\nA: one"))
      (episodic/append-episode! *ds* (rec "qa/s1/abc" "Q: x\nA: two"))   ; same id ⇒ update
      (episodic/append-episode! *ds* (rec "qa/s1/xyz" "Q: y\nA: z"))     ; new id ⇒ insert
      (is (= 2 (episodic/count-episodes *ds*)) "duplicate entry_id did not add a row")
      (let [recent (episodic/get-recent-episodes *ds* "s1" 10)]
        (is (some     #(= "Q: x\nA: two" (:content %)) recent) "latest answer kept")
        (is (not-any? #(= "Q: x\nA: one" (:content %)) recent) "stale answer replaced")))))

(deftest append-and-retrieve-episode-test
  (testing "append and retrieve episode"
    (let [episode (episodic/append-episode! *ds*
                                            {:session-id "s1"
                                             :user-id "u1"
                                             :episode-type :conversation
                                             :role "user"
                                             :content "How do I optimize AWS costs?"})]
      (is (some? episode))
      (is (= "conversation" (:episode_type episode)))

      (let [retrieved (episodic/get-episode-by-id *ds* (:id episode))]
        (is (some? retrieved))
        (is (= "How do I optimize AWS costs?" (:content retrieved)))))))

(deftest fts-search-test
  (testing "FTS5 search with BM25"
    (episodic/append-episode! *ds*
                              {:session-id "s1" :user-id "u1"
                               :episode-type :conversation :role "user"
                               :content "How do I optimize AWS costs?"})
    (episodic/append-episode! *ds*
                              {:session-id "s1" :user-id "u1"
                               :episode-type :conversation :role "assistant"
                               :content "I can help analyze your AWS spending."})
    (episodic/append-episode! *ds*
                              {:session-id "s1" :user-id "u1"
                               :episode-type :observation :role "system"
                               :content "Total monthly cloud budget is $50,000."})

    (let [results (episodic/search-fts *ds* "AWS costs" :limit 5)]
      (is (pos? (count results)))
      (is (some #(clojure.string/includes? (:content %) "AWS") results)))))

(deftest session-based-queries-test
  (testing "recent episodes"
    (episodic/append-episode! *ds*
                              {:session-id "s1" :user-id "u1"
                               :episode-type :conversation :role "user"
                               :content "First message"})
    (episodic/append-episode! *ds*
                              {:session-id "s1" :user-id "u1"
                               :episode-type :conversation :role "assistant"
                               :content "Second message"})
    (episodic/append-episode! *ds*
                              {:session-id "s2" :user-id "u1"
                               :episode-type :conversation :role "user"
                               :content "Different session"})

    (let [recent (episodic/get-recent-episodes *ds* "s1" 10)]
      (is (= 2 (count recent)))))

  (testing "conversation history"
    (let [history (episodic/get-conversation-history *ds* "s1")]
      (is (= 2 (count history)))
      ;; Conversation history is in ASC order
      (is (= "First message" (:content (first history))))))

  (testing "total count"
    (is (= 2 (episodic/get-total-count *ds* "s1")))
    (is (= 1 (episodic/get-total-count *ds* "s2")))))

(deftest episode-type-filter-test
  (testing "filter by episode type"
    (episodic/append-episode! *ds*
                              {:session-id "s1" :user-id "u1"
                               :episode-type :conversation :role "user"
                               :content "Deploy to AWS ECS"})
    (episodic/append-episode! *ds*
                              {:session-id "s1" :user-id "u1"
                               :episode-type :action :role "tool"
                               :content "deploy_to_ecs(cluster='prod')"})
    (episodic/append-episode! *ds*
                              {:session-id "s1" :user-id "u1"
                               :episode-type :observation :role "system"
                               :content "ECS deployment successful"})

    (let [actions (episodic/search-fts *ds* "ECS"
                                       :episode-types [:action]
                                       :limit 5)]
      (is (every? #(= "action" (:episode_type %)) actions)))))

;; The EpisodicMemoryImpl protocol-implementation test was removed in
;; the unified-store refactor — the per-protocol defrecord is gone.
;; All protocol-level coverage now lives in unified_store_test.clj
;; against the IMemoryStore surface.

(deftest delete-old-episodes-test
  (testing "delete old episodes"
    (episodic/append-episode! *ds*
                              {:session-id "s1" :user-id "u1"
                               :episode-type :conversation :role "user"
                               :content "Recent message"})
    (is (= 1 (episodic/count-episodes *ds*)))
    ;; Delete episodes for a specific session
    (let [deleted (episodic/delete-session-episodes! *ds* "s1")]
      (is (= 1 deleted))
      (is (= 0 (episodic/count-episodes *ds*))))))
