;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-app.dotenv-test
  "Discovery order for the `.env` loader — docs/design/env-files-design.md §3.1.

   NOTE: `dotenv.clj` lives in the project `src`, which Polylith's `poly test`
   does NOT cover. Run via the project test alias:

       cd projects/agent-tui-app && clojure -M:test"
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.agent-tui-app.dotenv :as dotenv]
            [ai.brainyard.agent-tui-app.main :as main]
            [clojure.java.io :as io]))

(defn- rm-rf [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f) (doseq [c (.listFiles f)] (rm-rf c)))
    (.delete f)))

(defn- with-project [f]
  ;; `io/file`, not `str` — java.io.tmpdir carries a trailing slash on macOS and
  ;; the double slash survives string comparison while `io/file` normalises it.
  (let [root  (.getPath (io/file (System/getProperty "java.io.tmpdir")
                                 (str "by-dotenv-" (System/nanoTime))))
        prior (System/getProperty "BY_PROJECT_DIR")]
    (.mkdirs (io/file root ".brainyard"))
    (try
      (System/setProperty "BY_PROJECT_DIR" root)
      (f root)
      (finally
        (if prior (System/setProperty "BY_PROJECT_DIR" prior)
            (System/clearProperty "BY_PROJECT_DIR"))
        (rm-rf (io/file root))))))

(defn- clear! [& ks] (doseq [k ks] (System/clearProperty k)))

(deftest brainyard-env-outranks-the-project-env
  ;; Forced, not aesthetic: `.brainyard/.env` is the file `by env set` writes,
  ;; so a project that already has an application `.env` carrying the same name
  ;; would otherwise shadow every write made through the tool — silently.
  (with-project
    (fn [root]
      (spit (io/file root ".env")              "SHARED=from-project-env\nONLY_APP=app\n")
      (spit (io/file root ".brainyard" ".env") "SHARED=from-brainyard-env\nONLY_BY=by\n")
      (clear! "SHARED" "ONLY_APP" "ONLY_BY")
      (dotenv/load-from-dotenv!)
      (is (= "from-brainyard-env" (System/getProperty "SHARED")))
      (testing "and the project .env still contributes names of its own"
        (is (= "app" (System/getProperty "ONLY_APP")))
        (is (= "by"  (System/getProperty "ONLY_BY"))))
      (clear! "SHARED" "ONLY_APP" "ONLY_BY"))))

(deftest by-project-dir-names-a-LEVEL-not-one-file
  ;; Both of a level's files, in the same order. A root that contributed only
  ;; one of its two would be a third rule nobody could remember.
  (with-project
    (fn [root]
      (let [paths (map str (dotenv/candidate-paths))]
        (is (= (str root "/.brainyard/.env") (first paths)))
        (is (= (str root "/.env") (second paths)))))))

(deftest every-level-of-the-walk-offers-both
  (with-project
    (fn [_root]
      (let [paths (map str (dotenv/candidate-paths))
            ;; drop the BY_PROJECT_DIR pair, then look at the walk itself
            walk  (drop 2 paths)]
        (is (every? true?
                    (map (fn [[a b]]
                           (and (.endsWith ^String a "/.brainyard/.env")
                                (.endsWith ^String b "/.env")
                                (= (.getParent (io/file (.getParent (io/file a))))
                                   (.getParent (io/file b)))))
                         (partition 2 walk)))
            ".brainyard/.env then .env, at each level")))))

(deftest a-real-env-var-still-wins
  ;; The contract both loaders implement, and the one this change must not move.
  (with-project
    (fn [root]
      (let [k (first (keys (into {} (System/getenv))))]
        (spit (io/file root ".brainyard" ".env") (str k "=from-dotenv\n"))
        (dotenv/load-from-dotenv!)
        (is (not= "from-dotenv" (System/getProperty k))
            "a name already in the real environment is skipped entirely")))))

