;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui.persist-keying-test
  "Verify the TUI session map carries an explicit `:agent-session-id` and that
   it round-trips into `persist/session-dir` so the on-disk dir name matches
   the agent's stable session id, not the TUI's process-local `:id`.

   Per docs/simplified-agent-tui-arch-design.md §6.3."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent-tui.sessions :as sessions]
            [ai.brainyard.agent-tui-persist.interface :as persist])
  (:import [java.io File]))

(use-fixtures :each
  (fn [t]
    (let [tmp (File/createTempFile "agent-tui-persist-keying-" "")]
      (.delete tmp)
      (.mkdirs tmp)
      (try (persist/with-root tmp (t))
           (finally
             (doseq [^File f (reverse (file-seq tmp))]
               (.delete f)))))))

(deftest agent-session-id-defaults-to-nil-and-overrides
  (testing "make-session via create-session! exposes :agent-session-id"
    (sessions/reset-sessions!)
    (let [stub-agent (reify
                       ai.brainyard.agent.core.protocol/IAgent
                       (session-id [_] "agt-stub-1234")
                       java.io.Closeable
                       (close [_] nil))]
      (sessions/create-session! {:id 0
                                 :label "main"
                                 :agent stub-agent
                                 :agent-id :stub-agent
                                 :agent-instances [stub-agent]
                                 :skip-agent-creation true})
      (let [s (sessions/get-session 0)]
        (is (= "agt-stub-1234" (:agent-session-id s)))))))

(deftest persist-session-dir-keys-on-agent-session-id
  (testing "persist/session-dir uses the agent-session-id (string) as dir name"
    (sessions/reset-sessions!)
    (let [stub-agent (reify
                       ai.brainyard.agent.core.protocol/IAgent
                       (session-id [_] "agt-roundtrip-1")
                       java.io.Closeable
                       (close [_] nil))]
      (sessions/create-session! {:id 0
                                 :label "main"
                                 :agent stub-agent
                                 :agent-id :stub-agent
                                 :agent-instances [stub-agent]
                                 :skip-agent-creation true})
      (let [sid (:agent-session-id (sessions/get-session 0))
            dir (persist/session-dir sid)]
        (is (= "agt-roundtrip-1" (:agent-session-id (sessions/get-session 0))))
        (is (.endsWith (.getName dir) "agt-roundtrip-1"))))))

(deftest two-tui-sessions-with-different-agents-get-distinct-dirs
  (testing "Different agent-session-ids ⇒ distinct on-disk dirs"
    (sessions/reset-sessions!)
    (let [mk (fn [sid]
               (reify
                 ai.brainyard.agent.core.protocol/IAgent
                 (session-id [_] sid)
                 java.io.Closeable
                 (close [_] nil)))
          a (mk "agt-aa")
          b (mk "agt-bb")]
      (sessions/create-session! {:id 0 :label "a" :agent a :agent-id :a
                                 :agent-instances [a] :skip-agent-creation true})
      (sessions/create-session! {:id 1 :label "b" :agent b :agent-id :b
                                 :agent-instances [b] :skip-agent-creation true})
      (let [da (persist/session-dir (:agent-session-id (sessions/get-session 0)))
            db (persist/session-dir (:agent-session-id (sessions/get-session 1)))]
        (is (not= (.getAbsolutePath da) (.getAbsolutePath db)))))))
