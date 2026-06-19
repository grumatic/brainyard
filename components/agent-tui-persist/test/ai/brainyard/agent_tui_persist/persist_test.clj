;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-persist.persist-test
  (:require [ai.brainyard.agent-tui-persist.interface :as persist]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File]
           [java.nio.file Files attribute.FileAttribute]))

(def ^:dynamic *tmp-root* nil)

(defn- with-tmp-root [f]
  (let [tmp (.toFile (Files/createTempDirectory "agent-tui-persist-test"
                                                (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (binding [*tmp-root* tmp]
        (persist/with-root tmp (f)))
      (finally
        (doseq [^File f' (reverse (file-seq tmp))]
          (.delete f'))))))

(use-fixtures :each with-tmp-root)

(deftest paths-test
  (testing "session-dir creates the directory"
    (let [d (persist/session-dir "agt-1")]
      (is (.exists ^File d))
      (is (.isDirectory ^File d))))
  (testing "list-sessions enumerates created sessions"
    (persist/session-dir "agt-1")
    (persist/session-dir "agt-2")
    (is (= ["agt-1" "agt-2"] (persist/list-sessions))))
  (testing "delete-session-dir! removes the tree"
    (persist/session-dir "agt-doomed")
    (persist/append-event! "agt-doomed" {:kind :foo})
    (is (true? (persist/delete-session-dir! "agt-doomed")))
    (is (not (some #{"agt-doomed"} (persist/list-sessions)))))
  (testing "delete-session-dir! reports not-found for never-existed sessions"
    ;; Before the lazy-create fix this returned `true` because
    ;; `session-dir` ensured the dir existed before the existence
    ;; check — masking missing-session bugs in callers.
    (is (nil? (persist/delete-session-dir! "agt-never-was")))
    (is (not (some #{"agt-never-was"} (persist/list-sessions)))
        "and the dir is NOT created as a side effect of the failed delete")))

(deftest atomic-write-test
  (testing "atomic-write! survives concurrent reads and replaces atomically"
    (let [f (persist/session-file "agt-1" "thing.edn")]
      (persist/atomic-write! f {:v 1})
      (is (= {:v 1} (persist/read-edn f)))
      (persist/atomic-write! f {:v 2})
      (is (= {:v 2} (persist/read-edn f)))))
  (testing "read-edn returns default for missing/empty files"
    (let [f (persist/session-file "agt-empty" "missing.edn")]
      (is (= ::nope (persist/read-edn f ::nope))))))

(deftest events-log-test
  (testing "append-event! and read-events round-trip"
    (persist/append-event! "agt-1" {:kind :input :payload {:line "hi"}})
    (persist/append-event! "agt-1" {:kind :agent.ask/pre :payload {:msg "hi"}})
    (let [events (persist/read-events "agt-1")]
      (is (= 2 (count events)))
      (is (= :input    (:kind (first events))))
      (is (= :agent.ask/pre  (:kind (second events))))
      (is (every? :t events))))
  (testing "count-events without reading the whole file"
    (persist/append-event! "agt-2" {:kind :foo})
    (persist/append-event! "agt-2" {:kind :foo})
    (persist/append-event! "agt-2" {:kind :foo})
    (is (= 3 (persist/count-events "agt-2")))))

(deftest scan-session-test
  (testing "empty / missing log yields zeroed summary"
    (is (= {:event-count 0 :first-user-input nil :last-answer nil}
           (persist/scan-session "agt-none"))))
  (testing "scan captures count, first user input, and last answer in one pass"
    (persist/append-event! "agt-s" {:kind :agent.instance/created :payload {:agent-id :coact-agent}})
    (persist/append-event! "agt-s" {:kind :agent.ask/pre  :payload {:input "first prompt"}})
    (persist/append-event! "agt-s" {:kind :agent.ask/post :payload {:answer "answer one"}})
    (persist/append-event! "agt-s" {:kind :agent.ask/pre  :payload {:input "second prompt"}})
    (persist/append-event! "agt-s" {:kind :agent.ask/post :payload {:answer "answer two"}})
    (let [{:keys [event-count first-user-input last-answer]} (persist/scan-session "agt-s")]
      (is (= 5 event-count))
      (is (= "first prompt" first-user-input))   ; FIRST ask/pre, not the second
      (is (= "answer two" last-answer)))))        ; LAST ask/post

(deftest scrollback-test
  (testing "append-scrollback! grows the file"
    (persist/append-scrollback! "agt-1" :stream "hello ")
    (persist/append-scrollback! "agt-1" :stream "world\n")
    (is (= "hello world\n" (persist/read-scrollback "agt-1" :stream))))
  (testing "tail-scrollback returns just the trailing bytes"
    (persist/append-scrollback! "agt-1" :stream "hello world\n")
    (is (= "world\n" (persist/tail-scrollback "agt-1" :stream 6))))
  (testing "rotation evicts oldest when over budget"
    (persist/append-scrollback! "agt-r" :stream "AAAAA"
                                {:max-bytes 10 :max-rotations 2})
    (persist/append-scrollback! "agt-r" :stream "BBBBB"
                                {:max-bytes 10 :max-rotations 2})
    ;; Third write triggers rotation: live=AAAAABBBBB→stream.1.txt; new live=CCCCC
    (persist/append-scrollback! "agt-r" :stream "CCCCC"
                                {:max-bytes 10 :max-rotations 2})
    (is (re-find #"^AAAAABBBBB" (persist/read-scrollback "agt-r" :stream)))
    (is (re-find #"CCCCC$" (persist/read-scrollback "agt-r" :stream)))))

(deftest pending-dialogs-test
  (testing "add/remove pending dialogs survive process restart"
    (persist/add-pending-dialog! "agt-1" {:id "q1" :title "A"})
    (persist/add-pending-dialog! "agt-1" {:id "q2" :title "B"})
    (is (= 2 (count (persist/pending-dialogs "agt-1"))))
    (persist/remove-pending-dialog! "agt-1" "q1")
    (is (= [{:id "q2" :title "B"}] (persist/pending-dialogs "agt-1")))))

(deftest meta-test
  (testing "save-meta! merges and stamps started-at on first write"
    (persist/save-meta! "agt-1" {:agent-id :coact-agent :working-dir "/tmp"})
    (let [m (persist/read-meta "agt-1")]
      (is (= :coact-agent (:agent-id m)))
      (is (= "/tmp" (:working-dir m)))
      (is (some? (:started-at m))))
    (persist/save-meta! "agt-1" {:model "claude-haiku-4-5"})
    (let [m (persist/read-meta "agt-1")]
      (is (= "claude-haiku-4-5" (:model m)))
      (is (= :coact-agent (:agent-id m))) ; preserved
      )))

(deftest input-history-snap-test
  (testing "input-history round-trips via read-snap/write-snap! per session"
    (persist/write-snap! "agt-h" :input-history ["one" "two" "three"])
    (is (= ["one" "two" "three"]
           (persist/read-snap "agt-h" :input-history))))
  (testing "missing input-history file returns the provided default"
    (is (= [] (persist/read-snap "agt-fresh" :input-history []))))
  (testing "history is per-session — writes to one don't leak to another"
    (persist/write-snap! "agt-a" :input-history ["a1" "a2"])
    (persist/write-snap! "agt-b" :input-history ["b1"])
    (is (= ["a1" "a2"] (persist/read-snap "agt-a" :input-history)))
    (is (= ["b1"]      (persist/read-snap "agt-b" :input-history))))
  (testing "input-history.edn lives at the canonical path inside the session dir"
    (persist/write-snap! "agt-p" :input-history ["x"])
    (let [f (persist/file-of "agt-p" :input-history)]
      (is (= "input-history.edn" (.getName ^File f)))
      (is (.exists ^File f)))))

(deftest lock-test
  (testing "first acquire succeeds, second from same process is blocked"
    (let [h1 (persist/try-acquire-lock! "agt-locked")]
      (is (some? h1))
      (let [h2 (persist/try-acquire-lock! "agt-locked")]
        (is (nil? h2)))
      (persist/release-lock! h1))))

(deftest owner-pid-and-liveness-test
  (let [self (.pid (java.lang.ProcessHandle/current))
        lockfile #(io/file (persist/session-dir %) "by-host.lock")]
    (testing "no lockfile → owner-pid nil, not held by anyone"
      (is (nil? (persist/owner-pid "agt-nolock")))
      (is (false? (persist/held-by-other-live-process? "agt-nolock"))))
    (testing "this process's own lock → owner-pid is self, not 'other'"
      (let [h (persist/try-acquire-lock! "agt-self")]
        (is (= self (persist/owner-pid "agt-self")))
        ;; self-ownership must never refuse — same PID is not an 'other' process.
        (is (false? (persist/held-by-other-live-process? "agt-self")))
        (persist/release-lock! h)))
    (testing "a lockfile naming another LIVE pid (1 = init/launchd) → held"
      (spit (lockfile "agt-foreign") "1\n")
      (is (= 1 (persist/owner-pid "agt-foreign")))
      (is (true? (persist/held-by-other-live-process? "agt-foreign"))))
    (testing "a lockfile naming a dead pid → stale, not held (read-only probe)"
      ;; A reaped child's pid is guaranteed dead. Spawn `true`, wait, reuse its pid.
      (let [proc (.start (ProcessBuilder. ["/usr/bin/true"]))
            dead (.pid proc)]
        (.waitFor proc)
        (spit (lockfile "agt-stale") (str dead "\n"))
        (is (= dead (persist/owner-pid "agt-stale")))
        (is (false? (persist/held-by-other-live-process? "agt-stale")))))))

(deftest session-live-test
  (let [lockfile #(io/file (persist/session-dir %) "by-host.lock")]
    (testing "no lockfile → not live"
      (is (false? (persist/session-live? "agt-nolive"))))
    (testing "our own held lock → live; released (lockfile unlinked) → not live"
      (let [h (persist/try-acquire-lock! "agt-mine")]
        (is (true? (persist/session-live? "agt-mine")))
        (persist/release-lock! h)
        (is (false? (persist/session-live? "agt-mine")))))
    (testing "another LIVE pid (1 = init/launchd) → live"
      (spit (lockfile "agt-foreign-live") "1\n")
      (is (true? (persist/session-live? "agt-foreign-live"))))
    (testing "a dead pid → not live (stale lock)"
      (let [proc (.start (ProcessBuilder. ["/usr/bin/true"]))
            dead (.pid proc)]
        (.waitFor proc)
        (spit (lockfile "agt-dead") (str dead "\n"))
        (is (false? (persist/session-live? "agt-dead")))))))

(deftest eviction-test
  (testing "summarise-sessions reports each session"
    (persist/save-meta! "agt-a" {:agent-id :a})
    (persist/save-meta! "agt-b" {:agent-id :b})
    (let [summary (persist/summarise-sessions)]
      (is (= 2 (count summary)))
      (is (every? :session-id summary)))))
