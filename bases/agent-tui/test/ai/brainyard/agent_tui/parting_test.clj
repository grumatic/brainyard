;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.parting-test
  "The parting line + resume hint printed on TUI exit.

   Two exit routes reach it: `/quit` runs `stop!`, and the JVM shutdown hook
   runs too; double-Ctrl-C and SIGTERM reach the hook ALONE. Before the hook
   learned to print, a Ctrl-C exit saved the session and never named it — the
   id is unguessable, so the conversation was only findable by trawling
   `by sessions list`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent-tui.core :as core]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.terminal :as terminal]))

(def ^:private !emitted (atom []))

(defn- reset-latch! []
  (reset! (var-get #'core/!parting-printed?) false))

(defn with-capture [f]
  (reset! !emitted [])
  (reset-latch!)
  (with-redefs [tui-session/emit! (fn [s & _] (swap! !emitted conj s) nil)]
    (f))
  (reset-latch!))

(use-fixtures :each with-capture)

(defn- joined [] (apply str @!emitted))

(deftest prints-the-parting-line-and-the-resume-command
  (with-redefs [terminal/stdout-terminal? (constantly true)]
    (core/print-parting! [{:session-id "agt-1-2" :label "main0"}])
    (testing "the session is named, and named with the command that reopens it"
      (is (re-find #"TUI session ended" (joined)))
      (is (re-find #"Session saved" (joined)))
      (is (re-find #"by --resume agt-1-2" (joined)))
      (is (re-find #"main0" (joined))))))

(deftest prints-once-across-both-exit-routes
  ;; `/quit` prints via `stop!`; the shutdown hook then runs for the same
  ;; process. Without the latch the user sees the block twice.
  (with-redefs [terminal/stdout-terminal? (constantly true)]
    (core/print-parting! [{:session-id "agt-1-2" :label "main0"}])
    (core/print-parting! [{:session-id "agt-1-2" :label "main0"}])
    (is (= 1 (count (re-seq #"TUI session ended" (joined)))))
    (is (= 1 (count (re-seq #"by --resume agt-1-2" (joined)))))))

(deftest an-empty-session-list-prints-no-resume-command
  (with-redefs [terminal/stdout-terminal? (constantly true)]
    (core/print-parting! [])
    (testing "a run that held no conversation exits as quietly as it always did"
      (is (re-find #"TUI session ended" (joined)))
      (is (not (re-find #"by --resume" (joined)))))))

(deftest every-resumable-tab-is-named-not-just-the-active-one
  ;; Quitting closes all tabs at once and the ids are not guessable.
  (with-redefs [terminal/stdout-terminal? (constantly true)]
    (core/print-parting! [{:session-id "agt-a" :label "main0"}
                          {:session-id "agt-b" :label "docs"}])
    (is (re-find #"2 sessions saved" (joined)))
    (is (re-find #"by --resume agt-a" (joined)))
    (is (re-find #"by --resume agt-b" (joined)))))

(deftest a-non-terminal-run-stays-silent
  ;; Addressed to a person about to lose their scrollback; a `--serve` daemon
  ;; has no one to read it, and stdout may be a pipe someone is parsing.
  (with-redefs [terminal/stdout-terminal? (constantly false)]
    (core/print-parting! [{:session-id "agt-1-2" :label "main0"}])
    (is (empty? @!emitted))))
