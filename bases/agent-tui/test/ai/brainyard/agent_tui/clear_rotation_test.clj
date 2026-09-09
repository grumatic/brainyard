;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.clear-rotation-test
  "Coverage for the process half of `/clear` — `core/rotate-session-id!` — and
   for the resume hint `stop!` prints on the way out.

   The session id lives in exactly ONE authoritative place (`:session-id`
   inside the shared session atom) and in four derived copies that do not
   follow it on their own: the tab's `:agent-session-id`, `!tui-state`, the
   session store's key, and the per-session lock/socket registries. Every bug
   this function can have is one of those copies left behind, so that is what
   these tests enumerate.

   `format-resume-hint` is covered here rather than in the format suite because
   its contract is set by what `resumable-tabs` can hand it."
  (:require [ai.brainyard.agent-tui.core :as core]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.sessions :as sessions]
            [ai.brainyard.agent-tui-persist.interface :as persist]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [ai.brainyard.agent-tui.terminal :as terminal]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File]))

(defrecord StubAgent [!session !state]
  proto/IAgent
  (session-id [_] (:session-id @!session))
  java.io.Closeable
  (close [_] nil))

(defn- stub-agent
  "An agent that is exactly what `rotate-session-id!` reads: a session atom
   whose `:session-id` is the live id. A record rather than a `reify` because
   the function takes `(:!session ag)` by keyword — which is the whole point,
   since that atom is what every session-sharing subagent holds too."
  [sid]
  (->StubAgent (atom {:session-id sid :user-id "u-1" :messages [] :total-turns 0})
               (atom {})))

(defn- seat-tab!
  "Register `ag` as tab 0 with a persisted session already on disk."
  [ag label]
  (sessions/reset-sessions!)
  (let [sid (agent/session-id ag)]
    (persist/save-meta! sid {:agent-id :coact-agent/x :defagent-id :coact-agent
                             :model "claude-opus-5" :provider :anthropic
                             :user-id "u-1" :label label})
    (persist/write-snap! sid :input-history ["what is 2+2"])
    (persist/append-event! sid {:kind :message :payload {:role "user"}})
    ;; `:agent-id` is what `create-session!` derives the tab's `:defagent-id`
    ;; from, and `rotate-session-id!` re-stamps that onto the new meta — the
    ;; tab is the live authority on its own type and label.
    (sessions/create-session! {:id 0 :label label :agent ag
                               :agent-id :coact-agent
                               :agent-instances [ag]
                               :skip-agent-creation true})
    (swap! tui-session/!tui-state assoc :session-id sid)
    sid))

(use-fixtures :each
  (fn [t]
    (let [tmp (File/createTempFile "agent-tui-clear-rotation-" "")]
      (.delete tmp) (.mkdirs tmp)
      (try (persist/with-root tmp (t))
           (finally
             (sessions/reset-sessions!)
             (reset! terminal/!input-history [])
             (doseq [^File f (reverse (file-seq tmp))] (.delete f)))))))

(deftest rotation-moves-every-copy-of-the-session-id
  (let [ag  (stub-agent "agt-old-1")
        old (seat-tab! ag "main")
        r   (core/rotate-session-id! ag 0)
        new (:new-id r)]
    (is (= old (:old-id r)))
    (is (not= old new))
    (testing "the authoritative copy — the shared session atom"
      (is (= new (:session-id @(:!session ag))))
      (is (= new (agent/session-id ag))))
    (testing "the tab, so a switch away and back does not restore the old id"
      (is (= new (:agent-session-id (sessions/get-session 0)))))
    (testing "!tui-state, which run logging reads"
      (is (= new (:session-id @tui-session/!tui-state))))))

(deftest the-new-session-is-identified-and-labelled-like-the-tab
  (let [ag  (stub-agent "agt-old-2")
        _   (seat-tab! ag "docs")
        new (:new-id (core/rotate-session-id! ag 0))
        m   (persist/read-meta new)]
    (testing "a tab keeps its name across a clear, and so does its directory"
      (is (= "docs" (:label m))))
    (testing "and enough identity that --resume comes back as the same agent"
      (is (= :coact-agent (:defagent-id m)))
      (is (= "claude-opus-5" (:model m))))))

(deftest the-old-session-is-left-whole-and-unlocked
  (let [ag  (stub-agent "agt-old-3")
        old (seat-tab! ag "main")
        new (:new-id (core/rotate-session-id! ag 0))]
    (testing "its transcript stayed where it was written"
      (is (= 1 (persist/count-events old)))
      (is (zero? (persist/count-events new))))
    (testing "releasing the lock is what makes it resumable right away, rather
              than 'already open in another running by'"
      (is (not (persist/held-by-other-live-process? old))))
    (testing "and it names where the live process went"
      (is (= new (:rotated-to (persist/read-meta old)))))))

