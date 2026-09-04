;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.recall-access-test
  "Usage feedback on recall — `policy/record-access!` and the manager-level
   `record-recall-access!` that dispatches it.

   A1 of docs/design/evoharness-rl-comparison.md. What the agent actually READS
   is the cheapest relevance signal available, and until this landed nothing in
   the runtime wrote it: `access_count` existed on `semantic_facts` and was
   incremented by no production caller, and `episodes` had no such column at
   all.

   Three things here are regression guards rather than API tests, and each
   protects a mistake that would be invisible in production:

     * the ALTER-when-missing migration, so an existing database gains the
       columns instead of failing every write;
     * the FTS update triggers being column-scoped, so a counter write does not
       depend on the state of a virtual table it has nothing to do with;
     * dispatch on the STORAGE layer rather than the retrieval arm, which is
       what stops every `:vec` and `:graph` hit from being silently uncounted."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.memory.core.policy :as policy]
            [ai.brainyard.memory.interface :as mem]))

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

(defn- unqualified [ds sql]
  (jdbc/execute-one! ds sql {:builder-fn rs/as-unqualified-lower-maps}))

(defn- has-col? [ds table col]
  (boolean (some #(= col (:name %))
                 (jdbc/execute! ds [(str "PRAGMA table_info(" table ")")]
                                {:builder-fn rs/as-unqualified-lower-maps}))))

(defn- access-count [ds table entry-id]
  (:access_count (unqualified ds [(str "SELECT access_count FROM " table
                                       " WHERE entry_id = ?") entry-id])))

