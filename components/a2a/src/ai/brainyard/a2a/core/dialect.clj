;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a.core.dialect
  "The two A2A wire dialects, and translation between them.

   A2A v1.0 did not extend v0.3 — it **replaced the JSON-RPC binding**. v0.3
   is a hand-shaped JSON encoding; v1.0 is **ProtoJSON over the protobuf
   model** (`specification/a2a.proto`). They differ on method names, enum
   spellings, `Part` structure, the result envelope and the streaming frame
   shape, so a v0.3 client cannot call a v1.0 server at all.

   ## One canonical form; dialects only at the edges

   Everything inside brainyard works on ONE representation — the v0.3 shape,
   which is what the rest of this component was built against. This namespace
   is the only place that knows a second dialect exists.

       wire (v0.3)  --decode-->  canonical  --encode-->  wire (v0.3)
       wire (v1.0)  --decode-->  canonical  --encode-->  wire (v1.0)

   The consequence is deliberate and load-bearing: `client/result->outcome`,
   `events/translate`, `events/frame-kind`, the server handlers and the SSE
   writer need NO dialect awareness. Threading `if v1?` through those call
   sites is how a two-dialect codebase rots — every new call site becomes one
   more place to forget.

   Because the canonical form IS v0.3, every v0.3 function here is close to
   identity, and all real translation is confined to the v1.0 arms.

   ## The differences (from `specification/a2a.proto`, cross-checked against
   a running `a2a-sdk` 1.1.0)

   | | v0.3 | v1.0 |
   |---|---|---|
   | method   | `message/send`            | `SendMessage` |
   | version  | header optional           | `A2A-Version: 1.0` REQUIRED |
   | role     | `\"user\"`                  | `\"ROLE_USER\"` |
   | Part     | `{:kind \"text\" …}`        | one-of `text\\|raw\\|url\\|data`, no discriminator |
   | state    | `\"completed\"`             | `\"TASK_STATE_COMPLETED\"` |
   | send     | `<Task>`                  | `{\"task\": …}` \\| `{\"message\": …}` |
   | stream   | `{:kind \"status-update\"}` | `{\"statusUpdate\": …}` |
   | `final`  | present on status events  | ABSENT — terminal state ends the stream |

   `Message` and `Task` also carry no `kind` field in v1.0, and `Artifact`
   keeps `artifactId` in both."
  (:require [clojure.string :as str]
            [ai.brainyard.a2a.core.methods :as methods]))

;; =============================================================================
;; The dialects
;; =============================================================================