(deftest by-no-dotenv-suppresses-everything
  (with-project
    (fn [root]
      (spit (io/file root ".brainyard" ".env") "BY_TEST_SUPPRESSED=x\n")
      (clear! "BY_TEST_SUPPRESSED")
      (try
        (System/setProperty "BY_NO_DOTENV" "1")
        (let [r (dotenv/load-from-dotenv!)]
          (is (= :by-no-dotenv (:skipped r)))
          (is (zero? (:loaded-count r)))
          (is (nil? (System/getProperty "BY_TEST_SUPPRESSED"))))
        (finally (clear! "BY_NO_DOTENV" "BY_TEST_SUPPRESSED"))))))

;; ============================================================================
;; --env-file — pinning discovery for one invocation
;;
;; The scanner is pure and pre-runs cli-matic, because the `.env` has to be
;; loaded before anything reads config: a flag parsed later would arrive too
;; late for the 69 `:env-fn` knobs it exists to supply.
;; ============================================================================

(def ^:private scan  main/env-file-arg)
(def ^:private strip main/strip-env-file-arg)

(deftest env-file-arg-accepts-both-forms
  (is (= "/p/.env" (scan ["run" "--env-file" "/p/.env"])))
  (is (= "/p/.env" (scan ["run" "--env-file=/p/.env"])))
  (is (= "/p/.env" (scan ["ask" "-q" "hi" "--env-file" "/p/.env" "--json"])))
  (testing "absent, or present with nothing after it"
    (is (nil? (scan ["run"])))
    (is (nil? (scan [])))
    (is (nil? (scan ["run" "--env-file"])))
    (is (nil? (scan ["run" "--env-file="])))))

(deftest strip-removes-the-flag-and-its-value
  ;; Consumed after scanning so cli-matic never sees it — which is what lets the
  ;; flag work on EVERY subcommand while being declared only on the two where it
  ;; belongs in --help. A flag that worked on some and errored on others would
  ;; be the worse outcome.
  (is (= ["run"] (strip ["run" "--env-file" "/p/.env"])))
  (is (= ["run"] (strip ["run" "--env-file=/p/.env"])))
  (is (= ["ask" "-q" "hi" "--json"] (strip ["ask" "-q" "hi" "--env-file" "/p/.env" "--json"])))
  (testing "leaves everything else alone"
    (is (= ["env" "list" "--json"] (strip ["env" "list" "--json"])))
    (is (= [] (strip [])))))

(deftest an-explicit-env-file-pins-discovery-and-beats-by-env-file
  (with-project
    (fn [root]
      (let [pinned (io/file root "pinned.env")
            other  (io/file root "other.env")]
        (spit pinned "BY_TEST_PIN=from-flag\n")
        (spit other  "BY_TEST_PIN=from-env-var\n")
        (spit (io/file root ".brainyard" ".env") "BY_TEST_PIN=from-walk\n")
        (clear! "BY_TEST_PIN")
        (try
          (System/setProperty "BY_ENV_FILE" (.getPath other))
          (let [r (dotenv/load-from-dotenv! {:env-file (.getPath pinned)})]
            (is (= [(.getPath pinned)] (mapv :path (:paths r)))
                "the pin REPLACES the walk — one file, not a merge")
            (is (= (.getPath pinned) @dotenv/pinned-file)
                "and it is recorded, so anything reporting on the chain says so"))
          (is (= "from-flag" (System/getProperty "BY_TEST_PIN")))
          (finally (clear! "BY_ENV_FILE" "BY_TEST_PIN")
                   (reset! dotenv/pinned-file nil)))))))

(deftest a-missing-pin-is-reported-with-its-source
  ;; The caller decides what a miss means, and it differs: a BY_ENV_FILE
  ;; inherited from elsewhere is plausibly stale (warn, walk on), while a
  ;; --env-file typed for this invocation is an assertion (error).
  (with-project
    (fn [root]
      (let [gone (str root "/nope.env")]
        (let [r (dotenv/load-from-dotenv! {:env-file gone})]
          (is (= gone (:env-file-missing r)))
          (is (true? (:env-file-from-flag? r))))
        (try
          (System/setProperty "BY_ENV_FILE" gone)
          (let [r (dotenv/load-from-dotenv!)]
            (is (= gone (:env-file-missing r)))
            (is (false? (:env-file-from-flag? r))))
          (finally (clear! "BY_ENV_FILE")))))))
