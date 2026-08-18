;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.rag-commands
  "RAG commands — retrieval over the project's Graph RAG corpus.

   These call the `rag-backend` HTTP service (FastAPI over Neo4j) rather than
   reaching for Bolt directly. That is a deliberate choice, not convenience:
   the backend owns embedding, Weighted Reciprocal Rank Fusion and graph
   expansion, and a second Clojure implementation of any of those would drift
   from the one the operator console shows. Agent and UI call the same
   endpoint, so they cannot disagree about what the corpus says.

   Base URL comes from `RAG_API_URL`, which the workspace section puts on this
   agent's owner process (SECTION_CONTEXTS.rag gates) so that one project's
   agent talks to that project's backend. `RAG_PROJECT` is the scope the
   backend stamps on everything it writes; it is set on the BACKEND, not read
   here — this namespace never has to know it.

   Every command returns `{:error \"...\"}` rather than throwing when the
   backend is unreachable, so the agent can tell the user the backend is not
   running instead of failing the turn.

   Design: brainyard-playground-apps/docs/design/rag-section-plan.md §5.

   REPLACES an earlier in-memory `!vector-store` implementation ported from
   cloudcast. That version was dead code — no namespace required it, it was in
   no agent's roster, and its `embed-fn` was never configured, so every command
   in it returned \"RAG not configured\". Rewritten rather than left beside a
   working surface, because two RAG command families with one working is worse
   than one."
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

(def ^:private default-base-url "http://127.0.0.1:8000")

