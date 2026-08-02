;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-persist.snapshots-test
  "Regression coverage for the meta.edn resilience pair:

   * `safe-read-meta` (READ side) — one corrupt meta.edn used to throw
     `Invalid token` out of `by sessions list` / `/session list` /
     discover-attach-target, breaking session listing for the WHOLE project.
   * `sanitise-identity` (WRITE side) — an identity keyword carrying
     whitespace/delimiters (e.g. a prompt landing as `:reply this: OK`)
     serialises fine but cannot be read back, which is how such a file is
     produced in the first place."
  (:require [ai.brainyard.agent-tui-persist.core.snapshots :as snapshots]
            [ai.brainyard.agent-tui-persist.interface :as persist]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File StringWriter]
           [java.nio.file Files]))

(def ^:dynamic *tmp-root* nil)

(defn- with-tmp-root [f]
  (let [tmp (.toFile (Files/createTempDirectory "agent-tui-persist-snapshots-test"
                                                (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (binding [*tmp-root* tmp]
        (persist/with-root tmp (f)))
      (finally
        (doseq [^File f' (reverse (file-seq tmp))]
          (.delete f'))))))

(use-fixtures :each with-tmp-root)

(defn- corrupt-meta!
  "Write a meta.edn whose printed keyword cannot be read back — exactly the
   shape that shipped in the wild (`:reply this: OK`)."
  [session-id]
  (persist/session-dir session-id)
  (spit (persist/file-of session-id :meta)
        "{:agent-id :reply this: OK, :user-id \"u-1\"}")
  session-id)

;; -- read side: safe-read-meta -------------------------------------------------

(deftest safe-read-meta-never-throws-on-corrupt-file
  (testing "the raw reader DOES throw on an unreadable meta.edn (the bug)"
    (corrupt-meta! "agt-corrupt")
    (is (thrown? Throwable (persist/read-meta "agt-corrupt"))))
  (testing "safe-read-meta absorbs it, yields nil, and warns once on stderr"
    (let [err (StringWriter.)
          v   (binding [*err* err] (persist/safe-read-meta "agt-corrupt"))]
      (is (nil? v) "corrupt meta.edn must degrade to nil, not throw")
      (is (re-find #"\[persist\] skipping unreadable meta\.edn for agt-corrupt"
                   (str err))
          "corruption must not be silent"))))

(deftest safe-read-meta-passes-through-good-and-missing
  (testing "a healthy meta.edn reads back unchanged"
    (persist/save-meta! "agt-ok" {:agent-id :coact-agent :user-id "u-1"})
    (let [m (persist/safe-read-meta "agt-ok")]
      (is (= :coact-agent (:agent-id m)))
      (is (= "u-1" (:user-id m)))
      (is (number? (:started-at m)))))
  (testing "a session with no meta.edn yields read-meta's default, not nil"
    (persist/session-dir "agt-fresh")
    (is (= {} (persist/safe-read-meta "agt-fresh")))))

(deftest safe-read-meta-keeps-session-iteration-alive
  (testing "one poisoned session cannot hide its healthy siblings"
    (persist/save-meta! "agt-a" {:agent-id :coact-agent})
    (corrupt-meta! "agt-b")
    (persist/save-meta! "agt-c" {:agent-id :react-agent})
    (let [err  (StringWriter.)
          rows (binding [*err* err]
                 (doall (for [sid (persist/list-sessions)]
                          [sid (persist/safe-read-meta sid)])))]
      (is (= ["agt-a" "agt-b" "agt-c"] (mapv first rows)))
      (is (= [:coact-agent nil :react-agent] (mapv #(:agent-id (second %)) rows)))
      (is (= 2 (count (filter some? (map second rows))))
          "exactly the two healthy sessions survive")
      (is (re-find #"skipping unreadable meta\.edn for agt-b" (str err))))))

;; -- write side: sanitise-identity --------------------------------------------

(deftest sanitise-identity-drops-only-unreadable-identity-keywords
  (testing "an identity keyword with whitespace/delimiters is dropped"
    (let [err (StringWriter.)
          m   (binding [*err* err]
                (snapshots/sanitise-identity
                 {:agent-id (keyword "reply this: OK")
                  :defagent-id (keyword "a{b}")
                  :user-id "u-1"}))]
      (is (not (contains? m :agent-id)))
      (is (not (contains? m :defagent-id)))
      (is (= "u-1" (:user-id m)) "non-identity keys are untouched")
      (is (re-find #"dropping unreadable :agent-id" (str err)))))
  (testing "well-formed keywords and plain strings are preserved"
    (let [m (snapshots/sanitise-identity
             {:agent-id :coact-agent
              :defagent-id "coact-agent/fuchsia-wolf-9154"})]
      (is (= :coact-agent (:agent-id m)))
      (is (= "coact-agent/fuchsia-wolf-9154" (:defagent-id m))))))

(deftest save-meta-round-trips-through-a-poisoned-identity
  (testing "save-meta! sanitises on write, so the file stays readable"
    (let [err (StringWriter.)]
      (binding [*err* err]
        (persist/save-meta! "agt-poison"
                            {:agent-id (keyword "reply this: OK")
                             :user-id  "u-1"})))
    (let [m (persist/read-meta "agt-poison")]
      (is (nil? (:agent-id m)) "the unreadable identity never reaches disk")
      (is (= "u-1" (:user-id m)))
      (is (= m (persist/safe-read-meta "agt-poison"))
          "safe reader and raw reader agree — nothing was corrupted"))))
