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
   [ai.brainyard.agent-tui-app.main :as main]))

(def ^:private inject @#'main/inject-bare-resume-sentinel)
(def ^:private sentinel @#'main/resume-pick-sentinel)

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
