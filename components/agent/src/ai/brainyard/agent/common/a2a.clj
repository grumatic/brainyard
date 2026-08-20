;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.a2a
  "The `a2a$*` command family — connection management for remote A2A peers.

   Four commands, deliberately: `a2a$connect`, `a2a$list`, `a2a$card`,
   `a2a$disconnect`. Connection management only.

   ## There is no `a2a$ask`, on purpose

   Asking a remote agent goes through `agent-registry$ask`, exactly like
   asking a local one. A remote peer IS a registry instance (see
   `agent.core.remote-agent`), so it inherits the reach policy, the depth
   guard, LRU eviction and the close cascade. Adding a second ask path here
   would fork that policy — and the forked copy would be the one that
   forgets a rule.

   Connecting also registers each of the peer's skills into
   `tool/!tool-defs` as `:a2a$<peer>$<skill>` with `:type :agent`, the same
   shape `user-agents/register-agent!` and `mcp/integration` use. So a
   remote skill is callable from a code block like any other agent.

   ## Statically required, never `requiring-resolve`d

   See the note in `components/agent/deps.edn`: AOT only follows a static
   `:require`, so a namespace reached only at runtime has no `.class` in
   the native image. That is how the ACP integration shipped broken in
   every released binary."
  (:require [clojure.string :as str]
            [ai.brainyard.a2a.interface :as a2a]
            [ai.brainyard.a2a-client.interface :as a2a-client]
            [ai.brainyard.agent.common.artifacts :as artifacts]
            [ai.brainyard.agent.common.user-tools :as user-tools]
            [ai.brainyard.agent.core.agent :as agent-core]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.feature :as feature]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.remote-agent :as remote]
            [ai.brainyard.agent.core.tool :as tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [ai.brainyard.util.interface :as util]))

;; =============================================================================
;; Id rendering
;; =============================================================================

(defn- id-str
  "Render a registry id for the LLM.

   `(str :a2a$b$planner)` yields \":a2a$b$planner\" — leading colon and all —
   and these strings are handed to the model AS THE TOOL NAME TO CALL. A
   stray colon makes it call something that does not exist. `util/kw->str`
   drops it while preserving any namespace."
  [id]
  (if (keyword? id) (util/kw->str id) (str id)))

;; =============================================================================
;; Gate
;; =============================================================================

