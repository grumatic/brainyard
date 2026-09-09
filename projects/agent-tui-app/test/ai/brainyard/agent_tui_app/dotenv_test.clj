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
