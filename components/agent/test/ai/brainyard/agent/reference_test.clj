;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.reference-test
  "Tests for file access permission management in reference.clj"
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.agent.common.reference :as ref]
            [clojure.java.io :as io]))

(deftest read-file-content-relative-path
  (testing "Relative paths resolve within base-dir"
    (let [base-dir (System/getProperty "user.dir")
          result (ref/read-file-content base-dir "deps.edn")]
      (is (contains? result :content))
      (is (contains? result :path))
      (is (not (contains? result :error))))))

(deftest read-file-content-tmp-auto-allowed
  (testing "/tmp paths are auto-allowed by default"
    (let [tmp-file (io/file "/tmp" (str "brainyard-test-" (System/currentTimeMillis) ".txt"))
          _ (spit tmp-file "test content")
          result (ref/read-file-content "/some/base" (.getPath tmp-file))]
      (try
        (is (= "test content" (:content result)))
        (is (not (contains? result :error)))
        (finally
          (.delete tmp-file))))))

(deftest read-file-content-outside-denied-no-permission-fn
  (testing "Absolute paths outside allowed-dirs are denied without permission-fn"
    (let [result (ref/read-file-content "/some/base" "/etc/hostname"
                                        :allowed-dirs ["/tmp"])]
      (is (contains? result :error))
      ;; reference.clj denies with "Access denied — outside allowed directories: <path>"
      (is (re-find #"outside allowed directories" (:error result))))))

(deftest read-file-content-permission-fn-allows
  (testing "permission-fn returning {:allowed true} grants access"
    (let [tmp-file (io/file "/tmp" (str "brainyard-test-" (System/currentTimeMillis) ".txt"))
          _ (spit tmp-file "permitted content")
          called? (atom false)
          perm-fn (fn [{:keys [path]}]
                    (reset! called? true)
                    {:allowed true})
          ;; Use an allowed-dirs that does NOT include /tmp, so permission-fn is invoked
          result (ref/read-file-content "/some/base" (.getPath tmp-file)
                                        :allowed-dirs ["/nonexistent"]
                                        :permission-fn perm-fn)]
      (try
        (is @called? "permission-fn should have been called")
        (is (= "permitted content" (:content result)))
        (finally
          (.delete tmp-file))))))

(deftest read-file-content-permission-fn-denies
  (testing "permission-fn returning {:denied true} blocks access"
    (let [tmp-file (io/file "/tmp" (str "brainyard-test-" (System/currentTimeMillis) ".txt"))
          _ (spit tmp-file "secret content")
          perm-fn (fn [_] {:denied true :reason "User said no"})
          result (ref/read-file-content "/some/base" (.getPath tmp-file)
                                        :allowed-dirs ["/nonexistent"]
                                        :permission-fn perm-fn)]
      (try
        (is (contains? result :error))
        (is (not= "secret content" (:content result)))
        (finally
          (.delete tmp-file))))))

(deftest read-file-content-custom-allowed-dirs
  (testing "Custom allowed-dirs expand access"
    (let [tmp-file (io/file "/tmp" (str "brainyard-test-" (System/currentTimeMillis) ".txt"))
          _ (spit tmp-file "custom dir content")
          result (ref/read-file-content "/some/base" (.getPath tmp-file)
                                        :allowed-dirs ["/tmp" "/data"])]
      (try
        (is (= "custom dir content" (:content result)))
        (finally
          (.delete tmp-file))))))

(deftest read-glob-content-tmp-auto-allowed
  (testing "Glob patterns in /tmp are auto-allowed"
    (let [tmp-dir (io/file "/tmp" (str "brainyard-glob-test-" (System/currentTimeMillis)))
          _ (.mkdirs tmp-dir)
          f1 (io/file tmp-dir "a.txt")
          f2 (io/file tmp-dir "b.txt")
          _ (spit f1 "file a")
          _ (spit f2 "file b")
          result (ref/read-glob-content "/some/base"
                                        (str (.getPath tmp-dir) "/*.txt"))]
      (try
        (is (not (contains? result :error)) (str "Got error: " (:error result)))
        (is (= 2 (:count result)))
        (finally
          (.delete f1)
          (.delete f2)
          (.delete tmp-dir))))))

(deftest read-glob-content-outside-denied
  (testing "Glob patterns outside allowed-dirs are denied"
    (let [result (ref/read-glob-content "/some/base" "/etc/*.conf"
                                        :allowed-dirs ["/tmp"])]
      (is (contains? result :error))
      (is (re-find #"not allowed" (:error result))))))

;; --- Partial read tests ---

(deftest read-file-content-offset-limit
  (testing "Partial read with :offset and :limit returns correct slice and metadata"
    (let [tmp-file (io/file "/tmp" (str "brainyard-partial-" (System/currentTimeMillis) ".txt"))
          content (apply str (repeat 100 "abcdefghij"))  ;; 1000 chars
          _ (spit tmp-file content)
          result (ref/read-file-content "/some/base" (.getPath tmp-file)
                                        :offset 100 :limit 200)]
      (try
        (is (= (subs content 100 300) (:content result)))
        (is (= 1000 (:size result)))
        (is (= 100 (:offset result)))
        (is (= 200 (:limit result)))
        (is (true? (:has-more result)))
        (is (contains? result :total-lines))
        (finally
          (.delete tmp-file))))))

(deftest read-file-content-offset-limit-end-of-file
  (testing "Partial read at end of file has :has-more false"
    (let [tmp-file (io/file "/tmp" (str "brainyard-partial-end-" (System/currentTimeMillis) ".txt"))
          content "short content"
          _ (spit tmp-file content)
          result (ref/read-file-content "/some/base" (.getPath tmp-file)
                                        :offset 0 :limit 5000)]
      (try
        (is (= content (:content result)))
        (is (false? (:has-more result)))
        (finally
          (.delete tmp-file))))))

(deftest read-file-content-lines
  (testing "Partial read with :lines returns correct line range"
    (let [tmp-file (io/file "/tmp" (str "brainyard-lines-" (System/currentTimeMillis) ".txt"))
          lines (mapv #(str "line-" %) (range 1 51))  ;; 50 lines
          content (clojure.string/join "\n" lines)
          _ (spit tmp-file content)
          result (ref/read-file-content "/some/base" (.getPath tmp-file)
                                        :lines [10 20])]
      (try
        (is (= (clojure.string/join "\n" (subvec lines 9 20)) (:content result)))
        (is (= 50 (:total-lines result)))
        (is (= [10 20] (:lines-range result)))
        (is (true? (:has-more result)))
        (finally
          (.delete tmp-file))))))

(deftest read-file-content-lines-end
  (testing "Partial read with :lines at end has :has-more false"
    (let [tmp-file (io/file "/tmp" (str "brainyard-lines-end-" (System/currentTimeMillis) ".txt"))
          lines (mapv #(str "line-" %) (range 1 11))  ;; 10 lines
          content (clojure.string/join "\n" lines)
          _ (spit tmp-file content)
          result (ref/read-file-content "/some/base" (.getPath tmp-file)
                                        :lines [1 10])]
      (try
        (is (= content (:content result)))
        (is (false? (:has-more result)))
        (finally
          (.delete tmp-file))))))

(deftest read-file-content-partial-skips-truncation
  (testing "Partial reads skip truncation even for large files"
    (let [tmp-file (io/file "/tmp" (str "brainyard-big-" (System/currentTimeMillis) ".txt"))
          ;; Create content larger than 100KB truncation limit
          content (apply str (repeat 20000 "abcdefghij"))  ;; 200K chars
          _ (spit tmp-file content)
          result (ref/read-file-content "/some/base" (.getPath tmp-file)
                                        :offset 0 :limit 5000)]
      (try
        (is (= 5000 (count (:content result))))
        (is (not (re-find #"TRUNCATED" (:content result))))
        (is (= 200000 (:size result)))
        (is (true? (:has-more result)))
        (finally
          (.delete tmp-file))))))

;; ---------------------------------------------------------------------------
;; :lines / :offset / :limit coercion — the LLM often emits a vector arg as a
;; JSON string (e.g. "[1, 12]"); the tool must coerce it, not blank-fail.
;; ---------------------------------------------------------------------------

(deftest read-file-content-lines-stringified
  (testing "A stringified :lines vector is coerced, not rejected"
    (let [tmp-file (io/file "/tmp" (str "brainyard-lines-str-" (System/currentTimeMillis) ".txt"))
          _ (spit tmp-file (apply str (map #(str "L" % "\n") (range 1 21))))]
      (try
        (doseq [form ["[1, 3]" "[1 3]" "1 3" "1,3"]]
          (let [result (ref/read-file-content "/some/base" (.getPath tmp-file) :lines form)]
            (is (not (contains? result :error)) (str "form " (pr-str form) " should coerce"))
            (is (= [1 3] (:lines-range result)) (str "form " (pr-str form)))
            (is (= "L1\nL2\nL3" (:content result)) (str "form " (pr-str form)))))
        (finally (.delete tmp-file))))))

(deftest read-file-content-lines-malformed-precise-error
  (testing "A malformed :lines returns a precise error (not the old blank failure)"
    (let [tmp-file (io/file "/tmp" (str "brainyard-lines-bad-" (System/currentTimeMillis) ".txt"))
          _ (spit tmp-file "x\ny\nz\n")
          result (ref/read-file-content "/some/base" (.getPath tmp-file) :lines "abc")]
      (try
        (is (contains? result :error))
        (is (re-find #"Invalid :lines" (:error result)))
        (finally (.delete tmp-file))))))

(deftest read-file-content-offset-limit-stringified
  (testing "Stringified :offset/:limit are coerced"
    (let [tmp-file (io/file "/tmp" (str "brainyard-off-str-" (System/currentTimeMillis) ".txt"))
          _ (spit tmp-file "0123456789abcdef")
          result (ref/read-file-content "/some/base" (.getPath tmp-file) :offset "2" :limit "4")]
      (try
        (is (= "2345" (:content result)))
        (finally (.delete tmp-file))))))

;; ---------------------------------------------------------------------------
;; Multi-base relative resolution: base-dir first (agrees with bash), then
;; fallback-dirs in order; the fallback widens discovery but NOT the security
;; envelope (a fallback hit outside allowed-dirs is still gated).
;; ---------------------------------------------------------------------------

(deftest read-file-content-fallback-resolution
  (testing "Relative path found via fallback-dirs when absent under base-dir"
    (let [stamp (System/currentTimeMillis)
          pd    (io/file "/tmp" (str "by-pd-" stamp))
          wd    (io/file "/tmp" (str "by-wd-" stamp))]
      (.mkdirs pd) (.mkdirs wd)
      (spit (io/file pd "a.txt") "in-project")
      (spit (io/file wd "b.txt") "in-working")
      (try
        (let [allowed [(.getPath pd) (.getPath wd)]
              fb      [(.getPath wd)]
              ra (ref/read-file-content (.getPath pd) "a.txt" :allowed-dirs allowed :fallback-dirs fb)
              rb (ref/read-file-content (.getPath pd) "b.txt" :allowed-dirs allowed :fallback-dirs fb)
              rm (ref/read-file-content (.getPath pd) "missing.txt" :allowed-dirs allowed :fallback-dirs fb)]
          (is (= "in-project" (:content ra)) "base-dir primary")
          (is (= "in-working" (:content rb)) "found via fallback")
          (is (re-find #"File not found" (:error rm)) "absent everywhere → not found"))
        (finally
          (doseq [f (reverse (file-seq pd))] (.delete f))
          (doseq [f (reverse (file-seq wd))] (.delete f)))))))

(deftest read-file-content-fallback-precedence
  (testing "When a relative path exists under BOTH base-dir and a fallback, base-dir wins"
    (let [stamp (System/currentTimeMillis)
          pd    (io/file "/tmp" (str "by-pdp-" stamp))
          wd    (io/file "/tmp" (str "by-wdp-" stamp))]
      (.mkdirs pd) (.mkdirs wd)
      (spit (io/file pd "dup.txt") "from-base")
      (spit (io/file wd "dup.txt") "from-fallback")
      (try
        (let [result (ref/read-file-content (.getPath pd) "dup.txt"
                                            :allowed-dirs [(.getPath pd) (.getPath wd)]
                                            :fallback-dirs [(.getPath wd)])]
          (is (= "from-base" (:content result)) "base-dir takes precedence (agrees with bash)"))
        (finally
          (doseq [f (reverse (file-seq pd))] (.delete f))
          (doseq [f (reverse (file-seq wd))] (.delete f)))))))

(deftest read-file-content-fallback-respects-allowed-dirs
  (testing "A fallback hit OUTSIDE allowed-dirs is still gated (no permission-fn → denied)"
    (let [stamp (System/currentTimeMillis)
          pd    (io/file "/tmp" (str "by-pdg-" stamp))
          home  (io/file "/tmp" (str "by-home-" stamp))]
      (.mkdirs pd) (.mkdirs home)
      (spit (io/file home "secret.txt") "in-home")
      (try
        ;; home is a fallback dir but NOT in allowed-dirs → must be denied.
        (let [denied  (ref/read-file-content (.getPath pd) "secret.txt"
                                             :allowed-dirs [(.getPath pd)]
                                             :fallback-dirs [(.getPath home)])
              allowed (ref/read-file-content (.getPath pd) "secret.txt"
                                             :allowed-dirs [(.getPath pd) (.getPath home)]
                                             :fallback-dirs [(.getPath home)])]
          (is (re-find #"outside allowed directories" (:error denied)) "fallback ≠ open access")
          (is (= "in-home" (:content allowed)) "allowed once its dir is whitelisted"))
        (finally
          (doseq [f (reverse (file-seq pd))] (.delete f))
          (doseq [f (reverse (file-seq home))] (.delete f)))))))
