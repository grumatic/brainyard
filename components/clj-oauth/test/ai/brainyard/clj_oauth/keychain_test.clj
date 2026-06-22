;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.keychain-test
  "Keychain backends (macOS `security` / Linux `secret-tool`) exercised through
   an in-memory fake subprocess runner — no real keychain is touched."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.brainyard.clj-oauth.core.store :as store]
            [ai.brainyard.clj-oauth.interface :as oauth]))

(defn- arg-after [argv flag]
  (let [i (.indexOf (vec argv) flag)]
    (when (>= i 0) (nth (vec argv) (inc i) nil))))

(defn- fake-security
  "Stand-in for macOS `security` over an atom-backed map. Mimics the exit codes
   and the trailing newline `find-generic-password -w` prints."
  [db]
  (fn [argv & _]
    (let [k (arg-after argv "-a")]
      (case (second argv)
        "add-generic-password"    (do (swap! db assoc k (arg-after argv "-w")) {:exit 0 :out "" :err ""})
        "find-generic-password"   (if-let [s (get @db k)]
                                    {:exit 0 :out (str s "\n") :err ""}
                                    {:exit 44 :out "" :err "could not be found"})
        "delete-generic-password" (do (swap! db dissoc k) {:exit 0 :out "" :err ""})
        {:exit 1 :out "" :err "unexpected"}))))

(defn- fake-secret-tool
  "Stand-in for Linux `secret-tool`; the secret arrives on stdin (`:in`)."
  [db]
  (fn [argv & {:keys [in]}]
    (let [k (arg-after argv "account")]
      (case (second argv)
        "store"  (do (swap! db assoc k in) {:exit 0 :out "" :err ""})
        "lookup" (if-let [s (get @db k)] {:exit 0 :out s :err ""} {:exit 1 :out "" :err ""})
        "clear"  (do (swap! db dissoc k) {:exit 0 :out "" :err ""})
        {:exit 1 :out "" :err "unexpected"}))))

(deftest macos-backend-roundtrip
  (let [db (atom {})]
    (binding [store/*backend* :keychain-macos
              store/*run*     (fake-security db)]
      (testing "save → load → clear through the dispatcher"
        (is (false? (oauth/authenticated? "notion")))
        (oauth/save-tokens! "notion" {:access_token "AT" :refresh_token "RT"})
        (is (true? (oauth/authenticated? "notion")))
        (is (= {:access_token "AT" :refresh_token "RT"} (oauth/load-tokens "notion"))
            "stored secret is the JSON bundle; trailing newline trimmed on load")
        (oauth/clear-tokens! "notion")
        (is (false? (oauth/authenticated? "notion"))))
      (testing "the keychain item key folds in the user id"
        (oauth/save-tokens! "notion" {:access_token "AT"})
        (is (= 1 (count @db)))
        (is (str/includes? (key (first @db)) ":notion"))
        (is (str/includes? (val (first @db)) "\"access_token\":\"AT\""))))))

(deftest macos-load-missing-returns-nil
  (binding [store/*backend* :keychain-macos
            store/*run*     (fake-security (atom {}))]
    (is (nil? (oauth/load-tokens "absent")))))

(deftest libsecret-backend-roundtrip
  (let [db (atom {})]
    (binding [store/*backend* :keychain-libsecret
              store/*run*     (fake-secret-tool db)]
      (oauth/save-tokens! "linear" {:access_token "X"})
      (is (= {:access_token "X"} (oauth/load-tokens "linear")))
      (is (= "{\"access_token\":\"X\"}" (val (first @db))) "secret stored via stdin")
      (oauth/clear-tokens! "linear")
      (is (nil? (oauth/load-tokens "linear"))))))

(deftest probe-roundtrips-and-cleans-up
  (let [probe @#'store/probe-backend!
        db    (atom {})]
    (is (true? (probe :keychain-macos (fake-security db))))
    (is (empty? @db) "probe deletes its throwaway credential")))

(deftest probe-fails-when-keychain-unavailable
  (let [probe @#'store/probe-backend!]
    (is (false? (probe :keychain-macos (fn [& _] (throw (RuntimeException. "no keychain daemon"))))))
    (is (false? (probe :keychain-macos (fn [& _] {:exit 1 :out "" :err "denied"})))
        "non-zero exit on save → not usable")))
