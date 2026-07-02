;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.commands
  "Slash-command handlers extracted from core.
   Covers inspection, config, tasks, sandbox, MCP, and the input dispatcher."
  (:require [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.oauth-render :as oauth-render]
            [ai.brainyard.agent-tui.sessions :as sessions]
            [ai.brainyard.agent-tui.session-summary :as ssum]
            [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.helpers :as helpers]
            [ai.brainyard.agent-tui.input :as input]
            [ai.brainyard.agent-tui.permissions :as permissions]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.clj-sandbox.interface :as clj-sandbox]
            [ai.brainyard.agent-tui.side-pane-commands :as side-pane-cmd]
            [ai.brainyard.agent-tui-persist.interface :as persist]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.pprint]
            [malli.core :as m]
            [malli.error :as me]))

;; ============================================================================
;; Internal Helpers
;; ============================================================================

(defn- available-models
  "Return up to `n` popular models filtered by available API keys."
  [n]
  (let [all-models  (clj-llm/get-popular-models)
        provs       clj-llm/providers
        has-key?    (fn [{:keys [provider]}]
                      (let [env-var (get-in provs [provider :api-key-env])]
                        (or (nil? env-var)
                            (some? (System/getenv env-var)))))]
    (->> all-models
         (filter has-key?)
         (take n)
         vec)))

(defn- agent-id-str
  "Convert an agent-id keyword to its full string representation.
   Handles namespaced keywords (clones): :ns/name → \"ns/name\"."
  [kw]
  (subs (str kw) 1))

(defn- instance-id->defagent-id
  "Extract the defagent type keyword from an instance-id.
   :coact-agent/tui-1713000000 → :coact-agent
   :coact-agent → :coact-agent (already a def-id)"
  [instance-id]
  (if (namespace instance-id)
    (keyword (namespace instance-id))
    instance-id))

;; ============================================================================
;; Public API — Inspection
;; ============================================================================

(defn status
  "Show current agent state snapshot."
  []
  (let [ag    (tui-session/get-active-agent)]
    (if-not ag
      (tui-session/emit! (ansi/warning "No TUI agent running."))
      (let [st-mem-atom (agent/get-bt-st-memory ag)
            st-mem      (when st-mem-atom @st-mem-atom)
            todo        (:todo-list st-mem)
            total       (count todo)
            done        (count (filter :done todo))]
        (tui-session/emit!
         (fmt/format-status-summary
          {:agent-id       (agent/agent-id ag)
           :status         (:status @(:!state ag))
           :iteration      (:iteration-count st-mem)
           :max-iterations (agent/get-config ag :max-iterations)
           :todo-progress  (when (pos? total) (str done "/" total))
           :goal-achieved  (:goal-achieved st-mem)}))))))

(defn history
  "Show conversation history.
   Options: :last-n (default: all)"
  [& {:keys [last-n]}]
  (let [ag (tui-session/get-active-agent)]
    (when-not ag
      (tui-session/emit! (ansi/warning "No TUI agent running.")))
    (when ag
      (let [messages (:messages @(:!session ag))]
        (tui-session/emit!
         (fmt/format-conversation-history messages :last-n last-n))))))

(defn clear!
  "Restart the active session in place.
   Clears conversation history, scrollback (in-memory and persisted),
   resets st-memory to st-memory-init, drops any display selection, and
   repaints the now-empty viewport. Keeps the same agent instance and
   agent-session-id; tools, instruction, and input history are preserved."
  []
  (let [ag (tui-session/get-active-agent)]
    (if-not ag
      (tui-session/emit! (ansi/warning "No TUI agent running."))
      (let [!session    (:!session ag)
            st-mem-atom (agent/get-bt-st-memory ag)
            st-mem-init (agent/get-st-memory-init ag)
            init-map    (if st-mem-init @st-mem-init {})
            asid        (try (agent/session-id ag) (catch Throwable _ nil))
            active-idx  (sessions/active-idx)]
        ;; --- Agent state ----------------------------------------------------
        ;; Clear session messages, progress data, and total-turns counter
        (swap! !session (fn [s]
                          (-> s
                              (assoc :messages [] :total-turns 0)
                              (agent/clear-data))))
        ;; Reset per-agent turn-id (lives on st-memory-init)
        (when st-mem-init
          (swap! st-mem-init assoc :turn-id 0 :total-turns 0))
        ;; Reset st-memory to initial state (preserves tool bindings, instruction, etc.)
        (when st-mem-atom
          (reset! st-mem-atom init-map))
        ;; --- In-memory scrollback / display --------------------------------
        (reset! layout/!scrollback [])
        (reset! layout/!live-blocks {})
        (swap! layout/!layout assoc :viewport-offset 0)
        (swap! layout/!layout dissoc :collapse-highlight)
        (try (layout/set-popover-active! false) (catch Throwable _))
        ;; Mirror the cleared display state into the session map so a later
        ;; tab switch or resume doesn't restore the old view.
        (when active-idx
          (sessions/update-session! active-idx merge
                                    {:scrollback      []
                                     :live-blocks     {}
                                     :viewport-offset 0
                                     :has-unread?     false}))
        ;; --- On-disk persistence -------------------------------------------
        (when asid
          (try (persist/truncate-scrollback! asid :stream) (catch Throwable _))
          (try (persist/truncate-scrollback! asid :activity) (catch Throwable _))
          (try (let [^java.io.File f (persist/file-of asid :messages)]
                 (when (and f (.exists f)) (.delete f)))
               (catch Throwable _)))
        ;; --- Repaint --------------------------------------------------------
        (try (layout/render-viewport!) (catch Throwable _))
        (try (layout/draw-separator!) (catch Throwable _))
        (try (layout/redraw-chrome!)  (catch Throwable _))
        (try (tui-session/update-status-bar!) (catch Throwable _))
        (tui-session/emit!
         (ansi/success "Cleared session and restarted."))))))

(defn- compact-cmd
  "Handle /compact [ratio] command.
   Compacts context (previous turns, agent-context, iterations) to reduce
   token usage. Live progress + frozen summary render via the TUI session's
   compaction-block handlers (fired by :agent.compaction/pre, /phase, /post)."
  [args]
  (let [ag (tui-session/get-active-agent)]
    (if-not ag
      (tui-session/emit! (ansi/warning "No TUI agent running."))
      (let [target-ratio (if (str/blank? args)
                           (agent/get-config ag :compaction-target-ratio)
                           (try (Double/parseDouble (str/trim args))
                                (catch Exception _ nil)))]
        (if (or (nil? target-ratio) (< target-ratio 0.05) (> target-ratio 0.9))
          (tui-session/emit! (ansi/warning "Usage: /compact [ratio 0.05-0.9] (default from config)"))
          (agent/compact-context! ag :target-ratio target-ratio :trigger :manual))))))

(defn todo
  "Show current TODO list."
  []
  (let [ag (tui-session/get-active-agent)]
    (when-not ag
      (tui-session/emit! (ansi/warning "No TUI agent running.")))
    (when ag
      (let [st-mem-atom (agent/get-bt-st-memory ag)
            todo-list   (when st-mem-atom (:todo-list @st-mem-atom))]
        (if (seq todo-list)
          (tui-session/emit! (fmt/format-todo-list todo-list))
          (tui-session/emit! (ansi/muted "No TODO list.")))))))

(defn- collect-all-usage
  "Collect usage summaries and tagged history entries from all agent instances
   in the active TUI session, plus any sub-trackers.

   Every history entry is tagged with `:agent-instance-id` — main-agent
   entries get the agent's `:agent-id`; sub-tracker entries get the
   sub-tracker's `:label`. `format-usage-table` decides whether to show the
   tags as a single combined table or per-agent sub-tables."
  []
  (let [instances     (:agent-instances (sessions/get-active-session))
        sub-trackers  (:sub-trackers @tui-session/!tui-state)
        instance-summaries (keep helpers/get-usage instances)
        sub-summaries     (keep (fn [{:keys [tracker]}]
                                  (when tracker (clj-llm/get-usage-summary tracker)))
                                sub-trackers)
        all-summaries     (concat instance-summaries sub-summaries)
        combined          (when (seq all-summaries)
                            (clj-llm/merge-usage-summaries all-summaries))
        instance-histories (mapcat (fn [ag]
                                     (when-let [tracker (agent/get-session-config @(:!session ag) :usage-tracker)]
                                       (map #(assoc % :agent-instance-id (:agent-id ag))
                                            (clj-llm/get-usage-history tracker))))
                                   instances)
        sub-histories     (mapcat (fn [{:keys [tracker label]}]
                                    (when tracker
                                      (map #(assoc % :agent-instance-id (or label "sub-agent"))
                                           (clj-llm/get-usage-history tracker))))
                                  sub-trackers)
        all-histories     (sort-by #(or (:timestamp %) (java.util.Date. 0))
                                   (concat instance-histories sub-histories))]
    {:combined combined :history all-histories}))

(def ^:private default-usage-window 10)

