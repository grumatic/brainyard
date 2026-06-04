;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.web-share.core-test
  "Unit tests for the ttyd argv builder, credential resolution, and self-exec
   resolution. The pure constructors are the primary surface; process spawning
   (serve!) is exercised by the integration tests in step 3."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.brainyard.web-share.core :as core]))

;; ---------------------------------------------------------------------------
;; build-ttyd-argv
;; ---------------------------------------------------------------------------

(defn- pair-after
  "Return the token immediately after the first occurrence of `flag` in argv."
  [argv flag]
  (let [i (.indexOf ^java.util.List (vec argv) flag)]
    (when (>= i 0) (nth argv (inc i)))))

(deftest build-ttyd-argv-defaults
  (let [argv (core/build-ttyd-argv {:credential "by:secret"
                                    :child-argv ["by" "run"]})]
    (testing "binary, bind, port, origin-check defaults"
      (is (= "ttyd" (first argv)))
      (is (= "127.0.0.1" (pair-after argv "-i")))
      (is (= "7681" (pair-after argv "-p")))
      (is (some #{"-O"} argv) "always sets check-origin"))
    (testing "credential is included"
      (is (= "by:secret" (pair-after argv "-c"))))
    (testing "writable by default"
      (is (some #{"-W"} argv)))
    (testing "child argv appended after -- separator"
      (let [sep (.indexOf ^java.util.List (vec argv) "--")]
        (is (>= sep 0))
        (is (= ["by" "run"] (subvec (vec argv) (inc sep))))))
    (testing "max-clients omitted when 0; once omitted by default"
      (is (not (some #{"-m"} argv)))
      (is (not (some #{"-o"} argv))))))

(deftest build-ttyd-argv-options
  (testing "readonly omits -W"
    (let [argv (core/build-ttyd-argv {:credential "u:p" :writable? false
                                      :child-argv ["x"]})]
      (is (not (some #{"-W"} argv)))))
  (testing "explicit bind/port map through"
    (let [argv (core/build-ttyd-argv {:bind "0.0.0.0" :port 9000
                                      :credential "u:p" :child-argv ["x"]})]
      (is (= "0.0.0.0" (pair-after argv "-i")))
      (is (= "9000" (pair-after argv "-p")))))
  (testing "positive max-clients adds -m; once adds -o"
    (let [argv (core/build-ttyd-argv {:credential "u:p" :max-clients 3
                                      :once? true :child-argv ["x"]})]
      (is (= "3" (pair-after argv "-m")))
      (is (some #{"-o"} argv))))
  (testing "custom ttyd-path is the head"
    (let [argv (core/build-ttyd-argv {:ttyd-path "/opt/ttyd" :credential "u:p"
                                      :child-argv ["x"]})]
      (is (= "/opt/ttyd" (first argv)))))
  (testing "nil credential is omitted (launcher enforces, builder tolerates)"
    (let [argv (core/build-ttyd-argv {:credential nil :child-argv ["x"]})]
      (is (not (some #{"-c"} argv))))))

;; ---------------------------------------------------------------------------
;; resolve-credential
;; ---------------------------------------------------------------------------

(deftest resolve-credential-test
  (testing "generates a password when none supplied; default user 'by'"
    (let [{:keys [user pass credential generated?]} (core/resolve-credential {})]
      (is (= "by" user))
      (is generated?)
      (is (= 12 (count pass)))
      (is (= (str "by:" pass) credential))))
  (testing "uses supplied user and pass verbatim (trimmed)"
    (let [{:keys [user pass credential generated?]}
          (core/resolve-credential {:user "  alice " :pass " s3cret "})]
      (is (= "alice" user))
      (is (= "s3cret" pass))
      (is (= "alice:s3cret" credential))
      (is (not generated?))))
  (testing "blank pass still triggers generation"
    (is (:generated? (core/resolve-credential {:user "alice" :pass "   "})))))

;; ---------------------------------------------------------------------------
;; self-exec-argv
;; ---------------------------------------------------------------------------

(deftest self-exec-argv-override
  (testing "BY_WEB_SELF override is whitespace-split"
    (is (= {:ok? true :argv ["bb" "tui"]}
           (core/self-exec-argv {"BY_WEB_SELF" "bb tui"})))
    (is (= {:ok? true :argv ["/path/to/by"]}
           (core/self-exec-argv {"BY_WEB_SELF" "  /path/to/by  "}))))
  (testing "blank override falls through to resolution (does not crash)"
    (let [r (core/self-exec-argv {"BY_WEB_SELF" "   "})]
      (is (contains? r :ok?))
      (is (or (true? (:ok? r)) (string? (:reason r)))))))

;; ---------------------------------------------------------------------------
;; available? — shells out; assert only the shape so it is host-independent
;; ---------------------------------------------------------------------------

(deftest available-shape
  (let [r (core/available?)]
    (is (contains? r :ok?))
    (if (:ok? r)
      (is (string? (:path r)))
      (is (str/includes? (:hint r) "ttyd")))))
