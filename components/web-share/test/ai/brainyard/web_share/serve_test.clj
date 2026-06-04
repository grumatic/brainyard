;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.web-share.serve-test
  "Integration test for serve!: spawns a real ttyd wrapping a trivial child,
   asserts basic-auth is enforced (401 without creds, 200 with), and that the
   process is reaped by the returned :stop fn. Skips with a log line when ttyd
   is not installed, so CI without ttyd stays green."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.web-share.core :as core])
  (:import [java.net URL HttpURLConnection ServerSocket]
           [java.util Base64]))

(defn- free-port
  "Grab an ephemeral port from the OS, then release it for ttyd to claim.
   Small TOCTOU window, acceptable for a local integration test."
  []
  (with-open [s (ServerSocket. 0)]
    (.getLocalPort s)))

(defn- http-status
  "GET `url`, optionally with HTTP Basic `user:pass`. Returns the status code,
   or :error on a connection failure."
  [url user-pass]
  (try
    (let [conn ^HttpURLConnection (.openConnection (URL. url))]
      (try
        (when user-pass
          (.setRequestProperty conn "Authorization"
                               (str "Basic " (.encodeToString (Base64/getEncoder)
                                                              (.getBytes ^String user-pass)))))
        (.setConnectTimeout conn 2000)
        (.setReadTimeout conn 2000)
        (.getResponseCode conn)
        (finally (.disconnect conn))))
    (catch Exception _ :error)))

(defn- await-status
  "Poll http-status until it is not :error (ttyd needs a beat to bind), up to
   ~5s. Returns the final status (possibly :error)."
  [url user-pass]
  (loop [tries 25]
    (let [st (http-status url user-pass)]
      (if (or (not= :error st) (zero? tries))
        st
        (do (Thread/sleep 200) (recur (dec tries)))))))

(deftest serve-and-auth-and-reap
  (let [avail (core/available?)]
    (if-not (:ok? avail)
      (println "[serve-test] SKIP: ttyd not on PATH —" (:hint avail))
      (let [port (free-port)
            url  (str "http://127.0.0.1:" port "/")
            ;; `cat` is a harmless per-connection child; the server binds
            ;; regardless of whether the child would succeed.
            handle (core/serve! {:ttyd-path  (:path avail)
                                 :bind       "127.0.0.1"
                                 :port       port
                                 :credential "alice:s3cret"
                                 :writable?  true
                                 :child-argv ["cat"]})]
        (try
          (testing "server binds and answers"
            (is (not= :error (await-status url "alice:s3cret"))
                "ttyd should be reachable on the bound port"))
          (testing "auth is enforced"
            (is (= 401 (http-status url nil)) "no credentials → 401")
            (is (= 200 (http-status url "alice:s3cret")) "valid credentials → 200")
            (is (= 401 (http-status url "alice:wrong")) "wrong credentials → 401"))
          (testing "handle shape"
            (is (= url (str (:url handle) "/")))
            (is (instance? Process (:proc handle))))
          (finally
            ((:stop handle))))
        (testing ":stop reaps the ttyd process"
          (is (not (.isAlive ^Process (:proc handle)))))))))

(deftest serve-rejects-empty-child-argv
  (testing "serve! refuses to launch without a child command"
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/serve! {:port (free-port) :credential "u:p" :child-argv []})))))

;; ---------------------------------------------------------------------------
;; Tier 2 — serve-tmux! (needs both ttyd and tmux)
;; ---------------------------------------------------------------------------

(deftest serve-tmux-lifecycle
  (let [avail (core/available?)]
    (cond
      (not (:ok? avail))
      (println "[serve-test] SKIP serve-tmux: ttyd not on PATH")

      (not (core/tmux-available?))
      (println "[serve-test] SKIP serve-tmux: tmux not on PATH")

      :else
      (let [port   (free-port)
            url    (str "http://127.0.0.1:" port "/")
            ;; `sleep` keeps the pane (and thus the tmux session) alive for the
            ;; duration of the test; the real child would be `by run`.
            handle (core/serve-tmux! {:ttyd-path  (:path avail)
                                      :bind       "127.0.0.1"
                                      :port       port
                                      :credential "alice:s3cret"
                                      :writable?  true
                                      :child-argv ["sleep" "60"]})]
        (try
          (testing "isolated socket + session created and alive"
            (is (string? (:socket handle)))
            (is (= "brainyard" (:session handle)))
            (is ((:alive? handle)) "tmux session should be running"))
          (testing "ttyd serves the session with auth enforced"
            (is (not= :error (await-status url "alice:s3cret")))
            (is (= 401 (http-status url nil)))
            (is (= 200 (http-status url "alice:s3cret"))))
          (testing "attach-argv targets the private socket"
            (is (= ["attach" "-t" "brainyard"]
                   (take-last 3 (:attach-argv handle))))
            (is (some #{(:socket handle)} (:attach-argv handle))))
          (finally
            ((:stop handle))))
        (testing ":stop kills the tmux server, reaps ttyd, unlinks the socket"
          (is (not ((:alive? handle))) "session should be gone after stop")
          (is (not (.isAlive ^Process (:proc handle))))
          (when-let [sp (:socket-path handle)]
            (is (not (.exists (java.io.File. ^String sp)))
                "stale tmux socket file should be removed")))))))
