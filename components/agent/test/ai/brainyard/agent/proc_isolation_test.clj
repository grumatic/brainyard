;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.proc-isolation-test
  "Every LLM-supplied shell command must run in its own session.

   The protection is invisible without a terminal to block on: headless —
   CI, a test runner — /dev/tty fails ENXIO and an UNPROTECTED command looks
   identical to a protected one. So these tests assert the mechanism
   (process-group leadership) rather than the symptom (a prompt), and add a
   source-level guard, because the bug originally spread by the spawn shape
   being copied five times rather than shared."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [ai.brainyard.agent.core.proc :as proc]
            [ai.brainyard.agent.core.exec-backend :as exec-backend]
            [ai.brainyard.agent.common.tools :as ctools]
            [ai.brainyard.agent.common.skills :as skills]
            [ai.brainyard.agent.task.manager :as manager]
            [ai.brainyard.agent.task.protocol :as tp]))

;; `setsid` makes the process a group leader, so pgid == pid exactly when the
;; wrapper ran. An unwrapped child inherits the JVM's group and reports
;; pgid != pid — true with or without a controlling terminal, which is what
;; makes this assertion meaningful in CI.
(def ^:private REPORT-IDS "echo \"$$ $(ps -o pgid= -p $$ | tr -d ' ')\"")

(defn- leader?
  "True when `output` reports a process that leads its own group."
  [output]
  (let [[pid pgid] (-> (str output) string/trim (string/split #"\s+"))]
    (and pid pgid (= pid pgid))))

;; =============================================================================
;; Unit — the shared primitives
;; =============================================================================

(deftest sh-argv-passes-command-as-argv-test
  (testing "the command is an argv element, never spliced into the script"
    (let [argv (proc/sh-argv "echo 'quoted \"deeply\"' && ls")]
      (is (= "/bin/sh" (first argv)))
      (is (= "-c" (second argv)))
      (is (= proc/new-session-script (nth argv 2))
          "the script is fixed text")
      (is (= "echo 'quoted \"deeply\"' && ls" (last argv))
          "the command survives verbatim, so its quoting cannot break the wrapper")))

  (testing "perl is tried before setsid"
    ;; util-linux setsid FORKS when already a group leader, which would let the
    ;; parent exit early and lose both exit code and output.
    (let [s proc/new-session-script]
      (is (< (string/index-of s "perl") (string/index-of s "setsid"))))))

(deftest harden-env-disarms-prompts-test
  (testing "credential prompts fail rather than wait"
    (is (= "0" (get proc/non-interactive-env "GIT_TERMINAL_PROMPT")))
    (is (= "false" (get proc/non-interactive-env "GIT_ASKPASS")))
    (is (= "false" (get proc/non-interactive-env "SSH_ASKPASS")))
    (is (= "never" (get proc/non-interactive-env "SSH_ASKPASS_REQUIRE"))))

  (testing "credential.helper is NOT among them"
    ;; It answers git programmatically and never prompts; disabling it would
    ;; break keychain-backed auth that already works headlessly.
    (is (not-any? #(string/includes? (string/lower-case %) "credential")
                  (keys proc/non-interactive-env)))))

;; =============================================================================
;; Every spawn site
;; =============================================================================

(deftest inline-bash-tool-runs-in-own-session-test
  (let [tm (manager/create-task-manager)]
    (manager/set-default-manager! tm)
    (let [r (@#'ctools/run-bash-inline REPORT-IDS 10000)]
      (is (= "completed" (:status r)))
      (is (leader? (:output r)) (str "bash tool: " (pr-str (:output r)))))))

(deftest local-exec-shell-runs-in-own-session-test
  (let [r (exec-backend/local-exec-shell REPORT-IDS {:timeout-ms 10000})]
    (is (leader? (:output r)) (str "local-exec-shell: " (pr-str (:output r))))))

(deftest skills-run-cmd-sync-runs-in-own-session-test
  (let [r (@#'skills/run-cmd-sync REPORT-IDS 10000)]
    (is (zero? (:exit-code r)))
    (is (leader? (:output r)) (str "skills runner: " (pr-str (:output r))))))

(deftest bash-job-executor-runs-in-own-session-test
  (testing "the :bash task path — task$run :job-type bash"
    (let [tm (manager/create-task-manager)]
      (manager/set-default-manager! tm)
      (let [task (tp/create-task tm "session-probe" :bash
                                 {:command REPORT-IDS :timeout-ms 10000})
            _    (tp/start-task tm (:id task))
            done (loop [n 0]
                   (let [t (tp/get-task tm (:id task))]
                     (cond
                       (contains? #{:completed :failed :cancelled :timeout} (:status t)) t
                       (> n 100) t
                       :else (do (Thread/sleep (long 100)) (recur (inc n))))))]
        ;; :output-lines is an ATOM on the task record (a tail cache; the full
        ;; record lives on disk), not a plain key — (:output task) is always nil.
        (let [lines (some-> (:output-lines done) deref)]
          (is (= :completed (:status done))
              "a blocked job would still be :running here")
          (is (leader? (string/join " " lines))
              (str "bash job: " (pr-str lines))))))))

;; =============================================================================
;; Source guard — stop a sixth copy inheriting the bug
;; =============================================================================

(deftest no-raw-shell-argv-outside-proc-test
  (testing "nothing builds its own [\"/bin/sh\" \"-c\" …] for an agent command"
    ;; Located via the classpath rather than a relative path, so the test does
    ;; not depend on the runner's working directory.
    (let [proc-url (io/resource "ai/brainyard/agent/core/proc.clj")
          src-root (-> (io/file (.toURI proc-url))   ; …/agent/core/proc.clj
                       .getParentFile .getParentFile ; …/agent
                       .getParentFile .getParentFile ; …/ai/brainyard → src root
                       .getParentFile)
          offenders (->> (file-seq src-root)
                         (filter #(.endsWith (.getName %) ".clj"))
                         (remove #(= "proc.clj" (.getName %)))
                         ;; These two deliberately talk to the REAL terminal
                         ;; over /dev/tty — format.clj measures it (`stty size
                         ;; < /dev/tty`), terminal_caps.clj negotiates DEC mode
                         ;; 2027 with it (`stty -g`, `stty raw -echo min 0 time
                         ;; 5`). `sh-argv` exists to take the controlling
                         ;; terminal AWAY, so routing these through it would
                         ;; make /dev/tty fail ENXIO and defeat their entire
                         ;; purpose. The guard is about LLM-supplied commands,
                         ;; which neither of these runs: every command here is
                         ;; an `stty` literal, and the one interpolated value
                         ;; is `stty -g`'s own output being handed back to
                         ;; `stty` to restore the mode.
                         (remove #(#{"format.clj" "terminal_caps.clj"} (.getName %)))
                         (filter #(string/includes? (slurp %) "\"/bin/sh\" \"-c\""))
                         (mapv #(.getName %)))]
      (is (some? proc-url) "core/proc.clj must be on the classpath")
      (is (empty? offenders)
          (str "these build a shell argv directly instead of using "
               "agent.core.proc/sh-argv, so they miss session isolation: "
               (pr-str offenders))))))
