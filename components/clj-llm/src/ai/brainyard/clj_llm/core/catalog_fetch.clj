;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.catalog-fetch
  "Ask each provider which models it actually serves.

   Produces overlay entries for `catalog/set-overlay!` — id sets only, never
   curation (see `catalog` for why that split exists).

   Everything here is best-effort and total: a provider with no credentials,
   an unreachable host, a changed response shape or a timeout yields nil, and
   nil means 'this provider was not refreshed', which the merge treats as
   'leave the baked entries alone'. A refresh must never be able to narrow the
   catalog by failing.

   Only providers the user can actually reach are contacted: a provider whose
   `:api-key-env` is unset is skipped without a request, so a refresh on a
   machine configured for one provider does not fire six pointless calls."
  (:require [ai.brainyard.clj-http-native.interface :as http]
            [ai.brainyard.clj-llm.core.catalog :as catalog]
            [ai.brainyard.clj-llm.core.catalog-store :as store]
            [ai.brainyard.clj-llm.core.providers :as providers]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.time Instant]))

(def ^:private request-timeout-ms
  "Short by design: a refresh is background work behind a TTL, so a slow
   provider should be abandoned rather than delay anything the user is
   waiting on."
  8000)

(defn- now-iso [] (str (Instant/now)))

(defn- safe-require-resolve
  "Resolve a symbol in a namespace loaded on demand. Mirrors the lazy loading
   in `core.bedrock` so the AWS client is not pulled in unless a Bedrock
   refresh actually runs."
  [sym]
  (try (requiring-resolve sym) (catch Throwable _ nil)))

;; ============================================================================
;; Non-chat filtering
;; ============================================================================

