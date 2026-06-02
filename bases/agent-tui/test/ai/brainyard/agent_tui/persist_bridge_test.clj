;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.persist-bridge-test
  "Verify that persist-bridge handlers translate agent hook events into the
   right on-disk artifacts under `~/.brainyard/sessions/<sid>/`.

   Each test drives the private handlers directly via @#'private-fn (rather
   than through the live hook registry) so we exercise the bridge in isolation
   without touching other tests' hooks or background threads."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent-tui-persist.interface :as persist]
            [ai.brainyard.agent-tui.persist-bridge :as bridge])
  (:import [java.io File]
           [java.nio.file Files]))

;; --- Stub agent --------------------------------------------------------------
;;
;; We satisfy just enough of IAgent for the bridge: `session-id` reads from
;; the agent map's `:!session` atom. `:!session` and `:agent-id` are
;; accessed as map keys (the bridge does not invoke them as protocol calls).

(defn- stub-agent
  ([session-id user-id]
   (stub-agent session-id user-id :stub-agent/instance-1))
  ([session-id user-id agent-id]
   (let [!session (atom {:session-id session-id
                         :user-id    user-id
                         :messages   []
                         :data       {:traces [] :todo-info nil :artifacts []
                                      :cache-hits 0 :exceptions []}
                         :agent-activity []
                         :agent-activity-seq 0
                         :total-turns 0
                         :config     {}
                         :created-at 100
                         :updated-at 100})]
     (reify
       ai.brainyard.agent.core.protocol/IAgent
       (session-id [_] session-id)
       clojure.lang.ILookup
       (valAt [_ k]
         (case k
           :!session !session
           :agent-id agent-id
           nil))
       (valAt [_ k not-found]
         (case k
           :!session !session
           :agent-id agent-id
           not-found))))))

;; --- Fixture: tmp persistence root + clean bridge state ----------------------

(def ^:dynamic *tmp-root* nil)

