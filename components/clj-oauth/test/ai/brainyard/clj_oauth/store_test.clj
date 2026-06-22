;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.store-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.data.json :as json]
            [ai.brainyard.clj-oauth.core.store :as store]
            [ai.brainyard.clj-oauth.interface :as oauth])
  (:import [java.nio.file Files LinkOption]
           [java.nio.file.attribute PosixFilePermissions]))

;; ---------------------------------------------------------------------------
;; Each test runs against a throwaway HOME so the real ~/.brainyard is untouched.
;; ---------------------------------------------------------------------------

(def ^:dynamic *home* nil)

(use-fixtures :each
  (fn [t]
    (let [home (str (System/getProperty "java.io.tmpdir")
                    "/clj-oauth-test-" (System/nanoTime))
          prev-home (System/getProperty "user.home")
          prev-name (System/getProperty "user.name")]
      (System/setProperty "user.home" home)
      (System/setProperty "user.name" "tester")
      (try
        (binding [*home* home] (t))
        (finally
          (System/setProperty "user.home" prev-home)
          (System/setProperty "user.name" prev-name))))))

(defn- posix? [^java.io.File f]
  (-> f .toPath .getFileSystem .supportedFileAttributeViews (.contains "posix")))

(defn- perms-str [^java.io.File f]
  (PosixFilePermissions/toString
   (Files/getPosixFilePermissions (.toPath f) (make-array LinkOption 0))))

(deftest round-trip-and-clear
  (testing "save → load → clear for a string account-id"
    (let [bundle {:access_token "a1" :refresh_token "r1" :expires_at 9999999999999}]
      (is (nil? (oauth/load-tokens "notion")))
      (is (false? (oauth/authenticated? "notion")))
      (oauth/save-tokens! "notion" bundle)
      (is (= bundle (oauth/load-tokens "notion")))
      (is (true? (oauth/authenticated? "notion")))
      (oauth/clear-tokens! "notion")
      (is (nil? (oauth/load-tokens "notion")))
      (is (false? (oauth/authenticated? "notion"))))))

(deftest keyword-account-id
  (testing "keyword account-ids resolve to <name>.json"
    (oauth/save-tokens! :anthropic {:access_token "x"})
    (is (= "anthropic.json" (.getName (oauth/token-file :anthropic))))
    (is (= {:access_token "x"} (oauth/load-tokens :anthropic)))))

(deftest user-partitioned-path
  (testing "the store keys by (account, user-id)"
    (is (= "tester" (store/resolve-user-id)))
    (is (.contains (.getPath (oauth/token-file :anthropic))
                   (str java.io.File/separator "tester" java.io.File/separator)))))

(deftest file-and-dir-permissions
  (testing "file is 0600, parent dir is 0700"
    (oauth/save-tokens! "notion" {:access_token "a"})
    (let [f (oauth/token-file "notion")]
      (when (posix? f)
        (is (= "rw-------" (perms-str f)) "token file must be 0600")
        (is (= "rwx------" (perms-str (.getParentFile f))) "user dir must be 0700")))))

(deftest atomic-overwrite
  (testing "re-saving replaces the bundle without leaving a temp file"
    (oauth/save-tokens! "notion" {:access_token "first"})
    (oauth/save-tokens! "notion" {:access_token "second"})
    (is (= {:access_token "second"} (oauth/load-tokens "notion")))
    (let [dir (.getParentFile (oauth/token-file "notion"))
          tmps (filter #(.endsWith (.getName %) ".tmp") (.listFiles dir))]
      (is (empty? tmps) "no leftover .tmp files after atomic move"))))

(deftest token-expired-logic
  (testing "60s skew, missing expiry treated as expired"
    (is (true?  (oauth/token-expired? {:expires_at 1})))
    (is (true?  (oauth/token-expired? {})) "missing :expires_at → expired")
    (is (false? (oauth/token-expired? {:expires_at (+ (System/currentTimeMillis) 999999)})))
    (is (true?  (oauth/token-expired? {:expires_at (+ (System/currentTimeMillis) 30000)}))
        "within 60s skew → expired")))

(deftest refresh-body-encodings
  (testing "form encoding URL-encodes; json encoding emits a JSON object"
    (let [refresh-body @#'store/refresh-body]
      (is (= "grant_type=refresh_token&refresh_token=r%2Fx&client_id=cid"
             (refresh-body :form {:refresh_token "r/x" :client-id "cid"})))
      (is (= {:grant_type "refresh_token" :refresh_token "r1" :client_id "cid"}
             (json/read-str
              (refresh-body :json {:refresh_token "r1" :client-id "cid"})
              :key-fn keyword))))))

(deftest refresh-requires-endpoint-and-token
  (testing "guards before any HTTP"
    (is (thrown? clojure.lang.ExceptionInfo
                 (oauth/refresh-access-token :anthropic {} {:token-endpoint "https://x"}))
        "missing refresh_token throws")
    (is (thrown? clojure.lang.ExceptionInfo
                 (oauth/refresh-access-token :anthropic {:refresh_token "r"} {}))
        "missing :token-endpoint throws")))
