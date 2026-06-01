;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
(ns ai.brainyard.clj-nrepl.server-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.clj-nrepl.interface :as n]
            [ai.brainyard.clj-nrepl.core.confirm :as confirm]
            [ai.brainyard.clj-nrepl.core.drift :as drift]))

(defn- with-running-server [t]
  (try
    (n/start-server! :bind "127.0.0.1" :port 0)
    (n/grant! :scope :read-only :ttl-ms 60000)
    (drift/clear!)
    (confirm/revoke-confirmation!)
    (confirm/set-confirm-fn! nil)
    (t)
    (finally
      (drift/clear!)
      (confirm/revoke-confirmation!)
      (confirm/set-confirm-fn! nil)
      (n/revoke!)
      (try (n/stop-server!) (catch Exception _)))))

(use-fixtures :each with-running-server)

;; --- baseline -------------------------------------------------------------

(deftest server-lifecycle
  (is (n/running?))
  (is (pos? (n/server-port))))

(deftest non-loopback-bind-rejected
  (n/stop-server!)
  (try
    (is (thrown? Exception (n/start-server! :bind "0.0.0.0" :port 0)))
    (finally
      (n/start-server! :bind "127.0.0.1" :port 0))))

(deftest eval-roundtrip
  (let [r (n/eval-string "(+ 1 2)")]
    (is (= "3" (:result r)))
    (is (nil? (:error r)))
    (is (= "user" (:ns r))))
  (let [r (n/eval-string "(println \"hello\") :done")]
    (is (= ":done" (:result r)))
    (is (re-find #"hello" (:output r)))))

(deftest session-isolation
  (let [s1 (n/new-session)
        s2 (n/new-session)]
    (try
      (is (and (string? s1) (string? s2) (not= s1 s2)))
      (finally
        (n/close-session s1)
        (n/close-session s2)))))

;; --- gate: grant ----------------------------------------------------------

(deftest no-grant-no-eval
  (n/revoke!)
  (let [r (n/eval-string "(+ 1 2)")]
    (is (re-find #"no clj-nrepl grant active" (:error r)))))

;; --- gate: deny-list (applies in BOTH scopes) ----------------------------

(deftest deny-list-rejected-under-read-only
  (let [r (n/eval-string "(System/exit 0)")]
    (is (some? (:error r)))
    (is (re-find #"denied by clj-nrepl allow/deny" (:error r)))))

(deftest deny-list-rejected-under-mutate
  (n/revoke!)
  (n/grant! :scope :mutate :ttl-ms 60000)
  (let [r (n/eval-string "(System/exit 0)")]
    (is (re-find #"denied by clj-nrepl allow/deny" (:error r))
        ":mutate scope must NOT lift the deny-list")))

;; --- gate: read-only rejects mutating forms ------------------------------

(deftest mutating-form-rejected-under-readonly
  (let [r (n/eval-string "(def secret 42)")]
    (is (re-find #"mutating form rejected under read-only" (:error r)))))

;; --- gate: :mutate scope allows defs + drift fires ----------------------

(deftest mutate-marks-drift-even-when-later-form-errors
  ;; A block whose first form is a successful (def …) but whose later
  ;; form errors must STILL mark drift — the runtime really did change.
  ;; Catches the regression where (nil? (:error result)) was the gate
  ;; and any later form's failure suppressed the marker.
  (n/revoke!)
  (n/grant! :scope :mutate :ttl-ms 60000)
  (let [sid (n/new-session)]
    (try
      (let [r (n/eval-string
               "(def drift-survives-error 1)\n(this-symbol-does-not-resolve)"
               :session sid)]
        (is (some? (:error r))
            "later form errors → :error present in result"))
      (is (n/drifted?)
          "drift must still be marked because (def …) executed before the later error")
      (is (= 1 (n/drift-count)))
      (finally
        (n/close-session sid)))))

(deftest mutate-scope-allows-defs-and-marks-drift
  (n/revoke!)
  (n/grant! :scope :mutate :ttl-ms 60000)
  ;; The nREPL server only accepts session ids it issued via `clone`, so the
  ;; test opens one. Confirm/drift use the same id for tracking.
  (let [sid (n/new-session)]
    (try
      ;; First mutation needs confirmation — default fn (nil) auto-allows
      (let [r (n/eval-string "(def drift-probe-1 42)" :session sid)]
        (is (nil? (:error r)) (str "expected success, got: " (:error r)))
        (is (= "#'user/drift-probe-1" (:result r))))
      (is (n/drifted?) "successful mutating eval must record drift")
      (is (= 1 (n/drift-count)))
      (let [m (first (n/drift-markers))]
        (is (= sid (:session m))))
      (finally
        (n/close-session sid)))))

;; --- gate: read-only success does NOT mark drift -------------------------

(deftest read-only-success-does-not-drift
  (n/eval-string "(+ 1 2)")
  (is (not (n/drifted?))
      "non-mutating evals should never mark drift"))

;; --- gate: confirmation prompt -------------------------------------------

(deftest confirm-fn-called-once-per-session-on-mutate
  (n/revoke!)
  (n/grant! :scope :mutate :ttl-ms 60000)
  (let [sid (n/new-session)
        calls (atom [])]
    (try
      (confirm/set-confirm-fn! (fn [ev] (swap! calls conj ev) true))
      (let [r1 (n/eval-string "(def a 1)" :session sid)
            r2 (n/eval-string "(def b 2)" :session sid)]
        (is (nil? (:error r1)) (str "r1: " (:error r1)))
        (is (nil? (:error r2)) (str "r2: " (:error r2)))
        (is (= 1 (count @calls))
            "confirm-fn called only on first mutation in the session"))
      (finally
        (n/close-session sid)))))

(deftest declined-confirm-blocks-eval
  (n/revoke!)
  (n/grant! :scope :mutate :ttl-ms 60000)
  (confirm/set-confirm-fn! (fn [_] false))
  (let [r (n/eval-string "(def nope 1)" :session "sess-d")]
    (is (re-find #"declined mutation confirmation" (:error r))))
  (is (not (n/drifted?))
      "declined mutation must NOT mark drift"))