(defn- with-tmp-root [t]
  (let [tmp (.toFile (Files/createTempDirectory "persist-bridge-test"
                                                (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (binding [*tmp-root* tmp]
        (persist/with-root tmp
          (bridge/reset-state!)
          (t)))
      (finally
        (doseq [^File f (reverse (file-seq tmp))]
          (.delete f))))))

(use-fixtures :each with-tmp-root)

(defn- on-session-created  [event] ((deref #'bridge/on-session-created)  event))
(defn- on-session-closed   [event] ((deref #'bridge/on-session-closed)   event))
(defn- on-instance-created [event] ((deref #'bridge/on-instance-created) event))
(defn- on-instance-closed  [event] ((deref #'bridge/on-instance-closed)  event))
(defn- on-ask-pre          [event] ((deref #'bridge/on-ask-pre)          event))
(defn- on-ask-post         [event] ((deref #'bridge/on-ask-post)         event))
(defn- on-tool-pre         [event] ((deref #'bridge/on-tool-pre)         event))
(defn- on-tool-post        [event] ((deref #'bridge/on-tool-post)        event))

;; --- Tests -------------------------------------------------------------------

(deftest session-created-writes-meta
  (testing "on-session-created seeds meta.edn with user-id and started-at"
    (on-session-created {:session-id "agt-1" :user-id "u-1"})
    (let [meta (persist/read-meta "agt-1")]
      (is (= "u-1" (:user-id meta)))
      (is (number? (:started-at meta)))
      (is (string? (:working-dir meta))))))

(deftest instance-created-records-agent-id
  (testing "on-instance-created appends event AND merges :agent-id into meta"
    (on-session-created {:session-id "agt-2" :user-id "u"})
    (on-instance-created {:agent (stub-agent "agt-2" "u" :coact-agent/abc)})
    (is (= :coact-agent/abc (:agent-id (persist/read-meta "agt-2"))))
    (let [events (persist/read-events "agt-2")]
      (is (some #(= :agent.instance/created (:kind %)) events)))))

(deftest ask-pre-and-post-write-messages-and-snap
  (testing "ask/pre logs event; ask/post flushes new messages + writes session snap"
    (let [ag (stub-agent "agt-3" "u")]
      (on-session-created {:session-id "agt-3" :user-id "u"})
      (on-ask-pre {:agent ag :input "hello"})
      ;; Simulate the agent recording user + assistant messages during the ask.
      (swap! (:!session ag) update :messages conj
             {:role "user" :content "hello"})
      (swap! (:!session ag) update :messages conj
             {:role "assistant" :content "hi there" :agent-id :stub})
      (swap! (:!session ag) assoc :total-turns 1)
      (on-ask-post {:agent ag :input "hello" :result {:answer "hi there"}})
      (let [events  (persist/read-events "agt-3")
            msg-evs (filterv #(= :message (:kind %)) events)
            snap    (persist/read-snap "agt-3" :session)]
        (is (= ["hello" "hi there"] (mapv (comp :content :payload) msg-evs)))
        (is (= 1 (:total-turns snap)))
        (is (nil? (:messages snap)) "snap stores everything except :messages")))))

(deftest ask-post-deduplicates-across-asks
  (testing "high-water mark keeps :message events from duplicating on a 2nd ask"
    (let [ag (stub-agent "agt-4" "u")]
      (on-session-created {:session-id "agt-4" :user-id "u"})
      (swap! (:!session ag) update :messages conj {:role "user" :content "q1"})
      (swap! (:!session ag) update :messages conj {:role "assistant" :content "a1"})
      (on-ask-post {:agent ag :input "q1" :result {:answer "a1"}})
      (swap! (:!session ag) update :messages conj {:role "user" :content "q2"})
      (swap! (:!session ag) update :messages conj {:role "assistant" :content "a2"})
      (on-ask-post {:agent ag :input "q2" :result {:answer "a2"}})
      (let [msg-evs (->> (persist/read-events "agt-4")
                         (filterv #(= :message (:kind %)))
                         (mapv (comp :content :payload)))]
        (is (= ["q1" "a1" "q2" "a2"] msg-evs))))))

(deftest tool-use-events-recorded
  (testing "tool-use/pre and /post append events with truncated args"
    (let [ag (stub-agent "agt-5" "u")
          long-args {:big (apply str (repeat 1000 "x"))}]
      (on-tool-pre  {:agent ag :tool-name :read-file :args long-args :call-id (random-uuid) :depth 0})
      (on-tool-post {:agent ag :tool-name :read-file :args long-args :call-id (random-uuid) :depth 0 :result {:ok true}})
      (let [evs (persist/read-events "agt-5")
            pre (first (filter #(= :agent.tool-use/pre  (:kind %)) evs))
            post (first (filter #(= :agent.tool-use/post (:kind %)) evs))]
        (is (= :read-file (-> pre :payload :tool-name)))
        (is (<= (count (-> pre :payload :args-summary)) 400))
        (is (= :read-file (-> post :payload :tool-name)))))))

(deftest instance-closed-and-session-closed-events
  (testing "instance/closed and session/closed lifecycle events land on disk"
    (let [ag (stub-agent "agt-6" "u" :stub/x)]
      (on-session-created  {:session-id "agt-6" :user-id "u"})
      (on-instance-closed  {:agent ag})
      (on-session-closed   {:session-id "agt-6" :session (:!session ag)})
      (let [evs (persist/read-events "agt-6")]
        (is (some #(= :agent.instance/closed (:kind %)) evs)))
      (is (some? (persist/read-snap "agt-6" :session)) "session snap written on close"))))

(deftest tee-scrollback-writes-bytes
  (testing "tee-scrollback! appends each emit as a newline-terminated line"
    ;; The in-memory `!scrollback` atom splits every emit on `\n` into
    ;; its own entries; the on-disk file must mirror that. So even when
    ;; a caller emits content WITHOUT a trailing `\n`, the tee terminates
    ;; it — otherwise the next emit's bytes would be appended directly
    ;; on disk and resume would replay them as one concatenated line.
    (persist/session-dir "agt-tee")
    (bridge/tee-scrollback! "agt-tee" "hello")
    (bridge/tee-scrollback! "agt-tee" "world\n")
    (is (= "hello\nworld\n" (persist/read-scrollback "agt-tee" :stream))))
  (testing "tee-scrollback! with nil session-id or empty string is a no-op"
    (bridge/tee-scrollback! nil "anything")
    (bridge/tee-scrollback! "agt-tee" "")
    ;; no exception, no extra bytes
    (is (= "hello\nworld\n" (persist/read-scrollback "agt-tee" :stream)))))

(deftest snap-survives-session-id-fallback-on-restore
  (testing "round-trip via restore-session-map yields the messages we logged"
    (let [ag (stub-agent "agt-rt" "u-rt")]
      (on-session-created {:session-id "agt-rt" :user-id "u-rt"})
      (swap! (:!session ag) update :messages conj {:role "user" :content "ping"})
      (swap! (:!session ag) update :messages conj {:role "assistant" :content "pong"})
      (swap! (:!session ag) assoc :total-turns 7 :config {:lm :stub})
      (on-ask-post {:agent ag :input "ping" :result {:answer "pong"}})
      (let [restored (persist/restore-session-map "agt-rt")]
        (is (= ["ping" "pong"] (mapv :content (:messages restored))))
        (is (= 7 (:total-turns restored)))
        ;; :config is intentionally NOT round-tripped through the bridge — it
        ;; holds live runtime objects that don't serialise. restore defaults
        ;; it to {}; the TUI rebuilds it on session creation.
        (is (= {} (:config restored)))
        (is (= "u-rt" (:user-id restored)))))))

(deftest resume-primes-counts-no-duplicate-on-followup-ask
  (testing "after restore + prime-session-counts!, a follow-up ask appends ONLY new messages"
    ;; Phase 1: simulate a prior TUI run — two messages persisted, then crash.
    (let [ag1 (stub-agent "agt-resume" "u")]
      (on-session-created {:session-id "agt-resume" :user-id "u"})
      (swap! (:!session ag1) update :messages conj {:role "user" :content "q1"})
      (swap! (:!session ag1) update :messages conj {:role "assistant" :content "a1"})
      (swap! (:!session ag1) assoc :total-turns 1)
      (on-ask-post {:agent ag1 :input "q1" :result {:answer "a1"}}))
    ;; Phase 2: fresh process — bridge state was cleared by the fixture; restore.
    (bridge/reset-state!)
    (let [restored (persist/restore-session-map "agt-resume")]
      (is (= 2 (count (:messages restored))))
      ;; The TUI primes the bridge so on-ask-post only appends NEW messages.
      (bridge/prime-session-counts! "agt-resume" (count (:messages restored)))
      ;; Phase 3: new ask in the resumed session — agent's session atom is the
      ;; restored map plus newly-added messages.
      (let [!s (atom (-> restored
                         (update :messages conj {:role "user" :content "q2"})
                         (update :messages conj {:role "assistant" :content "a2"})
                         (assoc :total-turns 2)))
            ag2 (reify
                  ai.brainyard.agent.core.protocol/IAgent
                  (session-id [_] "agt-resume")
                  clojure.lang.ILookup
                  (valAt [_ k] (case k :!session !s :agent-id :stub nil))
                  (valAt [_ k nf] (case k :!session !s :agent-id :stub nf)))]
        (on-ask-post {:agent ag2 :input "q2" :result {:answer "a2"}})
        (let [all-msgs (->> (persist/read-events "agt-resume")
                            (filter #(= :message (:kind %)))
                            (mapv (comp :content :payload)))]
          (is (= ["q1" "a1" "q2" "a2"] all-msgs)
              "messages.log retains the original two; bridge appended only the new two")
          (is (= 4 (count all-msgs))
              "exactly four message events on disk — no duplicates"))))))

