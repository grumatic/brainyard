;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.mcp.client-test
  "Unit tests for MCP client construction — focused on the per-request
   `:timeout` plumbing. A stdio server (e.g. Gmail/Calendar via mcp-remote)
   must be able to raise the 30s `send-request!` default through its
   `:config :timeout`, mirroring the HTTP transport."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.agent.mcp.client :as mcp-client]))

(deftest stdio-client-carries-timeout
  (testing "create-client :stdio threads :timeout from config onto the record"
    (let [client (mcp-client/create-client :stdio {:timeout 90000})]
      (is (= 90000 (:timeout client))
          "send-request! reads (:timeout client); it must be present for stdio")))

  (testing "absent :timeout → nil, so send-request! falls back to its 30s default"
    (let [client (mcp-client/create-client :stdio {})]
      (is (nil? (:timeout client)))))

  (testing "connect!'s assoc-threading preserves :timeout onto the connected record"
    ;; connect! returns (-> client (assoc :process ...) (assoc :stdin ...) ...);
    ;; assoc onto a defrecord keeps extension-map keys, so the registered client
    ;; still carries the timeout. Assert the invariant without a live process.
    (let [client    (mcp-client/create-client :stdio {:timeout 75000})
          connected (assoc client :process :fake :stdin :fake)]
      (is (= 75000 (:timeout connected))))))

(deftest http-client-carries-timeout
  (testing "create-client :http carries :timeout in :options (parity with stdio)"
    (let [client (mcp-client/create-client :http {:timeout 90000})]
      ;; HTTP send-request reads (:timeout (:options client)).
      (is (= 90000 (:timeout (:options client))))))

  (testing "absent :timeout → nil in :options, 30s default applies"
    (let [client (mcp-client/create-client :http {})]
      (is (nil? (:timeout (:options client)))))))

(deftest stderr-drain-reads-all-lines-and-terminates
  (testing "drain-stderr-lines! forwards every line to the sink and returns at EOF (no hang/blocking)"
    (let [collected (atom [])
          rdr (java.io.BufferedReader.
               (java.io.StringReader.
                "Connecting...\nPlease authorize: https://accounts.google.com/o/oauth2/v2/auth?x=1\nDone\n"))
          drain @#'mcp-client/drain-stderr-lines!]
      (drain rdr (fn [l] (swap! collected conj l)))
      (is (= ["Connecting..."
              "Please authorize: https://accounts.google.com/o/oauth2/v2/auth?x=1"
              "Done"]
             @collected)
          "all stderr lines drained, including the auth URL; loop exits at EOF"))))

(deftest env-refs-expand-from-environment-and-properties
  (let [expand @#'mcp-client/expand-env-refs]

    (testing "a JVM system property resolves a reference"
      ;; THE CASE THE WHOLE CHANGE EXISTS FOR. dotenv.clj puts `.env` keys into
      ;; PROPERTIES, not the environment, and a spawned server inherits only the
      ;; environment — so without this half, the `.env` route the MCP catalog
      ;; documents cannot reach a server at all.
      (System/setProperty "BY_TEST_PG_URL" "postgresql://u:pw@localhost/db")
      (try
        (is (= "postgresql://u:pw@localhost/db" (expand "${BY_TEST_PG_URL}")))
        (finally (System/clearProperty "BY_TEST_PG_URL"))))

    (testing "an environment variable resolves a reference"
      ;; HOME rather than a fixture: a test cannot set an env var in its own JVM.
      (is (= (System/getenv "HOME") (expand "${HOME}"))))

    (testing "the environment wins over a property of the same name"
      (System/setProperty "HOME" "/property/side")
      (try
        (is (= (System/getenv "HOME") (expand "${HOME}"))
            "getenv is consulted first, matching every other setting here")
        (finally (System/clearProperty "HOME"))))

    (testing "an unresolved reference is left as its own text, not blanked"
      ;; An empty string would read as a malformed URL and say nothing about
      ;; why; the literal names the variable that was missing.
      (is (= "${BY_TEST_DEFINITELY_UNSET}" (expand "${BY_TEST_DEFINITELY_UNSET}"))))

    (testing "surrounding text survives, and every reference in a string expands"
      (System/setProperty "BY_TEST_HOST" "db.internal")
      (System/setProperty "BY_TEST_PORT" "5432")
      (try
        (is (= "postgresql://db.internal:5432/app"
               (expand "postgresql://${BY_TEST_HOST}:${BY_TEST_PORT}/app")))
        (finally (System/clearProperty "BY_TEST_HOST")
                 (System/clearProperty "BY_TEST_PORT"))))

    (testing "text with no reference is returned unchanged"
      (is (= "@modelcontextprotocol/server-postgres"
             (expand "@modelcontextprotocol/server-postgres")))
      ;; A bare `$` or an unbraced `$FOO` is not a reference — only `${…}` is,
      ;; so a password containing `$` passes through untouched.
      (is (= "p@ss$word$FOO" (expand "p@ss$word$FOO"))))))

(deftest env-var-names-drop-the-keyword-colon
  (let [env-name @#'mcp-client/env-var-name]
    (testing "a keyword key yields the bare name, not \":NAME\""
      ;; `(str :CLICKHOUSE_HOST)` keeps the colon, so this used to spawn the
      ;; child with a variable no process reads: the server started, its
      ;; lookup missed, and it fell back to a default host.
      (is (= "CLICKHOUSE_HOST" (env-name :CLICKHOUSE_HOST)))
      (is (not= ":CLICKHOUSE_HOST" (env-name :CLICKHOUSE_HOST))))

    (testing "a string key — the documented form — is unchanged"
      (is (= "CLICKHOUSE_HOST" (env-name "CLICKHOUSE_HOST"))))

    (testing "a symbol key resolves the same way"
      (is (= "CLICKHOUSE_HOST" (env-name 'CLICKHOUSE_HOST))))

    (testing "an odd key degrades to its printed form rather than throwing"
      ;; Refusing to spawn over a cosmetic key mistake would turn it into an
      ;; outage; the name is still visible in the child's environment.
      (is (= "42" (env-name 42))))))

(deftest spawned-child-receives-usable-env-var-names
  ;; End to end through ProcessBuilder, because the defect was never in the
  ;; name-building — it was in what the CHILD ended up seeing.
  (testing "both key spellings arrive as names the child can read"
    (let [env-name @#'mcp-client/env-var-name
          expand   @#'mcp-client/expand-env-refs
          env      {"BY_TEST_ENV_STR" "from-string-key"
                    :BY_TEST_ENV_KW   "from-keyword-key"}
          pb       (ProcessBuilder. ^"[Ljava.lang.String;"
                    (into-array String ["env"]))
          _        (let [m (.environment pb)]
                     (doseq [[k v] env]
                       (.put m (env-name k) (expand v))))
          seen     (with-open [r (io/reader (.getInputStream (.start pb)))]
                     (into #{} (filter #(str/starts-with? % "BY_TEST_ENV"))
                           (line-seq r)))]
      (is (contains? seen "BY_TEST_ENV_STR=from-string-key"))
      (is (contains? seen "BY_TEST_ENV_KW=from-keyword-key"))
      (is (not (contains? seen ":BY_TEST_ENV_KW=from-keyword-key"))
          "the colon-prefixed name is what the child used to get"))))