(def ^:private non-chat-id-re
  "Ids a provider inventory returns that a chat client cannot drive.

   Every list endpoint here is an inventory of everything the account or
   server can reach — embeddings, speech, transcription, reranking, image and
   video models included — and nothing in the response distinguishes them, so
   this matches on naming convention.

   It applies to EVERY provider, not just OpenAI: a live refresh showed a
   local Ollama serving `nomic-embed-text:latest`, and Bedrock's
   `ListFoundationModels` returns Titan and Cohere embeddings, Stability image
   models and TwelveLabs video models alongside the chat ones.

   Deliberately NOT filtered: OpenAI's `-pro` tier and the `-codex` line,
   which are chat-shaped names served only by `/v1/responses` or already
   deprecated. Telling those apart needs a probe, not a regex, so curation
   handles them — a discovered model never gets a `:curated-rank`, so they
   cannot reach the picker regardless."
  #"(?i)embed|rerank|whisper|moderation|transcribe|(^|[-.])(tts|dall-e|sora)|image|realtime|audio|speech|video|upscale|canvas|marengo|pegasus")

(defn- chat-ish?
  "True when `id` is plausibly a chat model."
  [id]
  (not (re-find non-chat-id-re (str id))))

;; ============================================================================
;; Fetchers
;; ============================================================================

(defn- get-json
  "GET `url` and parse JSON. Returns nil on any failure — status, transport,
   or malformed body."
  [url headers]
  (try
    (let [resp (http/get url {:headers headers
                              :socket-timeout request-timeout-ms
                              :connection-timeout request-timeout-ms
                              :throw-exceptions false})]
      (when (= 200 (:status resp))
        (json/read-str (:body resp) :key-fn keyword)))
    (catch Throwable _ nil)))

(defn fetch-openai-compatible
  "Model ids from a `/models` endpoint (OpenAI's shape: `{:data [{:id …}]}`).

   Covers every provider in the registry with `:message-format :openai` and a
   base-url, which is most of them — including Ollama, whose OpenAI-compatible
   surface lists exactly the models installed on that server."
  [provider {:keys [base-url api-key]}]
  (when-not (str/blank? (str base-url))
    (let [url  (str (str/replace base-url #"/+$" "") "/models")
          hdrs (cond-> {"Accept" "application/json"}
                 (not (str/blank? (str api-key)))
                 (assoc "Authorization" (str "Bearer " api-key)))]
      (when-let [body (get-json url hdrs)]
        (let [ids (->> (:data body)
                       (keep :id)
                       (map str)
                       (filter (complement str/blank?)))]
          (when (seq ids)
            {:models     (set ids)
             :fetched-at (now-iso)
             :source     url}))))))

(defn fetch-anthropic
  "Model ids from the Anthropic Models API. Same `{:data [{:id …}]}` shape as
   OpenAI's, but authenticated with `x-api-key` and a required version header."
  [{:keys [base-url api-key]}]
  (when-not (or (str/blank? (str base-url)) (str/blank? (str api-key)))
    (let [url (str (str/replace base-url #"/+$" "") "/models?limit=1000")]
      (when-let [body (get-json url {"Accept"            "application/json"
                                     "x-api-key"         api-key
                                     "anthropic-version" "2023-06-01"})]
        (let [ids (->> (:data body) (keep :id) (map str))]
          (when (seq ids)
            {:models     (set ids)
             :fetched-at (now-iso)
             :source     url}))))))

(defn fetch-bedrock
  "Model ids served by Bedrock in `region`: foundation models UNION
   cross-region inference profiles.

   Both are needed and neither subsumes the other. The catalog's Anthropic
   entries are mostly profile ids (`global.anthropic.…`, `us.anthropic.…`)
   which `ListFoundationModels` does not return, while open-weights models are
   usually reachable only by their bare foundation id.

   Region matters: a model served in us-east-1 may be absent in the caller's
   region, which is exactly what the catalog's `:region` pins record. This
   fetches the caller's region only, so a refresh from ap-northeast-2 must not
   be allowed to retire a us-east-1-only entry — see `refresh-provider` for
   how that is prevented."
  [{:keys [region]}]
  (let [client-fn (safe-require-resolve 'cognitect.aws.client.api/client)
        invoke-fn (safe-require-resolve 'cognitect.aws.client.api/invoke)]
    (when (and client-fn invoke-fn)
      (try
        (let [c        (client-fn {:api :bedrock :region region})
              summaries (:modelSummaries (invoke-fn c {:op :ListFoundationModels}))
              profiles  (:inferenceProfileSummaries
                         (invoke-fn c {:op :ListInferenceProfiles
                                       :request {:maxResults 200}}))
              ids (concat (keep :modelId summaries)
                          (keep :inferenceProfileId profiles))]
          (when (seq ids)
            {:models     (set (map str ids))
             :fetched-at (now-iso)
             :source     (str "bedrock:" region)
             :region     region
             ;; Partial by construction: one region's view.
             :partial?   true}))
        (catch Throwable _ nil)))))

;; ============================================================================
;; Orchestration
;; ============================================================================

(defn- api-key-for [provider]
  (when-let [env (get-in providers/providers [provider :api-key-env])]
    (System/getenv env)))

(defn- openai-compatible? [provider cfg]
  (and (= :openai (:message-format cfg))
       (some? (:base-url cfg))
       (catalog/overlayable? provider)))

(defn refreshable-providers
  "Providers this machine can actually refresh right now: an enumerable
   provider with a reachable endpoint and, where required, a key in the
   environment.

   Ollama and other local servers qualify with no credentials at all, which is
   the point — a local server's installed model list is the one thing a baked
   catalog can never get right."
  []
  (vec
   (sort-by
    name
    (keep (fn [[provider cfg]]
            (cond
              (not (catalog/overlayable? provider)) nil
              (= :bedrock provider)                 provider
              (= :anthropic provider)               (when (api-key-for provider) provider)
              (openai-compatible? provider cfg)
              ;; A provider needing a key is skipped without a request; one
              ;; that needs none (Ollama, apple-fm-style local servers) is
              ;; always a candidate — reaching it is the probe.
              (if (:api-key-env cfg)
                (when (api-key-for provider) provider)
                provider)
              :else nil))
          providers/providers))))

(defn refresh-provider
  "Fetch one provider's live id set. Returns an overlay entry or nil.

   `opts` may carry `:region` for Bedrock; it defaults to the same detection
   `create-lm` uses, so a refresh targets the region the user actually calls."
  ([provider] (refresh-provider provider {}))
  ([provider opts]
   (let [cfg (get providers/providers provider)
         entry
         (cond
           (= :bedrock provider)
           (fetch-bedrock {:region (or (:region opts)
                                       (providers/detect-aws-region))})

           (= :anthropic provider)
           (fetch-anthropic {:base-url (:base-url cfg) :api-key (api-key-for provider)})

           (openai-compatible? provider cfg)
           (fetch-openai-compatible provider
                                    {:base-url (:base-url cfg)
                                     :api-key  (api-key-for provider)})

           :else nil)]
     ;; Filter centrally, after fetching, so every provider gets the same
     ;; treatment and a fetcher only has to know its wire shape. If filtering
     ;; empties the set the whole entry is dropped: `usable-entry?` would
     ;; reject it downstream anyway, and an empty entry must never be mistaken
     ;; for "this provider serves nothing".
     (let [entry (when entry
                   (let [ids (into #{}
                                   (comp (filter chat-ish?)
                                         ;; Ids probed and deliberately rejected
                                         ;; (see providers/excluded-model-patterns).
                                         ;; Filtered here so they are never
                                         ;; discovered AND never re-proposed by
                                         ;; `bb catalog:refresh` on every run.
                                         (remove #(providers/excluded-model? provider %)))
                                   (:models entry))]
                     (when (seq ids) (assoc entry :models ids))))]
       (if entry
         (do (mulog/log ::catalog-refreshed :provider provider
                        :count (count (:models entry)) :source (:source entry))
             entry)
         (do (mulog/log ::catalog-refresh-skipped :provider provider)
             nil))))))

(defn refresh-all
  "Refresh every provider this machine can reach. Returns a map of
   provider -> entry for those that succeeded; providers that failed or were
   skipped are simply absent, which the merge reads as 'leave alone'."
  ([] (refresh-all {}))
  ([opts]
   (into {}
         (keep (fn [provider]
                 (when-let [e (refresh-provider provider opts)]
                   [provider e])))
         (refreshable-providers))))

;; ============================================================================
;; TTL-driven refresh
;; ============================================================================

(defn refresh-stale!
  "Refresh only the providers whose cached entry is missing or past its TTL,
   persisting and installing each as it lands.

   Installing per provider rather than in one batch at the end means a slow
   provider cannot hold up the ones that already answered, and a crash midway
   still leaves the successful ones cached.

   Returns the providers actually refreshed. `:force?` ignores the TTL, which
   is what `by models refresh` passes."
  ([] (refresh-stale! {}))
  ([{:keys [ttl-hours force? region] :as opts}]
   (into []
         (keep (fn [provider]
                 (when (or force? (store/stale? provider ttl-hours))
                   (when-let [entry (refresh-provider provider (select-keys opts [:region]))]
                     (store/save-entry! provider entry)
                     (catalog/put-overlay! provider entry)
                     provider))))
         (refreshable-providers))))

(defonce ^:private !refresh-in-flight (atom false))

(defn refresh-stale-async!
  "Run `refresh-stale!` on a daemon thread and return immediately.

   This is the path behind the TTL: nothing the user is waiting on may block
   on a provider's API. Re-entrancy is guarded, so several sessions or a burst
   of catalog reads cannot stack up concurrent refreshes against the same
   endpoints. Failures are swallowed by design — a refresh that cannot run
   leaves the catalog exactly as it was."
  ([] (refresh-stale-async! {}))
  ([opts]
   (when (compare-and-set! !refresh-in-flight false true)
     (doto (Thread.
            (fn []
              (try (refresh-stale! opts)
                   (catch Throwable e
                     (mulog/log ::catalog-refresh-failed :error (.getMessage e)))
                   (finally (reset! !refresh-in-flight false)))))
       (.setDaemon true)
       (.setName "catalog-refresh")
       (.start))
     true)))