(def dialects #{:v0.3 :v1.0})

(def ^:const DEFAULT_DIALECT
  "What an unmarked peer or request is assumed to speak.

   v0.3, because the spec says an absent `A2A-Version` is read as 0.3 — and
   because assuming the OLDER dialect fails loudly (`MethodNotFound`) rather
   than silently mis-encoding against a newer one."
  :v0.3)

(defn dialect?
  "True when `d` is a dialect this build understands."
  [d]
  (contains? dialects d))

;; =============================================================================
;; Detection
;; =============================================================================

(defn of-version
  "Dialect for an `A2A-Version` value. Anything at major 1 or above is
   v1.0; absent, blank or unparseable is `DEFAULT_DIALECT`.

   Forward-leaning on purpose: a future 1.1 peer is far better served by the
   v1.0 codec than by v0.3, which would not even resolve its method names."
  [version]
  (let [v (some-> version str str/trim)]
    (if (str/blank? (str v))
      DEFAULT_DIALECT
      (let [major (try (Integer/parseInt (first (str/split v #"\.")))
                       (catch Exception _ nil))]
        (if (and major (>= major 1)) :v1.0 DEFAULT_DIALECT)))))

(defn of-card
  "Dialect a peer speaks, inferred from its Agent Card.

   v1.0 cards declare `:supportedInterfaces` with a `:protocolVersion` per
   entry; v0.3 cards declare a top-level `:protocolVersion`. A card carrying
   BOTH (as brainyard's own now does, to serve both generations) resolves to
   v1.0 — prefer the current dialect when a peer offers a choice."
  [card]
  (let [iface-version (some->> (:supportedInterfaces card)
                               (keep :protocolVersion)
                               first)]
    (cond
      iface-version              (of-version iface-version)
      (seq (:supportedInterfaces card)) :v1.0  ;; v1.0-only field, version omitted
      :else                      (of-version (:protocolVersion card)))))

(defn version-of
  "The `A2A-Version` header value for a dialect."
  [dialect]
  (case dialect :v1.0 "1.0" "0.3"))

;; =============================================================================
;; Method names
;; =============================================================================

(def v1-methods
  "v1.0 JSON-RPC method names — the gRPC service method names from
   `A2AService` in the proto. Verified against `a2a-sdk` 1.1.0's own
   `METHOD_TO_MODEL` dispatch table, not from documentation.

   Note two shape changes beyond the renaming: the push-notification setter
   is `Create…` (v0.3 said `…/set`), and there is no `…/resubscribe` — it is
   `SubscribeToTask`."
  {:message-send        "SendMessage"
   :message-stream      "SendStreamingMessage"
   :tasks-get           "GetTask"
   :tasks-list          "ListTasks"
   :tasks-cancel        "CancelTask"
   :tasks-resubscribe   "SubscribeToTask"
   :push-config-set     "CreateTaskPushNotificationConfig"
   :push-config-get     "GetTaskPushNotificationConfig"
   :push-config-list    "ListTaskPushNotificationConfigs"
   :push-config-delete  "DeleteTaskPushNotificationConfig"
   :agent-extended-card "GetExtendedAgentCard"})

(defn method-table
  "Method keyword -> wire name, for a dialect."
  [dialect]
  (case dialect :v1.0 v1-methods methods/client-methods))

(defn method-name
  "Wire method name for a method keyword in `dialect`. Throws on an unknown
   keyword — a typo would otherwise surface as `MethodNotFound` from every
   server in the ecosystem."
  [dialect k]
  (or (get (method-table dialect) k)
      (throw (ex-info (str "Unknown A2A method " (pr-str k) " for dialect " dialect)
                      {:type :a2a/unknown-method :method k :dialect dialect}))))

(defn method->kw
  "Wire method name -> method keyword, for server dispatch. nil when the
   name is not part of `dialect`."
  [dialect wire]
  (let [w (str wire)]
    (some (fn [[k v]] (when (= w v) k)) (method-table dialect))))

(defn any-method->kw
  "Resolve a wire method name under EITHER dialect, returning
   `[dialect method-kw]` or nil.

   Lets a server answer a client that sent the right method name but the
   wrong (or no) version header, instead of a bare `MethodNotFound` that
   says nothing about why."
  [wire]
  (or (some->> (method->kw :v0.3 wire) (vector :v0.3))
      (some->> (method->kw :v1.0 wire) (vector :v1.0))))

;; =============================================================================
;; Enums
;; =============================================================================

(def ^:private v1-state-prefix "TASK_STATE_")

(defn decode-state
  "Wire task state -> canonical (v0.3) state string.

   v1.0 sends protobuf enum constants. `TASK_STATE_UNSPECIFIED` maps to
   `\"unknown\"`, which is v0.3's name for the same idea — v1.0 dropped
   `unknown` and added `UNSPECIFIED`."
  [dialect state]
  (let [s (some-> state str str/trim)]
    (cond
      (str/blank? (str s)) nil
      (not= :v1.0 dialect) (str/lower-case s)
      :else
      (let [bare (-> s
                     (str/replace (re-pattern (str "^" v1-state-prefix)) "")
                     str/lower-case
                     (str/replace "_" "-"))]
        (if (= "unspecified" bare) "unknown" bare)))))

(defn encode-state
  "Canonical state string -> wire, for `dialect`."
  [dialect state]
  (let [s (some-> state str str/trim str/lower-case)]
    (cond
      (str/blank? (str s)) nil
      (not= :v1.0 dialect) s
      :else (str v1-state-prefix
                 (str/upper-case (str/replace (if (= "unknown" s) "unspecified" s)
                                              "-" "_"))))))

(defn decode-role
  "Wire role -> canonical (`\"user\"` / `\"agent\"`)."
  [dialect role]
  (let [r (some-> role str str/trim)]
    (when-not (str/blank? (str r))
      (if (= :v1.0 dialect)
        (-> r (str/replace #"^ROLE_" "") str/lower-case)
        (str/lower-case r)))))

(defn encode-role
  "Canonical role -> wire, for `dialect`."
  [dialect role]
  (let [r (some-> role str str/trim str/lower-case)]
    (when-not (str/blank? (str r))
      (if (= :v1.0 dialect) (str "ROLE_" (str/upper-case r)) r))))

;; =============================================================================
;; Part
;;
;; The widest gap. v0.3 tags each part with `:kind`; v1.0 uses a protobuf
;; one-of, so the variant is implied by WHICH field is set and there is no
;; discriminator at all.
;; =============================================================================

(defn decode-part
  "Wire Part -> canonical Part. nil for a part carrying no recognizable
   content, so a caller can drop it rather than propagate a malformed entry."
  [dialect p]
  (when (map? p)
    (if (not= :v1.0 dialect)
      p
      (let [{:keys [text raw url data metadata filename mediaType]} p
            file-meta (cond-> {}
                        filename  (assoc :name filename)
                        mediaType (assoc :mimeType mediaType))]
        (cond
          (some? text) (cond-> {:kind "text" :text text}
                         metadata (assoc :metadata metadata))
          (some? raw)  (cond-> {:kind "file" :file (assoc file-meta :bytes raw)}
                         metadata (assoc :metadata metadata))
          (some? url)  (cond-> {:kind "file" :file (assoc file-meta :uri url)}
                         metadata (assoc :metadata metadata))
          (some? data) (cond-> {:kind "data" :data data}
                         metadata (assoc :metadata metadata))
          :else nil)))))

(defn encode-part
  "Canonical Part -> wire Part for `dialect`. nil when the part carries
   nothing encodable."
  [dialect p]
  (when (map? p)
    (if (not= :v1.0 dialect)
      p
      (let [{:keys [kind text file data metadata]} p
            {:keys [bytes uri name mimeType]} file
            base (cond-> {}
                   metadata (assoc :metadata metadata)
                   name     (assoc :filename name)
                   mimeType (assoc :mediaType mimeType))]
        (case (str kind)
          "text" (assoc base :text (str text))
          "data" (assoc base :data data)
          "file" (cond
                   (some? bytes) (assoc base :raw bytes)
                   (some? uri)   (assoc base :url uri)
                   :else         nil)
          nil)))))

(defn- decode-parts [dialect parts]
  (into [] (keep #(decode-part dialect %)) parts))

(defn- encode-parts [dialect parts]
  (into [] (keep #(encode-part dialect %)) parts))

;; =============================================================================
;; Message / Task / Artifact
;; =============================================================================

(defn decode-message
  "Wire Message -> canonical. v1.0 carries no `:kind`, so one is added to
   keep the canonical form uniform."
  [dialect m]
  (when (map? m)
    (if (not= :v1.0 dialect)
      m
      (cond-> (assoc m :kind "message")
        (:role m)  (assoc :role (decode-role dialect (:role m)))
        (:parts m) (assoc :parts (decode-parts dialect (:parts m)))))))

(defn encode-message
  "Canonical Message -> wire for `dialect`."
  [dialect m]
  (when (map? m)
    (if (not= :v1.0 dialect)
      m
      (cond-> (dissoc m :kind)
        (:role m)  (assoc :role (encode-role dialect (:role m)))
        (:parts m) (assoc :parts (encode-parts dialect (:parts m)))))))

(defn decode-artifact
  "Wire Artifact -> canonical. `artifactId` is unchanged between dialects;
   only the parts differ."
  [dialect a]
  (when (map? a)
    (cond-> a
      (and (= :v1.0 dialect) (:parts a)) (assoc :parts (decode-parts dialect (:parts a))))))

(defn encode-artifact
  [dialect a]
  (when (map? a)
    (cond-> a
      (and (= :v1.0 dialect) (:parts a)) (assoc :parts (encode-parts dialect (:parts a))))))

(defn decode-status
  "Wire TaskStatus -> canonical."
  [dialect s]
  (when (map? s)
    (cond-> s
      (:state s)   (assoc :state (decode-state dialect (:state s)))
      (:message s) (assoc :message (decode-message dialect (:message s))))))

(defn encode-status
  [dialect s]
  (when (map? s)
    (cond-> s
      (:state s)   (assoc :state (encode-state dialect (:state s)))
      (:message s) (assoc :message (encode-message dialect (:message s))))))

(defn decode-task
  "Wire Task -> canonical. v1.0 carries no `:kind`, so one is added."
  [dialect t]
  (when (map? t)
    (if (not= :v1.0 dialect)
      t
      (cond-> (assoc t :kind "task")
        (:status t)    (assoc :status (decode-status dialect (:status t)))
        (:artifacts t) (assoc :artifacts (mapv #(decode-artifact dialect %) (:artifacts t)))
        (:history t)   (assoc :history (mapv #(decode-message dialect %) (:history t)))))))

(defn encode-task
  [dialect t]
  (when (map? t)
    (if (not= :v1.0 dialect)
      t
      (cond-> (dissoc t :kind)
        (:status t)    (assoc :status (encode-status dialect (:status t)))
        (:artifacts t) (assoc :artifacts (mapv #(encode-artifact dialect %) (:artifacts t)))
        (:history t)   (assoc :history (mapv #(encode-message dialect %) (:history t)))))))

;; =============================================================================
;; Results and stream frames — the envelopes
;; =============================================================================

(defn decode-send-result
  "`message/send` result -> canonical.

   v1.0 returns a protobuf one-of, so the payload is WRAPPED:
   `{\"task\": …}` or `{\"message\": …}`. v0.3 returns the object bare."
  [dialect r]
  (when (map? r)
    (if (not= :v1.0 dialect)
      r
      (cond
        (:task r)    (decode-task dialect (:task r))
        (:message r) (decode-message dialect (:message r))
        :else        r))))

(defn encode-send-result
  "Canonical Task/Message -> `message/send` result for `dialect`.
   v1.0 re-wraps into the one-of."
  [dialect r]
  (when (map? r)
    (if (not= :v1.0 dialect)
      r
      (if (or (= "message" (:kind r)) (and (:parts r) (not (:status r))))
        {:message (encode-message dialect r)}
        {:task (encode-task dialect r)}))))

(defn decode-result
  "Decode a JSON-RPC `result` to canonical form for a given METHOD.

   The result shape varies by method, not only by dialect: `SendMessage`
   returns the `Task | Message` one-of, while `GetTask` and `CancelTask`
   return a bare `Task` and `ListTasks` a page of them. Decoding everything
   as a send-result would leave a `tasks/get` Task carrying raw
   `TASK_STATE_*` enums — which the task poller compares against canonical
   states, so it would never observe a terminal state and would poll
   forever."
  [dialect method result]
  (case method
    :message-send
    (decode-send-result dialect result)

    (:tasks-get :tasks-cancel)
    (decode-task dialect result)

    :tasks-list
    (cond-> result
      (:tasks result) (assoc :tasks (mapv #(decode-task dialect %) (:tasks result))))

    ;; Push-notification configs and the extended card carry no dialect-
    ;; specific shapes.
    result))

(defn decode-stream-frame
  "One streaming frame -> canonical.

   v1.0 wraps in the `StreamResponse` one-of
   (`task` / `message` / `statusUpdate` / `artifactUpdate`) instead of
   tagging with `:kind`, and its status events carry NO `final` field — the
   terminal state is what ends the stream. `final` is synthesised here so
   the canonical form stays uniform and `events/translate` needs no changes."
  [dialect f]
  (when (map? f)
    (if (not= :v1.0 dialect)
      f
      (cond
        (:statusUpdate f)
        (let [u (:statusUpdate f)
              status (decode-status dialect (:status u))
              state  (:state status)]
          (cond-> (assoc u :kind "status-update" :status status)
            (some? state) (assoc :final (contains? methods/terminal-states state))))

        (:artifactUpdate f)
        (let [u (:artifactUpdate f)]
          (cond-> (assoc u :kind "artifact-update")
            (:artifact u) (assoc :artifact (decode-artifact dialect (:artifact u)))))

        (:task f)    (decode-task dialect (:task f))
        (:message f) (decode-message dialect (:message f))
        :else        f))))

(defn encode-stream-frame
  "Canonical frame -> wire frame for `dialect`. v1.0 wraps into the one-of
   and drops `:final`, which its schema does not have."
  [dialect f]
  (when (map? f)
    (if (not= :v1.0 dialect)
      f
      (case (str (:kind f))
        "status-update"
        {:statusUpdate (-> (dissoc f :kind :final)
                           (assoc :status (encode-status dialect (:status f))))}

        "artifact-update"
        {:artifactUpdate (-> (dissoc f :kind)
                             (assoc :artifact (encode-artifact dialect (:artifact f))))}

        "task"    {:task (encode-task dialect f)}
        "message" {:message (encode-message dialect f)}
        ;; Shape fallback for a frame that carries no :kind.
        (cond
          (:status f) {:task (encode-task dialect f)}
          (:parts f)  {:message (encode-message dialect f)}
          :else       f)))))

;; =============================================================================
;; Params
;; =============================================================================

(defn encode-config
  "Canonical `SendMessageConfiguration` -> wire for `dialect`.

   v1.0 did not merely rename these fields:

     v0.3 `blocking`                 -> v1.0 `returnImmediately`, **INVERTED**
     v0.3 `pushNotificationConfig`   -> v1.0 `taskPushNotificationConfig`
     `acceptedOutputModes` / `historyLength` are unchanged.

   The inversion is the dangerous one. It is not a parse error a server can
   catch — send `blocking true` to a v1.0 peer and, if the field were
   tolerated, you would get exactly the opposite behaviour: fire-and-forget
   where you asked to wait. Here it fails loudly instead (`-32602`, unknown
   field), which is how it was found."
  [dialect config]
  (when (map? config)
    (if (not= :v1.0 dialect)
      config
      (cond-> (dissoc config :blocking :pushNotificationConfig)
        (contains? config :blocking)
        (assoc :returnImmediately (not (boolean (:blocking config))))

        (:pushNotificationConfig config)
        (assoc :taskPushNotificationConfig (:pushNotificationConfig config))))))

(defn decode-config
  "Wire `SendMessageConfiguration` -> canonical (server side)."
  [dialect config]
  (when (map? config)
    (if (not= :v1.0 dialect)
      config
      (cond-> (dissoc config :returnImmediately :taskPushNotificationConfig)
        (contains? config :returnImmediately)
        (assoc :blocking (not (boolean (:returnImmediately config))))

        (:taskPushNotificationConfig config)
        (assoc :pushNotificationConfig (:taskPushNotificationConfig config))))))

(defn encode-send-params
  "Canonical `message/send` params -> wire for `dialect`."
  [dialect params]
  (when (map? params)
    (cond-> params
      (:message params)       (assoc :message (encode-message dialect (:message params)))
      (:configuration params) (assoc :configuration
                                     (encode-config dialect (:configuration params))))))

(defn decode-send-params
  "Wire `message/send` params -> canonical (server side)."
  [dialect params]
  (when (map? params)
    (cond-> params
      (:message params)       (assoc :message (decode-message dialect (:message params)))
      (:configuration params) (assoc :configuration
                                     (decode-config dialect (:configuration params))))))
