;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.auth
  "Auth-method registry behind /login and /logout.

   A *login target* is a provider/backend the user can authenticate. Each
   declares exactly one auth METHOD:

     :api-key       credential is an env var (.env or real env) that clj-llm
                    reads (e.g. ANTHROPIC_API_KEY); Bedrock uses the AWS chain.
     :oauth         credential is an OAuth bundle in clj-oauth's store — WE own
                    it (~/.brainyard/oauth/<user>/<account>.json).
     :cli-delegate  credential belongs to an EXTERNAL CLI's own store; we only
                    detect status and instruct. E.g. `claude /login` writes to
                    the macOS Keychain (service \"Claude Code-credentials\") or
                    ~/.claude/.credentials.json on Linux — the same credential
                    the ACP :claude-code backend consumes.

   v1 is detect-and-instruct: `auth-status` reports sign-in state and
   `auth-instructions` returns the exact command for the user to run. We never
   drive a PTY or hold a subscription token.

   See docs/design/login-auth-methods.md."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.clj-oauth.interface :as oauth]))

;; =============================================================================
;; Target registry
;; =============================================================================

(def auth-targets
  "Ordered login targets surfaced by /login. Each: {:id :method :label + method
   fields}. Keep curated (not the full clj-llm catalog) so the table stays short."
  [{:id :anthropic :method :api-key
    :label "Anthropic (API key)" :env "ANTHROPIC_API_KEY"}
   {:id :openai :method :api-key
    :label "OpenAI (API key)" :env "OPENAI_API_KEY"}
   {:id :bedrock :method :api-key
    :label "AWS Bedrock (profile / credential chain)" :probe :aws}
   {:id :claude :method :cli-delegate
    :label "Claude subscription (Claude Code CLI)"
    :cli "claude" :login-cmd "claude /login" :logout-cmd "claude /logout"
    :store "~/.claude"}])

(defn auth-find-target
  "Resolve a target by id name (case-insensitive), or nil."
  [id]
  (let [k (some-> id str str/trim str/lower-case not-empty)]
    (some (fn [t] (when (= k (name (:id t))) t)) auth-targets)))

;; Injectable env reader so tests can redef it without touching System/getenv.
(defn- getenv [k] (System/getenv k))

(defn- which
  "Absolute path of `cmd` on PATH, or nil. Kept local so /login stays free of a
   hard dependency on acp-client (which reaches its own registry via
   requiring-resolve as a SOFT, optional brick dep)."
  [cmd]
  (some (fn [dir]
          (let [f (io/file dir cmd)]
            (when (and (.exists f) (.canExecute f))
              (.getAbsolutePath f))))
        (some-> (System/getenv "PATH") (str/split #":"))))

;; =============================================================================
;; Claude subscription probe (empirically verified — see design doc)
;; =============================================================================

(defn claude-logged-in?
  "True when Claude Code has a stored subscription credential.
   macOS: login Keychain service \"Claude Code-credentials\" (existence only —
   never read the secret). Linux/other: ~/.claude/.credentials.json (0600).
   Never throws; falls back to false so /login still instructs."
  []
  (let [mac? (str/starts-with? (str/lower-case (System/getProperty "os.name" "")) "mac")]
    (if mac?
      (try
        (zero? (:exit (shell/sh "security" "find-generic-password"
                                "-s" "Claude Code-credentials")))
        (catch Exception _ false))
      (.exists (io/file (System/getProperty "user.home") ".claude" ".credentials.json")))))

;; =============================================================================
;; Status — :signed-in | :not-signed-in | :cli-missing | :unknown
;; =============================================================================

(defmulti auth-status
  "Sign-in status for a target, dispatched on :method. Never throws."
  :method)

(defmethod auth-status :api-key [{:keys [env probe]}]
  (cond
    (= probe :aws) (if (clj-llm/aws-credentials-detected?) :signed-in :not-signed-in)
    env            (if (str/blank? (getenv env)) :not-signed-in :signed-in)
    :else          :unknown))

(defmethod auth-status :oauth [{:keys [account-id]}]
  (if (and account-id (oauth/token-usable? account-id)) :signed-in :not-signed-in))

(defmethod auth-status :cli-delegate [{:keys [cli]}]
  (cond
    (nil? (which cli))           :cli-missing
    (and (= cli "claude") (claude-logged-in?)) :signed-in
    :else                                   :not-signed-in))

(defmethod auth-status :default [_] :unknown)

;; =============================================================================
;; Instructions — the exact command for the user to run (detect-and-instruct)
;; =============================================================================

(defmulti auth-instructions
  "User-facing sign-in instructions for a target, dispatched on :method."
  :method)

(defmethod auth-instructions :api-key [{:keys [env probe]}]
  (if (= probe :aws)
    (str "Configure AWS credentials — set AWS_PROFILE in your shell or ~/.brainyard/.env:\n"
         "  echo 'AWS_PROFILE=your-profile' >> ~/.brainyard/.env\n"
         "(AWS_DEFAULT_PROFILE is NOT honored by the binary.)")
    (str "Set " env " in your shell env or ~/.brainyard/.env:\n"
         "  echo '" env "=<your-key>' >> ~/.brainyard/.env\n"
         "(a real shell env var always wins; see .env.example.)")))

(defmethod auth-instructions :oauth [{:keys [id]}]
  (str "Run OAuth sign-in for " (name id) " (browser / device flow)."))

(defmethod auth-instructions :cli-delegate [{:keys [cli login-cmd]}]
  (let [installed? (some? (which cli))]
    (str cli " is " (if installed? "installed" "NOT installed — install it first") ".\n"
         "Sign in with:  !" login-cmd "\n"
         "(the leading '!' runs it in this session; the credential lives in "
         cli "'s own store, not brainyard's.)")))

(defmethod auth-instructions :default [{:keys [id]}]
  (str "No sign-in instructions available for " (name id) "."))

;; =============================================================================
;; Logout
;; =============================================================================

(defn auth-logout!
  "Sign out of a target; returns a user-facing message. :oauth we own → clear
   the bundle. :api-key / :cli-delegate → instruct (we don't mutate the user's
   shell env or another CLI's store)."
  [{:keys [id method env cli logout-cmd account-id]}]
  (case method
    :oauth        (do (when account-id (oauth/logout! account-id))
                      (str "Signed out of " (name id) "."))
    :api-key      (str "To sign out, remove " (or env "the credential")
                       " from your shell env / ~/.brainyard/.env.")
    :cli-delegate (str "Sign out with:  !" (or logout-cmd (str cli " /logout")))
    (str "Nothing to sign out for " (name id) ".")))
