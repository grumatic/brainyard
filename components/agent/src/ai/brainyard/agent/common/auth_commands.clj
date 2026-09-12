;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.auth-commands
  "auth$* — the identity of the APPLICATION a project builds.

   NOT the console's own identity. Three different things in this codebase are
   called auth, and keeping them apart is the first thing to know here:

     * `ai.brainyard.agent.common.auth` (this namespace's neighbour) is how
       brainyard signs in to an LLM PROVIDER — API keys, clj-oauth, CLI
       delegates. Nothing to do with this.
     * The workspace's ACCESS section is who may use the console and a project.
       Its realm is `brainyard`; its commands, when they exist, are `access$*`.
     * THIS is the realm the product being built signs ITS users in to: its
       clients, its roles, its end users. A separate Keycloak realm, so a token
       minted for the application cannot verify against the console at all.

   These call the auth sidecar's `/v1/apps/**` family rather than Keycloak
   directly, for the reason rag-commands gives about rag-backend: the sidecar
   owns the confinement, the artifact compiler and the drift machinery, and the
   console calls the same routes. A second implementation here would drift from
   the one the operator is looking at.

   CREDENTIAL. Every call carries `BY_AUTH_DELEGATION` and nothing else. An
   owner process is handed `AUTH_API_URL` and that handle — deliberately not the
   sidecar's internal token, which can resolve any session. The handle is bound
   to one session and one project and dies with either, so this agent cannot act
   for anyone but the user who launched it, and cannot touch another project.
   The bound is enforced in the sidecar, not in the instruction above it: an
   injected 'make me an admin of another realm' meets the same refusal the user
   would.

   NO COMMAND HERE WRITES THE ARTIFACT. `.brainyard/auth/realm.edn` is an
   ordinary EDN file in the project tree, like `tools/`, `hooks/` and `fsm/`, so
   the agent edits it with the file tools every specialist already has — and
   `auth$diff` and `auth$apply` validate what it wrote, in the sidecar, before
   anything reaches Keycloak. A write command here would be a second validator
   in a third language for one small file.

   Every command returns `{:error \"...\"}` rather than throwing when the
   sidecar is unreachable, so the agent can say authentication is off in this
   workspace instead of failing the turn.

   Design: brainyard-playground-apps/docs/design/app-auth-section-plan.md §6."
  (:require [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.clj-http-native.interface :as http]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URLEncoder]
           [java.nio.charset StandardCharsets]))

;; =====================================================
;; Transport
;; =====================================================

(def ^:private default-base-url "http://127.0.0.1:8400")