(defn- parse-usage-args
  "Parse /usage args. Accepts an optional positive integer N (window size)
   and an optional --breakdown flag in any order.
   Returns {:n int :breakdown? bool} or nil on parse error."
  [args]
  (let [tokens     (->> (str/split (or args "") #"\s+")
                        (remove str/blank?))
        breakdown? (boolean (some #{"--breakdown"} tokens))
        n-token    (first (remove #{"--breakdown"} tokens))]
    (if n-token
      (when-let [v (try (Long/parseLong n-token) (catch Exception _ nil))]
        (when (pos? v)
          {:n v :breakdown? breakdown?}))
      {:n default-usage-window :breakdown? breakdown?})))

(defn usage
  "Show LLM token/cost summary plus a per-call latency/token table for the
   latest N calls (default 10). Pass --breakdown for the per-call
   system/signature/user-message token split.
   Usage: /usage [N] [--breakdown]"
  [args]
  (let [ag (tui-session/get-active-agent)]
    (when-not ag
      (tui-session/emit! (ansi/warning "No TUI agent running.")))
    (when ag
      (if-let [{:keys [n breakdown?]} (parse-usage-args args)]
        (let [{:keys [combined history]} (collect-all-usage)
              recent (when (seq history) (vec (take-last n history)))]
          (if combined
            (do
              (tui-session/emit! (fmt/format-usage-summary combined))
              (when (seq recent)
                (tui-session/emit! "")
                (tui-session/emit! (fmt/format-usage-table recent {:breakdown? breakdown?}))))
            (tui-session/emit! (ansi/muted "No usage data."))))
        (tui-session/emit! (ansi/warning "Usage: /usage [N] [--breakdown]"))))))

(defn show-traces
  "Show BT trace entries from session thinking state.
   Options: :last-n (default: all). Exposed via `/agent trace [N]`."
  [& {:keys [last-n]}]
  (let [ag (tui-session/get-active-agent)]
    (when-not ag
      (tui-session/emit! (ansi/warning "No TUI agent running.")))
    (when ag
      (let [traces (get-in @(:!session ag) [:data :traces])
            traces (if last-n (take-last last-n traces) traces)]
        (if (seq traces)
          (doseq [t traces]
            (tui-session/emit! (if (map? t)
                                 (fmt/format-trace t)
                                 (str (ansi/muted (str "  [trace] " t))))))
          (tui-session/emit! (ansi/muted "No traces.")))))))

(defn display-format!
  "Set TUI display-format level.
   :quiet  — answer only
   :normal — iterations + tools + answer
   :verbose — + BT traces"
  [level]
  (let [old-level (agent/get-config (tui-session/get-active-agent) :display-format)]
    (tui-session/set-display-format! level)
    ;; Toggle TUI mulog publisher based on display-format
    (cond
      (and (= level :verbose) (not= old-level :verbose))
      (tui-session/start-tui-publisher!)

      (and (not= level :verbose) (= old-level :verbose))
      (tui-session/stop-tui-publisher!))
    (tui-session/emit! (ansi/muted (str "Display format: " (name level))))))

(defn deliver!
  "Deliver a user response to a pending action promise."
  [action-id value]
  (let [ag (tui-session/get-active-agent)]
    (when-not ag
      (throw (ex-info "No TUI agent running." {})))
    (agent/deliver-action ag action-id value)))

;; ============================================================================
;; Config Commands (private)
;; ============================================================================

(defn- handle-display-format-command
  "Handle /display-format with optional level argument.
   No arg → show current. With arg → set level."
  [args]
  (if (str/blank? args)
    (tui-session/emit! (ansi/muted (str "Display format: " (name (agent/get-config (tui-session/get-active-agent) :display-format)))))
    (let [level (keyword args)]
      (if (#{:quiet :normal :verbose} level)
        (display-format! level)
        (tui-session/emit! (ansi/warning (str "Invalid level: " args ". Use quiet, normal, or verbose.")))))))

(defn- switch-model!
  "Hot-swap to a new model by name. Reuses API key when possible."
  [model-name]
  (try
    (let [current-lm    (clj-llm/get-default-lm)
          ;; Check popular models first (handles short names like "haiku" → :claude-code)
          popular-match (some #(when (= model-name (:model %)) %) (clj-llm/get-popular-models))
          new-provider  (or (:provider popular-match) (clj-llm/get-provider-from-model model-name))
          ;; Reuse the current key ONLY when staying on the same provider. On a
          ;; provider switch, leave :api-key unset and let create-lm resolve the
          ;; new provider's key from its own :api-key-env. Do NOT carry the old
          ;; key over (an OpenAI key sent to free-llm as Bearer → 401) and do NOT
          ;; keep a partial env-var map here — it drifts out of sync with the
          ;; provider catalog (free-llm, mistral, together, … were all missing).
          api-key       (when (and (= new-provider (:provider current-lm))
                                   (not (#{:claude-code :ollama} new-provider)))
                          (:api-key current-lm))
          ;; Bedrock entries in get-popular-models may pin a :region (e.g. us-east-1
          ;; for vendors not yet in ap-northeast-2). Honor it so a /model pick
          ;; doesn't silently fall back to AWS_REGION and fail with ValidationException.
          region        (:region popular-match)
          new-lm        (clj-llm/create-lm (cond-> {:model model-name :provider new-provider}
                                             api-key (assoc :api-key api-key)
                                             region  (assoc :region region)))]
      (clj-llm/configure-default-lm! new-lm)
      ;; Persist the switched model+provider so --resume restores it.
      ;; on-instance-created writes :model only once at agent creation, so
      ;; without this every /model swap is lost on resume.
      (when-let [ag (tui-session/get-active-agent)]
        (when-let [sid (try (agent/session-id ag) (catch Throwable _ nil))]
          (try (persist/save-meta! sid {:model model-name :provider new-provider})
               (catch Throwable _))))
      (tui-session/emit!
       (ansi/success (str "Switched to " (name new-provider) " / " model-name)))
      (tui-session/update-status-bar!))
    (catch Exception e
      (tui-session/emit! (ansi/failure (str "Failed to switch model: " (.getMessage e)))))))

(defn- handle-model-command
  "Handle /model command with three modes:
   - No args: show current model (use /model + Space for overlay picker)
   - Numeric arg: select by index from available models
   - Model name: direct hot-swap"
  [args _reader]
  (let [current-lm  (clj-llm/get-default-lm)
        cur-model   (:model current-lm)
        cur-provider (or (:provider current-lm) :unknown)
        numeric-arg (when-not (str/blank? args)
                      (try (Long/parseLong (str/trim args)) (catch Exception _ nil)))]
    (cond
      ;; Mode C: model name argument → direct hot-swap
      (and (not (str/blank? args)) (nil? numeric-arg))
      (switch-model! args)

      ;; Mode B: numeric arg — select by index
      numeric-arg
      (let [models (available-models 50)
            n      (count models)]
        (if (<= 1 numeric-arg n)
          (switch-model! (:model (nth models (dec numeric-arg))))
          (tui-session/emit! (ansi/warning (str "Invalid selection. Choose 1-" n ".")))))

      ;; Mode A: no args — show current model, hint about overlay
      :else
      (tui-session/emit!
       (str (ansi/header "Current Model") "\n"
            "  Provider: " (ansi/style (name cur-provider) ansi/bold ansi/cyan) "\n"
            "  Model:    " (ansi/style (str cur-model) ansi/bold ansi/bright-white) "\n\n"
            (ansi/muted "Tip: type /model then Space to browse models interactively."))))))

(defn- handle-config-command
  "Handle /config with optional key/value arguments.
   No args → show all. One arg → show key. Two args → set key value."
  [args]
  (let [ag (tui-session/get-active-agent)]
    (if-not ag
      (tui-session/emit! (ansi/warning "No TUI agent running."))
      (let [parts (when-not (str/blank? args)
                    (str/split (str/trim args) #"\s+" 2))]
        (case (count (or parts []))
          ;; No args: show all config (merged snapshot: defaults < global < per-agent)
          0 (let [current (agent/get-config-snapshot ag)]
              (tui-session/emit! (str (ansi/header "Runtime Config")))
              (doseq [[k {:keys [type default]}] (sort-by key agent/config-schema)]
                (let [val (get current k default)
                      changed? (not= val default)]
                  (tui-session/emit!
                   (str "  " (ansi/style (format "%-30s" (name k)) ansi/bold ansi/bright-white)
                        (ansi/style (format "%-8s" (str val)) (if changed? ansi/bright-cyan ansi/dim))
                        (ansi/muted (str "    (" type
                                         (if changed?
                                           (str ", default: " default)
                                           "")
                                         ")")))))))

          ;; One arg: show single key
          1 (let [k (keyword (first parts))]
              (if-not (contains? agent/config-keys k)
                (tui-session/emit!
                 (ansi/warning (str "Unknown config key: " (first parts)
                                    ". Valid: " (str/join ", " (sort (map name agent/config-keys))))))
                (let [{:keys [type default]} (get agent/config-schema k)
                      val (agent/get-config ag k)
                      changed? (not= val default)]
                  (tui-session/emit!
                   (str "  " (ansi/style (name k) ansi/bold ansi/bright-white) " = "
                        (ansi/style (str val) ansi/bright-cyan)
                        (ansi/muted (str "  (" type
                                         (if changed?
                                           (str ", default: " default)
                                           "")
                                         ")")))))))

          ;; Two args: set key value. `set-config!` writes the per-agent
          ;; override AND persists to .brainyard/config.edn so the value
          ;; survives future sessions.
          (let [k     (keyword (first parts))
                v-str (second parts)]
            (if-not (contains? agent/config-keys k)
              (tui-session/emit!
               (ansi/warning (str "Unknown config key: " (first parts)
                                  ". Valid: " (str/join ", " (sort (map name agent/config-keys))))))
              (let [coerced (agent/coerce-config-value k v-str)]
                (agent/set-config! ag k coerced)
                (tui-session/emit!
                 (ansi/success (str (name k) " = " coerced)))))))))))

(def ^:private effort-presets
  ;; Effort now maps purely to the in-loop refinement budget. The old
  ;; finalize-answer polish pass was merged into ThinkActCode's answer channel
  ;; (goal-achieved / next-user-prompt are always produced), so the only knob
  ;; that varies by effort is how many answer rejections the loop will refine
  ;; through before accepting.
  {"low"    {:max-refinements 0}
   "medium" {:max-refinements 1}
   "high"   {:max-refinements 2}})

(defn- current-effort-level
  "Derive the current effort level from the agent's merged config view,
   or nil if custom."
  [ag]
  (let [mr (agent/get-config ag :max-refinements)]
    (some (fn [[level preset]]
            (when (= mr (:max-refinements preset))
              level))
          effort-presets)))

(defn- handle-effort-command
  "Handle /effort — show or set effort level (low|medium|high)."
  [args]
  (let [ag (tui-session/get-active-agent)]
    (if-not ag
      (tui-session/emit! (ansi/warning "No TUI agent running."))
      (if (str/blank? args)
        ;; Show current level
        (let [level (current-effort-level ag)]
          (tui-session/emit!
           (str "  effort = " (ansi/style (or level "custom") ansi/bold ansi/bright-cyan)
                (ansi/muted "  (low | medium | high)"))))
        ;; Set level — writes per-agent override + persists to config.edn.
        (let [level (str/lower-case (str/trim args))]
          (if-let [preset (get effort-presets level)]
            (do (doseq [[k v] preset]
                  (agent/set-config! ag k v))
                (tui-session/emit!
                 (ansi/success
                  (str "effort = " level
                       "  (max-refinements=" (:max-refinements preset) ")"))))
            (tui-session/emit!
             (ansi/warning (str "Unknown effort level: " level ". Valid: low, medium, high")))))))))

;; ============================================================================
;; Task Meta-Commands (private)
;; ============================================================================

(defn- tasks-cmd []
  (if-let [mgr (agent/get-default-manager)]
    (tui-session/emit! (agent/format-task-list (agent/list-tasks mgr)))
    (tui-session/emit! (ansi/warning "Task manager not initialized."))))

(defn- task-detail-cmd [args]
  (if-let [mgr (agent/get-default-manager)]
    (let [task-id (keyword (str/trim (str args)))]
      (if-let [task (agent/get-task mgr task-id)]
        (tui-session/emit! (agent/format-task-detail task))
        (tui-session/emit! (ansi/warning (str "Task not found: " (name task-id))))))
    (tui-session/emit! (ansi/warning "Task manager not initialized."))))

(defn- task-cancel-cmd [args]
  (if-let [mgr (agent/get-default-manager)]
    (let [task-id (keyword (str/trim (str args)))]
      (if (agent/cancel-task mgr task-id)
        (tui-session/emit! (ansi/success (str "Cancelled: " (name task-id))))
        (tui-session/emit! (ansi/warning (str "Could not cancel: " (name task-id))))))
    (tui-session/emit! (ansi/warning "Task manager not initialized."))))

(defn- task-log-cmd [args]
  (if-let [mgr (agent/get-default-manager)]
    (let [parts (str/split (str/trim (str args)) #"\s+" 2)
          task-id (keyword (first parts))
          last-n  (some-> (second parts) parse-long)]
      (if-let [task (agent/get-task mgr task-id)]
        (tui-session/emit! (agent/format-task-output task last-n))
        (tui-session/emit! (ansi/warning (str "Task not found: " (name task-id))))))
    (tui-session/emit! (ansi/warning "Task manager not initialized."))))

(defn- task-del-cmd [args]
  (if-let [mgr (agent/get-default-manager)]
    (let [task-id (keyword (str/trim (str args)))]
      (if (str/blank? (str args))
        (tui-session/emit! (ansi/warning "Usage: /task del ID"))
        (do (agent/remove-task mgr task-id)
            (tui-session/emit! (ansi/success (str "Removed: " (name task-id)))))))
    (tui-session/emit! (ansi/warning "Task manager not initialized."))))

(defn- task-run-cmd [args]
  (if-let [mgr (agent/get-default-manager)]
    (let [cmd  (str/trim (str args))
          task-name (str "bash: " (subs cmd 0 (min 200 (count cmd))))
          task (agent/create-task mgr task-name :bash {:command cmd :timeout-ms 120000})]
      (agent/start-task mgr (:id task))
      (tui-session/emit! (ansi/success (str "Started " (name (:id task)) ": " task-name))))
    (tui-session/emit! (ansi/warning "Task manager not initialized."))))

(defn- task-display-mode-cmd
  "Flip a running task's display-mode. mode is :foreground or :background.
   The TUI watch handles block creation/disposal."
  [args mode]
  (if-let [mgr (agent/get-default-manager)]
    (let [task-id (keyword (str/trim (str args)))]
      (cond
        (str/blank? (str args))
        (tui-session/emit! (ansi/warning (str "Usage: /task " (case mode :foreground "fg" :background "bg") " ID")))
        (nil? (agent/get-task mgr task-id))
        (tui-session/emit! (ansi/warning (str "Task not found: " (name task-id))))
        :else
        (if (agent/set-display-mode! mgr task-id mode)
          (tui-session/emit! (ansi/success (str (name task-id) " → " (name mode))))
          (tui-session/emit! (ansi/warning (str "Could not flip " (name task-id) " to " (name mode)))))))
    (tui-session/emit! (ansi/warning "Task manager not initialized."))))

(defn- handle-task-command
  "Handle /task with subcommands: list, detail, cancel, del, log, run, bg, fg."
  [args]
  (let [parts   (when-not (str/blank? args) (str/split (str/trim args) #"\s+" 2))
        subcmd  (first parts)
        subcmd-args (or (second parts) "")]
    (case subcmd
      ("list" nil) (tasks-cmd)
      "detail"     (task-detail-cmd subcmd-args)
      "cancel"     (task-cancel-cmd subcmd-args)
      "del"        (task-del-cmd subcmd-args)
      "log"        (task-log-cmd subcmd-args)
      "run"        (task-run-cmd subcmd-args)
      "bg"         (task-display-mode-cmd subcmd-args :background)
      "fg"         (task-display-mode-cmd subcmd-args :foreground)
      ;; Bare ID → treat as detail
      (if (parse-long subcmd)
        (task-detail-cmd subcmd)
        (task-detail-cmd args)))))

(defn- capture-cmd
  "Save scrollback buffer to a file for debugging."
  [args]
  (if (str/blank? args)
    (tui-session/emit! (ansi/warning "Usage: /capture <file-path>"))
    (try
      (let [lines @layout/!scrollback
            content (str/join "\n" lines)]
        (spit args content)
        (tui-session/emit! (ansi/success (str "Captured " (count lines) " lines to " args))))
      (catch Exception e
        (tui-session/emit! (ansi/failure (str "Failed to write: " (.getMessage e))))))))

;; ============================================================================
;; Sandbox Command (private)
;; ============================================================================

(defn- handle-sandbox-fn
  "Execute a sandbox binding function interactively."
  [args]
  (let [ag (tui-session/get-active-agent)]
    (if-not ag
      (tui-session/emit! (ansi/warning "No TUI agent running."))
      (let [sandbox (get-in @(:!state ag) [:sandbox])]
        (if-not sandbox
          (tui-session/emit! (ansi/warning "No sandbox available. Ask a question first so the RLM agent creates its sandbox."))
          (if (str/blank? args)
            (tui-session/emit! (agent/format-sandbox-help))
            (let [parts   (str/split (str/trim args) #"\s+" 2)
                  fn-name (first parts)
                  fn-args (second parts)
                  fn-meta (agent/sandbox-fn-by-name fn-name)]
              (if-not fn-meta
                (tui-session/emit! (ansi/warning (str "Unknown sandbox function: " fn-name
                                                      "\nType /sandbox to see available functions.")))
                (let [code (if (= (:args fn-meta) :none)
                             (:code-template fn-meta)
                             (if (str/blank? fn-args)
                               (do (tui-session/emit!
                                    (ansi/warning (str fn-name " requires args: " (:args fn-meta))))
                                   nil)
                               (format (:code-template fn-meta) fn-args)))]
                  (when code
                    (try
                      (let [result (clj-sandbox/eval-code sandbox code :timeout-ms 15000)]
                        (when-not (str/blank? (:output result))
                          (tui-session/emit! (:output result)))
                        (if (:error result)
                          (tui-session/emit! (ansi/failure (str "Error: " (:error result))))
                          (tui-session/emit! (with-out-str (clojure.pprint/pprint (:result result))))))
                      (catch clojure.lang.ExceptionInfo e
                        (if (:ai.brainyard.clj-sandbox.core.sandbox/termination (ex-data e))
                          (tui-session/emit! (str "FINAL: " (:value (ex-data e))))
                          (tui-session/emit! (ansi/failure (str "Sandbox error: " (.getMessage e))))))
                      (catch Exception e
                        (tui-session/emit! (ansi/failure (str "Sandbox error: " (.getMessage e))))))))))))))))

;; ============================================================================
;; Sandbox Unified Command (private)
;; ============================================================================

(defn- handle-sandbox-eval
  "Evaluate arbitrary Clojure code in the agent's sandbox.
   Usage: /sandbox eval (+ 1 2)"
  [args]
  (let [ag (tui-session/get-active-agent)]
    (if-not ag
      (tui-session/emit! (ansi/warning "No TUI agent running."))
      (let [sandbox (get-in @(:!state ag) [:sandbox])]
        (if-not sandbox
          (tui-session/emit! (ansi/warning "No sandbox available. Ask a question first."))
          (if (str/blank? args)
            (tui-session/emit! (ansi/warning "Usage: /sandbox eval <clojure-code>\nExample: /sandbox eval (list-tools)"))
            (try
              (let [result (clj-sandbox/eval-code sandbox args :timeout-ms 15000)]
                (tui-session/emit! (str (ansi/muted "Code: ") (ansi/style args ansi/cyan)))
                (when-not (str/blank? (:output result))
                  (tui-session/emit! (str (ansi/muted "Output: ") (:output result))))
                (if (:error result)
                  (tui-session/emit! (str (ansi/muted "Error: ") (ansi/failure (:error result))))
                  (tui-session/emit! (str (ansi/muted "Result: ") "\n" (with-out-str (clojure.pprint/pprint (:result result)))))))
              (catch clojure.lang.ExceptionInfo e
                (if (:ai.brainyard.clj-sandbox.core.sandbox/termination (ex-data e))
                  (tui-session/emit! (str (ansi/muted "FINAL: ") (:value (ex-data e))))
                  (tui-session/emit! (ansi/failure (str "Sandbox error: " (.getMessage e))))))
              (catch Exception e
                (tui-session/emit! (ansi/failure (str "Sandbox error: " (.getMessage e))))))))))))

(defn- handle-sandbox-command
  "Handle /sandbox with subcommands: eval, or sandbox function names."
  [args]
  (let [parts (when-not (str/blank? args) (str/split (str/trim args) #"\s+" 2))
        subcmd (first parts)
        subcmd-args (second parts)]
    (if (= subcmd "eval")
      (handle-sandbox-eval subcmd-args)
      (handle-sandbox-fn args))))

;; ============================================================================
;; MCP Command (private)
;; ============================================================================

(defn- format-mcp-server-status
  "Format a single MCP server's status line."
  [server-name config connected?]
  (let [;; An enabled :lazy server is deferred at startup — it connects on
        ;; demand via /mcp <name> start, so flag it rather than reading as a
        ;; plain "disconnected".
        lazy?       (and (not connected?)
                         (:enabled config true)
                         (boolean (:lazy config)))
        status-icon (cond connected? ansi/check
                          lazy?       ansi/ellipsis
                          :else       ansi/cross-mark)
        status-text (cond connected? (ansi/success "connected")
                          lazy?       (ansi/muted "lazy (on demand)")
                          :else       (ansi/muted "disconnected"))
        transport   (name (or (:transport config) :unknown))]
    (str "  " status-icon " " server-name " " status-text
         (ansi/muted (str " (" transport ")")))))

;; Defined below (OAuth Commands section); used by /mcp <s> auth here.
(declare reauth-mcp-async!)

;; ---------------------------------------------------------------------------
;; Async spinner — OAuth/MCP ops (start/stop/auth/status) block on the network
;; (the initialize handshake, token exchange, the user authorizing in a
;; browser), so they run off the command thread behind a one-line braille
;; spinner. The live block is disposed the instant the op settles, so
;; scrollback shows only the outcome line — no leftover "…working" noise.
;; ---------------------------------------------------------------------------

(def ^:private async-spinner-frames
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn- run-async-with-spinner!
  "Run blocking `thunk` on a background thread, animating a one-line spinner
   labelled `label` until it settles. `done-fn` receives the result and returns
   the success line to emit (nil = stay silent); `fail-fn` receives the
   exception and returns the failure line. Returns immediately so the command
   loop stays responsive."
  [label thunk done-fn fail-fn]
  (let [block-id (keyword "async-spin" (str (System/nanoTime)))
        running  (atom true)
        ticker   (Thread.
                  ^Runnable
                  (fn []
                    (loop [i 0]
                      (when @running
                        (try
                          (layout/update-live-block!
                           block-id
                           [(str "  "
                                 (ansi/spinner-active
                                  (nth async-spinner-frames
                                       (mod i (count async-spinner-frames))))
                                 " " label (ansi/muted "…"))])
                          (catch Throwable _ nil))
                        (Thread/sleep (long 120))
                        (recur (inc i))))))]
    (.setDaemon ticker true)
    (.start ticker)
    (future
      (let [[ok? payload] (try [true (thunk)]
                               (catch Throwable e [false e]))]
        ;; Stop + join the ticker BEFORE disposing so it can't recreate the
        ;; block after we've torn it down (one-frame orphan otherwise).
        (reset! running false)
        (try (.join ticker 300) (catch Throwable _ nil))
        (layout/dispose-live-block! block-id)
        (if ok?
          (when-let [line (done-fn payload)] (tui-session/emit! line))
          (tui-session/emit! (fail-fn payload)))
        ;; We emitted from a background thread into the scroll region while the
        ;; user sits idle at the prompt — return the hardware cursor to the
        ;; input bar (no-op under a popover, which owns the cursor).
        (try (layout/restore-input-cursor!) (catch Throwable _ nil))))))

(defn- handle-mcp-command
  "Handle /mcp meta-command.
   No args:             list all servers with status.
   <server>:            show server status detail.
   <server> start:      connect server.
   <server> stop:       disconnect server.
   <server> auth:       (re)run OAuth for a native :http OAuth server.
   <server> status:     show server status detail."
  [args]
  (let [configured (agent/list-configured-servers) active (set (agent/list-active-clients))]
    (if (str/blank? args)
      ;; No args: list all servers
      (if (empty? configured)
        (tui-session/emit! (ansi/muted "No MCP servers configured."))
        (let [lines (mapv (fn [sn]
                            (format-mcp-server-status sn (agent/get-mcp-server-config sn) (contains? active sn)))
                          configured)]
          (tui-session/emit! (str (ansi/header "MCP Servers") "\n"
                                  (str/join "\n" lines) "\n"
                                  (ansi/muted (str "\n  " (count active) "/" (count configured) " connected"
                                                   "  •  /mcp <server> start|stop|auth|status"))))))
      ;; Has args: parse server-name and optional action
      (let [parts       (str/split (str/trim args) #"\s+" 2)
            server-name (first parts)
            action      (some-> (second parts) str/trim str/lower-case)]
        (if-not (some #{server-name} configured)
          (tui-session/emit! (ansi/warning (str "Unknown MCP server: " server-name
                                                "\nConfigured: " (str/join ", " configured))))
          (let [connected? (contains? active server-name)
                config     (agent/get-mcp-server-config server-name)]
            (case action
              "start"
              (if connected?
                (tui-session/emit! (ansi/muted (str server-name " is already connected.")))
                (run-async-with-spinner!
                 (str "Connecting " server-name)
                 #(agent/start-mcp-server! server-name config)
                 (fn [_] (ansi/success (str "Started " server-name)))
                 (fn [e] (ansi/failure (str "Failed to start " server-name ": " (.getMessage e))))))

              "stop"
              (if-not connected?
                (tui-session/emit! (ansi/muted (str server-name " is not connected.")))
                (run-async-with-spinner!
                 (str "Disconnecting " server-name)
                 #(agent/stop-mcp-server! server-name)
                 (fn [_] (ansi/success (str "Stopped " server-name)))
                 (fn [e] (ansi/failure (str "Failed to stop " server-name ": " (.getMessage e))))))

              "auth"
              (if-not (agent/mcp-oauth-server? server-name)
                (tui-session/emit! (ansi/warning (str server-name " is not OAuth-configured"
                                                      " (set :config :auth {:type :oauth …}).")))
                (reauth-mcp-async! server-name))

              "status"
              (let [transport (name (or (:transport config) :unknown))
                    enabled?  (get config :enabled true)
                    ;; Building the detail for a connected server round-trips to
                    ;; it (tools list + serverInfo), so do it behind the spinner.
                    build-detail
                    (fn []
                      (let [tools (when connected? (try (agent/list-server-tools server-name) (catch Exception _ [])))
                            info  (when connected? (try (agent/get-server-info server-name) (catch Exception _ nil)))]
                        (str (ansi/header (str "MCP: " server-name)) "\n"
                             "  Status:    " (if connected? (ansi/success "connected") (ansi/muted "disconnected")) "\n"
                             "  Transport: " transport "\n"
                             "  Enabled:   " (if enabled? "yes" "no") "\n"
                             (when info
                               (let [si (:serverInfo info)]
                                 (str "  Server:    " (or (:name si) (:name info) "?") " v" (or (:version si) (:version info) "?") "\n")))
                             (when tools
                               (str "  Tools:     " (count tools) "\n")))))]
                (if connected?
                  (run-async-with-spinner!
                   (str "Querying " server-name)
                   build-detail
                   identity
                   (fn [e] (ansi/failure (str "Status query failed for " server-name ": " (.getMessage e)))))
                  (tui-session/emit! (build-detail))))

              ;; No action or unknown — show status (same as "status")
              (let [transport (name (or (:transport config) :unknown))
                    enabled?  (get config :enabled true)]
                (tui-session/emit!
                 (str (ansi/header (str "MCP: " server-name)) "\n"
                      "  Status:    " (if connected? (ansi/success "connected") (ansi/muted "disconnected")) "\n"
                      "  Transport: " transport "\n"
                      "  Enabled:   " (if enabled? "yes" "no") "\n"
                      (ansi/muted "  Actions: /mcp ") server-name (ansi/muted " start|stop|auth|status")))))))))))

;; ============================================================================
;; OAuth Commands — /login /logout (private)
;; ============================================================================

(defn- reauth-mcp-async!
  "Kick off a device/auth-code re-auth for an OAuth MCP server off the command
   thread (it blocks until the user authorizes elsewhere). Used by
   `/mcp <server> auth`. The verification box (device code / browser URL) is
   printed by the OAuth renderer below the spinner while it animates.
   (MCP server auth lives under /mcp, not /login.)"
  [server-name]
  (run-async-with-spinner!
   (str "Authenticating " server-name)
   #(agent/reauth-mcp-server! server-name)
   (fn [_] (ansi/success (str "Re-authenticated " server-name)))
   (fn [e] (ansi/failure (str "Auth failed for " server-name ": " (.getMessage e))))))

;; /login and /logout manage SPECIAL AUTH PROVIDERS (e.g. Anthropic
;; subscription OAuth) — distinct from MCP servers, whose auth is handled by
;; /mcp <server> start|stop|auth|status.

(defn- provider-status-overview []
  (let [anthropic? (try (clj-llm/oauth-authenticated?) (catch Exception _ false))]
    (str (ansi/header "Auth providers") "\n"
         "  anthropic  " (if anthropic? (ansi/success "signed in") (ansi/muted "not signed in")) "\n"
         (ansi/muted "\n  /login <provider>   sign in    •    /logout <provider>   sign out")
         "\n"
         (ansi/muted "  (MCP server auth is separate — use /mcp <server> auth | status)"))))

(defn- handle-login-command
  "/login            → list auth providers and their sign-in status.
   /login <provider> → sign in to a provider (e.g. anthropic).
   MCP servers are NOT providers — authenticate them with /mcp <server> auth."
  [args]
  (let [target (some-> args str/trim str/lower-case not-empty)]
    (cond
      (nil? target)
      (tui-session/emit! (provider-status-overview))

      (= "anthropic" target)
      (if (try (clj-llm/oauth-authenticated?) (catch Exception _ false))
        (tui-session/emit! (ansi/muted "Already signed in to anthropic."))
        (tui-session/emit! (ansi/warning
                            (str "Anthropic subscription OAuth is restricted to Claude Code and "
                                 "claude.ai, so it can't be completed here.\n"
                                 "Other providers authenticate via API keys in your .env."))))

      :else
      (tui-session/emit! (ansi/warning (str "Unknown auth provider: " target
                                            "\nRun /login with no args to list providers."
                                            "\n(For an MCP server, use /mcp " target " auth.)"))))))

(defn- handle-logout-command
  "/logout <provider> → sign out of an auth provider (e.g. anthropic).
   MCP servers are managed via /mcp <server> stop — not here."
  [args]
  (let [target (some-> args str/trim str/lower-case not-empty)]
    (cond
      (nil? target)
      (tui-session/emit! (ansi/warning "Usage: /logout <provider>   (e.g. anthropic)"))

      (= "anthropic" target)
      (do (try (clj-llm/oauth-logout!) (catch Exception _ nil))
          (tui-session/emit! (ansi/success "Signed out of anthropic.")))

      :else
      (tui-session/emit! (ansi/warning (str "Unknown auth provider: " target
                                            "\n(For an MCP server, use /mcp " target " stop.)"))))))

;; ============================================================================
;; Continue Command (private)
;; ============================================================================

(defn- continue-agent
  "Continue the RLM agent from where it left off with more iterations."
  [args]
  (let [ag (tui-session/get-active-agent)
        st-mem (some-> ag agent/get-bt-st-memory deref)]
    (cond
      (not ag)
      (tui-session/emit! (ansi/warning "No TUI agent running."))

      (not (:iterations-exhausted st-mem))
      (tui-session/emit! (ansi/warning "Nothing to continue — last response completed normally."))

      (not (:question st-mem))
      (tui-session/emit! (ansi/warning "No previous question to continue."))

      :else
      (let [question    (:question st-mem)
            st-mem-init (agent/get-st-memory-init ag)
            ;; If user passes /continue N, persist as the per-agent override
            ;; (also writes to .brainyard/config.edn via set-config!). With
            ;; no arg, just read whatever the merged config currently says.
            extra-iters (if (str/blank? args)
                          (agent/get-config ag :max-iterations)
                          (let [n (or (parse-long (str/trim args)) 20)]
                            (agent/set-config! ag :max-iterations n)
                            n))]
        ;; Continuation state lives on st-memory-init (survives run-bt reset).
        (swap! st-mem-init assoc :continuation
               {:max-iterations extra-iters})
        (tui-session/emit!
         (str "\n" (ansi/style
                    (str "Continuing with " extra-iters " more iterations...")
                    ansi/bright-cyan)))
        (try
          ;; Prepend "[CONTINUATION] " so the LLM knows this is a continuation,
          ;; not a fresh question — it should check existing variables and
          ;; plan progress instead of starting over.
          ((requiring-resolve 'ai.brainyard.agent-tui.core/ask)
           (str "[CONTINUATION] " question))
          (catch Exception e
            (tui-session/emit! (ansi/failure (str "Error: " (.getMessage e))))))))))

;; ============================================================================
;; Agent Command
;; ============================================================================

(defn available-agent-types
  "Return a sorted vector of {:id :description} for registered defagent types.
   Excludes hidden agents (visibility :hidden in :tool-use-control)."
  []
  (->> (agent/get-tool-defs :type :agent)
       vals
       (remove #(= :hidden (get-in % [:meta :tool-use-control :visibility])))
       (mapv (fn [{:keys [id description]}]
               {:id id :description (or description "")}))
       (sort-by (comp name :id))
       vec))

(defn session-instances
  "Return alive agent instances for the active TUI session."
  []
  (let [session (sessions/get-active-session)]
    (filterv #(agent/agent-running? %)
             (or (:agent-instances session) []))))

(defn- agent-status!
  "Display status of the current agent."
  []
  (let [ag (tui-session/get-active-agent)]
    (if-not ag
      (tui-session/emit! (ansi/warning "No agent running."))
      (let [defid     (tui-session/get-active-defagent-id)
            inst-id   (tui-session/get-active-agent-id)
            state     @(:!state ag)
            st-mem-atom (agent/get-bt-st-memory ag)
            st-mem    (when st-mem-atom @st-mem-atom)
            msg-cnt   (count (agent/get-messages @(:!session ag)))
            instances (session-instances)
            tui-st    @tui-session/!tui-state
            resumed?  (:resumed? tui-st)]
        (tui-session/emit!
         (str (ansi/header "Agent Status") "\n"
              "  Type:       " (ansi/tool-name (agent-id-str defid)) "\n"
              "  Instance:   " (ansi/muted (agent-id-str inst-id)) "\n"
              "  Status:     " (case (:status state)
                                 :running (ansi/success "running")
                                 :idle    (ansi/muted "idle")
                                 (ansi/muted (str (or (:status state) "unknown")))) "\n"
              "  Iteration:  " (or (:iteration-count st-mem) 0)
              "/" (agent/get-config ag :max-iterations) "\n"
              "  Messages:   " msg-cnt "\n"
              "  Session:    " (ansi/muted (or (agent/session-id ag) "unknown")) "\n"
              (when resumed?
                (str "  Resumed:    "
                     (ansi/muted (str "from " (or (:resume-from-sid tui-st) "?")
                                      " · continuing from turn "
                                      (or (:resume-from-turn tui-st) 0)))
                     (when-let [cost (:resume-restored-cost tui-st)]
                       (when (pos? (double cost))
                         (ansi/muted (format "  · $%.4f carried" (double cost)))))
                     (when-let [defs (:resume-restored-defs tui-st)]
                       (when (pos? defs)
                         (ansi/muted (str "  · " defs " defs restored"))))
                     "\n"))
              "  Instances:  " (count instances)
              (when (> (count instances) 1)
                (str "  " (ansi/muted (str/join ", " (map #(agent-id-str (:agent-id %)) instances)))))))))))

(def ^:private max-instances-per-session 10)

(defn- create-new-agent!
  "Create a new agent of the given defagent type in the current session.
   Delegates to create-tui-agent! for actual creation. Keeps the current agent
   alive in the registry (use /agent switch to return, /agent close to remove)."
  [target-agent-id]
  (let [current-ag (tui-session/get-active-agent)
        n-existing (count (session-instances))]
    (cond
      (nil? (agent/get-tool-defs :id target-agent-id))
      (tui-session/emit! (ansi/warning (str "Unknown agent type: " (agent-id-str target-agent-id)
                                            ". Use /agent new to see types.")))

      (>= n-existing max-instances-per-session)
      (tui-session/emit! (ansi/warning (str "Instance limit reached (" max-instances-per-session
                                            "). Use /agent close to remove one first.")))

      (and current-ag (= :running (:status @(:!state current-ag))))
      (tui-session/emit! (ansi/warning "Agent is currently running. Wait for it to finish or cancel first."))

      :else
      ;; Seed the new agent's iteration cap from the current agent's resolved
      ;; config (the source of truth), flowing into its per-agent override.
      (let [max-iter (agent/get-config current-ag :max-iterations)]
        (when current-ag
          (tui-session/detach-watches!))
        (try
          (let [create-fn (requiring-resolve 'ai.brainyard.agent-tui.core/create-tui-agent!)
                agt-sess-id (agent/generate-session-id "agt")
                new-ag    (create-fn target-agent-id
                                     :session-id agt-sess-id
                                     :max-iterations max-iter)]
            ;; Register new agent in TUI session's :agent-instances
            (sessions/update-session! (sessions/active-idx)
                                      update :agent-instances conj new-ag)
            (tui-session/set-agent! new-ag (:agent-id new-ag))
            (let [n-instances (count (session-instances))]
              (tui-session/emit!
               (str "\n" (ansi/success (str "Created new " (agent-id-str target-agent-id)))
                    (when (> n-instances 1)
                      (str " " (ansi/muted (str "(" n-instances " instances, /agent switch to navigate)"))))))))
          (catch Exception e
            (when current-ag
              (tui-session/set-agent! current-ag (:agent-id current-ag)))
            (tui-session/emit!
             (ansi/failure (str "Failed to create agent: " (.getMessage e))))))))))

(defn- show-new-agent-menu!
  "Display the list of available agent types for /agent new."
  []
  (let [agents (available-agent-types)]
    (tui-session/emit! (str (ansi/header "New Agent") "\n"))
    (doseq [[i {:keys [id description]}] (map-indexed vector agents)]
      (tui-session/emit!
       (str "  " (ansi/style (str (inc i)) ansi/bold) ". "
            (ansi/style (agent-id-str id) ansi/bright-white)
            (when (seq description)
              (str "  " (ansi/muted (subs description 0 (min 60 (count description)))))))))
    (tui-session/emit! (str "\n" (ansi/muted "Use /agent new <name|#> to create.")))))

(defn- handle-agent-new
  "Handle /agent new: create a new agent by name or number."
  [args]
  (let [agents      (available-agent-types)
        numeric-arg (when-not (str/blank? args)
                      (try (Long/parseLong (str/trim args)) (catch Exception _ nil)))]
    (cond
      (str/blank? args)
      (show-new-agent-menu!)

      (nil? numeric-arg)
      (create-new-agent! (keyword (str/trim args)))

      :else
      (let [n (count agents)]
        (if (<= 1 numeric-arg n)
          (create-new-agent! (:id (nth agents (dec numeric-arg))))
          (tui-session/emit! (ansi/warning (str "Invalid selection. Choose 1-" n "."))))))))

(defn- switch-to-instance!
  "Switch the TUI to an existing agent instance by instance-id."
  [target-instance-id]
  (let [current-ag  (tui-session/get-active-agent)
        current-id  (tui-session/get-active-agent-id)
        instances   (session-instances)]
    (cond
      (nil? current-ag)
      (tui-session/emit! (ansi/warning "No agent running."))

      (= target-instance-id current-id)
      (tui-session/emit! (ansi/muted (str "Already using " (agent-id-str current-id) ".")))

      (= :running (:status @(:!state current-ag)))
      (tui-session/emit! (ansi/warning "Agent is currently running. Wait for it to finish or cancel first."))

      :else
      (let [target-ag (some #(when (= target-instance-id (:agent-id %)) %) instances)]
        (if-not target-ag
          (tui-session/emit! (ansi/warning (str "Instance not found: " (agent-id-str target-instance-id)
                                                ". Use /agent switch to see instances.")))
          (do
            (tui-session/detach-watches!)
            (tui-session/set-agent! target-ag target-instance-id)

            (let [msg-count (count (agent/get-messages @(:!session target-ag)))]
              (tui-session/emit!
               (str "\n"
                    (ansi/success (str "Switched to " (agent-id-str target-instance-id)))
                    (when (pos? msg-count)
                      (str " " (ansi/muted (str "(" msg-count " messages)")))))))))))))

(defn- show-switch-menu!
  "Display alive agent instances for /agent switch."
  []
  (let [current-id (tui-session/get-active-agent-id)
        instances  (session-instances)]
    (if (<= (count instances) 1)
      (tui-session/emit! (ansi/muted "Only one instance running. Use /agent new to create another."))
      (do
        (tui-session/emit!
         (str (ansi/header "Switch Instance") "\n"))
        (doseq [[i ag] (map-indexed vector (sort-by #(str (:agent-id %)) instances))]
          (let [ag-id   (:agent-id ag)
                defid   (instance-id->defagent-id ag-id)
                status  (get-in @(:!state ag) [:status])
                msg-cnt (count (agent/get-messages @(:!session ag)))
                marker  (if (= ag-id current-id) "▸ " "  ")]
            (tui-session/emit!
             (str marker (ansi/style (str (inc i)) ansi/bold) ". "
                  (ansi/style (agent-id-str ag-id) ansi/bright-white)
                  "  " (ansi/muted (str "[" (name defid) "]"))
                  (when status (str "  " (case status
                                           :running (ansi/style "running" ansi/bright-green)
                                           :idle    (ansi/muted "idle")
                                           (ansi/muted (name status)))))
                  (str "  " (ansi/muted (str msg-cnt " msgs")))))))
        (tui-session/emit! (str "\n" (ansi/muted "Use /agent switch <#> to select.")))))))

(defn- handle-agent-switch
  "Handle /agent switch: switch between existing instances by number or instance-id."
  [args]
  (let [instances   (sort-by #(str (:agent-id %)) (session-instances))
        numeric-arg (when-not (str/blank? args)
                      (try (Long/parseLong (str/trim args)) (catch Exception _ nil)))]
    (cond
      (str/blank? args)
      (show-switch-menu!)

      (nil? numeric-arg)
      (switch-to-instance! (keyword (str/trim args)))

      :else
      (let [n (count instances)]
        (if (<= 1 numeric-arg n)
          (switch-to-instance! (:agent-id (nth instances (dec numeric-arg))))
          (tui-session/emit! (ansi/warning (str "Invalid selection. Choose 1-" n "."))))))))

(defn- close-agent!
  "Close an agent instance. With no arg → close the current instance.
   With a numeric arg (1-based) or instance-id → close that specific instance.
   When the closed instance is the current one, swap focus to the next remaining."
  [args]
  (let [instances  (sort-by #(str (:agent-id %)) (session-instances))
        current-id (tui-session/get-active-agent-id)
        numeric    (when-not (str/blank? args)
                     (try (Long/parseLong (str/trim args)) (catch Exception _ nil)))
        target     (cond
                     (str/blank? args)
                     (some #(when (= current-id (:agent-id %)) %) instances)

                     numeric
                     (when (<= 1 numeric (count instances))
                       (nth instances (dec numeric)))

                     :else
                     (let [target-id (keyword (str/trim args))]
                       (some #(when (= target-id (:agent-id %)) %) instances)))]
    (cond
      (empty? instances)
      (tui-session/emit! (ansi/warning "No agent running."))

      (and (not (str/blank? args)) (nil? target))
      (tui-session/emit! (ansi/warning (str "Instance not found: " (str/trim args)
                                            ". Use /agent switch to see instances.")))

      (<= (count instances) 1)
      (tui-session/emit! (ansi/warning "Cannot close the last agent instance. Use /agent new to replace it."))

      (= :running (:status @(:!state target)))
      (tui-session/emit! (ansi/warning (str (agent-id-str (:agent-id target))
                                            " is currently running. Wait for it to finish or cancel first.")))

      :else
      (let [target-id       (:agent-id target)
            closed-current? (= target-id current-id)
            others          (remove #(= target-id (:agent-id %)) instances)
            next-ag         (when closed-current? (first others))
            next-id         (:agent-id next-ag)]
        (when closed-current?
          (tui-session/detach-watches!))
        (try (.close ^java.io.Closeable target) (catch Exception _))
        (sessions/update-session! (sessions/active-idx)
                                  update :agent-instances
                                  (fn [insts] (vec (remove #(= target-id (:agent-id %)) insts))))
        (when closed-current?
          (tui-session/set-agent! next-ag next-id))
        (tui-session/emit!
         (str (ansi/success (str "Closed " (agent-id-str target-id)))
              (when closed-current?
                (str " " (ansi/muted (str "→ " (agent-id-str next-id)))))))))))

(defn- handle-agent-trace
  "Handle /agent trace [N] — show BT trace entries (N = last-N, default: all)."
  [subcmd-args]
  (let [last-n (when-not (str/blank? subcmd-args)
                 (try (parse-long (str/trim subcmd-args))
                      (catch Exception _ nil)))]
    (if last-n
      (show-traces :last-n last-n)
      (show-traces))))

(defn- handle-agent-command
  "Handle /agent command with subcommands: status, new, switch, close, trace."
  [args]
  (let [parts   (when-not (str/blank? args) (str/split (str/trim args) #"\s+" 2))
        subcmd  (first parts)
        subcmd-args (second parts)]
    (case subcmd
      ("status" nil) (agent-status!)
      "new"          (handle-agent-new subcmd-args)
      "switch"       (handle-agent-switch subcmd-args)
      "close"        (close-agent! subcmd-args)
      "trace"        (handle-agent-trace subcmd-args)
      ;; Unknown subcommand
      (tui-session/emit! (ansi/warning (str "Unknown: /agent " subcmd ". Use: status, new, switch, close, trace"))))))

;; ============================================================================
;; Prompt & Header (public — called from core)
;; ============================================================================

(defn parse-command
  "Classify an input line into one of three kinds:
     :slash  — slash-prefixed command (local TUI handler or agent fallback)
     :colon  — colon-prefixed command (direct internal tool invocation)
     :input  — regular user input to the active agent
   Returns [kind cmd args-str]:
     :slash/:colon → cmd includes the leading prefix, args-str is the rest
     :input        → cmd is nil, args-str is the untrimmed original input"
  [input]
  (cond
    (str/starts-with? input "/")
    (let [parts (str/split input #"\s+" 2)]
      [:slash (first parts) (str/trim (or (second parts) ""))])

    (str/starts-with? input ":")
    (let [parts (str/split input #"\s+" 2)]
      [:colon (first parts) (str/trim (or (second parts) ""))])

    :else
    [:input nil input]))

(defn draw-prompt!
  "Draw the input prompt with placeholder hint.
   Cursor positioned right after '> ', not at end of placeholder.
   Delegates to `tui-session/redraw-idle-prompt!` so the loop-top draw and the
   agent-suggestion hook share one source of truth for the idle prompt line."
  []
  (tui-session/redraw-idle-prompt!))

(defn emit-command-header!
  "Emit the full command as a user-input-style header before command output."
  [input]
  (tui-session/emit! (str "\n" (ansi/style (str "> " input) ansi/bold ansi/dim))))

;; ============================================================================
;; /memory — memory-agent shortcut
;; ============================================================================

(def ^:private memory-help
  "Usage:
  /memory                    — same as /memory stats
  /memory stats              — composite stats (db size, layer counts)
  /memory remember <content> — save the rest of the line as an L3 fact
  /memory consolidate        — LLM-driven L2 → L3 reduction over the current session
  /memory purge              — dry-run purge plan (no tombstoning)
  /memory purge --apply      — apply the purge plan (tombstones orphans)
  /memory verify <fact-id>   — challenge an L3 fact against fresh recall
  /memory correct <evidence> — user-authoritative correction; locates the
                                offending fact by recall on <evidence>")

(defn- emit-memory-result!
  "Render a memory-agent result map for the user."
  [r]
  (cond
    (instance? clojure.lang.Agent r)
    (tui-session/emit!
     (ansi/muted "memory-agent dispatched; result will surface in the agent's stream"))

    (and (map? r) (:error-message r))
    (tui-session/emit! (ansi/failure (:error-message r)))

    (and (map? r) (:status r))
    (tui-session/emit! (str (ansi/style (str "[memory-agent " (or (:answer r) "")) ansi/bold)
                            (ansi/style "]" ansi/bold)
                            "\nstatus: " (pr-str (:status r))
                            (when-let [c (:counts r)] (str "\ncounts: " (pr-str c)))))

    (and (map? r) (:answer r))
    (tui-session/emit! (:answer r))

    :else
    (tui-session/emit! (pr-str r))))

(defn- dispatch-memory-op!
  "Send `args-map` to memory-agent via call-tool and emit the result.
   Threads the active TUI agent so memory-agent inherits its
   :user-id / :session-id (required by the unified memory store)."
  [args-map]
  (tui-session/emit! (ansi/muted (str "→ memory-agent " (pr-str args-map))))
  (try
    (let [active (tui-session/get-active-agent)
          r (if active
              (agent/call-tool :memory-agent args-map :agent active)
              (agent/call-tool :memory-agent args-map))]
      (emit-memory-result! r))
    (catch Exception e
      (tui-session/emit! (ansi/failure (str "memory-agent error: " (.getMessage e)))))))

(defn- handle-memory-command
  "Handle /memory subcommands."
  [args]
  (let [parts (when-not (str/blank? args) (str/split (str/trim args) #"\s+" 2))
        subcmd (or (first parts) "stats")
        rest-args (second parts)]
    (case subcmd
      ("help" "--help" "-h")
      (tui-session/emit! memory-help)

      "stats"
      (dispatch-memory-op! {:op "stats"})

      "remember"
      (if (str/blank? rest-args)
        (tui-session/emit! (ansi/warning "Usage: /memory remember <content>"))
        (dispatch-memory-op! {:op "remember" :content rest-args :scope "user"}))

      "consolidate"
      (dispatch-memory-op! {:op "consolidate" :scope "session"})

      "purge"
      (let [apply? (and rest-args
                        (re-find #"(?i)--apply\b|--no-dry-run\b" rest-args))]
        (dispatch-memory-op! {:op "purge" :dry-run? (not apply?)}))

      "verify"
      (if (str/blank? rest-args)
        (tui-session/emit! (ansi/warning "Usage: /memory verify <fact-id>"))
        (dispatch-memory-op! {:op "verify-fact" :fact-id (str/trim rest-args)}))

      "correct"
      (if (str/blank? rest-args)
        (tui-session/emit! (ansi/warning "Usage: /memory correct <evidence>"))
        ;; The evidence is the corrected truth; memory-agent does the
        ;; recall to locate the offending fact.
        (dispatch-memory-op! {:op "correct" :evidence rest-args :query rest-args}))

      ;; Unknown subcommand
      (do (tui-session/emit! (ansi/warning (str "Unknown /memory subcommand: " subcmd)))
          (tui-session/emit! memory-help)))))

;; ============================================================================
;; /init — init-agent shortcut (BRAINYARD.md authoring & maintenance)
;; ============================================================================

(def ^:private init-help
  "Usage:
  /init                          — show BRAINYARD.md status; offer to seed
                                    from CLAUDE.md / AGENTS.md if missing
  /init <prompt>                 — update BRAINYARD.md per instruction
                                    (\"/init we use Polylith\")
  /init show                     — render both BRAINYARD.md files inline
  /init list-snapshots [N]       — list last N snapshots (default 10)
  /init reseed                   — re-import from CLAUDE.md / AGENTS.md,
                                    diff against current
  /init revert <snapshot-path>   — restore a snapshot (current is snapshotted first)
  /init --diff                   — dry-run: show the diff init-agent would
                                    propose, but don't write
  /init --scope :user|:project|:both
                                 — override the auto-scope choice (works
                                    with all forms above)")

(defn- parse-init-flags
  "Pull out --scope and --diff / --reseed flags from a free-form arg string.
   Returns {:scope kw :diff? bool :reseed? bool :rest <str without flags>}."
  [args]
  (if (str/blank? args)
    {:scope nil :diff? false :reseed? false :rest ""}
    (let [tokens (str/split (str/trim args) #"\s+")
          {:keys [scope diff? reseed? kept skip?]}
          (reduce
           (fn [{:keys [scope diff? reseed? kept skip?] :as st} tok]
             (cond
               skip?
               (assoc st :scope (keyword (str/replace tok #"^:" "")) :skip? false)

               (= tok "--scope")
               (assoc st :skip? true)

               (str/starts-with? tok "--scope=")
               (assoc st :scope (keyword (str/replace (subs tok 8) #"^:" "")))

               (= tok "--diff")    (assoc st :diff? true)
               (= tok "--reseed")  (assoc st :reseed? true)
               :else               (update st :kept conj tok)))
           {:scope nil :diff? false :reseed? false :kept [] :skip? false}
           tokens)]
      {:scope   scope
       :diff?   diff?
       :reseed? reseed?
       :rest    (str/join " " kept)})))

(defn- emit-init-result!
  "Render an init-agent (or init$*-command) result for the user."
  [r]
  (cond
    (instance? clojure.lang.Agent r)
    (tui-session/emit!
     (ansi/muted "init-agent dispatched; result will surface in the agent's stream"))

    (and (map? r) (:error-message r))
    (tui-session/emit! (ansi/failure (:error-message r)))

    (and (map? r) (:answer r))
    (tui-session/emit! (:answer r))

    (map? r)
    (tui-session/emit! (with-out-str (clojure.pprint/pprint r)))

    :else
    (tui-session/emit! (pr-str r))))

(defn- render-init-show
  "Pretty-print init$read output across both scopes (or one)."
  [r]
  (let [render (fn [scope-key]
                 (when-let [s (get r scope-key)]
                   (let [hdr (str "── " (str/upper-case (name scope-key))
                                  " (" (or (:path s) "no path") ")")]
                     (str (ansi/style hdr ansi/bold ansi/bright-cyan) "\n"
                          (if (:exists? s)
                            (str (ansi/muted (str "size: " (:size s) " B"
                                                  ", sections: "
                                                  (count (:sections s))))
                                 "\n\n"
                                 (:content s)
                                 "\n")
                            (str (ansi/muted "  (no BRAINYARD.md at this scope)") "\n"))))))]
    (->> [(render :project) (render :user)]
         (remove nil?)
         (str/join "\n"))))

(defn- render-init-snapshots
  "Render init$list-snapshots output as a numbered list."
  [r]
  (let [snaps (:snapshots r)]
    (if (empty? snaps)
      (ansi/muted "  (no snapshots)")
      (->> snaps
           (map-indexed
            (fn [i {:keys [ts scope reason path size-bytes]}]
              (str "  " (format "%2d" (inc i)) ". "
                   (ansi/style ts ansi/bright-white) " "
                   (ansi/style (name scope) ansi/bright-cyan) "  "
                   reason
                   (ansi/muted (str "  " size-bytes " B  " path)))))
           (str/join "\n")))))

(defn- handle-init-command
  "Handle /init with subcommands and flags. Shortcuts (show, list-snapshots,
   revert) bypass the LLM loop; everything else dispatches to init-agent."
  [args]
  (let [{:keys [scope diff? reseed? rest]} (parse-init-flags args)
        parts  (when-not (str/blank? rest) (str/split rest #"\s+" 2))
        subcmd (some-> (first parts) str/lower-case)
        rest-args (second parts)]
    (case subcmd
      ("help" "--help" "-h")
      (tui-session/emit! init-help)

      ("show" "read")
      (try
        (let [r (agent/invoke-tool :init$read :scope (or scope :both))]
          (tui-session/emit! (render-init-show r)))
        (catch Exception e
          (tui-session/emit! (ansi/failure (str "init$read error: " (.getMessage e))))))

      "list-snapshots"
      (try
        (let [limit (or (some-> rest-args str/trim parse-long) 10)
              r (agent/invoke-tool :init$list-snapshots
                                   :scope (or scope :both)
                                   :limit limit)]
          (tui-session/emit! (render-init-snapshots r)))
        (catch Exception e
          (tui-session/emit! (ansi/failure (str "init$list-snapshots error: " (.getMessage e))))))

      "revert"
      (if (str/blank? rest-args)
        (tui-session/emit!
         (ansi/warning "Usage: /init revert <snapshot-path>  (use /init list-snapshots first)"))
        (try
          (let [r (agent/invoke-tool :init$revert
                                     :snapshot-path (str/trim rest-args))]
            (emit-init-result! r))
          (catch Exception e
            (tui-session/emit! (ansi/failure (str "init$revert error: " (.getMessage e)))))))

      ;; Default: dispatch to init-agent with a plain-text prompt.
      ;; Leading "/" is reserved for agent system-commands — strip it here
      ;; and rephrase the no-args / flag-only cases as natural language.
      (let [question  (cond
                        (str/blank? args)
                        "Show me BRAINYARD.md status. If it is missing or empty, offer to seed from CLAUDE.md / AGENTS.md."

                        (and diff? (str/blank? rest))
                        "Dry-run only: show the diff init-agent would propose. Do not write."

                        (and reseed? (str/blank? rest))
                        "Re-import from CLAUDE.md / AGENTS.md. Show the diff against the current BRAINYARD.md."

                        :else rest)
            args-map  (cond-> {:question question}
                        scope   (assoc :scope scope)
                        diff?   (assoc :op :show)
                        reseed? (assoc :op :reseed))]
        (tui-session/emit! (ansi/muted (str "→ init-agent " (pr-str args-map))))
        (try
          ;; Thread the active TUI agent in so init-agent inherits its
          ;; :user-id / :session-id (required by the unified memory store).
          (let [active (tui-session/get-active-agent)
                r (if active
                    (agent/call-tool :init-agent args-map :agent active)
                    (agent/call-tool :init-agent args-map))]
            (emit-init-result! r))
          (catch Exception e
            (tui-session/emit! (ansi/failure (str "init-agent error: " (.getMessage e))))))))))

;; ============================================================================
;; Session Commands
;; ============================================================================

;; The persisted-session handlers are defined later in this file but referenced
;; from handle-session-command's subcommand dispatch — declare them forward.
(declare handle-tree-command handle-fork-command
         handle-persisted-list-command handle-show-command active-session-id)

(defn- handle-session-command
  "Handle /session subcommands.

   Two domains, distinct subcommands:
   - LIVE in-memory tabs: `tabs` (default), `switch <N>`, `new`, `close`, `rename`
   - PERSISTED on-disk sessions: `list`, `show`, `label`, `tree`, `fork`"
  [args]
  (let [parts (when-not (str/blank? args) (str/split (str/trim args) #"\s+" 2))
        subcmd (first parts)
        subcmd-args (second parts)]
    (cond
      ;; /session (no args) or /session tabs — list LIVE in-memory tabs
      (or (nil? subcmd) (= subcmd "tabs"))
      (tui-session/emit! (sessions/format-session-list))

      ;; /session list — list PERSISTED on-disk sessions (mirrors `by sessions
      ;; list`). Shared renderer with the CLI. (`list` was the old name for the
      ;; live-tab list — that is now `tabs`.)
      (= subcmd "list")
      (handle-persisted-list-command subcmd-args)

      ;; /session show <id> — full detail for one persisted session
      (= subcmd "show")
      (handle-show-command (or subcmd-args ""))

      ;; /session tree — persisted-on-disk session tree
      (= subcmd "tree")
      (handle-tree-command subcmd-args)

      ;; /session fork [label] — fork the active session on disk
      (= subcmd "fork")
      (handle-fork-command (or subcmd-args ""))

      ;; /session new [agent-id] — create new session
      (= subcmd "new")
      (let [agent-id (or (when-not (str/blank? subcmd-args) (keyword subcmd-args))
                         (tui-session/get-active-defagent-id)
                         :coact-agent)]
        (try
          (let [label (sessions/next-root-tab-label!)
                idx (sessions/create-session! {:label label :agent-id agent-id})]
            (sessions/switch-to! idx)
            (tui-session/emit! (ansi/success (str "Created session " idx " [" label "]"))))
          (catch Exception e
            (tui-session/emit! (ansi/failure (str "Failed to create session: " (.getMessage e)))))))

      ;; /session close [N] — close session N or current
      (= subcmd "close")
      (let [idx (if (str/blank? subcmd-args)
                  (sessions/active-idx)
                  (parse-long subcmd-args))]
        (if (nil? idx)
          (tui-session/emit! (ansi/warning "Usage: /session close [N]"))
          (let [non-output-count (count (remove #(= :output (:session-type %))
                                                (sessions/session-list)))]
            (if (and (<= non-output-count 1)
                     (not= :output (:session-type (sessions/get-session idx))))
              (tui-session/emit! (ansi/warning "Cannot close the last session."))
              (if (sessions/get-session idx)
                (do (sessions/close-session! idx)
                    (tui-session/emit! (ansi/muted (str "Closed session " idx ".")))
                    (tui-session/update-status-bar!))
                (tui-session/emit! (ansi/warning (str "Session " idx " not found."))))))))

      ;; /session rename <label> (alias: label) — rename the current tab AND
      ;; persist the label on disk so `by sessions list` / the resume picker
      ;; show the same name.
      (or (= subcmd "rename") (= subcmd "label"))
      (if (str/blank? subcmd-args)
        (tui-session/emit! (ansi/warning (str "Usage: /session " subcmd " <label>")))
        (let [label (str/trim subcmd-args)]
          (sessions/rename-session! label)
          (when-let [sid (active-session-id)]
            (try (persist/set-session-label! (name sid) label) (catch Throwable _)))
          (tui-session/emit! (ansi/muted (str "Renamed to: " label)))
          (tui-session/update-status-bar!)))

      ;; /session N (or /session switch N) — switch to LIVE tab N
      :else
      (if-let [idx (parse-long (if (= subcmd "switch") (or subcmd-args "") subcmd))]
        (if (sessions/get-session idx)
          (do (sessions/switch-to! idx)
              (tui-session/update-status-bar!)
              (tui-session/emit! (ansi/muted (str "Switched to session " idx "."))))
          (tui-session/emit! (ansi/warning (str "Session " idx " not found."))))
        (tui-session/emit! (ansi/warning
                            (str "Usage: /session [tabs|list|show <id>|label <text>|"
                                 "tree|fork|new|close|switch N]")))))))

;; ============================================================================
;; Session-tree commands (/tree /fork /name)
;;
;; These operate on the persisted session tree (`agent-tui-persist`'s
;; meta.edn per session) — independent from the in-process multi-session
;; manager (`sessions.clj`) which tracks open sessions in this UI.
;; ============================================================================

(defn- active-session-id
  "Return the persisted session-id of the currently-active agent, or nil."
  []
  (when-let [ag (tui-session/get-active-agent)]
    (agent/session-id ag)))

(defn- handle-persisted-list-command
  "List PERSISTED on-disk sessions (mirrors `by sessions list`) using the
   shared `session-summary` renderer. The active session is marked ▸."
  [_args]
  (let [rows   (ssum/enriched-summaries)
        active (some-> (active-session-id) name)]
    (tui-session/emit!
     (if (empty? rows)
       (ansi/muted "No persisted sessions.")
       (str (ansi/header "Persisted sessions") "\n"
            (str/join "\n" (ssum/format-table rows {:ansi? true :active active})))))))

(defn- handle-show-command
  "Show full detail for one persisted session (shared with `by sessions show`)."
  [args]
  (let [id (when-not (str/blank? args) (str/trim args))]
    (cond
      (nil? id)
      (tui-session/emit! (ansi/warning "Usage: /session show <session-id>"))

      (not (some #{id} (persist/list-sessions)))
      (tui-session/emit! (ansi/failure (str "Session not found: " id)))

      :else
      (let [row (first (filter #(= id (:session-id %)) (ssum/enriched-summaries)))]
        (tui-session/emit! (str/join "\n" (ssum/format-detail row {:ansi? true})))))))

(defn- handle-tree-command
  "Render the session tree (parents + forks across all persisted
   sessions) to scrollback. Marks the currently-active session with ▸."
  [_args]
  (let [active (some-> (active-session-id) name)
        n      (count (:nodes (persist/session-tree)))
        title  (ansi/header "Session tree")
        help   (ansi/muted (str "  " n " session(s); ▸ = active"))]
    (tui-session/emit! (str title "\n" help "\n"
                            (str/join "\n" (ssum/format-tree {:active active}))))))

(defn- handle-fork-command
  "Fork the active session at its current event count. Optional positional
   arg becomes the child's :label. Emits the new session-id on success."
  [args]
  (if-let [parent (active-session-id)]
    (try
      (let [label    (when-not (str/blank? args) (str/trim args))
            new-id   (str (name parent) "-fork-" (System/currentTimeMillis))
            child    (persist/fork-session! parent new-id (cond-> {} label (assoc :label label)))]
        (tui-session/emit!
         (str (ansi/success "Forked ")
              (ansi/header (:id child))
              (when label (str " — " (ansi/iter-label label)))
              (ansi/muted (str "  (parent: " (name parent)
                               ", at event " (:fork-point child) ")")))))
      (catch Exception e
        (tui-session/emit! (ansi/failure (str "Fork failed: " (.getMessage e))))))
    (tui-session/emit! (ansi/warning "No active session to fork"))))

;; ============================================================================
;; Run pause/resume (cooperative — checked at BT iteration boundaries)
;; ============================================================================

(defn- handle-pause-run-command
  "Cooperatively pause the current BT run on the active agent. The BT
   picks the signal up at the next :condition / :action / iteration
   boundary and parks on the runtime's pause condition. /resume
   unparks it; Ctrl-C still cancels even from a paused state."
  [_args]
  (if-let [ag (tui-session/get-active-agent)]
    (try
      (agent/pause-run (:!state ag))
      (tui-session/emit! (ansi/muted "[paused] (use /resume to continue)"))
      (try (tui-session/update-status-bar!) (catch Throwable _))
      (catch Throwable t
        (tui-session/emit! (ansi/failure (str "pause-run failed: " (.getMessage t))))))
    (tui-session/emit! (ansi/warning "No TUI agent running."))))

(defn- handle-resume-run-command
  "Unpark a paused BT run on the active agent."
  [_args]
  (if-let [ag (tui-session/get-active-agent)]
    (try
      (agent/resume-run (:!state ag))
      (input/hide-pause-tips!)
      (tui-session/emit! (ansi/muted "[resumed]"))
      (try (tui-session/update-status-bar!) (catch Throwable _))
      (catch Throwable t
        (tui-session/emit! (ansi/failure (str "resume-run failed: " (.getMessage t))))))
    (tui-session/emit! (ansi/warning "No TUI agent running."))))

;; ============================================================================
;; Queue Command (consolidated)
;; ============================================================================

(defn- active-input-queue
  "The input queue for the ACTIVE tab's root agent (or nil). Input queues are
   per-root now (tabs run concurrently), so /queue operates on the current
   tab's queue. requiring-resolve avoids a circular require with core."
  []
  (let [queues  @(requiring-resolve 'ai.brainyard.agent-tui.core/!input-queues)
        root-id (requiring-resolve 'ai.brainyard.agent-tui.session/root-agent-id)
        ag      (tui-session/get-active-agent)]
    (get @queues (when ag (root-id ag)))))

(defn- handle-queue-command
  "Handle /queue with subcommands: list, cancel. Operates on the ACTIVE tab's
   per-root input queue."
  [args]
  (let [parts   (when-not (str/blank? args) (str/split (str/trim args) #"\s+" 2))
        subcmd  (first parts)
        subcmd-args (or (second parts) "")]
    (case subcmd
      ("list" nil)
      (let [!queue (active-input-queue)]
        (if (and !queue (seq (:items @!queue)))
          (let [items (:items @!queue)]
            (tui-session/emit!
             (str/join "\n"
                       (map-indexed
                        (fn [i {:keys [id input status]}]
                          (str "  " (inc i) ". [" (name status) "] "
                               (if (> (count input) 60)
                                 (str (subs input 0 60) "...")
                                 input)
                               (ansi/muted (str " (" (subs (str id) 0 8) ")"))))
                        items))))
          (tui-session/emit! (ansi/muted "No items in queue."))))

      "cancel"
      (let [!queue (active-input-queue)]
        (if-not !queue
          (tui-session/emit! (ansi/muted "No queue active."))
          (if (or (str/blank? subcmd-args) (= subcmd-args "all"))
            (let [n (agent/cancel-all-queued! !queue)]
              (tui-session/emit! (str "Cancelled " n " queued item(s).")))
            (let [item-id (try (java.util.UUID/fromString subcmd-args) (catch Exception _ nil))]
              (if-not item-id
                (tui-session/emit! (ansi/warning "Usage: /queue cancel [all|<uuid>]"))
                (if (agent/cancel-item! !queue item-id)
                  (tui-session/emit! (str "Cancelled item " (subs subcmd-args 0 8) "."))
                  (tui-session/emit! (ansi/warning "Item not found or already processing."))))))))

      ;; Unknown → show queue
      (handle-queue-command nil))))

;; ============================================================================
;; Generic Tool-Matching Dispatch
;; ============================================================================

(defn- resolve-tool-command
  "Try to match a slash command name against !tool-defs.
   Checks :id (as keyword) and :aliases (vector of strings) metadata.
   Returns {:tool-def ... :tool-id ...} or nil."
  [cmd-name]
  (let [kw (keyword cmd-name)
        defs @agent/!tool-defs]
    ;; Direct ID match
    (or (when-let [td (get defs kw)]
          {:tool-def td :tool-id kw})
        ;; Alias match
        (some (fn [[id td]]
                (when (some #(= cmd-name %) (get-in td [:meta :aliases]))
                  {:tool-def td :tool-id id}))
              defs))))

(defn- format-inputs-schema
  "Render a tool-def's :input-schema entries as indented name/desc lines (no header)."
  [input-schema]
  (str/join
   "\n"
   (for [entry (agent/malli-map-entries input-schema)
         :let [k           (agent/malli-map-entry-key entry)
               entry-props (agent/malli-map-entry-props entry)
               raw-schema  (agent/malli-map-entry-schema entry)
               props       (when (and (vector? raw-schema) (map? (second raw-schema)))
                             (second raw-schema))]]
     (str "  :" (name k)
          (when (:desc props) (str "  — " (:desc props)))
          (when (:optional entry-props) "  (optional)")))))

(defn- no-required-inputs?
  "True when an :input-schema has no required entries — empty [:map], or every
   entry carries `{:optional true}` in its entry props."
  [input-schema]
  (let [entries (agent/malli-map-entries input-schema)]
    (or (nil? entries)
        (every? (fn [entry]
                  (true? (:optional (agent/malli-map-entry-props entry))))
                entries))))

(defn- tokenize-args
  "Tokenize a CLI-style argument string. Whitespace separates tokens;
   substrings enclosed in double quotes preserve whitespace and the quote
   characters are stripped. Unterminated quotes absorb the rest of the string."
  [^String s]
  (let [len (count s)
        sb  (StringBuilder.)]
    (loop [i 0
           tokens []
           in-quote? false
           has-content? false]
      (if (>= i len)
        (cond-> tokens has-content? (conj (.toString sb)))
        (let [c (.charAt s i)]
          (cond
            (= c \")
            (recur (inc i) tokens (not in-quote?) true)

            (and (not in-quote?) (Character/isWhitespace c))
            (if has-content?
              (let [tok (.toString sb)]
                (.setLength sb 0)
                (recur (inc i) (conj tokens tok) false false))
              (recur (inc i) tokens false false))

            :else
            (do (.append sb c)
                (recur (inc i) tokens in-quote? true))))))))

(defn- tokens->kwargs
  "Parse tokens as alternating `:key value` pairs (Clojure kwargs style).
   A key may be written bare (`foo`) or colon-prefixed (`:foo`); both yield
   the keyword `:foo`. A dangling key (odd token count) is paired with the
   empty string so Malli surfaces the missing-value error rather than the
   caller seeing a confusing NPE."
  [tokens]
  (let [->kw (fn [^String s]
               (keyword (if (str/starts-with? s ":") (subs s 1) s)))]
    (into {}
          (map (fn [[k v]] [(->kw k) (or v "")]))
          (partition-all 2 tokens))))

(defn- coerce-kwargs
  "Coerce string values in `kw-args` to the JSON-schema types implied by
   `input-schema`. CLI tokenization always produces strings; Malli :int / :double /
   :boolean / :vector schemas reject those. We mirror the same coercion that
   `invoke-tool` runs internally so the pre-call Malli check sees typed
   values."
  [input-schema kw-args]
  (if-not (seq (agent/malli-map-entries input-schema))
    kw-args
    (let [props (agent/schema->type input-schema)]
      (reduce-kv (fn [m k v]
                   (assoc m k (agent/coerce-value v (get-in props [k :type]))))
                 {} kw-args))))

(defn- record-console-activity!
  "Record a finished colon-command interaction as a live artifact on the active
   agent, so the next turn's ## Live Artifacts reflects what the user inspected.
   No-op when there is no active agent or :enable-console-activity is off; never
   throws into the dispatch path."
  [active-agent tool-id args result ok?]
  (when (and active-agent (agent/get-config active-agent :enable-console-activity))
    (try
      (agent/record-console-activity!
       active-agent
       {:cmd          (str ":" (name tool-id))
        :args         args
        :result       result
        :ok?          ok?
        :max-entries  (agent/get-config active-agent :console-activity-max-entries)
        :result-chars (agent/get-config active-agent :console-activity-result-chars)})
      (catch Exception _ nil))))

(defn- handle-tool-invoke
  "Colon-command dispatch: parse args as Clojure kwargs
   (`:key value :key value ...`) against the tool's :input-schema, bind the
   active agent as `*current-agent*`, and invoke via `agent/invoke-tool`. Bare
   keys (`foo`) are also accepted; double-quoted substrings keep whitespace.
   String values are coerced to the schema's declared types before
   validation (CLI tokens are always strings).
   Preconditions:
     - args validate against the :input-schema Malli schema (else: humanized error)
     - active agent, if any, must be in :idle state (colon commands run
       synchronously and would race with an in-flight BT iteration)."
  [tool-id tool-def args]
  (let [input-schema (get-in tool-def [:meta :input-schema] [:map])
        tokens       (tokenize-args (or args ""))
        kw-args      (coerce-kwargs input-schema (tokens->kwargs tokens))
        err          (when (seq (agent/malli-map-entries input-schema))
                       (m/explain (agent/inputs->malli-map-schema input-schema) kw-args))
        active-agent (tui-session/get-active-agent)
        status       (when active-agent (:status @(:!state active-agent)))]
    (cond
      err
      (do
        (tui-session/emit! (ansi/failure (str "Invalid args for :" (name tool-id))))
        (tui-session/emit! (ansi/muted (pr-str (me/humanize err))))
        (tui-session/emit! (ansi/muted (str "Expected inputs (pass as :key value pairs):\n"
                                            (format-inputs-schema input-schema)))))

      (and active-agent (not= status :idle))
      (do
        (tui-session/emit! (ansi/failure
                            (format "Cannot invoke :%s — agent is %s."
                                    (name tool-id) (name (or status :unknown)))))
        (tui-session/emit! (ansi/muted
                            "Colon commands run synchronously; wait for the active agent to return to :idle.")))

      :else
      (try
        (let [invoke! #(apply agent/invoke-tool tool-id (mapcat identity kw-args))
              result  (if active-agent
                        (agent/with-agent active-agent (invoke!))
                        (invoke!))]
          (tui-session/emit! (with-out-str (clojure.pprint/pprint result)))
          (record-console-activity! active-agent tool-id args result true))
        (catch Exception e
          (tui-session/emit! (ansi/failure (str "Error: " (.getMessage e))))
          (record-console-activity! active-agent tool-id args (.getMessage e) false))))))

(defn- handle-tool-dispatch
  "Generic dispatch for slash commands that match a registered tool.
   Enqueues `call <tool-name> <args>` to the active agent so it routes the
   request through the normal tool-invocation pipeline.
   When args is blank:
     - tool declares no :input-schema entries → enqueue with no args (tool takes none)
     - tool declares :input-schema entries    → emit Usage + schema hint (don't enqueue)"
  [enqueue-fn tool-id tool-def args]
  (let [{:keys [type]} tool-def
        tmeta        (:meta tool-def)
        desc         (:description tmeta)
        input-schema (:input-schema tmeta)]
    ;; Show matched tool header
    (tui-session/emit!
     (str (ansi/style (str "[" (name tool-id) "]") ansi/bold)
          " " (ansi/muted (or desc ""))))
    (case type
      (:tool :command :skill :agent)
      (cond
        (not (str/blank? args))
        (try
          (enqueue-fn (str "call directly as a tool: " (name tool-id) " for " args))
          (catch Exception e
            (tui-session/emit! (ansi/failure (str "Error: " (.getMessage e))))))

        (no-required-inputs? input-schema)
        (try
          (enqueue-fn (str "call directly as a tool: " (name tool-id)))
          (catch Exception e
            (tui-session/emit! (ansi/failure (str "Error: " (.getMessage e))))))

        :else
        (do
          (tui-session/emit! (ansi/muted (str "Usage: /" (name tool-id) " <args> or <question>")))
          (tui-session/emit! (ansi/muted (str "Inputs:\n" (format-inputs-schema input-schema))))))

      ;; Unknown type
      (tui-session/emit! (ansi/warning (str "Unsupported tool type: " type))))))

;; ============================================================================
;; Input Dispatch (public — called from core)
;; ============================================================================

(defn handle-input-line
  "Process a single input line (command dispatch or question).
   `enqueue-fn` is the function to call for non-command input (e.g. enqueue-input! from core).
   Returns :quit to exit, :continue to keep looping."
  [enqueue-fn input reader]
  (let [[kind cmd args] (parse-command input)]
    (cond
      ;; An OAuth paste flow waiting for the code? This line IS the code —
      ;; deliver it to the blocked login instead of dispatching it.
      (oauth-render/consume-code! input) :continue

      (= :colon kind)
      ;; Colon-command: direct invoke-tool, bypasses the active agent.
      (let [cmd-name (subs cmd 1)]
        (if-let [{:keys [tool-id tool-def]} (resolve-tool-command cmd-name)]
          (do (emit-command-header! input)
              (handle-tool-invoke tool-id tool-def args)
              :continue)
          (do (tui-session/emit! (ansi/warning (str "Unknown tool: " cmd)))
              :continue)))
      :else
      ;; :slash or :input — existing dispatch (cmd is slash-string or nil).
      (case cmd
        "/quit"         (do ((requiring-resolve 'ai.brainyard.agent-tui.core/stop!)) :quit)
        "/status"       (do (emit-command-header! input) (status) :continue)
        "/history"      (do (emit-command-header! input) (history) :continue)
        "/clear"        (do (emit-command-header! input) (clear!) :continue)
        "/compact"      (do (emit-command-header! input) (compact-cmd args) :continue)
        "/todo"         (do (emit-command-header! input) (todo) :continue)
        "/usage"        (do (emit-command-header! input) (usage args) :continue)
        "/display-format" (do (emit-command-header! input) (handle-display-format-command args) :continue)
        "/model"        (do (emit-command-header! input) (handle-model-command args reader) :continue)
        "/config"       (do (emit-command-header! input) (handle-config-command args) :continue)
        "/init"         (do (emit-command-header! input) (handle-init-command args) :continue)
        "/effort"       (do (emit-command-header! input) (handle-effort-command args) :continue)
        "/help"         (do (emit-command-header! input) (tui-session/emit! (fmt/format-help)) :continue)
        "/continue"     (do (emit-command-header! input) (continue-agent args) :continue)
        "/task"         (do (emit-command-header! input) (handle-task-command args) :continue)
        "/allow-path"   (do (emit-command-header! input) (permissions/handle-allow-path-command args) :continue)
        "/capture"      (do (emit-command-header! input) (capture-cmd args) :continue)
        "/sandbox"      (do (emit-command-header! input) (handle-sandbox-command args) :continue)
        "/mcp"          (do (emit-command-header! input) (try (handle-mcp-command args) (catch Exception e (tui-session/emit! (ansi/failure (str "MCP error: " (.getMessage e)))))) :continue)
        "/login"        (do (emit-command-header! input) (handle-login-command args) :continue)
        "/logout"       (do (emit-command-header! input) (handle-logout-command args) :continue)
        "/agent"        (do (emit-command-header! input) (handle-agent-command args) :continue)
        "/session"      (do (emit-command-header! input) (handle-session-command args) :continue)
        "/memory"       (do (emit-command-header! input) (handle-memory-command args) :continue)
        "/pause"        (do (emit-command-header! input) (handle-pause-run-command args) :continue)
        "/resume"       (do (emit-command-header! input) (handle-resume-run-command args) :continue)
        "/queue"        (do (emit-command-header! input) (handle-queue-command args) :continue)
        "/activity"     (do (emit-command-header! input) (side-pane-cmd/handle-activity-command args))
        "/log"          (do (emit-command-header! input) (side-pane-cmd/handle-log-command args))
        "/scrollback"   (do (emit-command-header! input) (side-pane-cmd/handle-scrollback-command args))
        "/popup"        (do (emit-command-header! input) (side-pane-cmd/handle-popup-command args))
        ;; Fallback: try tool-matching dispatch or regular input
        (if cmd
          ;; Unrecognized slash command — try matching against !tool-defs
          (let [cmd-name (subs cmd 1)]
            (if-let [{:keys [tool-id tool-def]} (resolve-tool-command cmd-name)]
              (do (emit-command-header! input)
                  (handle-tool-dispatch enqueue-fn tool-id tool-def args)
                  :continue)
              (do (tui-session/emit! (ansi/warning (str "Unknown command: " cmd)))
                  :continue)))
          ;; No slash — regular input
          (if (str/blank? input)
            :continue
            (do (try
                  (enqueue-fn input)
                  (catch Exception e
                    (tui-session/emit! (ansi/failure (str "Error: " (.getMessage e))))))
                :continue)))))))
