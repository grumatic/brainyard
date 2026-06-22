;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.core.store
  "Provider-agnostic OAuth token store + refresh.

   Supersedes the plaintext, world-readable file that clj-llm/core/oauth.clj
   shipped at `~/.config/clj-llm/anthropic-oauth-tokens.json`. Tokens now live
   under `~/.brainyard/oauth/<user-id>/<account>.json` with `0700` dirs and
   `0600` files, written atomically (temp-then-rename) so a crash mid-write
   cannot truncate a rotating refresh token.

   Keyed by `(account-id, user-id)` so credentials partition by user the same
   way memory/sessions do (CLAUDE.md). `account-id` is a keyword or string —
   e.g. `:anthropic`, `\"notion\"`. Multi-identity-per-account (open question
   §11.4) stays additive: the bundle already carries the token's claims.

   Backend here is the hardened file fallback only. The keychain backend
   (macOS `security` / Linux `secret-tool`) lands in Phase 4; this namespace is
   the seam it will plug into.

   This is the bare store + refresh — no flow orchestration, no discovery, so
   both clj-llm (Anthropic adapter) and the agent MCP transport depend on it
   without pulling either of them in."
  (:require [ai.brainyard.clj-http-native.interface :as http]
            [ai.brainyard.clj-oauth.core.encode :as encode]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

;; ============================================================================
;; User / account keying
;; ============================================================================

(defn resolve-user-id
  "Resolve the identity that partitions the token store, mirroring the
   `BY_USER_ID` ladder in CLAUDE.md minus the agent-only `--user-id` flag:
   `BY_USER_ID` env > `user.name` system property > \"by-user\"."
  []
  (or (some-> (System/getenv "BY_USER_ID") str/trim not-empty)
      (some-> (System/getProperty "user.name") str/trim not-empty)
      "by-user"))

(defn ^:private account->filename
  "Filesystem-safe `<account>.json` segment for an account-id keyword/string."
  [account-id]
  (let [raw (if (keyword? account-id) (name account-id) (str account-id))]
    (str (str/replace raw #"[^A-Za-z0-9_.-]" "_") ".json")))

;; ============================================================================
;; POSIX permission helpers (no-op on non-POSIX filesystems)
;; ============================================================================

(defn ^:private posix? [^Path path]
  (-> path .getFileSystem .supportedFileAttributeViews (.contains "posix")))

(defn ^:private chmod! [^Path path perms]
  (when (posix? path)
    (Files/setPosixFilePermissions path (PosixFilePermissions/fromString perms))))

(defn ^:private mkdirs-0700! [^java.io.File dir]
  (when-not (.exists dir)
    (let [path (.toPath dir)]
      (if (posix? path)
        (Files/createDirectories
         path (into-array FileAttribute
                          [(PosixFilePermissions/asFileAttribute
                            (PosixFilePermissions/fromString "rwx------"))]))
        (.mkdirs dir))
      ;; Tighten in case an ancestor was created with a looser umask.
      (chmod! path "rwx------"))))

;; ============================================================================
;; Paths
;; ============================================================================

(defn oauth-root
  "`~/.brainyard/oauth/` — the store root. Created `0700` on demand."
  []
  (let [dir (io/file (System/getProperty "user.home") ".brainyard" "oauth")]
    (mkdirs-0700! dir)
    dir))

(defn token-file
  "`~/.brainyard/oauth/<user-id>/<account>.json` for `account-id`.
   Ensures the per-user dir exists `0700`."
  ^java.io.File [account-id]
  (let [user-dir (io/file (oauth-root) (resolve-user-id))]
    (mkdirs-0700! user-dir)
    (io/file user-dir (account->filename account-id))))

;; ============================================================================
;; File backend (atomic, 0600)
;; ============================================================================

(defn save-tokens!
  "Persist `tokens` (a bundle map) for `account-id`, atomically and `0600`.
   Writes a sibling temp file, chmods it, then renames over the target so a
   crash never leaves a half-written (or world-readable) credential. Returns
   `tokens`."
  [account-id tokens]
  (let [target (token-file account-id)
        dir    (.getParentFile target)
        tmp    (io/file dir (str "." (.getName target) ".tmp"))]
    (spit tmp (json/write-str tokens))
    (chmod! (.toPath tmp) "rw-------")
    (Files/move (.toPath tmp) (.toPath target)
                (into-array StandardCopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    (mulog/debug ::tokens-saved :account account-id :path (.getPath target))
    tokens))

(defn load-tokens
  "Load the token bundle for `account-id`, or nil if none / unreadable."
  [account-id]
  (let [f (token-file account-id)]
    (when (.exists f)
      (try
        (json/read-str (slurp f) :key-fn keyword)
        (catch Exception e
          (mulog/warn ::tokens-read-failed :account account-id :message (.getMessage e))
          nil)))))

(defn clear-tokens!
  "Delete the stored token bundle for `account-id` (logout)."
  [account-id]
  (let [f (token-file account-id)]
    (when (.exists f)
      (.delete f)
      (mulog/info ::tokens-cleared :account account-id))))

(defn authenticated?
  "True when a token bundle is stored for `account-id` (refreshable or not)."
  [account-id]
  (boolean (load-tokens account-id)))

;; ============================================================================
;; Validation & refresh (provider-agnostic)
;; ============================================================================

(defn token-expired?
  "True if the bundle is missing `:expires_at` or expires within 60s."
  [tokens]
  (if-let [expires-at (:expires_at tokens)]
    (< expires-at (+ (System/currentTimeMillis) 60000))
    true))

(defn ^:private refresh-body
  "Build the token-endpoint request body for a refresh. `:json` matches the
   Anthropic console (its working behavior, preserved on extraction); `:form`
   is the standard `application/x-www-form-urlencoded` grant used by RFC 8628
   device-flow providers (Phase 2)."
  [encoding {:keys [refresh_token client-id]}]
  (case encoding
    :form (encode/form-encode {"grant_type"    "refresh_token"
                               "refresh_token" refresh_token
                               "client_id"     client-id})
    (json/write-str {:grant_type    "refresh_token"
                     :refresh_token refresh_token
                     :client_id     client-id})))

(defn refresh-access-token
  "Refresh `tokens` for `account-id` against `:token-endpoint` using `:client-id`.
   Endpoint + client come from the caller (discovery in Phase 2, the Anthropic
   constant in the clj-llm adapter) — never hard-coded here. `:body-encoding`
   is `:json` (default) or `:form`. Persists the new bundle (carrying the
   rotated refresh token if the provider sent one) and returns it; throws on
   missing refresh token or non-2xx."
  [account-id {:keys [refresh_token] :as tokens}
   {:keys [token-endpoint client-id body-encoding]
    :or   {body-encoding :json}}]
  (when-not refresh_token
    (throw (ex-info "No refresh token available. Re-authenticate with OAuth."
                    {:account account-id})))
  (when-not token-endpoint
    (throw (ex-info "refresh-access-token requires :token-endpoint" {:account account-id})))
  (mulog/info ::refreshing-access-token :account account-id)
  (let [response (http/post token-endpoint
                            {:body         (refresh-body body-encoding
                                                         {:refresh_token refresh_token
                                                          :client-id     client-id})
                             :content-type (if (= body-encoding :form)
                                             "application/x-www-form-urlencoded"
                                             :json)
                             :as :string
                             :throw-exceptions true})
        body   (json/read-str (:body response) :key-fn keyword)
        now    (System/currentTimeMillis)
        ;; Merge over the existing bundle so caller-baked refresh metadata
        ;; (:token_endpoint/:client_id/:body_encoding) survives rotation; the
        ;; fresh response wins for the token fields it carries.
        merged (merge tokens body
                      {:expires_at    (+ now (* (:expires_in body 3600) 1000))
                       :refresh_token (or (:refresh_token body) refresh_token)
                       :refreshed_at  now})]
    (save-tokens! account-id merged)
    (mulog/info ::access-token-refreshed :account account-id)
    merged))

(defn get-valid-access-token
  "Return a non-expired access-token string for `account-id`, refreshing via
   `opts` (`:token-endpoint`/`:client-id`/`:body-encoding`) when stale. Throws
   when no bundle is stored."
  [account-id opts]
  (let [tokens (or (:tokens opts) (load-tokens account-id))]
    (when-not tokens
      (throw (ex-info "No OAuth tokens found. Run login first."
                      {:account account-id})))
    (:access_token (if (token-expired? tokens)
                     (refresh-access-token account-id tokens opts)
                     tokens))))