(deftest the-session-store-key-follows-the-atom
  (let [ag  (stub-agent "agt-old-4")
        old (seat-tab! ag "main")]
    (agent/set-session core/!session-store old (:!session ag))
    (let [new (:new-id (core/rotate-session-id! ag 0))]
      (testing "the live atom is reachable under the new id"
        (is (identical? (:!session ag) (agent/get-session core/!session-store new))))
      (testing "and NOT under the old one — otherwise resuming the old id in
                this process hands back the conversation that moved on"
        (is (nil? (agent/get-session core/!session-store old)))))))

(deftest input-history-survives-the-clear
  (let [ag (stub-agent "agt-old-5")]
    (seat-tab! ag "main")
    (reset! terminal/!input-history ["what is 2+2"])
    (let [new (:new-id (core/rotate-session-id! ag 0))]
      (testing "a clear resets the conversation, not the keyboard"
        (is (= ["what is 2+2"] (persist/read-snap new :input-history)))
        (is (= ["what is 2+2"] @terminal/!input-history))))))

(deftest rotating-twice-chains-rather-than-collides
  (let [ag  (stub-agent "agt-old-6")
        s0  (seat-tab! ag "main")
        s1  (:new-id (core/rotate-session-id! ag 0))
        s2  (:new-id (core/rotate-session-id! ag 0))]
    (is (= 3 (count (distinct [s0 s1 s2]))))
    (testing "each hop records the one before it, so the chain is walkable"
      (is (= s0 (:rotated-from (persist/read-meta s1))))
      (is (= s1 (:rotated-from (persist/read-meta s2))))
      (is (= s1 (:rotated-to (persist/read-meta s0))))
      (is (= s2 (:rotated-to (persist/read-meta s1)))))))

(deftest reclaiming-a-rotated-session-retires-its-breadcrumb
  (let [ag  (stub-agent "agt-old-7")
        old (seat-tab! ag "main")
        new (:new-id (core/rotate-session-id! ag 0))]
    (is (= new (:rotated-to (persist/read-meta old))))
    (testing "resuming the old id makes it live again under its own name, so
              the pointer at where its process went must stop being followed —
              otherwise an attach client is sent after a session that is itself
              no longer running"
      (core/release-session-lock! old)
      (core/acquire-session-lock! (stub-agent old))
      (is (nil? (:rotated-to (persist/read-meta old))))
      (core/release-session-lock! old))))

(deftest rotation-is-a-no-op-without-a-session-id
  (testing "an agent with no id has nothing to rotate — return nil rather than
            minting a directory that continues from nothing"
    (is (nil? (core/rotate-session-id! (->StubAgent (atom {}) (atom {})) 0)))))

;; ---------------------------------------------------------------------------
;; The exit hint
;; ---------------------------------------------------------------------------

(deftest resumable-tabs-skips-what-cannot-be-resumed
  (sessions/reset-sessions!)
  (let [chatty (stub-agent "agt-chatty")
        empty  (stub-agent "agt-empty")]
    (swap! (:!session chatty) assoc :messages [{:role "user"}])
    (sessions/create-session! {:id 0 :label "main" :agent chatty
                               :agent-id :stub :agent-instances [chatty]
                               :skip-agent-creation true})
    (sessions/create-session! {:id 1 :label "fresh" :agent empty
                               :agent-id :stub :agent-instances [empty]
                               :skip-agent-creation true})
    (sessions/create-session! {:id 2 :label "sub" :session-type :output
                               :sub-output-of 0 :skip-agent-creation true})
    (let [rows (#'core/resumable-tabs)]
      (testing "an :output tab views its root's stream and owns no session"
        (is (not-any? #(= "sub" (:label %)) rows)))
      (testing "a tab that said nothing has nothing to come back to"
        (is (not-any? #(= "fresh" (:label %)) rows)))
      (is (= [{:session-id "agt-chatty" :label "main"}] rows)))))

(deftest format-resume-hint-names-every-tab
  (testing "nothing saved ⇒ the exit stays as quiet as it always was"
    (is (nil? (fmt/format-resume-hint [])))
    (is (nil? (fmt/format-resume-hint nil))))
  (testing "one tab"
    (let [out (fmt/format-resume-hint [{:session-id "agt-1" :label "main"}])]
      (is (str/includes? out "Session saved."))
      (is (str/includes? out "by --resume agt-1"))))
  (testing "every tab is listed, because quitting closes all of them at once
            and the ids are not guessable"
    (let [out (fmt/format-resume-hint [{:session-id "agt-1" :label "main"}
                                       {:session-id "agt-2" :label "docs"}])]
      (is (str/includes? out "2 sessions saved."))
      (is (str/includes? out "by --resume agt-1"))
      (is (str/includes? out "by --resume agt-2"))
      (is (str/includes? out "docs")))))
