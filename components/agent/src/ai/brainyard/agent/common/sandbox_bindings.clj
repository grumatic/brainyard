;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.sandbox-bindings
  "Sandbox bindings builders and context briefing shared by code-emitting agents.

   Every public function here returns either a map of {symbol fn} suitable
   for sandbox :bindings, or a string (build-context-briefing) / EDN map
   (build-agent-state-snapshot).

   Tool implementations (deftool) are in ai.brainyard.agent.common.tools."
  (:require [ai.brainyard.agent.common.plan :as plan]
            [ai.brainyard.agent.common.commands]
            [ai.brainyard.agent.common.tools]
            [ai.brainyard.agent.common.user-tools]   ;; bare — registers tool-agent$create/list/read/delete
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.session :as session]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.task.manager :as task-manager]
            [ai.brainyard.agent.task.persist :as task-persist]
            [ai.brainyard.agent.task.protocol :as task-proto]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [clojure.string :as str]))

(defn get-dirs
  "Lazily resolve dirs from agent session config, falling back to init-dirs!.
   Intended for use inside closures so dirs is resolved at call time, not
   at binding-creation time (avoids stale nil when session config is set after
   bindings are constructed)."
  [agent]
  (or (when agent
        (some-> (:!session agent) deref (session/get-session-config :dirs)))
      (config/init-dirs!)))

;; ============================================================================
;; Auto-binding from !tool-defs
;;
;; Each registered tool (deftool / defcommand / defskill / defagent) is exposed
;; as a sandbox-callable function whose signature is derived from the tool's
;; :input-schema (Malli [:map ...]):
;;   - required entries (no {:optional true} in entry props) → positional args
;;   - optional entries                     → trailing & {:keys [...]} kwargs
;;   - tools with no required entries       → pure kwargs
;;
;; Visibility is filtered through `tool/tool-visible?`, which honors per-tool
;; `:tool-use-control` (`:visibility :hidden`, `:allow`, `:deny`) — that is the
;; opt-out mechanism for tools that should not appear in the sandbox.
;; ============================================================================

(defn- parse-input
  "Parse one [:map ...] entry vector into a normalized map.
   Entry may be [k schema] or [k {:optional true} schema]."
  [entry]
  (let [k           (tool/malli-map-entry-key entry)
        entry-props (tool/malli-map-entry-props entry)
        raw-schema  (tool/malli-map-entry-schema entry)
        {:keys [desc default]} (clj-llm/parse-malli-field raw-schema)]
    {:key k
     :sym (symbol (name k))
     :optional (boolean (:optional entry-props))
     :desc desc
     :default default}))