(defn- gate-off
  "An `{:error …}` when A2A is unavailable for the current agent, else nil.

   Checks BOTH `:enable-a2a` and the subagent kill-switch, because a remote
   peer is a subagent: `feature/off-reason` on `:agents/a2a` already
   encodes `:requires #{:agents/subagents}`."
  []
  (let [agent proto/*current-agent*]
    (when-let [r (and agent (feature/off-reason agent :agents/a2a))]
      {:error (str "A2A is disabled (" r "). Enable it with :enable-a2a"
                   " (env BY_ENABLE_A2A=1) — ask config-agent to set it.")})))

;; =============================================================================
;; Skill -> tool-def registration
;; =============================================================================

(defn skill-tool-id
  "Registry id for one remote skill: `:a2a$<peer>$<skill>`.

   Mirrors MCP's `:mcp$<server>$<tool>`, so the `$`-segmented convention
   reads the same across every external-capability family."
  [peer-name skill-id]
  (keyword (str "a2a$" peer-name "$" skill-id)))

(defn- skill-tool-def
  "Build the `!tool-defs` entry for one remote skill.

   `:type :agent` so it dispatches through the normal sub-agent path
   (depth guard, `*call-chain*`, the subagents display block) rather than
   looking like a plain tool call. The `:fn` is filled in by the caller —
   it needs the dispatch machinery, which lives above this namespace."
  [{:keys [peer-name skill card invoke]}]
  (let [sid  (str (:id skill))
        tid  (skill-tool-id peer-name sid)
        desc (str "[A2A remote] " (or (not-empty (str (:description skill)))
                                      (str (:name skill)))
                  " — skill '" sid "' on peer '" peer-name "' ("
                  (a2a/jsonrpc-endpoint card) ")")]
    {:id   tid
     :type :agent
     :fn   invoke
     :meta {:id           tid
            :type         :agent
            :description  desc
            :category     :a2a
            :remote?      true
            :peer-name    peer-name
            :skill-id     sid
            :remote-id    (a2a/peer-agent-id card sid)
            :input-schema  [:map
                            [:question [:string {:desc "Request for this remote agent"}]]
                            [:agent-context {:optional true}
                             [:string {:desc "Extra context"}]]]
            :output-schema [:map
                            [:answer [:string {:desc "The remote agent's answer"}]]
                            [:error [:string {:desc "Error: peer unreachable, call cycle, depth limit, or a remote failure"}]]]
            :tool-use-control {}}}))

(defn- make-invoke
  "The `:fn` for a remote-skill tool-def.

   Dispatches a `RemoteAgent` and asks it. The cycle/depth guards are
   checked HERE, before any network call — refusing after spending a round
   trip (and the peer's LLM turn) defeats the purpose."
  [peer-name skill card]
  (let [sid       (str (:id skill))
        remote-id (a2a/peer-agent-id card sid)]
    (fn [& {:as args}]
      (let [question (or (:question args) (:input args) "")
            parent   proto/*current-agent*]
        (cond
          (str/blank? (str question))
          {:error "question is required"}

          (nil? parent)
          {:error "no current agent — an A2A skill must be dispatched from a live agent"}

          (remote/cycle-target? remote-id)
          {:error (str "A2A call cycle refused: '" remote-id
                       "' is already in the call chain ("
                       (remote/describe-outbound-chain remote-id) ").")}

          (remote/depth-exceeded? parent)
          {:error (format "Agent call depth limit reached (%d); cannot call '%s'."
                          proto/*call-depth* remote-id)}

          :else
          (let [agent-id (agent-core/generate-instance-id (skill-tool-id peer-name sid))
                ra (remote/create {:agent-id        agent-id
                                   :peer-name       peer-name
                                   :skill-id        sid
                                   :parent-agent    parent
                                   :!session        (:!session parent)
                                   :description     (:description skill)
                                   :remote-agent-id remote-id})]
            (agent-core/register-agent ra)
            ;; Only the CHAIN is extended here, never the depth.
            ;; `tool/call-tool` already bound an incremented `*call-depth*`
            ;; for this dispatch (core/tool.clj) — incrementing again counted
            ;; one hop twice and, together with `stamp-chain`'s own +1, made
            ;; every first remote call arrive at the depth limit.
            ;; The chain conj is still ours: call-tool conj'd the TOOL id,
            ;; while `cycle-target?` and the outbound stamp need the
            ;; URL-scoped remote token.
            (binding [proto/*call-chain* (conj proto/*call-chain* remote-id)]
              (let [result (agent-core/ask ra question)]
                (cond-> {:answer (:answer result)
                         :id     (id-str agent-id)}
                  (:error result) (assoc :error (:error result))
                  true (assoc :ask-hint
                              (let [s (id-str agent-id)]
                                (format (str "This remote instance stays alive as %s. "
                                             "Follow up with (agent-registry$ask {:id \"%s\" :question \"…\"}); "
                                             "end it with (agent-registry$close {:id \"%s\"}).")
                                        s s s))))))))))))

(defn register-skills!
  "Register every skill on `card` as an `:a2a$<peer>$<skill>` tool-def.
   Returns the vector of registered ids."
  [peer-name card]
  (let [ids (mapv (fn [skill]
                    (let [td (skill-tool-def
                              {:peer-name peer-name :skill skill :card card
                               :invoke (make-invoke peer-name skill card)})]
                      (swap! tool/!tool-defs assoc (:id td) td)
                      ;; Same-turn callability from a code block: bind the
                      ;; new symbol into the CURRENT sandbox now instead of
                      ;; waiting for next turn's auto-tool-bindings rebuild.
                      ;; Reused from user-tools. Statically required here —
                      ;; user-tools does not reach back into this namespace,
                      ;; so there is no cycle to dodge, and a static require
                      ;; is what puts the class in the native image.
                      (user-tools/bind-into-live-sandbox! td)
                      (:id td)))
                  (a2a/card-skills card))]
    (mulog/info ::skills-registered :peer peer-name :count (count ids))
    ids))

(defn unregister-skills!
  "Drop every `:a2a$<peer>$…` tool-def. Returns the ids removed."
  [peer-name]
  (let [prefix (str "a2a$" peer-name "$")
        ids    (filterv #(str/starts-with? (name %) prefix) (keys @tool/!tool-defs))]
    (swap! tool/!tool-defs #(apply dissoc % ids))
    (mulog/info ::skills-unregistered :peer peer-name :count (count ids))
    ids))

;; =============================================================================
;; Commands
;; =============================================================================

(defcommand a2a$connect
  "Connect a remote agent over the Agent2Agent (A2A) protocol: fetch its Agent
   Card from <url>/.well-known/agent-card.json, negotiate the protocol version,
   and register each skill it advertises as a callable agent
   `a2a$<peer>$<skill>`. Ask a connected skill with agent-registry$ask (there is
   no separate a2a ask), or call the registered tool directly."
  (fn [& {:as args}]
    (or (gate-off)
        (let [nm    (some-> (:name args) str str/trim str/lower-case)
              url   (:url args)
              agent proto/*current-agent*
              cap   (or (config/get-config agent :a2a-max-peers-per-session) 8)]
          (cond
            (str/blank? (str url)) {:error "url is required"}
            (str/blank? (str nm))  {:error "name is required"}

            (and (>= (count (a2a-client/list-peers)) cap)
                 (nil? (a2a-client/get-peer nm)))
            {:error (format "A2A peer cap reached (%d). Disconnect one with a2a$disconnect first."
                            cap)}

            :else
            (let [pinned (let [d (config/get-config agent :a2a-dialect)]
                           ;; :auto (the default) means infer from the card;
                           ;; only an explicit dialect is passed through.
                           (when (contains? #{:v0.3 :v1.0} d) d))
                  {:keys [peer card error] :as res}
                  (a2a-client/connect!
                   {:name nm :url url :auth (:token args)
                    :timeout-ms (config/get-config agent :a2a-timeout-ms)
                    :dialect pinned
                    :refresh? (boolean (:refresh args))})]
              (if error
                res
                (let [ids (register-skills! nm card)]
                  {:connected true
                   :name      nm
                   :endpoint  (:endpoint peer)
                   :agent-name (:agent-name peer)
                   :streaming (:streaming peer)
                   :dialect (str (:dialect peer))
                   :skills    (mapv id-str ids)
                   :note      (str "Ask a skill with agent-registry$ask, or call "
                                   (id-str (first ids)) " directly.")})))))))
  :input-schema  [:map
                  [:name [:string {:desc "Local name for this peer; must match ^[a-z][a-z0-9-]*$ (becomes part of the tool id a2a$<peer>$<skill>)"}]]
                  [:url [:string {:desc "Peer base URL, e.g. https://peer.example (the /.well-known/agent-card.json path is appended for you)"}]]
                  [:token {:optional true} [:string {:desc "Bearer token, when the peer requires authentication"}]]
                  [:refresh {:optional true} [:boolean {:desc "Bypass the Agent Card cache"}]]]
  :output-schema [:map
                  [:connected [:boolean {:desc "True on success"}]]
                  [:name [:string {:desc "Local peer name"}]]
                  [:endpoint [:string {:desc "Resolved JSON-RPC endpoint"}]]
                  [:agent-name [:string {:desc "The peer's own name from its card"}]]
                  [:streaming [:boolean {:desc "Whether the peer advertises streaming"}]]
                  [:skills [:string {:desc "Vector of registered tool ids (a2a$<peer>$<skill>)"}]]
                  [:error [:string {:desc "Error: A2A disabled, blank args, bad name, peer cap, unreachable, malformed card, version mismatch, or no JSON-RPC binding"}]]])

(defcommand a2a$list
  "List connected A2A peers with their endpoints, skills and streaming support.
   Credentials are never included."
  (fn [& _args]
    (or (gate-off)
        {:peers (a2a-client/describe-peers)
         :total (count (a2a-client/list-peers))}))
  :input-schema  [:map]
  :output-schema [:map
                  [:peers [:string {:desc "Vector of {:name :url :endpoint :auth :agent-name :skills :streaming}. :auth names the scheme only — never the secret."}]]
                  [:total [:int {:desc "Number of connected peers"}]]
                  [:error [:string {:desc "Error when A2A is disabled"}]]])

(defcommand a2a$card
  "Show a connected peer's Agent Card — skills with descriptions, capabilities,
   protocol version and declared security schemes. Use to discover what a peer
   can actually do before asking it."
  (fn [& {:as args}]
    (or (gate-off)
        (let [nm (some-> (:name args) str str/trim)]
          (if (str/blank? nm)
            {:error "name is required"}
            (if-let [peer (a2a-client/get-peer nm)]
              (let [card (:card peer)]
                {:name        nm
                 :agent-name  (:name card)
                 :description (:description card)
                 :endpoint    (a2a/jsonrpc-endpoint card)
                 :protocol-version (:protocolVersion card)
                 :streaming   (a2a/card-supports? card :streaming)
                 :push-notifications (a2a/card-supports? card :pushNotifications)
                 :skills      (mapv (fn [s] {:id (str (:id s))
                                             :name (:name s)
                                             :description (:description s)
                                             :tool-id (id-str (skill-tool-id nm (:id s)))})
                                    (a2a/card-skills card))})
              {:error (str "no such A2A peer: " nm
                           " (connected: "
                           (str/join ", " (map :name (a2a-client/describe-peers)))
                           ")")})))))
  :input-schema  [:map
                  [:name [:string {:desc "Local peer name, from a2a$list"}]]]
  :output-schema [:map
                  [:agent-name [:string {:desc "The peer's own name"}]]
                  [:endpoint [:string {:desc "JSON-RPC endpoint"}]]
                  [:protocol-version [:string {:desc "A2A version the peer declares"}]]
                  [:streaming [:boolean {:desc "Streaming capability"}]]
                  [:skills [:string {:desc "Vector of {:id :name :description :tool-id}"}]]
                  [:error [:string {:desc "Error: A2A disabled, missing name, or unknown peer"}]]])

(defcommand a2a$disconnect
  "Disconnect an A2A peer and unregister its skills. Does NOT cancel work the
   peer is already doing — cancel a live remote task first if you need it
   stopped. Live RemoteAgent instances in the registry are left alone; close
   them with agent-registry$close."
  (fn [& {:as args}]
    (or (gate-off)
        (let [nm (some-> (:name args) str str/trim)]
          (if (str/blank? nm)
            {:error "name is required"}
            (let [{:keys [error] :as res} (a2a-client/disconnect! nm)]
              (if error
                res
                (let [ids (unregister-skills! nm)]
                  {:disconnected true
                   :name nm
                   :unregistered (mapv id-str ids)})))))))
  :input-schema  [:map
                  [:name [:string {:desc "Local peer name to disconnect"}]]]
  :output-schema [:map
                  [:disconnected [:boolean {:desc "True on success"}]]
                  [:unregistered [:string {:desc "Vector of tool ids removed"}]]
                  [:error [:string {:desc "Error: A2A disabled, missing name, or unknown peer"}]]])

;; =============================================================================
;; Deterministic peer CRUD — for the ask channel, not the LLM
;; =============================================================================
;;
;; The four commands above are what an AGENT calls: they are tool-defs, so
;; reaching them means a turn, a model, and a bill. An external driver (the
;; workspace app's A2A section) wants the same four operations with none of
;; that — it already knows the peer's name and url, and asking an LLM to
;; retype them into a tool call is both slower and less reliable than calling
;; the function.
;;
;; So this is the machine-facing face of the same commands: same validation,
;; same registry, same gate, no turn. `bases/agent-tui` exposes it as
;; `{:op :a2a :action …}` on the session's ask socket.
;;
;; Two things a caller has to know, and both are reported rather than hidden:
;;
;;   - PEERS ARE PROCESS-WIDE, not per-session. `a2a-client`'s registry is a
;;     `defonce` atom, so in a shared host every co-hosted session sees the
;;     same peers. `:host-wide? true` in the reply says so.
;;   - CONNECTING IS NOT FREE OF SIDE EFFECTS. It fetches the card and
;;     registers `a2a$<peer>$<skill>` tool-defs, which is exactly what makes
;;     the peer callable — but it also means `:add` reaches the network and
;;     can fail slowly.

(defn- peer-summary
  "One peer, redaction-safe (`describe-peer` never includes the secret)."
  [nm]
  (when-let [p (a2a-client/get-peer nm)]
    (a2a-client/describe-peer p)))

(defn peers-op
  "Deterministic `:list | :add | :update | :delete` over the A2A peer registry.

   Runs the same code path `a2a$connect` / `a2a$list` / `a2a$disconnect` run —
   including the `:enable-a2a` gate, the name regex and the per-session peer
   cap — with `*current-agent*` bound to `agent` so config and the gate resolve
   against the live session. Returns a plain map; never throws.

   `:add` refuses a name that already exists and `:update` refuses one that
   does not. That asymmetry is deliberate: this is an API, and a caller that
   thinks it is creating a peer while actually overwriting a colleague's
   (or vice versa) has no way to notice. `:update` is disconnect-then-connect,
   so a changed url cannot leave the old endpoint's skills registered."
  [agent {:keys [action name url token timeout-ms refresh]}]
  (binding [proto/*current-agent* agent]
    (let [nm (some-> name str str/trim str/lower-case not-empty)
          act (cond-> action (string? action) (-> (str/replace #"^:" "") keyword))
          exists? #(some? (a2a-client/get-peer nm))
          connect! (fn []
                     (let [r (a2a$connect (cond-> {:name nm :url url}
                                            token      (assoc :token token)
                                            timeout-ms (assoc :timeout-ms timeout-ms)
                                            refresh    (assoc :refresh true)))]
                       (if (:error r)
                         r
                         (assoc r :peer (peer-summary nm)))))]
      (case act
        :list
        (or (gate-off)
            {:peers (a2a-client/describe-peers)
             :total (count (a2a-client/list-peers))
             :host-wide? true})

        :add
        (or (gate-off)
            (cond
              (str/blank? (str nm))  {:error "name is required"}
              (str/blank? (str url)) {:error "url is required"}
              (exists?)              {:error (str "peer '" nm "' is already connected in this process"
                                                  " — use :action :update to change its url or token,"
                                                  " or :delete first")}
              :else (connect!)))

        :update
        (or (gate-off)
            (cond
              (str/blank? (str nm))  {:error "name is required"}
              (str/blank? (str url)) {:error "url is required"}
              (not (exists?))        {:error (str "no such A2A peer: " nm " — use :action :add to connect it")}
              :else
              ;; Disconnect first so a changed url cannot leave the previous
              ;; endpoint's skills registered under the same peer name.
              (let [_ (a2a$disconnect {:name nm})
                    r (connect!)]
                (if (:error r) r (assoc r :updated true)))))

        :delete
        (or (gate-off)
            (cond
              (str/blank? (str nm)) {:error "name is required"}
              (not (exists?))       {:error (str "no such A2A peer: " nm)}
              :else                 (a2a$disconnect {:name nm})))

        {:error (str "unknown a2a action: " (pr-str action)
                     " (expected :list, :add, :update or :delete)")}))))

;; =============================================================================
;; Session seeding
;; =============================================================================

(defn seed-configured-peers!
  "Connect every peer in `:a2a-peers` at session start, and register their
   skills. Best-effort — one unreachable peer must not stop the others, or
   a single dead endpoint in config would make `by` unusable.

   No-op when A2A is disabled."
  [agent]
  (when (and agent (nil? (feature/off-reason agent :agents/a2a)))
    (let [peers (config/get-config agent :a2a-peers)]
      (when (seq peers)
        (let [{:keys [connected failed] :as res} (a2a-client/seed-peers! peers)]
          (doseq [nm connected]
            (when-let [peer (a2a-client/get-peer nm)]
              (register-skills! nm (:card peer))))
          (when (seq failed)
            (mulog/warn ::seed-partial :connected connected :failed failed))
          res)))))

;; =============================================================================
;; Stream-event bridge
;;
;; `core/remote_agent.clj` fires `:a2a/artifact` on the hooks bus rather than
;; persisting artifacts itself: artifact storage lives in `common/artifacts.clj`,
;; and a `core.* -> common.*` require would invert the layering. This is the
;; subscriber that closes the loop, registered under a `:source` so an app can
;; drop it wholesale via `hooks/unregister-source!`.
;; =============================================================================

(def ^:const artifact-hook-source ::a2a-artifacts)

(defn- on-remote-artifact
  "Persist an artifact a remote peer streamed to us as a live artifact.

   Streaming artifacts arrive in CHUNKS (`:append true`), and only the frame
   carrying `:last-chunk` is the finished object. Persisting every chunk would
   fill `## Live Artifacts` with fragments of one document, so partial frames
   are skipped and only the final one is stored."
  [{:keys [agent artifact-id name text append last-chunk]}]
  (when (and agent (or last-chunk (not append)) (not (str/blank? (str text))))
    (try
      (artifacts/add-artifact! agent
                               {:name (or (not-empty (str name))
                                          (str "a2a-" artifact-id))
                                :content text
                                :description "Artifact from a remote A2A peer"})
      (catch Throwable t
        (mulog/warn ::artifact-persist-failed
                    :artifact-id artifact-id :error (ex-message t))))))

(defn install-stream-hooks!
  "Subscribe the artifact bridge. Idempotent — `register-hook!` replaces an
   entry with the same [event-key handler-id]."
  []
  (hooks/register-hook! :a2a/artifact ::persist-artifact on-remote-artifact
                        :source artifact-hook-source))

;; Side-effecting: requiring this namespace wires the bridge, the same way
;; `router-agent-hooks` and `loop-guard-hook` self-install.
(install-stream-hooks!)

(def a2a-commands
  "The `a2a$*` family, for registration in the command palette."
  [#'a2a$connect
   #'a2a$list
   #'a2a$card
   #'a2a$disconnect])
