;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-persist.rotate-test
  "Coverage for the disk half of the current `/clear`.

   `rotate-session!` is `archive-session!` run the other way: the transcript
   stays put and the LIVE PROCESS moves to a new id. Four properties carry the
   design and are each pinned here — the old session is untouched and stays
   resumable, the new one is genuinely EMPTY, identity travels but process
   state does not, and the two are linked in both directions."
  (:require [ai.brainyard.agent-tui-persist.interface :as persist]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File]
           [java.nio.file Files]))

(defn- with-tmp-root [f]
  (let [tmp (.toFile (Files/createTempDirectory "agent-tui-persist-rotate-test"
                                                (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (persist/with-root tmp (f))
         (finally (doseq [^File f' (reverse (file-seq tmp))] (.delete f'))))))

(use-fixtures :each with-tmp-root)

(defn- populate!
  "A live session with the full spread: identity, per-process state that must
   NOT travel, conversation, and the two user-scoped files that must."
  [sid]
  (persist/save-meta! sid {:agent-id     :coact-agent/lavender-koala-244
                           :defagent-id  :coact-agent
                           :model        "claude-opus-5"
                           :provider     :anthropic
                           :user-id      "u-1"
                           :working-dir  "/w"
                           :label        "main"
                           ;; process state — belongs to the id being left
                           :pid              4242
                           :ask-socket-path  "/tmp/old.sock"
                           :cleared-from     "agt-ancient"})
  (persist/append-event! sid {:kind :message :payload {:role "user" :content "q1"}})
  (persist/append-event! sid {:kind :message :payload {:role "assistant" :content "a1"}})
  (persist/write-snap! sid :session {:user-id "u-1" :total-turns 3})
  (persist/write-snap! sid :usage-tracker {:totals {:total-cost 0.42}})
  (persist/write-snap! sid :input-history ["what is 2+2" "/model"])
  (persist/write-snap! sid :permissions {:allowed #{"bash:ls"}})
  (persist/write-snap! sid :todo [{:text "ship it" :done false}])
  (persist/append-scrollback! sid :stream "ROW-1\n"))

(deftest rotate-leaves-the-conversation-where-it-is
  (populate! "agt-old")
  (persist/rotate-session! "agt-old" "agt-new")
  (testing "the old session still holds every byte of its transcript"
    (is (= 2 (persist/count-events "agt-old")))
    (is (= {:user-id "u-1" :total-turns 3} (persist/read-snap "agt-old" :session)))
    (is (= {:totals {:total-cost 0.42}} (persist/read-snap "agt-old" :usage-tracker)))
    (is (= "ROW-1\n" (persist/read-scrollback "agt-old" :stream))))
  (testing "and stays fully identified, so `by --resume <old>` still works"
    (let [m (persist/read-meta "agt-old")]
      (is (= :coact-agent (:defagent-id m)))
      (is (= "claude-opus-5" (:model m))))))

(deftest the-new-session-is-empty
  (populate! "agt-old")
  (persist/rotate-session! "agt-old" "agt-new")
  (testing "no conversation follows the process"
    (is (zero? (persist/count-events "agt-new")))
    (is (nil? (persist/read-snap "agt-new" :session)))
    (is (nil? (persist/read-snap "agt-new" :usage-tracker)))
    (is (= "" (persist/read-scrollback "agt-new" :stream))))
  (testing "todo belongs to the work that was cleared, so it does not travel"
    (is (nil? (persist/read-snap "agt-new" :todo)))))

(deftest identity-travels-but-process-state-does-not
  (populate! "agt-old")
  (persist/rotate-session! "agt-old" "agt-new")
  (let [m (persist/read-meta "agt-new")]
    (testing "enough identity to resume as the same kind of agent"
      (is (= :coact-agent/lavender-koala-244 (:agent-id m)))
      (is (= :coact-agent (:defagent-id m)))
      (is (= "claude-opus-5" (:model m)))
      (is (= :anthropic (:provider m)))
      (is (= "u-1" (:user-id m)))
      (is (= "/w" (:working-dir m)))
      (is (= "main" (:label m))))
    (testing "nothing that describes the directory being left behind"
      (is (nil? (:pid m)))
      (is (nil? (:ask-socket-path m)))
      (is (nil? (:cleared-from m))))
    (testing "fresh timestamps in BOTH conventions meta.edn uses — #inst for
              the tree sort, epoch millis for the TTL sweep and picker order"
      (is (inst? (:created-at m)))
      (is (inst? (:last-active m)))
      (is (int? (:started-at m)))
      (is (int? (:last-attached-at m))))))

(deftest user-scoped-files-are-copied-forward-not-moved
  (populate! "agt-old")
  (let [r (persist/rotate-session! "agt-old" "agt-new")]
    (is (= [:input-history :permissions] (:carried r)))
    (testing "the person's recall list and approvals follow the process"
      (is (= ["what is 2+2" "/model"] (persist/read-snap "agt-new" :input-history)))
      (is (= {:allowed #{"bash:ls"}} (persist/read-snap "agt-new" :permissions))))
    (testing "and the old session keeps its own copies, so it resumes intact"
      (is (= ["what is 2+2" "/model"] (persist/read-snap "agt-old" :input-history)))
      (is (= {:allowed #{"bash:ls"}} (persist/read-snap "agt-old" :permissions))))))

(deftest the-pair-is-linked-in-both-directions
  (populate! "agt-old")
  (persist/rotate-session! "agt-old" "agt-new")
  (testing "forward: the old meta names where the live process went, which is
            the only way an attach client holding the old id can be told
            anything better than 'not running'"
    (is (= "agt-new" (:rotated-to (persist/read-meta "agt-old")))))
  (testing "backward: the new meta names what it continued from"
    (is (= "agt-old" (:rotated-from (persist/read-meta "agt-new")))))
  (testing "and the old session stops advertising a socket it no longer has —
            these three fields are what `by sessions list` reports as live"
    (let [m (persist/read-meta "agt-old")]
      (is (nil? (:pid m)))
      (is (nil? (:ask-socket-path m)))
      (is (nil? (:ops m))))))

(deftest the-old-session-is-stamped-as-just-attached
  (populate! "agt-old")
  (let [before (:last-attached-at (persist/read-meta "agt-old"))]
    (is (nil? before) "populate! never set one")
    (persist/rotate-session! "agt-old" "agt-new")
    (testing "the session you just cleared is the likeliest one you want back,
              and the resume picker orders on this"
      (is (int? (:last-attached-at (persist/read-meta "agt-old")))))))

(deftest a-missing-old-session-still-produces-a-usable-new-one
  (testing "rotation must never be the thing that fails a /clear — an old id
            with no meta.edn yields a new session that is merely unlabelled"
    (let [r (persist/rotate-session! "agt-ghost" "agt-new")]
      (is (= "agt-new" (:new-id r)))
      (is (= [] (:carried r)))
      (is (= "agt-ghost" (:rotated-from (persist/read-meta "agt-new")))))))

(deftest refuses-a-collision-and-a-no-op
  (populate! "agt-old")
  (persist/save-meta! "agt-taken" {:agent-id :other})
  (testing "dropping a fresh session onto an existing transcript is refused,
            not resolved"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exists"
                          (persist/rotate-session! "agt-old" "agt-taken")))
    (is (= :other (:agent-id (persist/read-meta "agt-taken")))))
  (testing "rotating onto itself would stamp a session as its own predecessor"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must differ"
                          (persist/rotate-session! "agt-old" "agt-old")))))

(deftest a-rotated-session-is-listed-and-resumable
  (populate! "agt-old")
  (persist/rotate-session! "agt-old" "agt-new")
  (testing "both ids are real sessions on disk"
    (is (= ["agt-new" "agt-old"] (persist/list-sessions))))
  (testing "and the old one restores the conversation it kept"
    (let [restored (persist/restore-session-map "agt-old")]
      (is (= 2 (count (:messages restored)))))))
