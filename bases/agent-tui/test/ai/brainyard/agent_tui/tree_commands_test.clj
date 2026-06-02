;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.tree-commands-test
  "Smoke + unit tests for the persisted-session handlers
   (/session tree, /session fork, /session resume) in
   `bases/agent-tui/.../commands.clj`. Stubs the active agent and the
   persist root so the handlers operate against a tmp directory."
  (:require [ai.brainyard.agent-tui-persist.interface :as persist]
            [ai.brainyard.agent-tui.commands :as commands]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent.interface :as agent]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File]
           [java.nio.file Files]))

(def ^:dynamic *tmp-root* nil)

(defn- with-fixtures [t]
  (let [tmp (.toFile (Files/createTempDirectory "tree-cmd-test"
                                                (make-array java.nio.file.attribute.FileAttribute 0)))
        emitted (atom [])]
    (binding [*tmp-root* tmp]
      (with-redefs [tui-session/emit! (fn [s] (swap! emitted conj s) s)]
        (persist/with-root tmp
          (try (t)
               (finally
                 (doseq [^File f (reverse (file-seq tmp))]
                   (.delete f)))))))))

(use-fixtures :each with-fixtures)

(defn- last-emit
  "The most recently emitted scrollback chunk."
  []
  (last @(resolve 'commands.test/emitted)))

;; The fixture binds `emitted` lexically; we expose a reader via a
;; dynamic var that callers can deref without re-running with-redefs.
(def ^:dynamic *emit-log* nil)

(defn- with-stub-active-agent
  "Run `f` with `tui-session/get-active-agent` stubbed to return a fake
   agent whose session-id is `sid`. Captures emits into `emitted`."
  [sid emitted f]
  (let [stub-agent (reify Object
                     (toString [_] (str "stub-agent:" sid)))]
    (with-redefs [tui-session/get-active-agent (constantly stub-agent)
                  agent/session-id             (constantly sid)
                  tui-session/emit!            (fn [s] (swap! emitted conj s) s)]
      (f))))

;; -- /tree -------------------------------------------------------------------

(deftest tree-command-emits-tree-and-counts
  (let [emitted (atom [])]
    (persist/ensure-session-meta! "root" "boot")
    (Thread/sleep 2)
    (persist/fork-session! "root" "child" {:label "branch"})
    (with-stub-active-agent "root" emitted
      (fn [] (#'commands/handle-tree-command "")))
    (let [out   (str/join "\n" @emitted)
          plain (str/replace out #"\x1b\[[0-9;]*[A-Za-z]" "")]
      (is (str/includes? plain "Session tree"))
      (is (str/includes? plain "root"))
      (is (str/includes? plain "child"))
      (is (str/includes? plain "boot"))
      (is (str/includes? plain "branch"))
      (is (re-find #"▸\s+root" plain)
          "active session marked"))))

;; -- /fork -------------------------------------------------------------------

(deftest fork-command-creates-child
  (let [emitted (atom [])]
    (persist/ensure-session-meta! "active" "boot")
    (with-stub-active-agent "active" emitted
      (fn [] (#'commands/handle-fork-command "experiment")))
    (let [tree (persist/session-tree)
          children (get-in tree [:nodes "active" :children])]
      (is (= 1 (count children)))
      (let [child-id (first children)
            child-meta (persist/read-meta child-id)]
        (is (= "active" (:parent-id child-meta)))
        (is (= "experiment" (:label child-meta)))))
    (is (some #(str/includes? % "Forked") @emitted))))

(deftest fork-command-without-active-warns
  (let [emitted (atom [])]
    (with-redefs [tui-session/get-active-agent (constantly nil)
                  tui-session/emit!            (fn [s] (swap! emitted conj s) s)]
      (#'commands/handle-fork-command ""))
    (is (some #(str/includes? % "No active session") @emitted))))

;; -- /session resume ---------------------------------------------------------

(deftest resume-command-no-args-lists-sessions
  (let [emitted (atom [])]
    (persist/ensure-session-meta! "s1" "first")
    (persist/ensure-session-meta! "s2" "second")
    (with-stub-active-agent "s1" emitted
      (fn [] (#'commands/handle-resume-command "")))
    (let [out   (str/join "\n" @emitted)
          plain (str/replace out #"\x1b\[[0-9;]*[A-Za-z]" "")]
      (is (str/includes? plain "Sessions"))
      (is (str/includes? plain "s1"))
      (is (str/includes? plain "s2"))
      (is (re-find #"▸\s+s1" plain) "active session marked"))))

(deftest resume-command-known-id-reports-would-switch
  (let [emitted (atom [])]
    (persist/ensure-session-meta! "target")
    (with-stub-active-agent "current" emitted
      (fn [] (#'commands/handle-resume-command "target")))
    (is (some #(str/includes? % "would switch to") @emitted))))

(deftest resume-command-unknown-id-fails
  (let [emitted (atom [])]
    (with-stub-active-agent "anything" emitted
      (fn [] (#'commands/handle-resume-command "nope")))
    (is (some #(str/includes? % "Unknown session") @emitted))))
