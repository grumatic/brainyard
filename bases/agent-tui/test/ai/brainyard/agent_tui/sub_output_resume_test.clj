;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.sub-output-resume-test
  "Persisting and restoring the shared sub-output tab.

   The tab is created with `:skip-agent-creation true`, so it has no agent and
   its `:agent-session-id` is nil — and `emit-to-session!` teed to disk only
   when that was present. Every sub-agent transcript therefore lived in memory
   and nowhere else: `--resume` brought back the conversation and silently
   dropped the tab that had recorded how the answers were arrived at.

   Its bytes go to the ROOT's `:sub-output` stream rather than a directory of
   its own, because that is already its lifetime — one tab per root, created on
   the root's first dispatch, cascade-closed with it."
  (:require [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.session :as session]
            [ai.brainyard.agent-tui.sessions :as sessions]
            [ai.brainyard.agent-tui-persist.interface :as persist]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File]))

(def ^:private root-asid "agt-root-1234")

(defn- stub-agent
  "Stands in for the tab's agent, which `create-session!` asks for a session-id."
  [session-id agent-id]
  (reify
    ai.brainyard.agent.core.protocol/IAgent
    (session-id [_] session-id)
    (agent-id [_] agent-id)
    java.io.Closeable
    (close [_] nil)))

(defn- root-agent
  "Stands in for the ROOT agent handed to the restore. The real one is an Agent
   DEFRECORD and the sub-output registry reads `(:agent-id root-agent)` off it
   by map access, so a map is the faithful stand-in here — a `reify` answers nil
   to that lookup and would silently key the tab under nil."
  [agent-id]
  {:agent-id agent-id})

(defn- fake-tabs!
  "A root chat tab (0, active) with a persisted session id, and no output tab —
   the state a fresh process is in."
  []
  (sessions/reset-sessions!)
  (reset! session/!root-output-sessions {})
  (reset! layout/!scrollback [])
  (reset! layout/!scrollback-src [])
  (reset! layout/!live-blocks {})
  (swap! layout/!layout assoc :mode :inline :cols 100)
  (sessions/create-session! {:id 0
                             :label "main0"
                             :agent (stub-agent root-asid :coact-agent/root-1)
                             :agent-id :coact-agent/root-1
                             :agent-instances []
                             :skip-agent-creation true}))

(use-fixtures :each
  (fn [t]
    (let [tmp (File/createTempFile "sub-output-resume-" "")]
      (.delete tmp) (.mkdirs tmp)
      (try (persist/with-root tmp (t))
           (finally
             (sessions/reset-sessions!)
             (reset! session/!root-output-sessions {})
             (doseq [^File f (reverse (file-seq tmp))] (.delete f)))))))

(defn- output-tab!
  "Create an output-only tab under root tab 0, the way
   `ensure-shared-sub-output-session!` does."
  []
  (sessions/create-session! {:label "main0↓"
                             :agent-id :sub-output/root-1
                             :session-type :output
                             :sub-output-of 0
                             :skip-agent-creation true}))

(defn- stream [tag]
  (persist/tail-scrollback root-asid tag 1000000))

;; ---------------------------------------------------------------------------
;; Write
;; ---------------------------------------------------------------------------

(deftest an-output-tab-tees-to-its-roots-sub-output-stream
  (testing "the tab has no session id of its own, so it borrows the root's"
    (fake-tabs!)
    (let [sidx (output-tab!)]
      (is (nil? (:agent-session-id (sessions/get-session sidx)))
          "precondition: this is why nothing was persisted")
      (is (= root-asid (sessions/sub-output-tee-target sidx)))
      (sessions/emit-to-session! sidx "sub-agent said this")
      (is (str/includes? (str (stream :sub-output)) "sub-agent said this"))
      (is (not (str/includes? (str (stream :stream)) "sub-agent said this"))
          "and it must NOT land in the conversation stream — that is the
           transcript `--resume` replays into the chat tab"))))

(deftest a-chat-tab-is-unaffected
  (testing "a tab with its own session id still tees where it always did"
    (fake-tabs!)
    (is (nil? (sessions/sub-output-tee-target 0)) "not an output tab")
    (sessions/emit-to-session! 0 "user-facing line")
    (is (str/includes? (str (stream :stream)) "user-facing line"))
    (is (str/blank? (str (stream :sub-output))))))

(deftest the-replay-does-not-write-itself-back
  (testing ":persist? false — else every resume doubles the transcript"
    (fake-tabs!)
    (let [sidx (output-tab!)]
      (sessions/emit-to-session! sidx "recorded once")
      (let [before (str (stream :sub-output))]
        (sessions/emit-to-session! sidx "recorded once" {:persist? false})
        (is (= before (str (stream :sub-output))))))))

;; ---------------------------------------------------------------------------
;; Restore
;; ---------------------------------------------------------------------------

(deftest restoring-brings-the-tab-back-with-its-transcript
  (testing "a resumed root gets its output tab, content and all"
    (fake-tabs!)
    (let [root (root-agent :coact-agent/root-2)   ;; NEW instance id
          tail "── explore-agent · ask ──\n❯ a question\nOK\n"
          sidx (session/restore-sub-output-session!
                root 0 tail (fn [_cols] (str/split-lines tail)))]
      (is (some? sidx) "the tab exists again")
      (is (= :output (:session-type (sessions/get-session sidx))))
      (is (= 0 (:sub-output-of (sessions/get-session sidx))))
      (is (str/includes? (str/join " " (:scrollback (sessions/get-session sidx)))
                         "a question")
          "carrying what the sub-agents rendered last time")
      (is (false? (:has-unread? (sessions/get-session sidx)))
          "history, not new activity — an unread marker would be a lie")
      (is (str/blank? (str (stream :sub-output)))
          "and the replay did not write the transcript back to disk"))))

(deftest a-restored-tab-is-the-one-the-next-dispatch-finds
  (testing "registered under the RESUMED root's id, so no second tab appears"
    ;; The hazard this pins: a resumed root is a new instance with a new
    ;; agent-id. Restore the tab without registering it there and the next
    ;; sub-agent dispatch creates a SECOND output tab — history in one, live
    ;; output in the other.
    (fake-tabs!)
    (let [root (root-agent :coact-agent/root-2)
          tail "prior transcript\n"
          sidx (session/restore-sub-output-session!
                root 0 tail (fn [_cols] (str/split-lines tail)))]
      (is (= sidx (get @session/!root-output-sessions :coact-agent/root-2))
          "keyed by the resumed root's agent-id")
      (is (= 2 (sessions/session-count))
          "exactly two tabs: the chat tab and the one restored tab"))))

(deftest nothing-to-restore-is-not-an-error
  (testing "a session that never dispatched a sub-agent gets no empty tab"
    (fake-tabs!)
    (let [root (root-agent :coact-agent/root-2)]
      (is (nil? (session/restore-sub-output-session! root 0 "" (constantly []))))
      (is (nil? (session/restore-sub-output-session! root 0 nil (constantly []))))
      (is (= 1 (sessions/session-count)) "no tab was created"))))