(defn base-url
  "The RAG backend this agent talks to. Trailing slashes trimmed so callers can
   concatenate a path without thinking about it."
  []
  (let [raw (or (System/getenv "RAG_API_URL") "")
        raw (str/trim raw)]
    (str/replace (if (str/blank? raw) default-base-url raw) #"/+$" "")))

(defn- encode ^String [v]
  (URLEncoder/encode (str v) (.name StandardCharsets/UTF_8)))

(defn- query-string
  "Build a query string from a map, dropping nil/blank values."
  [params]
  (->> params
       (keep (fn [[k v]]
               (when (and (some? v) (not (and (string? v) (str/blank? v))))
                 (str (name k) "=" (encode v)))))
       (str/join "&")))

(defn- parse-body
  "Parse a JSON body, keywordizing keys. Returns nil when it is not JSON."
  [body]
  (try
    (json/read-str (str body) :key-fn keyword)
    (catch Exception _ nil)))

(defn- unreachable-error
  "The message the agent should relay when the service is not there.

   Deliberately actionable: the operator's next step differs by how the backend
   is managed, and 'connection refused' does not tell them which."
  [ex]
  {:error (str "RAG backend is not reachable at " (base-url) " (" (.getMessage ^Exception ex) "). "
               "Start it with `npm run dev -w @brainyard/rag-backend`, or — under "
               "Brainyard Desktop — from the project menu. Nothing was read or written.")})

(defn- request
  "One HTTP call to the backend, normalised to data.

   Never throws: a dead backend, a timeout and a 500 all become {:error ...},
   because an agent that dies on an unreachable sidecar cannot explain itself."
  [method path {:keys [body timeout-ms] :or {timeout-ms 60000}}]
  (let [url (str (base-url) path)
        opts (cond-> {:as :string
                      :throw-exceptions false
                      :timeout-ms timeout-ms
                      :connect-timeout-ms 5000}
               body (assoc :body (json/write-str body) :content-type :json))]
    (try
      (let [{:keys [status body]} (case method
                                    :get    (http/get* url opts)
                                    :post   (http/post url opts)
                                    :put    (http/put url opts)
                                    :delete (http/delete url opts))
            parsed (parse-body body)]
        (if (<= 200 status 299)
          (or parsed {})
          {:error (str "RAG backend returned " status ": "
                       (or (:detail parsed) (some-> body (subs 0 (min 300 (count (str body)))))
                           "no detail"))}))
      (catch Exception e
        (mulog/log ::rag-request-failed :url url :error (.getMessage e))
        (unreachable-error e)))))

;; =====================================================
;; Health & inventory
;; =====================================================

(defcommand rag$health
  "Check whether the RAG backend and its Neo4j database are reachable, and which embedder is configured."
  (fn [& _]
    (request :get "/health" {:timeout-ms 15000}))
  :input-schema  [:map]
  :output-schema [:map
                  [:ok       {:optional true} [:boolean {:desc "Backend is up and correctly configured"}]]
                  [:project  {:optional true} [:string  {:desc "Project scope this backend stamps on writes"}]]
                  [:embedder {:optional true} [:any     {:desc "Provider, model, width, fingerprint"}]]
                  [:neo4j    {:optional true} [:any     {:desc "Database reachability and schema state"}]]
                  [:error    {:optional true} [:string  {:desc "Why the backend could not be reached"}]]])

(defcommand rag$stats
  "Corpus composition: how many documents, chunks and entities this project has, and its share of the shared store."
  (fn [& _]
    (request :get "/stats" {:timeout-ms 30000}))
  :input-schema  [:map]
  :output-schema [:map
                  [:scoped   {:optional true} [:any {:desc "This project's counts"}]]
                  [:store    {:optional true} [:any {:desc "Every project's counts — the ratio matters for recall"}]]
                  [:projects {:optional true} [:any {:desc "Project scopes present in the store"}]]
                  [:index    {:optional true} [:any {:desc "Index health + embedder fingerprint state"}]]
                  [:wrrf     {:optional true} [:any {:desc "Active fusion weights"}]]
                  [:error    {:optional true} [:string]]])

(defcommand rag$documents
  "List the chunks indexed for this project, with their source document and chunking mode."
  (fn [& _]
    (let [res (request :get "/list" {:timeout-ms 30000})]
      (if (:error res)
        res
        ;; The backend keeps a Chroma-shaped envelope for its own back-compat;
        ;; flatten it here so the model never sees three parallel arrays.
        (let [{:keys [ids documents metadatas]} res]
          {:chunks (mapv (fn [id text meta]
                           (cond-> {:id id :text text}
                             (:source meta)   (assoc :source (:source meta))
                             (:chunk meta)    (assoc :index (:chunk meta))
                             (:mode meta)     (assoc :mode (:mode meta))
                             (:category meta) (assoc :category (:category meta))))
                         ids documents (or metadatas (repeat {})))
           :count (count ids)}))))
  :input-schema  [:map]
  :output-schema [:map
                  [:chunks {:optional true} [:any {:desc "Indexed chunks"}]]
                  [:count  {:optional true} [:int]]
                  [:error  {:optional true} [:string]]])

;; =====================================================
;; Retrieval
;; =====================================================

(def ^:private search-modes #{"vector" "fulltext" "graph" "hybrid"})

(defcommand rag$search
  "Search this project's corpus. Modes: hybrid (default, fuses all three signals), vector (dense/semantic), fulltext (exact tokens), graph (entity-seeded expansion). Read per_signal to see WHICH signal found each hit."
  (fn [& {:keys [q mode top-k source]}]
    (let [mode (str/lower-case (str (or mode "hybrid")))]
      (cond
        (str/blank? (str q))
        {:error "q is required — pass the text to search for"}

        (not (search-modes mode))
        {:error (str "invalid mode " (pr-str mode) "; allowed: " (str/join ", " (sort search-modes)))}

        :else
        (let [qs (query-string {:q q :mode mode :top_k (or top-k 5) :source source})
              res (request :get (str "/search?" qs) {:timeout-ms 60000})]
          (if (map? res)
            res
            ;; /search returns a bare JSON list; give it a key so the model
            ;; gets a map back from every command in this family.
            {:results res :count (count res) :mode mode})))))
  :input-schema  [:map
                  [:q      [:string {:desc "Query text"}]]
                  [:mode   {:optional true} [:string {:desc "hybrid | vector | fulltext | graph (default hybrid)"}]]
                  [:top-k  {:optional true} [:int    {:desc "How many results to return (default 5)"}]]
                  [:source {:optional true} [:string {:desc "Restrict to one source filename"}]]]
  :output-schema [:map
                  [:results {:optional true} [:any {:desc "Best-first hits with per_signal evidence"}]]
                  [:count   {:optional true} [:int]]
                  [:mode    {:optional true} [:string]]
                  [:error   {:optional true} [:string]]])

(defcommand rag$graph
  "Fetch the graph neighbourhood of a document (its chunks and their entities) or of an entity (the chunks mentioning it and its related entities)."
  (fn [& {:keys [kind id]}]
    (let [kind (str/lower-case (str (or kind "document")))]
      (cond
        (str/blank? (str id))
        {:error "id is required — a document filename or an entity name"}

        (not (#{"document" "entity"} kind))
        {:error (str "invalid kind " (pr-str kind) "; allowed: document, entity")}

        :else
        (request :get (str "/graph/" kind "/" (encode id)) {:timeout-ms 30000}))))
  :input-schema  [:map
                  [:kind {:optional true} [:string {:desc "document | entity (default document)"}]]
                  [:id   [:string {:desc "Document filename, or entity name"}]]]
  :output-schema [:map
                  [:nodes {:optional true} [:any {:desc "Document/Chunk/Entity nodes"}]]
                  [:edges {:optional true} [:any {:desc "HAS_CHUNK / NEXT / MENTIONS / RELATED_TO"}]]
                  [:error {:optional true} [:string]]])

;; =====================================================
;; Ingest & maintenance
;; =====================================================

(defcommand rag$ingest
  "Index text into this project's corpus, chunked properly. Pass :text for inline content or :path to read a file. Choose :mode deliberately — `section` splits on numbered headings (specs, manuals), `char` uses a sliding window sized for the prose."
  (fn [& {:keys [text path doc-id category mode chunk-size overlap]}]
    (let [mode (str/lower-case (str (or mode "char")))]
      (cond
        (and (str/blank? (str text)) (str/blank? (str path)))
        {:error "pass :text (inline content) or :path (a file to read)"}

        (not (#{"char" "section"} mode))
        {:error (str "invalid mode " (pr-str mode) "; allowed: char, section")}

        :else
        ;; A :path is read HERE and posted as JSON rather than proxied as a
        ;; multipart upload — brainyard's HTTP client has no multipart support.
        ;; /ingest runs the SAME pipeline /upload does, so an agent-ingested
        ;; document is indistinguishable from a browser-uploaded one.
        (let [f (when-not (str/blank? (str path)) (java.io.File. ^String path))]
          (if (and f (not (.isFile f)))
            {:error (str "no such file: " path)}
            (let [content  (if f (slurp f) text)
                  filename (or doc-id (when f (.getName f)) "inline.md")]
              (request :post "/ingest"
                       {:body (cond-> {:filename filename
                                       :text content
                                       :chunk_mode mode}
                                category   (assoc :category category)
                                chunk-size (assoc :chunk_size chunk-size)
                                overlap    (assoc :overlap overlap))
                        :timeout-ms 600000})))))))
  :input-schema  [:map
                  [:text       {:optional true} [:string {:desc "Inline content to index"}]]
                  [:path       {:optional true} [:string {:desc "File to read and index"}]]
                  [:doc-id     {:optional true} [:string {:desc "Document id (defaults to the filename)"}]]
                  [:category   {:optional true} [:string {:desc "Optional category tag"}]]
                  [:mode       {:optional true} [:string {:desc "char (default) | section — how to split the text"}]]
                  [:chunk-size {:optional true} [:int    {:desc "Window size in characters, char mode (default 800)"}]]
                  [:overlap    {:optional true} [:int    {:desc "Overlap in characters, char mode (default 100)"}]]]
  :output-schema [:map
                  [:filename {:optional true} [:string]]
                  [:chunks   {:optional true} [:int {:desc "How many chunks were written"}]]
                  [:ids      {:optional true} [:any]]
                  [:error    {:optional true} [:string]]])

(defcommand rag$delete
  "Remove a chunk by id, or a whole document (with all of its chunks) by document id."
  (fn [& {:keys [id]}]
    (if (str/blank? (str id))
      {:error "id is required"}
      (request :delete (str "/delete/" (encode id)) {:timeout-ms 30000})))
  :input-schema  [:map [:id [:string {:desc "Chunk id or document id"}]]]
  :output-schema [:map
                  [:chunks_deleted    {:optional true} [:int]]
                  [:documents_deleted {:optional true} [:int]]
                  [:error             {:optional true} [:string]]])

(defcommand rag$extract-kg
  "(Re-)extract entities and RELATED_TO relationships from documents into the graph. Costs an LLM call per document when one is configured; otherwise a deterministic rule-based pass runs."
  (fn [& {:keys [ids]}]
    (let [payload (if (seq ids) {:document_ids (vec ids)} {:all true})]
      ;; No ceiling short of the backend's own: extraction walks every document
      ;; and an LLM extractor makes that genuinely slow.
      (request :post "/admin/extract-kg" {:body payload :timeout-ms 900000})))
  :input-schema  [:map
                  [:ids {:optional true} [:any {:desc "Document ids; omit to extract from every document"}]]]
  :output-schema [:map
                  [:count        {:optional true} [:int]]
                  [:persisted    {:optional true} [:any {:desc "Entities, mentions and relationships written"}]]
                  [:llm_fallback {:optional true} [:boolean {:desc "True when the rule-based extractor ran"}]]
                  [:error        {:optional true} [:string]]])

(def all-rag-commands
  "The rag$* family, for a defagent's :agent-tools roster."
  [#'rag$health #'rag$stats #'rag$documents #'rag$search #'rag$graph
   #'rag$ingest #'rag$delete #'rag$extract-kg])
