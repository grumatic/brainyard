;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.core.store
  "Provider-agnostic OAuth token store + refresh.

   Supersedes the plaintext, world-readable file that clj-llm/core/oauth.clj
   shipped at `~/.config/clj-llm/anthropic-oauth-tokens.json`.

   Two backends, selected by `BY_OAUTH_TOKEN_STORE` (`auto` | `keychain` |
   `file`), default `auto`:

   - **Keychain** — the OS secret store via a subprocess (macOS `security`,
     Linux `secret-tool`). Subprocess, so no JNI/reflection — safe under
     native-image, unlike a bundled keyring lib. `auto` picks it only when its
     CLI is present and a probe write/read round-trips.
   - **File** — `~/.brainyard/oauth/<user-id>/<account>.json`, `0700` dirs /
     `0600` files, written atomically (temp-then-rename) so a crash mid-write
     can't truncate a rotating refresh token. The fallback the playground
     container takes (no keychain daemon).

   Keyed by `(account-id, user-id)` so credentials partition by user the same
   way memory/sessions do (CLAUDE.md). `account-id` is a keyword or string —
   e.g. `:anthropic`, `\"notion\"`. The resolved backend is logged once at
   `::oauth-store` — never the secret.

   This is the bare store + refresh — no flow orchestration, no discovery, so
   both clj-llm (Anthropic adapter) and the agent MCP transport depend on it
   without pulling either of them in."
  (:require [ai.brainyard.clj-http-native.interface :as http]
            [ai.brainyard.clj-oauth.core.encode :as encode]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
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

(defn ^:private account-label
  "Filesystem/keychain-safe label for an account-id keyword/string."
  [account-id]
  (let [raw (if (keyword? account-id) (name account-id) (str account-id))]
    (str/replace raw #"[^A-Za-z0-9_.-]" "_")))

(defn ^:private account->filename [account-id]
  (str (account-label account-id) ".json"))

(defn ^:private account-key
  "Keychain item account name: `<user-id>:<account>` (the keychain isn't
   path-partitioned like the file backend, so fold user-id into the key)."
  [account-id]
  (str (resolve-user-id) ":" (account-label account-id)))

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
;; File backend (atomic, 0600)
;; ============================================================================

(defn oauth-root
  "`~/.brainyard/oauth/` — the file-store root. Created `0700` on demand."
  []
  (let [dir (io/file (System/getProperty "user.home") ".brainyard" "oauth")]
    (mkdirs-0700! dir)
    dir))

(defn token-file
  "`~/.brainyard/oauth/<user-id>/<account>.json` for `account-id` (file backend).
   Ensures the per-user dir exists `0700`."
  ^java.io.File [account-id]
  (let [user-dir (io/file (oauth-root) (resolve-user-id))]
    (mkdirs-0700! user-dir)
    (io/file user-dir (account->filename account-id))))

(defn ^:private file-save! [account-id tokens]
  (let [target (token-file account-id)
        dir    (.getParentFile target)
        tmp    (io/file dir (str "." (.getName target) ".tmp"))]
    (spit tmp (json/write-str tokens))
    (chmod! (.toPath tmp) "rw-------")
    (Files/move (.toPath tmp) (.toPath target)
                (into-array StandardCopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    (mulog/debug ::tokens-saved :account account-id :backend :file :path (.getPath target))
    tokens))

(defn ^:private file-load [account-id]
  (let [f (token-file account-id)]
    (when (.exists f)
      (try
        (json/read-str (slurp f) :key-fn keyword)
        (catch Exception e
          (mulog/warn ::tokens-read-failed :account account-id :backend :file
                      :message (.getMessage e))
          nil)))))

(defn ^:private file-clear! [account-id]
  (let [f (token-file account-id)]
    (when (.exists f)
      (.delete f)
      (mulog/info ::tokens-cleared :account account-id :backend :file))))

;; ============================================================================
;; Keychain backends (subprocess — native-image safe)
;; ============================================================================

(def ^:private kc-service "ai.brainyard.oauth")

(defn ^:private default-run
  "Run `argv` (vector of strings); returns `{:exit :out :err}`. Optional `:in`
   stdin. Secrets ride argv (macOS `-w`) or stdin (libsecret) — never logged."
  [argv & {:keys [in]}]
  (apply shell/sh (cond-> (vec argv) in (concat [:in in]))))

(def ^:dynamic *run*
  "Subprocess runner for the keychain backends; rebindable for tests."
  default-run)

;; --- macOS `security` ---

(defn ^:private macos-save! [run k secret]
  (let [{:keys [exit err]} (run ["security" "add-generic-password" "-U"
                                 "-s" kc-service "-a" k "-w" secret])]
    (when-not (zero? exit)
      (throw (ex-info "keychain (security) save failed" {:exit exit :err err})))))

(defn ^:private macos-load [run k]
  (let [{:keys [exit out]} (run ["security" "find-generic-password"
                                 "-s" kc-service "-a" k "-w"])]
    (when (zero? exit) (str/trim-newline out))))

(defn ^:private macos-clear! [run k]
  (run ["security" "delete-generic-password" "-s" kc-service "-a" k]))

;; --- Linux `secret-tool` (libsecret) — reads the secret from stdin ---

(defn ^:private libsecret-save! [run k secret]
  (let [{:keys [exit err]} (run ["secret-tool" "store" "--label" kc-service
                                 "service" kc-service "account" k]
                                :in secret)]
    (when-not (zero? exit)
      (throw (ex-info "keychain (secret-tool) save failed" {:exit exit :err err})))))

(defn ^:private libsecret-load [run k]
  (let [{:keys [exit out]} (run ["secret-tool" "lookup" "service" kc-service "account" k])]
    (when (and (zero? exit) (not (str/blank? out))) (str/trim-newline out))))

(defn ^:private libsecret-clear! [run k]
  (run ["secret-tool" "clear" "service" kc-service "account" k]))

(def ^:private backends
  {:keychain-macos     {:save macos-save!     :load macos-load     :clear macos-clear!}
   :keychain-libsecret {:save libsecret-save! :load libsecret-load :clear libsecret-clear!}})

;; ============================================================================
;; Backend resolution
;; ============================================================================

(defn ^:private cli-available? [exe]
  (try (zero? (:exit (shell/sh "sh" "-c" (str "command -v " exe))))
       (catch Throwable _ false)))

(defn ^:private probe-backend!
  "Round-trip a throwaway credential; true when the keychain backend works."
  [backend run]
  (try
    (let [{:keys [save load clear]} (backends backend)
          k (str "__brainyard_probe__:" (System/nanoTime))]
      (save run k "{\"probe\":1}")
      (let [ok (= "{\"probe\":1}" (load run k))]
        (clear run k)
        ok))
    (catch Throwable _ false)))

(defn ^:private detect-keychain [run]
  (let [os (str/lower-case (str (System/getProperty "os.name")))]
    (cond
      (and (str/includes? os "mac") (cli-available? "security")
           (probe-backend! :keychain-macos run))     :keychain-macos
      (and (cli-available? "secret-tool")
           (probe-backend! :keychain-libsecret run))  :keychain-libsecret
      :else nil)))

(defn ^:private resolve-backend [run]
  (case (some-> (System/getenv "BY_OAUTH_TOKEN_STORE") str/trim str/lower-case not-empty)
    "file"     :file
    "keychain" (or (detect-keychain run) :file)
    ;; "auto" or unset
    (or (detect-keychain run) :file)))

(def ^:dynamic *backend*
  "Explicit backend override (mainly tests): `:file` | `:keychain-macos` |
   `:keychain-libsecret`. nil → resolved from `BY_OAUTH_TOKEN_STORE` /
   auto-detect, then cached."
  nil)

(defonce ^:private !resolved (atom nil))

(defn current-backend
  "The active store backend keyword. Honors `*backend*`, else the cached
   resolution, else resolves once (logging the choice — never the secret)."
  []
  (or *backend*
      @!resolved
      (let [b (resolve-backend *run*)]
        (reset! !resolved b)
        (mulog/info ::oauth-store :backend b)
        b)))

(defn reset-backend-cache!
  "Drop the cached backend resolution (tests / after an env change)."
  []
  (reset! !resolved nil))

(defn set-backend!
  "Pin the store backend from config: `:auto`/\"auto\" (re-resolve from env +
   auto-detect), `:file`, `:keychain` (detect the platform keychain, else file),
   or an already-resolved keyword. Lets `.brainyard/config.edn :oauth-token-store`
   drive the backend without an env var. Takes precedence over a later env read
   only when not `:auto` (`:auto` defers to the env/auto-detect ladder)."
  [choice]
  (let [c (some-> choice (#(if (keyword? %) % (keyword (str %)))))]
    (reset! !resolved
            (case c
              (nil :auto) nil
              :file       :file
              :keychain   (or (detect-keychain *run*) :file)
              (if (contains? backends c) c :file)))))

;; ============================================================================
;; Public store API (backend dispatch)
;; ============================================================================

(defn save-tokens!
  "Persist `tokens` for `account-id` via the active backend. File backend is
   atomic + `0600`; keychain backends store the JSON bundle as the secret.
   Returns `tokens`."
  [account-id tokens]
  (let [b (current-backend)]
    (if (= :file b)
      (file-save! account-id tokens)
      (do ((:save (backends b)) *run* (account-key account-id) (json/write-str tokens))
          (mulog/debug ::tokens-saved :account account-id :backend b)))
    tokens))

(defn load-tokens
  "Load the token bundle for `account-id` via the active backend, or nil."
  [account-id]
  (let [b (current-backend)]
    (if (= :file b)
      (file-load account-id)
      (try
        (some-> ((:load (backends b)) *run* (account-key account-id))
                (json/read-str :key-fn keyword))
        (catch Exception e
          (mulog/warn ::tokens-read-failed :account account-id :backend b
                      :message (.getMessage e))
          nil)))))

(defn clear-tokens!
  "Delete the stored token bundle for `account-id` (logout) via the backend."
  [account-id]
  (let [b (current-backend)]
    (if (= :file b)
      (file-clear! account-id)
      (do ((:clear (backends b)) *run* (account-key account-id))
          (mulog/info ::tokens-cleared :account account-id :backend b))))
  nil)

(defn authenticated?
  "True when a token bundle is stored for `account-id` (refreshable or not)."
  [account-id]
  (boolean (load-tokens account-id)))

;; ============================================================================
;; Validation & refresh (provider-agnostic)
;; ============================================================================

(defn token-expired?
  "True when the bundle has an `:expires_at` in the past (or within 60s). A
   bundle with NO `:expires_at` is treated as NON-expiring — e.g. a GitHub
   user-to-server token issued without expiry — so it is not expired. (Flows
   that always carry an expiry set `:expires_at`; only genuinely non-expiring
   tokens omit it, and forcing those through a refresh they can't do would
   dead-end with \"No refresh token available\".)"
  [tokens]
  (if-let [expires-at (:expires_at tokens)]
    (< expires-at (+ (System/currentTimeMillis) 60000))
    false))

(defn token-usable?
  "True when a stored bundle for `account-id` can yield a valid bearer WITHOUT an
   interactive re-login: present, and either not expired or holding a refresh
   token. A stale, refresh-less bundle is NOT usable — callers should re-run
   `login!` rather than dead-end on a refresh the provider can't service."
  [account-id]
  (when-let [t (load-tokens account-id)]
    (boolean (or (not (token-expired? t)) (:refresh_token t)))))

(defn ^:private refresh-body
  "Build the token-endpoint request body for a refresh. `:json` matches the
   Anthropic console (its working behavior, preserved on extraction); `:form`
   is the standard `application/x-www-form-urlencoded` grant used by RFC 8628
   device-flow providers (Phase 2)."
  [encoding {:keys [refresh_token client-id client-secret]}]
  (case encoding
    :form (encode/form-encode (cond-> {"grant_type"    "refresh_token"
                                       "refresh_token" refresh_token
                                       "client_id"     client-id}
                                client-secret (assoc "client_secret" client-secret)))
    (json/write-str (cond-> {:grant_type    "refresh_token"
                             :refresh_token refresh_token
                             :client_id     client-id}
                      client-secret (assoc :client_secret client-secret)))))

(defn refresh-access-token
  "Refresh `tokens` for `account-id` against `:token-endpoint` using `:client-id`.
   Endpoint + client come from the caller (discovery in Phase 2, the Anthropic
   constant in the clj-llm adapter) — never hard-coded here. `:body-encoding`
   is `:json` (default) or `:form`. Persists the new bundle (carrying the
   rotated refresh token if the provider sent one) and returns it; throws on
   missing refresh token or non-2xx."
  [account-id {:keys [refresh_token] :as tokens}
   {:keys [token-endpoint client-id client-secret body-encoding]
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
                                                          :client-id     client-id
                                                          :client-secret client-secret})
                             :content-type (if (= body-encoding :form)
                                             "application/x-www-form-urlencoded"
                                             :json)
                             ;; Request a JSON token response (RFC 6749 §5.1);
                             ;; GitHub form-encodes otherwise — see exchange-code!.
                             :headers      {"Accept" "application/json"}
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