(defn- seed! [ds]
  (jdbc/execute! ds ["INSERT INTO episodes (session_id, user_id, episode_type, content, entry_id)
                      VALUES ('s1','u1','turn','an episode','ep-1'),
                             ('s1','u1','turn','another','ep-2'),
                             ('s1','other','turn','not mine','ep-x')"])
  (jdbc/execute! ds ["INSERT INTO semantic_facts (user_id, fact_type, content, entry_id)
                      VALUES ('u1','pref','a fact','fa-1')"]))

;; ---------------------------------------------------------------- schema

(deftest both-layers-carry-the-columns-test
  (testing "episodes gained what semantic_facts always had"
    (is (has-col? *ds* "episodes" "access_count"))
    (is (has-col? *ds* "episodes" "last_accessed"))
    (is (has-col? *ds* "semantic_facts" "access_count"))))

(deftest migration-adds-columns-to-an-existing-database-test
  (testing "an episodes table predating the columns gains them on open"
    (let [ds (sqlite/create-datasource ":memory:?cache=shared2")]
      (try
        (jdbc/execute! ds ["CREATE TABLE episodes (
                              id INTEGER PRIMARY KEY AUTOINCREMENT,
                              session_id TEXT NOT NULL, user_id TEXT NOT NULL,
                              timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                              episode_type TEXT NOT NULL, role TEXT,
                              content TEXT NOT NULL, metadata TEXT, tags TEXT,
                              sources TEXT, entry_id TEXT,
                              keep_flag INTEGER NOT NULL DEFAULT 0,
                              archived_flag INTEGER NOT NULL DEFAULT 0,
                              tombstoned_flag INTEGER NOT NULL DEFAULT 0)"])
        (jdbc/execute! ds ["INSERT INTO episodes (session_id,user_id,episode_type,content,entry_id)
                            VALUES ('s','u','turn','old row','old-1')"])
        (is (not (has-col? ds "episodes" "access_count")) "precondition")

        (sqlite/init-schema! ds)
        (is (has-col? ds "episodes" "access_count"))
        (is (= 0 (access-count ds "episodes" "old-1"))
            "a pre-existing row reads 0, not NULL — nothing was counting before")
        (is (= 1 (policy/record-access! ds :l2 ["old-1"] "u")))

        (testing "and opening again is a no-op rather than a duplicate-column error"
          (sqlite/init-schema! ds)
          (is (has-col? ds "episodes" "access_count")))
        (finally (.close ds))))))

;; ---------------------------------------------------------------- counting

(deftest record-access-counts-per-layer-test
  (seed! *ds*)
  (testing "one statement updates every id in a layer"
    (is (= 2 (policy/record-access! *ds* :l2 ["ep-1" "ep-2"] "u1")))
    (is (= 1 (access-count *ds* "episodes" "ep-1")))
    (is (some? (:last_accessed (unqualified *ds* ["SELECT last_accessed FROM episodes WHERE entry_id='ep-1'"]))))

    (testing "and a second read increments again"
      (policy/record-access! *ds* :l2 ["ep-1"] "u1")
      (is (= 2 (access-count *ds* "episodes" "ep-1")))))

  (testing "L3 goes through the same path"
    (is (= 1 (policy/record-access! *ds* :l3 ["fa-1"] "u1")))
    (is (= 1 (access-count *ds* "semantic_facts" "fa-1"))))

  (testing "another user's entry is never touched — one store holds many"
    (is (= 0 (policy/record-access! *ds* :l2 ["ep-x"] "u1")))
    (is (= 0 (access-count *ds* "episodes" "ep-x")))))

(deftest record-access-is-total-test
  (seed! *ds*)
  (testing "degenerate input is a no-op, never an exception"
    (is (= 0 (policy/record-access! *ds* :l2 [] "u1")))
    (is (= 0 (policy/record-access! *ds* :l2 [nil nil] "u1")))
    (is (= 1 (policy/record-access! *ds* :l2 ["ep-2" "ep-2"] "u1"))
        "duplicate ids collapse to one row")
    (is (= 0 (policy/record-access! *ds* :l9 ["ep-1"] "u1"))
        "an unknown layer returns 0 rather than failing a turn")))

;; ------------------------------------------------- storage vs retrieval layer

(deftest dispatch-is-on-the-storage-layer-test
  (seed! *ds*)
  (let [manager {:ds *ds* :user-id "u1"}]
    (testing "a hit found only by the vector arm still lives in :l3"
      (mem/record-recall-access! manager [{:id "fa-1" :layer :l3 :_layer :vec}])
      (is (= 1 (access-count *ds* "semantic_facts" "fa-1"))))

    (testing "and one found only by the graph arm still lives in :l2"
      (mem/record-recall-access! manager [{:id "ep-1" :layer :l2 :_layer :graph}])
      (is (= 1 (access-count *ds* "episodes" "ep-1"))))

    (testing "one entry reached by two arms is ONE injection"
      ;; The RRF join keys on \"<layer>-<id>\", so the same entry arrives twice
      ;; with different :_layer values. Counting both would inflate the signal
      ;; for exactly the entries that retrieve well.
      (mem/record-recall-access! manager [{:id "fa-1" :layer :l3 :_layer :l3}
                                          {:id "fa-1" :layer :l3 :_layer :vec}])
      (is (= 2 (access-count *ds* "semantic_facts" "fa-1"))))

    (testing ":l1 is excluded — an overlay was written, not retrieved"
      (is (empty? (mem/record-recall-access! manager [{:id "x" :layer :l1 :_layer :l1}]))))

    (testing "the retrieval arm is the fallback when :layer is absent"
      (mem/record-recall-access! manager [{:id "ep-2" :_layer :l2}])
      (is (= 1 (access-count *ds* "episodes" "ep-2"))))

    (testing "a hit with no id is skipped rather than crashed on"
      (is (map? (mem/record-recall-access! manager [{:layer :l2}]))))))

;; ---------------------------------------------------------------- FTS triggers

(deftest fts-update-triggers-are-column-scoped-test
  (testing "the triggers name the columns their index actually mirrors"
    (let [episodes-au (:sql (unqualified *ds* ["SELECT sql FROM sqlite_master WHERE name='episodes_au'"]))
          semantic-au (:sql (unqualified *ds* ["SELECT sql FROM sqlite_master WHERE name='semantic_au'"]))]
      (is (str/includes? (str episodes-au) "UPDATE OF content, episode_type, role"))
      (is (str/includes? (str semantic-au) "UPDATE OF content, fact_type"))))

  (testing "so a counter write survives an FTS index that has drifted"
    ;; Unscoped, this raised SQLITE_CORRUPT_VTAB: the trigger's delete half
    ;; cannot find a row the external-content index never saw. record-access!
    ;; catches its own errors, so the symptom would have been a counter that
    ;; silently stayed at zero.
    (jdbc/execute! *ds* ["INSERT INTO episodes (session_id,user_id,episode_type,content,entry_id)
                          VALUES ('s','u','turn','indexed row','ok-1')"])
    (jdbc/execute! *ds* ["INSERT INTO episodes_fts(episodes_fts) VALUES ('delete-all')"])
    (is (= 1 (policy/record-access! *ds* :l2 ["ok-1"] "u"))))

  (testing "while a content update still reaches the index — the trigger's real job"
    (jdbc/execute! *ds* ["INSERT INTO episodes_fts(episodes_fts) VALUES ('rebuild')"])
    (jdbc/execute! *ds* ["UPDATE episodes SET content='searchable marker' WHERE entry_id='ok-1'"])
    (is (pos? (:n (unqualified *ds* ["SELECT COUNT(*) AS n FROM episodes_fts
                                      WHERE episodes_fts MATCH 'searchable'"]))))))
