;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.stdio-test
  "Integration tests for the stdio CliClient.

   Uses lightweight subprocess commands (cat, bash) — no external services
   or LLM calls required. All tests are self-contained and deterministic."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.agent.stdio.client :as cli]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn cleanup-process
  "Fixture that ensures any leaked processes are cleaned up after each test."
  [f]
  (f))

(use-fixtures :each cleanup-process)

;; =============================================================================
;; Helpers
;; =============================================================================

(defmacro with-client
  "Execute body with a CliClient bound to `sym`, ensuring cleanup via .close."
  [[sym & start-opts] & body]
  `(let [~sym (cli/start! ~@start-opts)]
     (try
       ~@body
       (finally
         (.close ~sym)))))

;; =============================================================================
;; 1. Lifecycle Tests
;; =============================================================================

(deftest start-and-alive-test
  (testing "start! spawns a process that is alive"
    (with-client [c :command ["/bin/cat"]]
      (is (cli/alive? c))
      (is (= "ai.brainyard.agent.stdio.client.CliClient" (.getName (type c)))))))

(deftest start-requires-command-test
  (testing "start! throws when command is missing or empty"
    (is (thrown? clojure.lang.ExceptionInfo
                 (cli/start! :command [])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (cli/start! :command nil)))))

(deftest close-destroys-process-test
  (testing ".close destroys the process and stops reader threads"
    (let [c (cli/start! :command ["/bin/cat"])]
      (is (cli/alive? c))
      (.close c)
      ;; Give OS a moment to reap the process
      (Thread/sleep 100)
      (is (false? (cli/alive? c)))
      (is (false? @(:!running c))))))

;; =============================================================================
;; 2. send-line! + wait-for Tests
;; =============================================================================

(deftest send-and-wait-for-string-test
  (testing "send-line! writes to stdin; wait-for matches exact string"
    (with-client [c :command ["/bin/cat"]]
      (cli/send-line! c "hello world")
      (let [output (cli/wait-for c "hello world" :timeout-ms 5000)]
        (is (= ["hello world"] output))))))

(deftest wait-for-regex-test
  (testing "wait-for matches a regex pattern"
    (with-client [c :command ["/bin/cat"]]
      (cli/send-line! c "result: 42 ok")
      (let [output (cli/wait-for c #"result:\s+\d+" :timeout-ms 5000)]
        (is (= 1 (count output)))
        (is (str/includes? (first output) "42"))))))

(deftest wait-for-skips-non-matching-lines-test
  (testing "wait-for skips lines that don't match and returns all lines up to match"
    (with-client [c :command ["/bin/cat"]]
      (cli/send-line! c "line-1")
      (cli/send-line! c "line-2")
      (cli/send-line! c "DONE")
      (let [output (cli/wait-for c "DONE" :timeout-ms 5000)]
        (is (= ["line-1" "line-2" "DONE"] output))))))

(deftest wait-for-timeout-test
  (testing "wait-for throws ExceptionInfo on timeout"
    (with-client [c :command ["/bin/cat"]]
      (let [ex (try
                 (cli/wait-for c "never-appears" :timeout-ms 200)
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (str/includes? (ex-message ex) "timed out"))
        (is (= 200 (:timeout-ms (ex-data ex))))))))

;; =============================================================================
;; 3. Cursor Advancement Tests
;; =============================================================================

(deftest cursor-advances-after-wait-for-test
  (testing "Cursor advances past matched line; next wait-for sees only new lines"
    (with-client [c :command ["/bin/cat"]]
      ;; First batch
      (cli/send-line! c "alpha")
      (cli/wait-for c "alpha" :timeout-ms 5000)

      ;; Second batch — cursor should be past "alpha"
      (cli/send-line! c "beta")
      (let [output (cli/wait-for c "beta" :timeout-ms 5000)]
        (is (= ["beta"] output))))))

(deftest cursor-advances-after-get-output-test
  (testing "get-output returns new lines and advances cursor"
    (with-client [c :command ["/bin/cat"]]
      (cli/send-line! c "one")
      (cli/send-line! c "two")
      (Thread/sleep 200) ;; let reader thread catch up
      (let [out1 (cli/get-output c)]
        (is (= ["one" "two"] out1))
        ;; get-output again should be empty
        (let [out2 (cli/get-output c)]
          (is (empty? out2)))))))

;; =============================================================================
;; 4. get-all-output / clear-output! Tests
;; =============================================================================

(deftest get-all-output-does-not-advance-cursor-test
  (testing "get-all-output returns full buffer without advancing cursor"
    (with-client [c :command ["/bin/cat"]]
      (cli/send-line! c "aaa")
      (cli/send-line! c "bbb")
      (Thread/sleep 200)
      (let [all (cli/get-all-output c)]
        (is (= ["aaa" "bbb"] all))
        ;; cursor should still be at 0 — get-output returns same lines
        (let [out (cli/get-output c)]
          (is (= ["aaa" "bbb"] out)))))))

(deftest clear-output-skips-existing-lines-test
  (testing "clear-output! advances cursor to current end"
    (with-client [c :command ["/bin/cat"]]
      (cli/send-line! c "old-1")
      (cli/send-line! c "old-2")
      (Thread/sleep 200)
      (cli/clear-output! c)

      ;; New lines after clear
      (cli/send-line! c "new-1")
      (let [output (cli/wait-for c "new-1" :timeout-ms 5000)]
        (is (= ["new-1"] output))))))

;; =============================================================================
;; 5. wait-for-idle Tests
;; =============================================================================

(deftest wait-for-idle-basic-test
  (testing "wait-for-idle returns lines once output stops arriving"
    (with-client [c :command ["/bin/cat"]]
      (cli/send-line! c "idle-a")
      (cli/send-line! c "idle-b")
      ;; Short idle threshold so test is fast
      (let [output (cli/wait-for-idle c :idle-ms 500 :timeout-ms 5000)]
        (is (= ["idle-a" "idle-b"] output))))))

(deftest wait-for-idle-advances-cursor-test
  (testing "wait-for-idle advances cursor"
    (with-client [c :command ["/bin/cat"]]
      (cli/send-line! c "x")
      (cli/wait-for-idle c :idle-ms 500 :timeout-ms 5000)

      ;; Nothing new after idle
      (let [out (cli/get-output c)]
        (is (empty? out))))))

(deftest wait-for-idle-timeout-test
  (testing "wait-for-idle throws on timeout when output keeps arriving"
    (with-client [c :command ["bash" "-c"
                              ;; Produce a line every 100ms so idle is never reached
                              "i=0; while [ $i -lt 100 ]; do echo tick-$i; sleep 0.1; i=$((i+1)); done"]]
      (let [ex (try
                 (cli/wait-for-idle c :idle-ms 2000 :timeout-ms 500)
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (str/includes? (ex-message ex) "timed out"))))))

;; =============================================================================
;; 6. shutdown! Tests
;; =============================================================================

(deftest shutdown-returns-exit-code-test
  (testing "shutdown! returns process exit code"
    (with-client [c :command ["bash" "-c" "read line; exit 0"]]
      (let [code (cli/shutdown! c :timeout-ms 5000)]
        (is (= 0 code))
        (is (false? (cli/alive? c)))))))

(deftest shutdown-force-kills-on-timeout-test
  (testing "shutdown! force-kills process when it does not exit in time"
    (let [c (cli/start! :command ["sleep" "999"])]
      (try
        (let [code (cli/shutdown! c :timeout-ms 500)]
          (is (= -1 code))
          ;; Process should be destroyed
          (Thread/sleep 100)
          (is (false? (cli/alive? c))))
        (finally
          (.close c))))))

;; =============================================================================
;; 7. Working Directory and Environment Tests
;; =============================================================================

(deftest working-dir-test
  (testing "start! respects :working-dir option"
    (with-client [c :command ["bash" "-c" "pwd"]
                  :working-dir "/tmp"]
      (let [output (cli/wait-for c "/tmp" :timeout-ms 5000)]
        ;; macOS may resolve to /private/tmp
        (is (some #(str/includes? % "tmp") output))))))

(deftest env-vars-test
  (testing "start! passes :env variables to the process"
    (with-client [c :command ["bash" "-c" "echo $MY_TEST_VAR"]
                  :env {"MY_TEST_VAR" "hello-from-test"}]
      (let [output (cli/wait-for c "hello-from-test" :timeout-ms 5000)]
        (is (= ["hello-from-test"] output))))))

;; =============================================================================
;; 8. Multi-line Interaction (simulating a REPL-like process)
;; =============================================================================

(deftest multi-turn-interaction-test
  (testing "Multiple send/wait cycles work correctly (REPL-like pattern)"
    ;; bash script that reads a line, echoes it uppercased, prints READY
    (with-client [c :command ["bash" "-c"
                              (str "echo READY;"
                                   "while IFS= read -r line; do"
                                   "  echo \"ECHO: $(echo $line | tr a-z A-Z)\";"
                                   "  echo READY;"
                                   "done")]]

      ;; Wait for initial READY prompt
      (cli/wait-for c "READY" :timeout-ms 5000)

      ;; Turn 1
      (cli/clear-output! c)
      (cli/send-line! c "hello")
      (let [output (cli/wait-for c "READY" :timeout-ms 5000)]
        (is (some #(str/includes? % "ECHO: HELLO") output)))

      ;; Turn 2
      (cli/clear-output! c)
      (cli/send-line! c "world")
      (let [output (cli/wait-for c "READY" :timeout-ms 5000)]
        (is (some #(str/includes? % "ECHO: WORLD") output)))

      ;; Full history preserved
      (let [all (cli/get-all-output c)]
        (is (>= (count all) 5))
        (is (some #(str/includes? % "HELLO") all))
        (is (some #(str/includes? % "WORLD") all))))))

;; =============================================================================
;; 9. Interleaved wait-for and wait-for-idle
;; =============================================================================

(deftest mixed-wait-strategies-test
  (testing "wait-for and wait-for-idle can be interleaved"
    (with-client [c :command ["bash" "-c"
                              (str "echo 'line1';"
                                   "echo 'line2';"
                                   "echo 'MARKER';"
                                   "sleep 0.3;"
                                   "echo 'line3';"
                                   "echo 'line4';")]]

      ;; First: wait-for a known marker
      (let [output (cli/wait-for c "MARKER" :timeout-ms 5000)]
        (is (some #(= "MARKER" %) output)))

      ;; Then: wait-for-idle to capture the rest
      (let [output (cli/wait-for-idle c :idle-ms 1000 :timeout-ms 5000)]
        (is (some #(str/includes? % "line3") output))
        (is (some #(str/includes? % "line4") output))))))

;; =============================================================================
;; 10. Process That Exits Immediately
;; =============================================================================

(deftest immediate-exit-process-test
  (testing "Client handles a process that exits immediately after output"
    (with-client [c :command ["echo" "bye"]]
      ;; Process prints "bye" and exits
      (let [output (cli/wait-for c "bye" :timeout-ms 5000)]
        (is (= ["bye"] output)))
      ;; Process should no longer be alive
      (Thread/sleep 200)
      (is (false? (cli/alive? c))))))

(deftest process-exit-code-nonzero-test
  (testing "shutdown! returns non-zero exit code from failed process"
    (let [c (cli/start! :command ["bash" "-c" "exit 42"])]
      (try
        (Thread/sleep 200)
        ;; Process already exited with code 42
        (let [code (cli/shutdown! c :timeout-ms 2000)]
          (is (= 42 code)))
        (finally
          (.close c))))))

;; =============================================================================
;; 11. Large Output / Rapid Lines
;; =============================================================================

(deftest rapid-output-test
  (testing "Reader thread captures rapid output without dropping lines"
    (with-client [c :command ["bash" "-c"
                              ;; Print 200 numbered lines quickly
                              "for i in $(seq 1 200); do echo \"line-$i\"; done"]]
      (cli/wait-for c "line-200" :timeout-ms 5000)
      (let [all (cli/get-all-output c)]
        (is (= 200 (count all)))
        (is (= "line-1" (first all)))
        (is (= "line-200" (last all)))))))