(defn base-url
  "The auth sidecar this agent talks to. It is SHARED — one per desktop, not one
   per project — so unlike RAG's, this address is the same for every workspace."
  []
  (let [raw (str/trim (or (System/getenv "AUTH_API_URL") ""))]
    (str/replace (if (str/blank? raw) default-base-url raw) #"/+$" "")))

(defn app-realm
  "The realm of the application this project builds, from `BY_APP_REALM`.

   Blank is not an error to raise here: the sidecar answers with the artifact's
   own realm, and a command that refused before asking would be unusable in a
   workspace whose gates predate this section."
  []
  (str/trim (or (System/getenv "BY_APP_REALM") "")))

(defn- delegation
  "The handle authorising this agent, read FRESH on every call.

   Read from a file, not from the environment, and that is the whole point. A
   handle dies with the browser session it was minted from, and that session has
   a thirty-minute idle timeout while this agent runs for hours — so the handle
   an owner process was launched with goes stale, and nothing inside a running
   process can change its own environment. Every call used to fail from then on,
   permanently, and relaunching the session to fix it threw away the conversation.

   Reading the file per call means someone pressing `Re-authorise` in the
   workspace revives this agent before its next command. `BY_AUTH_DELEGATION` is
   still honoured for a workspace that predates the file — it simply cannot be
   revived."
  []
  (let [f (str/trim (or (System/getenv "BY_AUTH_DELEGATION_FILE") ""))
        from-file (when-not (str/blank? f)
                    (try
                      (str/trim (slurp f))
                      (catch Exception _ nil)))]
    (or (not-empty from-file)
        (str/trim (or (System/getenv "BY_AUTH_DELEGATION") "")))))

(defn- encode ^String [v]
  (URLEncoder/encode (str v) (.name StandardCharsets/UTF_8)))

(defn- query-string [params]
  (->> params
       (keep (fn [[k v]]
               (when (and (some? v) (not (and (string? v) (str/blank? v))))
                 (str (name k) "=" (encode v)))))
       (str/join "&")))

(defn- parse-body [body]
  (try
    (json/read-str (str body) :key-fn keyword)
    (catch Exception _ nil)))

(defn- no-delegation-error []
  {:error (str "This agent holds no delegation, so it cannot act for anyone. "
               "One is given to an owner process when the workspace launches it with "
               "authentication on; a session started before authentication was turned on "
               "carries none. Open the Auth section and press Re-authorise — that hands this "
               "session your authority without restarting it.")})

(defn- unreachable-error [ex]
  {:error (str "The auth sidecar is not reachable at " (base-url) " (" (.getMessage ^Exception ex) "). "
               "Authentication may be off in this workspace, or the sidecar may be stopped — "
               "check the desktop's Backend tab. Nothing was read or written.")})

(defn- request
  "One call to the sidecar, as the delegating user. Never throws."
  [method path {:keys [body timeout-ms] :or {timeout-ms 60000}}]
  (let [handle (delegation)]
    (if (str/blank? handle)
      (no-delegation-error)
      (let [url  (str (base-url) path)
            opts (cond-> {:as :string
                          :throw-exceptions false
                          :timeout-ms timeout-ms
                          :connect-timeout-ms 5000
                          :headers {"x-by-delegation" handle}}
                   body (assoc :body (json/write-str body) :content-type :json))]
        (try
          (let [{:keys [status body]} (case method
                                        :get    (http/get* url opts)
                                        :post   (http/post url opts)
                                        :put    (http/put url opts)
                                        :delete (http/delete url opts))
                parsed (parse-body body)
                detail (get-in parsed [:detail :detail] (:detail parsed))]
            (if (<= 200 status 299)
              (or parsed {})
              {:error (str "The auth sidecar returned " status ": "
                           (or (when (string? detail) detail)
                               (some-> body (subs 0 (min 300 (count (str body)))))
                               "no detail")
                           (when (= status 401)
                             (str " — this usually means the delegation expired, which happens when the "
                                  "browser session it came from idled out. Press Re-authorise in the "
                                  "workspace's Auth section and ask again; this session keeps its "
                                  "conversation.")))}))
          (catch Exception e
            (mulog/log ::auth-request-failed :url url :error (.getMessage e))
            (unreachable-error e)))))))

(defn- realm-path
  "`/v1/apps/<realm><suffix>`, refusing to guess the realm."
  [suffix]
  (let [r (app-realm)]
    (when-not (str/blank? r)
      (str "/v1/apps/" (encode r) suffix))))

(defn- with-realm [suffix f]
  (if-let [p (realm-path suffix)]
    (f p)
    {:error (str "BY_APP_REALM is not set on this session, so there is no application realm to act on. "
                 "It is set from .brainyard/auth/realm.edn when the workspace launches an Auth session.")}))

;; =====================================================
;; Reading
;; =====================================================

(defcommand auth$status
  "Whether this workspace has an application realm, whether it exists in Keycloak yet, and whether realms can be created from here."
  (fn [& _]
    (let [res (request :get "/v1/apps" {:timeout-ms 20000})]
      (if (:error res) res (assoc res :appRealm (app-realm)))))
  :input-schema  [:map]
  :output-schema [:map
                  [:appRealm     {:optional true} [:string {:desc "The realm of the application this project builds"}]]
                  [:consoleRealm {:optional true} [:string {:desc "The console's own realm — never managed here"}]]
                  [:realms       {:optional true} [:any    {:desc "Application realms this Keycloak holds"}]]
                  [:canCreate    {:optional true} [:boolean {:desc "Whether a realm may be created from this desktop"}]]
                  [:error        {:optional true} [:string]]])

(defcommand auth$realm
  "The application's realm as it IS in Keycloak — clients, roles, login settings — in the artifact's own vocabulary."
  (fn [& _]
    (with-realm "" #(request :get % {:timeout-ms 30000})))
  :input-schema  [:map]
  :output-schema [:map
                  [:realm {:optional true} [:string]]
                  [:live  {:optional true} [:any {:desc "Realm settings, clients and roles"}]]
                  [:error {:optional true} [:string]]])

(defcommand auth$diff
  "What differs between .brainyard/auth/realm.edn and the live realm. Always safe; changes nothing. Read the findings verbatim — each names the setting, what Keycloak has and what the artifact says."
  (fn [& {:keys [spec]}]
    (if (nil? spec)
      {:error "auth$diff needs the artifact as `spec`. Read it with auth$realm, or ask the workspace for it."}
      (with-realm "/diff" #(request :post % {:body {:spec spec} :timeout-ms 60000}))))
  :input-schema  [:map [:spec [:any {:desc "The realm spec from .brainyard/auth/realm.edn"}]]]
  :output-schema [:map
                  [:realm    {:optional true} [:string]]
                  [:findings {:optional true} [:any {:desc "One entry per disagreement; empty means they match"}]]
                  [:error    {:optional true} [:string]]])

(defcommand auth$export
  "A realm export for another environment's Keycloak, with every credential stripped. This is how an application's identity reaches staging."
  (fn [& _]
    (with-realm "/export" #(request :get % {:timeout-ms 60000})))
  :input-schema  [:map]
  :output-schema [:map
                  [:realm  {:optional true} [:string]]
                  [:export {:optional true} [:any {:desc "Keycloak realm representation, secrets removed"}]]
                  [:error  {:optional true} [:string]]])

;; =====================================================
;; Changing the realm
;; =====================================================

(defcommand auth$apply
  "Make Keycloak match the artifact. With :confirm? false (the default) it returns the PLAN and changes nothing; with true it applies. Additive and modifying only — it never deletes a client, a role or a user."
  (fn [& {:keys [spec confirm?]}]
    (cond
      (nil? spec) {:error "auth$apply needs the artifact as `spec`."}
      :else (with-realm "/apply" #(request :post % {:body {:spec spec :confirm (boolean confirm?)}
                                                    :timeout-ms 120000}))))
  :input-schema  [:map
                  [:spec [:any {:desc "The realm spec from .brainyard/auth/realm.edn"}]]
                  [:confirm? {:optional true} [:boolean {:desc "false = show the plan; true = apply it"}]]]
  :output-schema [:map
                  [:realm     {:optional true} [:string]]
                  [:confirmed {:optional true} [:boolean]]
                  [:findings  {:optional true} [:any {:desc "What differed"}]]
                  [:applied   {:optional true} [:any {:desc "What was done, in words"}]]
                  [:error     {:optional true} [:string]]])

(defcommand auth$realm-create
  "Create the application's realm. The one privileged operation here: it needs the desktop's own Keycloak admin credential and refuses outright against an identity server this desktop does not run. Everything afterwards is confined to the new realm."
  (fn [& {:keys [spec]}]
    (if (nil? spec)
      {:error "auth$realm-create needs the artifact as `spec` — the realm is created from it."}
      (with-realm "" #(request :post % {:body {:spec spec} :timeout-ms 120000}))))
  :input-schema  [:map [:spec [:any {:desc "The realm spec from .brainyard/auth/realm.edn"}]]]
  :output-schema [:map
                  [:realm   {:optional true} [:string]]
                  [:created {:optional true} [:boolean]]
                  [:error   {:optional true} [:string]]])

(defcommand auth$client-secret-rotate
  "Rotate a confidential client's secret in Keycloak. Returns nothing: the new secret is Keycloak's, and anything holding the old one stops working until it is given the new one."
  (fn [& {:keys [client-id]}]
    (if (str/blank? (str client-id))
      {:error "auth$client-secret-rotate needs `client-id`."}
      (with-realm (str "/clients/" (encode client-id) "/secret")
        #(request :post % {:timeout-ms 30000}))))
  :input-schema  [:map [:client-id [:string {:desc "The client whose secret to rotate"}]]]
  :output-schema [:map
                  [:clientId {:optional true} [:string]]
                  [:rotated  {:optional true} [:boolean]]
                  [:error    {:optional true} [:string]]])

;; =====================================================
;; The application's people
;; =====================================================

(defcommand auth$users
  "List or search the application's end users. Always paged — a realm's user list is not something to put in a conversation."
  (fn [& {:keys [search limit offset]}]
    (with-realm (str "/users?" (query-string {:search search
                                              :limit (min 100 (max 1 (or limit 20)))
                                              :offset (max 0 (or offset 0))}))
      #(request :get % {:timeout-ms 30000})))
  :input-schema  [:map
                  [:search {:optional true} [:string {:desc "Username or email fragment"}]]
                  [:limit  {:optional true} [:int {:desc "1–100, default 20"}]]
                  [:offset {:optional true} [:int]]]
  :output-schema [:map
                  [:users {:optional true} [:any {:desc "id, username, email, enabled, requiredActions"}]]
                  [:error {:optional true} [:string]]])

(defcommand auth$user-add
  "Create an end user of the application. NO PASSWORD IS SET OR ACCEPTED — the account is created requiring the user to choose one at first sign-in."
  (fn [& {:keys [username email roles]}]
    (if (str/blank? (str username))
      {:error "auth$user-add needs `username`."}
      (with-realm "/users" #(request :post % {:body (cond-> {:username username}
                                                      email (assoc :email email)
                                                      roles (assoc :roles (vec roles)))
                                              :timeout-ms 30000}))))
  :input-schema  [:map
                  [:username [:string]]
                  [:email {:optional true} [:string]]
                  [:roles {:optional true} [:any {:desc "Realm roles to grant, all of which must be declared"}]]]
  :output-schema [:map
                  [:id       {:optional true} [:string]]
                  [:username {:optional true} [:string]]
                  [:error    {:optional true} [:string]]])

(defcommand auth$user-set
  "Enable or disable an end user, or replace the roles they hold."
  (fn [& {:keys [id enabled roles]}]
    (if (str/blank? (str id))
      {:error "auth$user-set needs the user's `id` — auth$users lists them."}
      (with-realm (str "/users/" (encode id))
        #(request :put % {:body (cond-> {}
                                    (some? enabled) (assoc :enabled (boolean enabled))
                                    roles (assoc :roles (vec roles)))
                            :timeout-ms 30000}))))
  :input-schema  [:map
                  [:id [:string]]
                  [:enabled {:optional true} [:boolean]]
                  [:roles {:optional true} [:any {:desc "The complete set of realm roles this user should hold"}]]]
  :output-schema [:map
                  [:id       {:optional true} [:string]]
                  [:username {:optional true} [:string]]
                  [:enabled  {:optional true} [:boolean]]
                  [:roles    {:optional true} [:any]]
                  [:error    {:optional true} [:string]]])

(def ^:private actions #{"UPDATE_PASSWORD" "VERIFY_EMAIL" "UPDATE_PROFILE" "CONFIGURE_TOTP"})

(defcommand auth$user-action
  "Require something of a user at their next sign-in. THIS IS HOW A PASSWORD IS RESET: UPDATE_PASSWORD makes them choose a new one. No command here sets, reads or returns a password — this conversation is written to disk."
  (fn [& {:keys [id actions*]}]
    (let [as (vec (or actions* []))]
      (cond
        (str/blank? (str id)) {:error "auth$user-action needs the user's `id`."}
        (empty? as) {:error (str "Name at least one action: " (str/join ", " (sort actions)))}
        (seq (remove actions as)) {:error (str "Not a required action: "
                                               (str/join ", " (remove actions as))
                                               ". Allowed: " (str/join ", " (sort actions)))}
        :else (with-realm (str "/users/" (encode id) "/actions")
                #(request :post % {:body {:actions as} :timeout-ms 30000})))))
  :input-schema  [:map
                  [:id [:string]]
                  [:actions* [:any {:desc "UPDATE_PASSWORD, VERIFY_EMAIL, UPDATE_PROFILE or CONFIGURE_TOTP"}]]]
  :output-schema [:map
                  [:id              {:optional true} [:string]]
                  [:username        {:optional true} [:string]]
                  [:requiredActions {:optional true} [:any]]
                  [:error           {:optional true} [:string]]])

(defcommand auth$user-remove
  "Delete an end user of the application. Their sessions end with them."
  (fn [& {:keys [id]}]
    (if (str/blank? (str id))
      {:error "auth$user-remove needs the user's `id`."}
      (with-realm (str "/users/" (encode id)) #(request :delete % {:timeout-ms 30000}))))
  :input-schema  [:map [:id [:string]]]
  :output-schema [:map
                  [:id      {:optional true} [:string]]
                  [:deleted {:optional true} [:boolean]]
                  [:error   {:optional true} [:string]]])

(def all-auth-commands
  "The auth$* family, for a defagent's :agent-tools roster."
  [#'auth$status #'auth$realm #'auth$diff #'auth$export
   #'auth$apply #'auth$realm-create #'auth$client-secret-rotate
   #'auth$users #'auth$user-add #'auth$user-set #'auth$user-action #'auth$user-remove])
