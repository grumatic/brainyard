;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-server.secrets
  "Per-user credential resolution — the `playground-secrets` component. Resolves
   the env a user's workspace should run with (LLM/MCP keys, etc.) and hands it
   to the runtime to inject at container start. Keys never get baked into the
   image, written to Postgres, logged, or passed on the docker command line —
   the runtime writes them to a private temp env-file consumed by `--env-file`.

   Two backends:
   - **Vault** (KV v2) when `VAULT_ADDR` is set: read `<mount>/data/<prefix>/<user-id>`
     with `VAULT_TOKEN`. The secret's fields become the container env verbatim.
   - **shared fallback** otherwise: no per-user map; the runtime falls back to
     `PG_WORKSPACE_ENV_FILE` (the shared `.env`) — the dev/Phase-0 path."
  (:require [jsonista.core :as j])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]))

(defonce ^:private http (delay (HttpClient/newHttpClient)))

(defn vault-configured? [] (boolean (not-empty (System/getenv "VAULT_ADDR"))))

(defn- vault-addr  [] (System/getenv "VAULT_ADDR"))
(defn- vault-token [] (System/getenv "VAULT_TOKEN"))
(defn- kv-mount    [] (or (not-empty (System/getenv "VAULT_KV_MOUNT")) "secret"))
(defn- user-prefix [] (or (not-empty (System/getenv "VAULT_USER_PREFIX")) "playground/users"))

(defn- vault-read
  "Read a KV v2 secret's data map at `path`, or nil. Never logs the value."
  [path]
  (try
    (let [url  (str (vault-addr) "/v1/" (kv-mount) "/data/" path)
          resp (.send @http (-> (HttpRequest/newBuilder (URI/create url))
                                (.header "X-Vault-Token" (str (vault-token)))
                                (.GET) (.build))
                      (HttpResponse$BodyHandlers/ofString))]
      (when (= 200 (.statusCode resp))
        (-> (.body resp)
            (j/read-value (j/object-mapper {:decode-key-fn keyword}))
            :data :data)))
    (catch Exception _ nil)))

(defn env-for-user
  "Per-user env map to inject into `user-id`'s workspace container — string keys
   (env var names) → string values — or nil when no per-user secrets exist (the
   runtime then uses the shared env-file). Values are returned, never logged."
  [user-id]
  (when (and (vault-configured?) user-id)
    (some-> (vault-read (str (user-prefix) "/" user-id))
            (->> (reduce-kv (fn [m k v] (assoc m (name k) (str v))) {}))
            not-empty)))
