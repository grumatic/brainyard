;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-persist.archive-test
  "Coverage for what `/clear` runs on.

   The old `/clear` truncated the scrollback streams and deleted messages.log,
   so a mistaken clear was unrecoverable. `archive-session!` moves the
   conversation to a new id instead. Three properties carry the design and are
   each pinned here: the conversation actually LANDS in the archive, the live
   session is left empty but still ITSELF (same id, lock and socket
   untouched), and the archive is RESUMABLE afterwards."
  (:require [ai.brainyard.agent-tui-persist.interface :as persist]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File]
           [java.nio.file Files]))

(defn- with-tmp-root [f]
  (let [tmp (.toFile (Files/createTempDirectory "agent-tui-persist-archive-test"
                                                (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (persist/with-root tmp (f))
         (finally (doseq [^File f' (reverse (file-seq tmp))] (.delete f'))))))

(use-fixtures :each with-tmp-root)

(defn- populate!
  "A session with the full spread: identity, messages, snapshot, usage, and a
   scrollback stream that has ROTATED, so the move has several files to carry."
  [sid]
  (persist/save-meta! sid {:agent-id :coact-agent :defagent-id :coact-agent
                           :model "claude-opus-5" :user-id "u-1"})
  (persist/append-event! sid {:kind :message :payload {:role "user" :content "q1"}})
  (persist/append-event! sid {:kind :message :payload {:role "assistant" :content "a1"}})
  (persist/write-snap! sid :session {:user-id "u-1" :total-turns 3})
  (persist/write-snap! sid :usage-tracker {:totals {:total-cost 0.42}})
  (doseq [i (range 1 6)]
    (persist/append-scrollback! sid :stream (str "ROW-" i "\n")
                                {:max-bytes 10 :max-rotations 3})))

(deftest archive-moves-the-conversation-and-leaves-the-live-id-empty
  (populate! "agt-live")
  (let [r (persist/archive-session! "agt-live" "agt-live-cleared-1" {:label "before /clear"})]
    (testing "the summary reports what moved"
      (is (= "agt-live-cleared-1" (:archive-id r)))
      (is (pos? (:bytes r)))
      (is (some #{"messages.log"} (:moved r)))
      (is (some #{"session.edn"} (:moved r)))
      (is (some #{"usage-tracker.edn"} (:moved r))))
    (testing "the conversation is in the archive"
      (let [m (persist/restore-session-map "agt-live-cleared-1")]
        (is (= ["q1" "a1"] (mapv :content (:messages m))))
        (is (= 3 (:total-turns m))))
      (is (= 0.42 (get-in (persist/read-snap "agt-live-cleared-1" :usage-tracker) [:totals :total-cost]))))
    (testing "and gone from the live session, which keeps its id"
      (let [m (persist/restore-session-map "agt-live")]
        (is (= "agt-live" (:session-id m)))
        (is (= [] (:messages m)))
        (is (= 0 (:total-turns m))))
      (is (= "" (persist/read-scrollback "agt-live" :stream))))))

(deftest archive-carries-every-rotation-file
  ;; A stream is not one file once it has rotated. Moving only the live file
  ;; would strand the older history in the cleared session, where the next
  ;; append would interleave new rows with it.
  (populate! "agt-rot")
  (let [before (persist/read-scrollback "agt-rot" :stream)]
    (is (< 1 (count (persist/scrollback-files "agt-rot" :stream)))
        "precondition: the stream really did rotate")
    (persist/archive-session! "agt-rot" "agt-rot-cleared-1" {})
    (is (= before (persist/read-scrollback "agt-rot-cleared-1" :stream))
        "every rotation landed, in order")
    (is (empty? (persist/scrollback-files "agt-rot" :stream))
        "and none was left behind in the live session")))

(deftest archive-is-resumable-as-the-same-kind-of-agent
  ;; meta.edn is COPIED, not moved: without :defagent-id a later
  ;; `--resume <archive-id>` comes back as the CLI default agent instead of
  ;; the one the conversation happened with.
  (populate! "agt-id")
  (persist/archive-session! "agt-id" "agt-id-cleared-1" {:label "before /clear 09:15"})
  (let [am (persist/read-meta "agt-id-cleared-1")
        lm (persist/read-meta "agt-id")]
    (is (= :coact-agent (:defagent-id am)))
    (is (= "claude-opus-5" (:model am)))
    (is (= "before /clear 09:15" (:label am)) "labelled, because the id is unmemorable")
    (is (= "agt-id" (:cleared-from am))
        "a distinct field — NOT :parent-id, which belongs to fork")
    (is (nil? (:parent-id am)) "the archive must not appear in the fork tree")
    (testing "the live session keeps its own identity"
      (is (= :coact-agent (:defagent-id lm)))
      (is (nil? (:cleared-from lm))))))

(deftest archive-never-takes-the-lock-or-the-socket
  ;; These belong to the LIVE PROCESS. A copied by-host.lock carries a live pid,
  ;; and `held-by-other-live-process?` would then refuse to resume the archive
  ;; as already open in another running by.
  (populate! "agt-lock")
  (spit (persist/file-of "agt-lock" :lock) "12345")
  (persist/archive-session! "agt-lock" "agt-lock-cleared-1" {})
  (is (.exists ^File (persist/file-of "agt-lock" :lock))
      "the live session keeps its lock")
  (is (not (.exists ^File (persist/file-of "agt-lock-cleared-1" :lock)))
      "and the archive never gets one")
  (is (not (persist/held-by-other-live-process? "agt-lock-cleared-1"))
      "so the archive is resumable"))

(deftest archive-leaves-ergonomics-and-approvals-with-the-live-session
  ;; Recalled input is not conversation, and remembered approvals are
  ;; preferences the user should not have to re-grant because they cleared.
  (populate! "agt-keep")
  (persist/write-snap! "agt-keep" :input-history ["ls" "git status"])
  (persist/save-permissions! "agt-keep" {"Bash(ls)" :allow})
  (persist/write-snap! "agt-keep" :todo {:tasks [{:id 1}]})
  (persist/archive-session! "agt-keep" "agt-keep-cleared-1" {})
  (is (= ["ls" "git status"] (persist/read-snap "agt-keep" :input-history)))
  (is (= {"Bash(ls)" :allow} (persist/read-permissions "agt-keep")))
  (is (= {:tasks [{:id 1}]} (persist/read-snap "agt-keep" :todo)))
  (is (nil? (persist/read-snap "agt-keep-cleared-1" :input-history)))
  (is (nil? (persist/read-snap "agt-keep-cleared-1" :todo))))

(deftest archive-refuses-to-merge-two-conversations
  (populate! "agt-a")
  (populate! "agt-b")
  (testing "an id that already holds a session is refused, not merged into"
    (is (thrown? Throwable (persist/archive-session! "agt-a" "agt-b" {})))
    (is (= ["q1" "a1"] (mapv :content (:messages (persist/restore-session-map "agt-b"))))
        "the would-be target is untouched")
    (is (= ["q1" "a1"] (mapv :content (:messages (persist/restore-session-map "agt-a"))))
        "and so is the source — a refused archive clears nothing"))
  (testing "archiving onto itself is refused"
    (is (thrown? Throwable (persist/archive-session! "agt-a" "agt-a" {})))))

(deftest archived-session-shows-up-for-listing-and-resume
  (populate! "agt-vis")
  (persist/archive-session! "agt-vis" "agt-vis-cleared-1" {:label "before /clear"})
  (is (some #{"agt-vis-cleared-1"} (persist/list-sessions)))
  (let [rows (persist/summarise-sessions)
        row  (first (filter #(= "agt-vis-cleared-1" (:session-id %)) rows))]
    (is (some? row) "the archive is listed")
    (is (= "before /clear" (:label row)))
    (is (pos? (:bytes row)) "with the conversation's bytes against it")))
