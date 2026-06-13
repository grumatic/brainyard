;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-app.main-test
  "Unit tests for the project entry-point's pure CLI helpers.

   NOTE: `main.clj` lives in the project `src`, which Polylith's `poly test`
   does NOT cover (it tests bricks only). Run these via the project test alias:

       cd projects/agent-tui-app && clojure -M:test

   or load this ns in the project's dev nREPL and `(run-tests)`."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.brainyard.agent-tui-app.main :as main]
   [ai.brainyard.agent-tui-persist.interface :as persist]))

(def ^:private inject @#'main/inject-bare-resume-sentinel)
(def ^:private sentinel @#'main/resume-pick-sentinel)
(def ^:private latest-session-id @#'main/latest-session-id)

(deftest inject-bare-resume-sentinel-test
  (testing "bare --resume / -r (no value) gets the sentinel spliced in"
    (is (= ["run" "--resume" sentinel]      (inject ["run" "--resume"])))
    (is (= ["run" "-r" sentinel]            (inject ["run" "-r"])))
    (is (= ["run" "--resume" sentinel "-i"] (inject ["run" "--resume" "-i"]))
        "next token is another flag → treated as bare")
    (is (= ["run" "-r" sentinel "--inline"] (inject ["run" "-r" "--inline"]))))

  (testing "--resume <id> is left untouched (id does not start with '-')"
    (is (= ["run" "--resume" "foo"] (inject ["run" "--resume" "foo"])))
    (is (= ["run" "-r" "agt-123"]   (inject ["run" "-r" "agt-123"]))))

  (testing "--resume=<id> equals-form is left untouched"
    (is (= ["run" "--resume=foo"] (inject ["run" "--resume=foo"]))))

  (testing "no resume flag → args unchanged"
    (is (= ["run" "-a" "coact-agent"] (inject ["run" "-a" "coact-agent"]))))

  (testing "multiple resume tokens each handled independently"
    (is (= ["-r" sentinel "-r" "id"] (inject ["-r" "-r" "id"]))
        "first -r is bare (followed by a flag), second takes the id")))

(deftest sentinel-cannot-collide-with-real-session-id
  ;; Real session ids are timestamp/uuid-shaped (e.g. "agt-1780236629321-928")
  ;; — the sentinel's leading dashes guarantee no overlap.
  (is (re-find #"^--" sentinel)))

(deftest latest-session-id-test
  (testing "picks the newest by last-attached-at among existing ids"
    (with-redefs [persist/summarise-sessions
                  (fn [] [{:session-id "a" :last-attached-at 100 :started-at 1}
                          {:session-id "b" :last-attached-at 300 :started-at 2}
                          {:session-id "c" :last-attached-at 200 :started-at 3}])]
      (is (= "b" (latest-session-id #{"a" "b" "c"})))))

  (testing "filters to existing ids — the newest overall (b) is excluded, c wins"
    (with-redefs [persist/summarise-sessions
                  (fn [] [{:session-id "a" :last-attached-at 100}
                          {:session-id "b" :last-attached-at 300}
                          {:session-id "c" :last-attached-at 200}])]
      (is (= "c" (latest-session-id #{"a" "c"})))))

  (testing "falls back to started-at when last-attached-at is absent"
    (with-redefs [persist/summarise-sessions
                  (fn [] [{:session-id "a" :started-at 50}
                          {:session-id "b" :started-at 90}])]
      (is (= "b" (latest-session-id #{"a" "b"})))))

  (testing "nil when nothing matches → caller starts a fresh session"
    (with-redefs [persist/summarise-sessions (fn [] [])]
      (is (nil? (latest-session-id #{"a"}))))
    (with-redefs [persist/summarise-sessions
                  (fn [] [{:session-id "x" :last-attached-at 1}])]
      (is (nil? (latest-session-id #{"a"}))
          "x exists on disk but isn't in the live id set → nil"))))