(defn- arglists-from-inputs
  "Derive arglists from a tool's :input-schema (Malli [:map ...]).
   Required entries become positional; optional become a
   trailing & {:keys [...]} form, with kwarg names sorted alphabetically."
  [input-schema]
  (let [entries (tool/malli-map-entries input-schema)
        parsed (mapv parse-input (or entries []))
        required (filterv (complement :optional) parsed)
        optional (->> parsed (filter :optional) (sort-by :sym))
        kwarg-form (when (seq optional)
                     ['& {:keys (mapv :sym optional)}])]
    (list (vec (concat (mapv :sym required) kwarg-form)))))

(defn- doc-from-meta
  "Build a doc string from :description and per-input :desc/:default/:optional."
  [{:keys [description input-schema]}]
  (let [entries (tool/malli-map-entries input-schema)
        input-lines (->> (or entries [])
                         (map parse-input)
                         (map (fn [{:keys [key desc default optional]}]
                                (str "  :" (name key)
                                     (when desc (str " — " desc))
                                     (when (some? default) (str " (default " (pr-str default) ")"))
                                     (when optional " [optional]")))))]
    (str/join "\n" (cons (or description "(no description)") input-lines))))

(defn- category-from-meta
  "Derive sandbox category for grouping in build-function-docs.
   Priority: explicit :category > id-prefix (e.g. :mcp$x → :mcp) > type fallback."
  [{:keys [id type] :as m}]
  (or (:category m)
      (when-let [n (some-> id name)]
        (when-let [idx (str/index-of n "$")]
          (keyword (subs n 0 idx))))
      (case type
        (:command :tool) :tools
        :skill           :skills
        :agent           :agents
        :tools)))

(defn- bind-one-tool
  "Build a [symbol fn-with-meta] entry for one registered tool def.

   Supports two equivalent calling conventions so the same binding works for
   both Clojure REPL callers and LLM-generated code:

     1. Positional-first  — `(f req1 req2 :opt1 v1)`. Required inputs map
        to the first N positional args (in declaration order); the rest
        are kwargs that must name optional inputs.

     2. Pure kwargs       — `(f :req1 v1 :req2 v2 :opt1 v1)`. Detected when
        the first arg is a keyword AND that keyword is a known input key.
        Args must be even count and form an alternating :key value sequence.
        This is what LLMs naturally emit and what the agent instructions
        teach as the canonical call shape.

   Kwargs mode is preferred because (a) it ignores declaration order — which
   matters for tools with >8 inputs whose map representation is unordered —
   and (b) it matches the LLM's natural style. Positional mode is preserved
   for backwards compatibility with REPL/test callers.

   nil kwargs are dropped so unspecified options don't pollute the args map."
  [tool-def agent]
  (let [m            (:meta tool-def)
        id           (:id m)
        input-schema (or (:input-schema m) [:map])
        entries      (or (tool/malli-map-entries input-schema) [])
        parsed       (mapv parse-input entries)
        req-keys     (mapv :key (filter (complement :optional) parsed))
        opt-keys  (set (mapv :key (filter :optional parsed)))
        all-keys  (into opt-keys req-keys)
        drop-nils (fn [m] (into {} (remove (fn [[_ v]] (nil? v))) m))
        f (fn [& args]
            (let [kwargs-mode? (and (seq args)
                                    (keyword? (first args))
                                    (contains? all-keys (first args)))
                  flat-map? (and (seq args)
                                 (map? (first args))
                                 (= 1 (count args)))
                  args-map
                  (cond
                    kwargs-mode?
                    (if (odd? (count args))
                      ::odd-kwargs
                      (drop-nils (apply hash-map args)))

                    flat-map? (first args)

                    :else
                    (let [n   (count req-keys)
                          pos (take n args)
                          kw  (apply hash-map (drop n args))
                          opts (drop-nils
                                (into {}
                                      (filter (fn [[k _]] (contains? opt-keys k)))
                                      kw))]
                      (merge (zipmap req-keys pos) opts)))]
              (cond
                (= ::odd-kwargs args-map)
                {:error (str "kwargs-style call to " (name id)
                             " requires an even number of args (pairs of :key value)")}

                :else
                (try
                  (let [r   (tool/call-tool id args-map :agent agent)
                        err (:error-message r)]
                    (if err {:error err} r))
                  (catch Exception e
                    {:error (str "call-tool failed: " (.getMessage e))})))))]
    [(symbol (name id))
     (with-meta f
       {:doc      (doc-from-meta m)
        :arglists (arglists-from-inputs input-schema)
        :category (category-from-meta m)})]))

(defn auto-tool-bindings
  "Auto-generate sandbox bindings for visible tools in !tool-defs.

   Skips:
     - any tool ID in :exclude (default #{})
     - tools hidden/restricted by `:tool-use-control` (via tool/tool-visible?)

   Returns a map of {symbol fn-with-meta}."
  [agent & {:keys [exclude] :or {exclude #{}}}]
  (let [agent-id (when agent (proto/agent-id agent))]
    (into {}
          (keep (fn [[id tool-def]]
                  (when (and (not (contains? exclude id))
                             (tool/tool-visible? tool-def agent-id))
                    (bind-one-tool tool-def agent))))
          (tool/get-tool-defs))))

(defn make-tool-bindings
  "Sandbox-callable bindings for agent tools.

   Produces one binding per visible tool in `!tool-defs` (auto-derived from
   each tool's `:input-schema`), plus the hand-written `call-tool` special: its
   routing kwargs (:source, :server-name) collide with target tool args, so
   the target args ride in a nested :tool-args map."
  [agent]
  (letfn [(call-tool* [tool-id args-map]
            (try
              (let [result (tool/call-tool tool-id args-map :agent agent)
                    err (:error-message result)]
                (if err {:error err} result))
              (catch Exception e {:error (str "call-tool failed: " (.getMessage e))})))]
    (assoc (auto-tool-bindings agent :exclude #{:call-tool})
           'call-tool
           (with-meta
             (fn [tool-name tool-args & {:keys [source server-name]}]
               ;; tool-args is a positional map of the target tool's arguments.
               ;; Passing it as a distinct positional avoids any collision between
               ;; call-tool's routing kwargs and the target tool's arg names.
               (call-tool* :call-tool
                           {:tool-name   (str tool-name)
                            :tool-args   (or tool-args {})
                            :source      source
                            :server-name server-name}))
             {:doc "Invoke a tool by name. (call-tool \"name\" {:arg v ...}) or (call-tool \"name\" {:arg v} :source \"mcp\" :server-name \"foo\"). tool-args is a positional map of the target tool's arguments."
              :arglists '([tool-name tool-args & {:keys [source server-name]}])
              :category :tools}))))

;; `usage$guide` is no longer special-cased here: it is a plain registered tool
;; (see agent.common.commands/usage$guide) and reaches the sandbox through the
;; generic `auto-tool-bindings` path like any other tool. Canonical call shapes:
;;   (usage$guide)                 — list all topics
;;   (usage$guide :topic :memory)  — fetch one guide
;; The `:topic` input is a keyword, so the auto-binder's kwargs mode validates it
;; directly and the tool-calls channel decodes the JSON string the same way.

;; ============================================================================
;; Note: the legacy `remember-note` / `get-note` / `list-notes` /
;; `forget-note` / `clear-notes` sandbox bindings and `notes-snapshot`
;; were removed in the L1 simplification refactor.
;;
;; Reason: those bindings were the only writer of `:kind :note` L1
;; entries; the broader L1 model has been simplified to:
;;   - `:kind :system-context` (operator-managed configuration)
;;   - `:kind :user-context`   (no producer in this revision)
;;
;; A new model-facing user-context API will be added back in a later
;; revision once its shape settles.
;; ============================================================================

(declare snapshot-pending-tasks)

(defn build-context-briefing
  "Pre-compute the per-turn context briefing for the first user message.
   Covers data directory, active tools, and in-progress plans.

   Conversation history / previous turns ride :user-context, and recalled
   memory is a direct DSPy signature input — neither is duplicated here.
   Static prompt material (function directory, brainyard instructions) lives
   in the system prompt, not in the briefing.

   Options:
     :dirs   - dirs map for listing in-progress plans
     :agent  - agent instance for tool listing"
  [sandbox-context & {:keys [dirs agent]}]
  (let [restored-vars (or (:restored-vars sandbox-context) [])
        lines (transient [])]

    ;; === Header ===
    ;; Previous turns and recalled memory are surfaced elsewhere:
    ;;   - :previous-turns  → :user-context (system message)
    ;;   - :recalled-memory → direct DSPy signature input
    ;; The briefing now only covers per-turn dynamic state that doesn't fit
    ;; cleanly into either of those channels.
    (conj! lines "## Context Briefing")
    (when (seq restored-vars)
      (conj! lines (str "Vars: " (str/join ", " restored-vars))))

    ;; === Data directory (context-get paths) ===
    (conj! lines "\n### Data Directory (via context-get)")
    (conj! lines "`[:user-vars]` — your `def`'d sandbox vars (live)")
    (when (seq restored-vars)
      (conj! lines (str "`[:restored-vars]` — [" (str/join ", " restored-vars) "]")))
    (conj! lines "`[:agent-state :info]` — agent identity (id, name, status)")
    (conj! lines "`[:agent-state :config]` — working dir, allowed dirs")
    (conj! lines "`[:agent-state :runtime]` — live state: introspect-fn, pending-tasks-fn")

    ;; === Active state (tools, plans) ===
    (let [tools (binding [proto/*current-agent* agent]
                  (tool/invoke-tool :list-tools {}))
          by-type (group-by :type tools)]
      (conj! lines (str "\n### Active State"))
      (conj! lines (str (count tools) " tools"
                        " (" (count (get by-type :tool)) " tool"
                        ", " (count (get by-type :command)) " command"
                        ", " (count (get by-type :skill)) " skill"
                        ", " (count (get by-type :agent)) " agent"
                        "). Use `(search \"keyword\")` to discover.")))

    ;; Active plans (lightweight check)
    (when dirs
      (try
        (let [active-plans (plan/list-plans dirs :status :in-progress)]
          (when (seq active-plans)
            (conj! lines (str "Plans in progress: "
                              (str/join ", "
                                        (map (fn [p]
                                               (str (:slug p)
                                                    (when-let [sp (:step-progress p)]
                                                      (str " (" sp ")"))))
                                             active-plans))))))
        (catch Exception _ nil)))

    ;; In-flight tasks. Auto-surfaces task status at turn-start so the LLM
    ;; can `slurp` the per-task output-file without first invoking
    ;; pending-tasks-fn or task$list. Covers BOTH LLM-launched background
    ;; work (task$run) AND CoAct soft-pending code blocks (auto-harvested
    ;; by the next iteration — no polling needed for those).
    ;;
    ;; Note: this section is built ONCE per turn (in coact-init-action).
    ;; A per-iteration in-flight roster is also injected into :iterations
    ;; by inject-in-flight-roster! — that's the surface the LLM reads to
    ;; avoid re-emitting code mid-turn. This section covers the rarer
    ;; cross-turn case (a prior turn ended while a task was still in
    ;; flight) and adds richer detail (output-file path, line count, tail).
    (try
      (let [{:keys [tasks]} (snapshot-pending-tasks dirs)]
        (when (seq tasks)
          (conj! lines (str "\n### 🚦 Active Background Tasks"
                            " — DO NOT re-emit; WAIT for auto-harvest"
                            " (resolved results arrive as [↺ async-completion]"
                            " records)"))
          (conj! lines (str "These tasks are still running. The resolved"
                            " result arrives automatically in a later"
                            " iteration — wait, do not poll. Use `task$detail`"
                            " only when you need the output tail mid-flight;"
                            " use `task$cancel` to stop a task."))
          (doseq [t tasks]
            (let [elapsed   (when-let [ms (:elapsed-ms t)]
                              (format " %.1fs" (/ ms 1000.0)))
                  ;; Prefer the CoAct :lang over the internal :job-type so
                  ;; python/javascript code blocks aren't mislabeled as "bash"
                  ;; and clojure isn't shown as "clj-sandbox-eval".
                  type-label (or (:lang t) (:job-type t))
                  coact-tag  (when-let [n (:from-coact-iter t)]
                               (str " (from coact iter " n " — auto-harvested next iter)"))
                  header  (str "- " (:id t) " [" (:status t) (or elapsed "") "] "
                               type-label (or coact-tag "") ": " (:name t))]
              (conj! lines header)
              (when-let [out (:output-file t)]
                (let [n (:total-lines t)]
                  (conj! lines (str "  log: " out
                                    (when n (str " (" n " line" (when (not= 1 n) "s") ")"))))))
              (when (seq (:tail t))
                (doseq [ln (:tail t)]
                  (let [s (str ln)
                        s (if (> (count s) 200) (str (subs s 0 200) "…") s)]
                    (conj! lines (str "  | " s)))))))))
      (catch Exception _ nil))

    (str/join "\n" (persistent! lines))))

(def ^:private runtime-blocklist
  "Keys hidden from introspect-fn — internal/unsafe/huge objects."
  #{:sandbox
    :sandbox-context
    :config
    :system-prompt
    :context-briefing
    :prompt-token-breakdown})

(defn- truncate-code [code]
  (let [c (or code "")]
    (if (> (count c) 200)
      (str (subs c 0 200) "...[truncated]")
      c)))

;; Step G removed `snapshot-pending-evals` along with the sandbox's
;; :pending-evals registry; the agent task manager is now the single source
;; of truth for in-flight evals (see snapshot-pending-tasks below).

(defn- snapshot-pending-tasks
  "Return a sanitized, LLM-safe view of in-flight tasks (status :pending or
   :running) from the default task manager. Covers everything in one view:
   LLM-launched `task$run` background jobs AND CoAct soft-pending code
   blocks (`:job-type :clj-sandbox-eval` or `:bash` with `:coact/lang`
   metadata). Each task carries `:output-file` (slurp for full content),
   `:total-lines` (on-disk count), and a short `:tail` so the LLM can read
   status without an extra tool call.

   CoAct-originated tasks additionally surface `:lang` (the real language
   — python/javascript share `:job-type :bash` so reading metadata is
   necessary to distinguish) and `:from-coact-iter` (the iteration that
   detached the eval; signals 'this will auto-harvest, no need to poll').

   Returns {:available false} when no manager has been initialized."
  [dirs]
  (if-let [mgr (task-manager/peek-default-manager)]
    (let [tasks (filterv #(#{:pending :running} (:status %))
                         (task-proto/list-tasks mgr))
          now   (System/currentTimeMillis)]
      {:count (count tasks)
       :tasks (mapv (fn [t]
                      (let [task-id   (:id t)
                            coact-lang (get-in t [:metadata :coact/lang])
                            coact-iter (get-in t [:metadata :coact/pending-from-iter])
                            out-file   (task-persist/output-path dirs task-id)
                            total      (when out-file
                                         (task-persist/line-count dirs task-id))
                            tail       (task-persist/read-tail dirs task-id 5)]
                        (cond-> {:id       (name task-id)
                                 :name     (:name t)
                                 :job-type (name (:job-type t))
                                 :status   (name (:status t))}
                          (:started-at t) (assoc :elapsed-ms (- now (:started-at t)))
                          coact-lang      (assoc :lang coact-lang)
                          coact-iter      (assoc :from-coact-iter coact-iter)
                          out-file        (assoc :output-file out-file)
                          (some? total)   (assoc :total-lines total)
                          (seq tail)      (assoc :tail tail))))
                    tasks)})
    {:available false :note "task manager not initialized"}))

(defn build-agent-state-snapshot
  "Build a read-only EDN snapshot of agent state for sandbox context.
   Returns {:info {...} :config {...} :runtime {...}}.
   Accessible to the LLM via context-get [:agent-state :info] etc.

   :runtime contains live callables:
     :introspect-fn     — st-memory navigator
     :pending-tasks-fn  — in-flight task manager snapshot (covers code-eval
                          tasks via :clj-sandbox-eval job type AND bash/tool)"
  [agent]
  (let [state @(:!state agent)
        session @(:!session agent)
        config (:config state)
        meta (:meta state)
        st-atom (get-in state [:behavior-tree :context :st-memory])]
    {:info {:agent-id (proto/agent-id agent)
            :user-id (proto/user-id agent)
            :session-id (proto/session-id agent)
            :status (:status state)
            :name (:name meta)
            :description (:description meta)}
     :config (merge
              (get-in (:config session) [:dirs])
              {:working-dir (config/working-dir agent)
               :permissions {:allowed-dirs (config/get-config agent :allowed-dirs)
                             :mode         (config/get-config agent :permission-mode)}}
              {:runtime (config/get-config-snapshot agent)})
     :runtime {:introspect-fn
               (fn [& path]
                 (let [st-atom (get-in state [:behavior-tree :context :st-memory])
                       st @st-atom
                       live-keys (vec (remove runtime-blocklist (keys st)))]
                   (if (empty? path)
                     {:available-keys live-keys}
                     (let [v (get-in st (vec path) ::not-found)]
                       (if (= v ::not-found)
                         (str "not found — available keys: " live-keys)
                         (let [s (pr-str v)]
                           (if (> (count s) 3000)
                             (str (subs s 0 3000) "...[truncated, " (count s) " chars total]")
                             v)))))))
               :pending-tasks-fn (fn [] (snapshot-pending-tasks (get-dirs agent)))
               :description (str "Live runtime state. "
                                 "(introspect-fn) lists keys, (introspect-fn :key …) reads/navigates. "
                                 "(pending-tasks-fn) → in-flight tasks (status :pending or :running) "
                                 "from the task manager — covers code-eval tasks "
                                 "(:clj-sandbox-eval) as well as bash/tool jobs.")}}))
