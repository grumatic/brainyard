;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.dossier-contract-test
  "One guard for every front-door agent that carries a dossier contract.

   WHY THIS IS CROSS-CUTTING RATHER THAN FOUR COPIES. A live run of
   predictor-agent authored a predictor correctly and then reported a dossier
   that did not exist: the model wrote it as a bash heredoc full of backticks,
   quotes and $(…), the emission never parsed, no tool event fired, and the next
   emission asserted the file was written. The session log had zero write-file
   and zero bash events.

   Two properties of the instruction let that happen, and both were shared by
   every agent in the family because they were copied from one another:

     1. the mechanism was left open — 'via (write-file …)' named the right tool
        without ruling out the fragile one;
     2. the FINAL-STEP checklist was self-reported — ticking 'DOSSIER WRITTEN'
        required nothing but intent, so a step that silently did not run looked
        exactly like one that did.

   So the test DISCOVERS its subjects (every registered agent whose instruction
   carries the checklist item) rather than listing them. A sixth agent adopting
   the dossier contract is covered the day it is written, which is the only way
   a family-wide rule stays one rule."
  (:require [ai.brainyard.agent.interface]
            [ai.brainyard.agent.core.tool :as tool]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- dossier-agents
  "`{agent-id instruction}` for every registered agent that claims a dossier is
   a hard final step."
  []
  (into (sorted-map)
        (keep (fn [[id td]]
                (let [ins (get-in td [:meta :instruction])]
                  (when (and (string? ins) (str/includes? ins "DOSSIER WRITTEN"))
                    [id ins]))))
        (tool/get-tool-defs :type :agent)))

(deftest the-family-is-not-empty
  (testing "the discovery actually finds the front-door agents"
    ;; A regex that matches nothing passes every assertion below vacuously.
    (let [ids (set (keys (dossier-agents)))]
      (is (<= 5 (count ids)) (str "found: " (sort ids)))
      (doseq [a [:predictor-agent :schedule-agent :event-agent
                 :state-machine-agent :a2a-agent]]
        (is (contains? ids a) (str a " should carry a dossier contract"))))))

(deftest dossier-names-its-mechanism
  (doseq [[id ins] (dossier-agents)]
    (testing (str id " forbids the write mechanism that actually failed")
      ;; Naming write-file is not enough — the failing run's instruction did
      ;; that. bash has to be ruled OUT, with the reason, or the model picks it
      ;; again for the same reason it did the first time (it is composing a
      ;; file, and a heredoc looks like the way to compose a file).
      (is (str/includes? ins "NOT bash")
          (str id ": the checklist item must name the mechanism"))
      (is (re-find #"(?i)never a bash heredoc" ins)
          (str id ": bash must be ruled out explicitly"))
      (is (re-find #"(?i)does not run AND does not report failure" ins)
          (str id ": the reason must be stated — a silent no-op is the hazard")))))

(deftest dossier-demands-a-read-back
  (doseq [[id ins] (dossier-agents)]
    (testing (str id " turns the checklist tick into evidence")
      (is (str/includes? ins "BOTH READ BACK")
          (str id ": the checklist needs a read-back item"))
      (is (re-find #"(?i)read.file" ins)
          (str id ": the read-back must name read-file"))
      (is (re-find #"(?i)never report a file you have not read back" ins)
          (str id ": the general rule must be stated, not just the checklist item")))))

(deftest dossier-is-still-mandatory
  (doseq [[id ins] (dossier-agents)]
    (testing (str id " keeps the contract hard, not advisory")
      ;; The pre-existing half of the rule. The sibling designs record that the
      ;; dossier was made mandatory after a live run skipped it while it was
      ;; advisory; hardening the mechanism must not have softened that.
      (is (re-find #"(?i)incomplete turn" ins))
      (is (str/includes? ins "FINAL-STEP CHECKLIST"))
      (is (str/includes? ins "INDEX.md")))))
