;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui.config-wizard
  "Interactive bootstrap for `bb tui config`.

   Three-phase pipeline (see docs/design/bootstrapping-design.md):
     1. DETECT   — full environment scan via env-detect (pure read)
     2. BOOTSTRAP — pick a workable provider via the fallback ladder;
                    install/pull only with explicit consent. Guarantees a
                    reachable LLM at exit, or stops honestly with a stub.
     3. HANDOFF  — (optional) offer to enter config-agent for richer
                    LLM-mediated configuration; otherwise exit.

   `--auto` runs the same pipeline without prompts using documented defaults."
  (:refer-clojure :exclude [run!])
  (:require [ai.brainyard.agent-tui.bootstrap :as boot]
            [ai.brainyard.agent-tui.bootstrap-driver :as driver]
            [ai.brainyard.agent-tui.helpers :as helpers]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [ai.brainyard.env-detect.interface :as env]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader]))

;; ============================================================================
;; Prompt helpers (gated by `--auto`)
;; ============================================================================

(def ^:private ^BufferedReader reader (BufferedReader. (InputStreamReader. System/in)))

(defn- prompt-line [prompt]
  (print prompt)
  (flush)
  (when-let [line (.readLine reader)]
    (str/trim line)))

(defn- prompt-select
  [question options & {:keys [default auto?] :or {default 1 auto? false}}]
  (println)
  (println (ansi/style question ansi/bold ansi/bright-cyan))
  (doseq [[i {:keys [label description]}] (map-indexed vector options)]
    (let [idx (inc i)]
      (println (str "  " (ansi/style (str "[" idx "]") ansi/bold) " " label
                    (when (= idx default) (ansi/muted " (current)"))
                    (when description (str " — " (ansi/muted description)))))))
  (if auto?
    (nth options (dec default))
    (loop []
      (let [input (prompt-line (str "Select [1-" (count options) "] (default: " default "): "))]
        (cond
          (or (nil? input) (str/blank? input))
          (nth options (dec default))

          :else
          (if-let [n (parse-long input)]
            (if (and (>= n 1) (<= n (count options)))
              (nth options (dec n))
              (do (println (ansi/warning (str "  Invalid. Enter 1-" (count options) ".")))
                  (recur)))
            (do (println (ansi/warning "  Enter a number."))
                (recur))))))))

(defn- prompt-confirm
  [question & {:keys [default auto?] :or {default true auto? false}}]
  (let [hint (if default "[Y/n]" "[y/N]")]
    (if auto?
      default
      (let [input (prompt-line (str question " " hint ": "))]
        (cond
          (or (nil? input) (str/blank? input)) default
          (contains? #{"y" "yes"} (str/lower-case input)) true
          (contains? #{"n" "no"} (str/lower-case input)) false
          :else default)))))

(defn- prompt-string
  [question & {:keys [default auto?] :or {auto? false}}]
  (let [hint (when default (str " [" default "]"))]
    (if auto?
      default
      (let [input (prompt-line (str question hint ": "))]
        (if (or (nil? input) (str/blank? input))
          default
          input)))))

(defn- section-header [title]
  (println)
  (println (ansi/style (str "--- " title " ---") ansi/bold)))

;; ============================================================================
;; Phase 3 deterministic-defaults steps — preserved from the prior wizard,
;; gated by `auto?` so --auto applies defaults without prompts.
;; ============================================================================

(defn- step-executables [_existing-config _opts]
  (section-header "Available Executables")
  (println)
  (let [executables (env/detect-executables)
        found       (filterv :available? executables)
        missing     (filterv (complement :available?) executables)]
    (doseq [{:keys [name path version]} found]
      (println (str "  " (ansi/style "[ok]" ansi/bright-green) " "
                    (format "%-12s" name)
                    (ansi/muted (str path (when version (str " (" version ")")))))))
    (doseq [{:keys [name]} missing]
      (println (str "  " (ansi/style "[--]" ansi/dim) " "
                    (format "%-12s" name)
                    (ansi/muted "not found"))))
    (println (str (count found) " of " (count executables) " executables found."))
    (into {} (map (fn [{:keys [name path]}] [(keyword name) path]) found))))

(defn- step-sandbox [existing-config opts]
  (section-header "Sandbox Environment")
  (let [sandbox-info (env/detect-sandbox-environment)
        os-info      (env/detect-os)
        {:keys [sandbox-type details terminal]} sandbox-info]
    (println (str "  Environment: " (ansi/style (name sandbox-type) ansi/bold)))
    (println (str "  OS: " (:name os-info) " " (:version os-info) " (" (:arch os-info) ")"))
    (let [current-mode (get-in existing-config [:environment :sandbox-mode] :standard)
          mode-options [{:label "permissive" :value :permissive :description "Allow all operations"}
                        {:label "standard"   :value :standard   :description "Ask for dangerous ops (recommended)"}
                        {:label "restricted" :value :restricted :description "Deny by default"}]
          default-idx  (or (some (fn [[i opt]] (when (= (:value opt) current-mode) (inc i)))
                                 (map-indexed vector mode-options))
                           2)
          selected     (:value (prompt-select "Set sandbox mode:" mode-options
                                              :default default-idx :auto? (:auto opts)))]
      {:sandbox-type sandbox-type
       :sandbox-mode selected
       :os           os-info})))

(defn- step-permissions [existing-config dirs opts]
  (section-header "Script Execution Permissions")
  (let [current-dirs (get-in existing-config [:permissions :allowed-dirs]
                             (agent/default-allowed-dirs dirs))
        project-dir  (:project-dir dirs)
        dirs-with-project
        (if (and project-dir (not (some #{project-dir} current-dirs)))
          (if (prompt-confirm (str "Add project directory? (" project-dir ")")
                              :auto? (:auto opts))
            (conj current-dirs project-dir)
            current-dirs)
          current-dirs)
        current-mode (get-in existing-config [:permissions :mode] :ask-each-time)
        mode-options [{:label "auto-approve"   :value :auto-approve   :description "Auto-approve within allowed dirs"}
                      {:label "ask-each-time"  :value :ask-each-time  :description "Ask for each operation (recommended)"}
                      {:label "deny-by-default" :value :deny-by-default :description "Deny unless explicitly allowed"}]
        default-idx  (or (some (fn [[i opt]] (when (= (:value opt) current-mode) (inc i)))
                               (map-indexed vector mode-options))
                         2)
        selected     (:value (prompt-select "Permission mode:" mode-options
                                            :default default-idx :auto? (:auto opts)))]
    {:mode         selected
     :allowed-dirs (vec (distinct dirs-with-project))}))

(defn- step-agent-defaults [existing-config opts]
  (section-header "Agent Defaults")
  (let [agent-options [{:label "coact-agent" :value :coact-agent
                        :description "CoAct (default — three-channel tool/code/answer loop)"}
                       {:label "main-agent" :value :main-agent
                        :description "Front-door router (picks the right specialist per question shape; CoAct-based)"}
                       {:label "react-agent" :value :react-agent
                        :description "ReAct (tool calling, single-step by default)"}]
        current-agent (get-in existing-config [:agent :default-agent] :coact-agent)
        agent-default-idx (or (some (fn [[i opt]] (when (= (:value opt) current-agent) (inc i)))
                                    (map-indexed vector agent-options))
                              1)
        selected-agent (:value (prompt-select "Default agent:" agent-options
                                              :default agent-default-idx :auto? (:auto opts)))
        current-max-iter (get-in existing-config [:agent :max-iterations] 100)
        max-iter-str     (prompt-string "Max iterations"
                                        :default (str current-max-iter) :auto? (:auto opts))
        max-iter         (or (parse-long max-iter-str) current-max-iter)]
    {:default-agent  selected-agent
     :max-iterations max-iter}))

(defn- step-mcp-servers [existing-config opts]
  (section-header "MCP Servers")
  (let [current-servers (get-in existing-config [:mcp :servers] {})]
    (if (seq current-servers)
      (do
        (println (str (count current-servers) " MCP server(s) configured."))
        (if (prompt-confirm "Keep current MCP configuration?" :auto? (:auto opts))
          {:servers current-servers}
          {:servers {}}))
      {:servers {}})))

;; ============================================================================
;; Phase 1 — DETECT
;; ============================================================================

(defn- run-detect-phase! []
  (println)
  (println (ansi/style "Detecting environment..." ansi/dim))
  (env/detect-all))

(defn- print-detection-summary
  "Show the user what we found AND what they could enable. Runs after DETECT,
   before the ladder picks. Goal: make the env-var menu discoverable so users
   on rung (e) (pull 7 GB) know they could set an API key instead."
  [{:keys [providers ollama-install] :as _detection}]
  (let [found    (filter :available? providers)
        api-key  #(= :api-key (:method %))
        missing-keys (->> providers (filter api-key) (remove :available?))]
    (println)
    (println (ansi/style "Detected providers:" ansi/bold))
    (if (seq found)
      (doseq [{:keys [provider method detail]} found]
        (println (str "  " (ansi/style "[ok]" ansi/bright-green) " "
                      (format "%-12s" (name provider))
                      (ansi/muted (str "(" (name method) ") " detail)))))
      (println (ansi/muted "  (none reachable)")))
    (when (:installed? ollama-install)
      (let [n (count (:pulled-models ollama-install))]
        (println (str "  " (ansi/style "[ok]" ansi/bright-green) " "
                      (format "%-12s" "ollama")
                      (ansi/muted (str "installed, " n " model(s) pulled, daemon "
                                       (if (:daemon-running? ollama-install)
                                         "running" "not running")))))))
    (when (seq missing-keys)
      (println)
      (println (ansi/style "Set any of these env vars to enable more providers:" ansi/dim))
      (doseq [{:keys [provider env-var]} missing-keys]
        (println (str "  " (ansi/muted (format "export %s=...     # %s"
                                               env-var (name provider))))))
      (println (ansi/muted "  (put in ~/.zshenv / ~/.bashrc for persistence, or .env at repo root)")))))

;; ============================================================================
;; Action handlers (Phase 2 side effects)
;; ============================================================================

;; The wizard injects interactive prompts via callbacks; the driver does the
;; actual work. Keep the same UX as before — pretty banners, ANSI progress —
;; just delivered through callbacks instead of inline println.

(defn- wizard-on-status
  "Status callback: pretty-print each driver event the wizard cares about."
  [event & [data]]
  (case event
    :install-manual-required
    (println (ansi/warning "  manual install required"))

    :install-declined
    (println (ansi/muted "  user declined install"))

    :install-ok
    (println (ansi/style "  Installed." ansi/bright-green))

    :install-failed
    (println (ansi/warning (str "  Install failed: " (:detail data))))

    :daemon-starting
    (do (println) (println "Starting Ollama daemon..."))

    :daemon-ok
    (println (ansi/style (str "  " (:detail data)) ansi/bright-green))

    :daemon-failed
    (println (ansi/warning (str "  " (:detail data))))

    :apple-fm-starting
    (do (println) (println "Starting apfel (Apple FM) daemon..."))

    :apple-fm-ok
    (println (ansi/style (str "  " (:detail data)) ansi/bright-green))

    :apple-fm-failed
    (println (ansi/warning (str "  " (:detail data))))

    :pull-starting
    (do (println) (println (str "Pulling model: " (ansi/style (:model data) ansi/bold))))

    :pull-ok
    (do (println) (println "  Pull complete."))

    :pull-failed
    (println (ansi/warning (str "  Pull failed: " (:detail data))))

    :smoke-starting
    (do (println)
        (println (str "Smoke-testing " (name (:provider data)) "/"
                      (or (:model data) "default") "..."))
        (when (= :ollama (:provider data))
          (println (ansi/muted "  (First call may take 30-45s while the model loads.)"))))

    :smoke-ok
    (println (ansi/style (str "  OK (" (:latency-ms data) "ms)") ansi/bright-green))

    :smoke-failed
    (println (ansi/warning (str "  Failed: " (:error data))))

    nil))

(defn- wizard-install-hint [{:keys [command url note]}]
  (println)
  (println (ansi/style "Ollama is required for the local-model fallback." ansi/bold))
  (when note    (println (str "  " note)))
  (when command (println (str "  Command:   " (ansi/style command ansi/bright-cyan))))
  (println (str "  Reference: " url)))

(defn- wizard-callbacks [opts]
  {:on-status         wizard-on-status
   :on-install-hint   wizard-install-hint
   :on-install-confirm
   (fn [{:keys [command auto-installable?]}]
     (cond
       (not auto-installable?) false
       (:auto opts)            true
       :else                   (prompt-confirm (str "Run `" command "` now?")
                                               :auto? false)))
   :on-pull-progress
   (let [last-pct (volatile! -1)]
     (fn [{:keys [line percent phase]}]
       (case phase
         :downloading
         (cond
           (and percent (not= percent @last-pct))
           (do (vreset! last-pct percent)
               (print (str "\r  " (format "%3d" percent) "% — " line))
               (flush))

           (and (nil? percent) (re-find #"^pulling|^verifying|^writing|^success" line))
           (do (println) (println (str "  " line))))
         :done nil)))})

(defn- execute-action! [action opts]
  (driver/execute-action! action (wizard-callbacks opts)))

;; ============================================================================
;; Bootstrap log
;; ============================================================================

(defn- log-path [dirs opts]
  (or (:log opts)
      (str (agent/user-config-dir dirs) "/bootstrap-log.edn")))

(defn- write-bootstrap-log! [path entry]
  (try
    (let [old (when (.exists (io/file path))
                (try (edn/read-string (slurp path)) (catch Exception _ {})))
          rotated (boot/rotate-log (or old {}) entry)]
      (io/make-parents path)
      (spit path (with-out-str (clojure.pprint/pprint rotated))))
    (catch Exception e
      (println (ansi/warning (str "  (warning: failed to write bootstrap-log: " (.getMessage e) ")"))))))

;; ============================================================================
;; Next-steps text for rung (g)
;; ============================================================================

(defn- next-steps-for-stop [detection]
  (let [{:keys [huggingface? ollama?]} (:network detection)
        os (some-> detection :os :name)]
    (cond-> []
      (not (some :available? (:providers detection)))
      (conj "Set an API key in your environment, e.g.:"
            "    export ANTHROPIC_API_KEY=sk-..."
            "  Then re-run: bb tui config")

      (not (or huggingface? ollama?))
      (conj "Network egress to huggingface.co / ollama.com appears blocked."
            "  Configure a proxy or use a profile that does not require pulls.")

      (and os (str/includes? (str/lower-case os) "windows"))
      (conj "Windows is not auto-installable yet — download Ollama manually from"
            "  https://ollama.com/download"))))

;; ============================================================================
;; Phase 2 — BOOTSTRAP
;; ============================================================================

(defn- pick-provider
  "Rung (b) only: if multiple API-key providers are available, let the user
   choose. Returns the (possibly updated) chosen map."
  [chosen detection opts]
  (let [avail (->> (:providers detection)
                   (filter #(and (= :api-key (:method %)) (:available? %)))
                   (mapv :provider))]
    (if (or (:auto opts) (<= (count avail) 1))
      chosen
      (let [options (mapv (fn [p] {:label (name p) :value p}) avail)
            default-idx (or (some (fn [[i o]]
                                    (when (= (:value o) (:provider chosen)) (inc i)))
                                  (map-indexed vector options))
                            1)
            picked  (:value (prompt-select "Pick an API-key provider:" options
                                           :default default-idx :auto? false))]
        (if (= picked (:provider chosen))
          chosen
          (assoc chosen
                 :provider picked
                 :model    (boot/default-model picked)))))))

(defn- pick-model
  "Offer a model picker for the chosen provider. Source:
     - rung (d): the pulled-models list from detection
     - others:   provider-model-suggestions for the provider
   Skipped in :auto mode or when only one candidate exists."
  [chosen detection opts]
  (let [{:keys [rung provider model]} chosen
        candidates
        (cond
          (:auto opts) []
          (= :d rung)  (->> (get-in detection [:ollama-install :pulled-models])
                            (mapv (fn [m] {:label m :value m})))
          :else        (vec (get (boot/provider-model-suggestions) provider)))]
    (if (<= (count candidates) 1)
      chosen
      (let [default-idx (or (some (fn [[i o]]
                                    (when (= (:value o) model) (inc i)))
                                  (map-indexed vector candidates))
                            1)
            picked (:value (prompt-select (str "Pick a model for " (name provider) ":")
                                          candidates
                                          :default default-idx :auto? false))]
        (assoc chosen :model picked)))))

(defn- refine-chosen
  "Interactive refinement of the ladder pick. No-op in :auto mode. For rungs
   that can offer alternatives (b: provider+model, c/d/f: model only), prompt
   the user once each."
  [chosen detection opts]
  (let [chosen (cond-> chosen
                 (= :b (:rung chosen)) (pick-provider detection opts))]
    (if (contains? #{:b :c :d :f} (:rung chosen))
      (pick-model chosen detection opts)
      chosen)))

(defn- print-rung-banner [chosen]
  (let [labels {:a "Existing config"
                :b "API-key provider"
                :c "Claude CLI"
                :d "Ollama (existing)"
                :e "Ollama (install/pull)"
                :f (if (:start? chosen)
                     "Apple Foundation Models (start apfel)"
                     "Apple Foundation Models")
                :g "Stop"}]
    (println)
    (println (ansi/style (str "Bootstrap rung (" (name (:rung chosen)) "): "
                              (labels (:rung chosen))) ansi/bright-cyan ansi/bold))
    (when (:reason chosen)
      (println (str "  " (ansi/muted (:reason chosen)))))))

(defn- run-bootstrap-phase!
  "Execute the chosen rung and return {:chosen :result :delta-config :ok?}.
   `:ok?` is false only when the wizard had to stop at rung (g) (or a critical
   action failure forced a stop).

   `excluded-rungs` (set of rung kws) is removed from the profile's allowed
   rungs before choosing — used by the fall-through loop in `run!` to skip
   rungs whose smoke-test already failed this invocation."
  ([detection existing-config opts]
   (run-bootstrap-phase! detection existing-config opts #{}))
  ([detection existing-config opts excluded-rungs]
   (let [profile-name (some-> (:profile opts) keyword)
         base-profile (boot/resolve-profile profile-name)
         profile      (update base-profile :allowed-rungs
                              #(set/difference % (set excluded-rungs)))
         ;; --re-bootstrap: hide existing :llm so applies-a? skips the no-op
         ;; rung and the ladder re-evaluates against current detection.
         ladder-input (if (:re-bootstrap opts)
                        (update existing-config :llm dissoc :default-provider :default-model)
                        existing-config)
         chosen       (-> (boot/choose-rung detection ladder-input profile)
                          (refine-chosen detection opts))
         actions      (boot/plan-actions chosen detection)
         phase-start  (System/currentTimeMillis)]
     (print-rung-banner chosen)
     (let [action-results
           (reduce
            (fn [acc action]
              (let [r (execute-action! action opts)]
                (conj acc {:action (:action action)
                           :ok?    (:ok? r true)
                           :detail (:detail r)
                           :raw    r})))
            []
            actions)

           all-ok?      (every? :ok? action-results)
           smoke-raw    (some #(when (= :smoke-test (:action %)) (:raw %)) action-results)
           installed    (when (some #(and (= :install-ollama (:action %)) (:ok? %)) action-results)
                          [:ollama])
           pulled       (when-let [m (some #(when (and (= :pull-model (:action %)) (:ok? %))
                                              (-> % :raw :model)) action-results)]
                          [m])
           rung-g?      (= :g (:rung chosen))
           stop?        (or rung-g? (not all-ok?))
           final-rung   (if stop? :g (:rung chosen))
           final-chosen (cond-> chosen
                          stop? (assoc :rung :g
                                       :provider nil
                                       :model nil))
           result       {:ok?         (not stop?)
                         :duration-ms (- (System/currentTimeMillis) phase-start)
                         :actions     action-results
                         :smoke-test  (when smoke-raw
                                        (select-keys smoke-raw [:ok? :latency-ms :error :ts]))
                         :installed   (or installed [])
                         :pulled      (or pulled [])
                         :next-steps  (if stop?
                                        (next-steps-for-stop detection)
                                        [])}
           delta-config (boot/merge-delta existing-config detection final-chosen result)]
       {:chosen       final-chosen
        :original     chosen
        :result       result
        :delta-config delta-config
        :ok?          (not stop?)}))))

;; ============================================================================
;; Fill in :environment/:permissions/:agent/:mcp (phase-2 deterministic merge)
;; ============================================================================

(defn- fill-non-llm-defaults
  "Phase 2 writes :llm/:bootstrap. The other keys come from existing-config
   merged with defaults so that callers exiting at handoff [2] (or --auto)
   still have a usable config. Steps 2-6 can override these interactively."
  [config dirs]
  (let [os-info (env/detect-os)
        sb      (env/detect-sandbox-environment)
        execs   (->> (env/detect-executables)
                     (filter :available?)
                     (map (fn [{:keys [name path]}] [(keyword name) path]))
                     (into {}))]
    (-> config
        (update :environment
                (fn [env]
                  (merge {:sandbox-mode :standard
                          :sandbox-type (:sandbox-type sb)
                          :os           os-info
                          :executables  execs}
                         env)))
        (update :permissions
                (fn [perm]
                  (merge {:mode         :ask-each-time
                          :allowed-dirs (agent/default-allowed-dirs dirs)}
                         perm)))
        (update :agent
                (fn [a]
                  (merge {:default-agent  :coact-agent
                          :max-iterations 100}
                         a)))
        (update :mcp
                (fn [m]
                  (or m {:servers {}}))))))

;; ============================================================================
;; Summary
;; ============================================================================

(defn- print-summary [config dirs]
  (println)
  (println (ansi/style "--- Summary ---" ansi/bold))
  (let [llm  (:llm config)
        env  (:environment config)
        perm (:permissions config)
        ag   (:agent config)
        mcp  (:mcp config)
        boot (:bootstrap config)]
    (println (str "  " (ansi/style "LLM:         " ansi/bold)
                  (or (some-> (:default-provider llm) name) "(none)")
                  " / "
                  (or (:default-model llm) "default")))
    (println (str "  " (ansi/style "Bootstrap:   " ansi/bold)
                  "rung " (some-> (:rung boot) name)
                  (when (seq (:installed boot))
                    (str ", installed " (str/join "," (map name (:installed boot)))))
                  (when (seq (:pulled boot))
                    (str ", pulled " (str/join "," (:pulled boot))))))
    (println (str "  " (ansi/style "Sandbox:     " ansi/bold)
                  (some-> (:sandbox-mode env) name)
                  " (" (some-> (:sandbox-type env) name) ")"))
    (println (str "  " (ansi/style "Permissions: " ansi/bold)
                  (some-> (:mode perm) name) ", "
                  (count (:allowed-dirs perm)) " dirs"))
    (println (str "  " (ansi/style "Agent:       " ansi/bold)
                  (some-> (:default-agent ag) name) ", "
                  (:max-iterations ag) " iters"))
    (println (str "  " (ansi/style "MCP:         " ansi/bold)
                  (if (seq (:servers mcp))
                    (str (count (:servers mcp)) " server(s)")
                    "none")))
    (println (str "  " (ansi/style "Project:     " ansi/bold)
                  (or (:project-dir dirs)
                      (str (ansi/muted "(none — config will be written to ")
                           (agent/user-config-dir dirs)
                           (ansi/muted ")")))))))

;; ============================================================================
;; Phase 3 — HANDOFF (optional, opt-in)
;; ============================================================================

;; Progress feedback while config-agent is thinking. The wizard prints to plain
;; stdout (the TUI render loop isn't up yet), so we drive a single rolling
;; status line with a braille spinner and convert each completed tool call
;; into a dim trail line so the user sees concrete activity instead of silence.

(def ^:private spinner-frames
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn- start-progress-spinner! []
  (let [!label   (atom "thinking…")
        !running (atom true)
        out-lock (Object.)
        thread   (Thread.
                  ^Runnable
                  (fn []
                    (loop [i 0]
                      (when @!running
                        (locking out-lock
                          (print (str "\r\033[K"
                                      (ansi/style (nth spinner-frames
                                                       (mod i (count spinner-frames)))
                                                  ansi/bright-cyan)
                                      " " (ansi/muted @!label)))
                          (flush))
                        (try (Thread/sleep 120)
                             (catch InterruptedException _ nil))
                        (recur (inc i))))))]
    (.setDaemon thread true)
    (.start thread)
    {:!label !label :!running !running :out-lock out-lock :thread thread}))

(defn- emit-progress-line!
  "Atomically clear the spinner line, print `line` on its own row, and let
   the spinner repaint on the next tick."
  [{:keys [out-lock]} line]
  (locking out-lock
    (print "\r\033[K")
    (println line)
    (flush)))

(defn- stop-progress-spinner! [{:keys [!running out-lock thread]}]
  (reset! !running false)
  (.interrupt ^Thread thread)
  (locking out-lock
    (print "\r\033[K")
    (flush)))

(defn- with-progress-feedback
  "Run `body-fn` (0-arg) with a stdout spinner + per-tool-use trail.
   `!diag` is an atom this fn mutates with progress diagnostics:
     :max-iter      highest iteration index seen
     :max-iter-cap  cap from :agent.iteration/pre :max-iterations
     :exhausted?    true when :agent.iteration/exhausted fired
     :tool-counts   {tool-name -> int}
   Registers transient agent hooks under `::wizard-progress`; tears them
   down + the spinner in a `finally`."
  [!diag body-fn]
  (let [ctrl      (start-progress-spinner!)
        !start-ts (atom {})]
    (agent/register-hook!
     :agent.iteration/pre ::wizard-progress-iter
     (fn [{:keys [iteration max-iterations]}]
       (swap! !diag (fn [d] (-> d
                                (update :max-iter (fnil max 0) iteration)
                                (assoc :max-iter-cap max-iterations))))
       (reset! (:!label ctrl)
               (str "iteration " iteration "/" max-iterations " · thinking…")))
     :source ::wizard-progress)
    (agent/register-hook!
     :agent.iteration/exhausted ::wizard-progress-exhaust
     (fn [_] (swap! !diag assoc :exhausted? true))
     :source ::wizard-progress)
    (agent/register-hook!
     :agent.tool-use/pre ::wizard-progress-tool-pre
     (fn [{:keys [tool-name call-id]}]
       (swap! !start-ts assoc call-id (System/currentTimeMillis))
       (reset! (:!label ctrl) (str "→ " (name tool-name))))
     :source ::wizard-progress)
    (agent/register-hook!
     :agent.tool-use/post ::wizard-progress-tool-post
     (fn [{:keys [tool-name call-id]}]
       (let [start (get @!start-ts call-id)
             ms    (when start (- (System/currentTimeMillis) start))
             tn    (name tool-name)]
         (swap! !start-ts dissoc call-id)
         (swap! !diag update-in [:tool-counts tn] (fnil inc 0))
         (emit-progress-line!
          ctrl
          (ansi/muted (str "  · " tn
                           (when ms (str " (" ms "ms)")))))
         (reset! (:!label ctrl) "thinking…")))
     :source ::wizard-progress)
    (try
      (body-fn)
      (finally
        (agent/unregister-source! ::wizard-progress)
        (stop-progress-spinner! ctrl)))))

(defn- stuck-symptoms
  "Return a vector of human-readable symptoms when this turn looks stuck.
   Empty vector = healthy. Reasons we flag:
     - iteration budget exhausted
     - any single tool called >=5 times in one turn (thrash)
     - high iteration count (>= 70% of cap) without exhaustion"
  [diag result]
  (let [{:keys [exhausted? max-iter max-iter-cap tool-counts]} diag
        thrash-tool (when (seq tool-counts)
                      (let [[tn n] (apply max-key val tool-counts)]
                        (when (>= n 5) [tn n])))
        near-cap?   (and max-iter max-iter-cap
                         (pos? max-iter-cap)
                         (>= (/ (double max-iter) max-iter-cap) 0.7))
        loop-guard? (when-let [ans (:answer result)]
                      (re-find #"Loop guard:" ans))]
    (cond-> []
      exhausted?  (conj (str "iteration budget exhausted ("
                             (or max-iter "?") "/" (or max-iter-cap "?") ")"))
      thrash-tool (conj (str "thrashing on tool '" (first thrash-tool)
                             "' (" (second thrash-tool) " calls in one turn)"))
      loop-guard? (conj "loop guard intervened — agent kept repeating a tool call")
      (and near-cap? (not exhausted?))
      (conj (str "high iteration count (" max-iter "/" max-iter-cap
                 ") — answer may be truncated thinking")))))

(defn- print-fallback-hint!
  "Print the 'quit and re-run with a stronger model' guidance after a stuck
   turn. Idempotent — prints at most once per spawn-config-agent! session."
  [!shown? symptoms]
  (when (and (seq symptoms) (not @!shown?))
    (reset! !shown? true)
    (println)
    (println (ansi/warning "Config-agent looks stuck on this turn."))
    (doseq [s symptoms] (println (ansi/muted (str "  · " s))))
    (println)
    (println (ansi/style "Recommended next steps:" ansi/bold))
    (println "  1. Type `exit` (or blank line) to leave this wizard handoff.")
    (println "  2. Re-run with a higher-capability model, then switch to config-agent:")
    (println (ansi/muted "       $ bb tui -m sonnet            # or `-m opus`"))
    (println (ansi/muted "       > /agent config-agent"))
    (println (ansi/muted "       > add tavily-mcp server in project scope"))
    (println (ansi/muted (str "  Smaller models (haiku, gpt-oss, llama-small) struggle with "
                              "config-agent's strict JSON tool-call schema and tend to "
                              "loop on idempotent reads.")))
    (println)))

(defn- spawn-config-agent!
  "Launch config-agent in-process for an interactive chat. Mirrors cmd-ask's
   setup pattern: configure LM from the freshly-written config, setup-agent-
   by-id, loop ask/print, .close in finally. Exits on blank line, `exit`,
   or `quit`."
  [config]
  (let [provider (some-> (get-in config [:llm :default-provider]))
        model    (get-in config [:llm :default-model])]
    (when-not provider
      (throw (ex-info "Cannot spawn config-agent: no :llm.default-provider in config.edn"
                      {:type :no-provider})))
    (try
      (helpers/setup-lm! provider :model model)
      (catch Exception e
        (println (ansi/warning (str "Could not setup LM: " (.getMessage e))))
        (throw e)))
    (let [session-id (str "config-" (System/currentTimeMillis))
          max-iter   (or (get-in (agent/get-tool-defs :id :config-agent)
                                 [:meta :max-iterations])
                         (get agent/default-config :max-iterations 100))
          ag (agent/setup-agent-by-id :config-agent
                                      :agent-session {:user-id (helpers/resolve-user-id)
                                                      :session-id session-id}
                                      :max-iterations max-iter)]
      (try
        (println)
        (println (ansi/style "config-agent is ready." ansi/bright-cyan ansi/bold))
        (println (ansi/muted "  Type a request; blank line, `exit`, or `quit` to leave."))
        (println (ansi/muted "  Replies usually take 10-60s while the agent inspects your setup."))
        (let [!fallback-shown? (atom false)]
          (loop []
            (let [q (prompt-line (ansi/style "config> " ansi/bold))]
              (cond
                (or (nil? q) (str/blank? q))
                (println (ansi/muted "(exiting config-agent)"))

                (contains? #{"exit" "quit"} (str/lower-case q))
                (println (ansi/muted "(exiting config-agent)"))

                :else
                (do
                  (let [!diag (atom {:max-iter 0 :exhausted? false :tool-counts {}})
                        r     (try (with-progress-feedback !diag #(agent/ask ag q))
                                   (catch Throwable t
                                     {:error (.getMessage t)}))]
                    (println)
                    (println (or (:answer r) (:error r) "(no answer)"))
                    (print-fallback-hint! !fallback-shown? (stuck-symptoms @!diag r))
                    (println))
                  (recur))))))
        (finally
          (try (.close ^java.io.Closeable ag) (catch Throwable _ nil)))))))

(defn- run-handoff-phase!
  "Returns true if the caller should continue with steps 2-6 interactively,
   false if the wizard should exit."
  [config dirs opts]
  (if (or (:auto opts) (:skip-handoff opts))
    false
    (let [pick (:value (prompt-select
                        "Continue with LLM-assisted configuration?"
                        [{:label "Yes — enter config-agent now (recommended)"
                          :value :agent}
                         {:label "No  — keep deterministic defaults, exit"
                          :value :exit}]
                        :default 1))]
      (case pick
        :agent (do
                 (try (spawn-config-agent! config)
                      (catch Throwable t
                        (println (ansi/warning (str "config-agent failed: "
                                                    (.getMessage t))))))
                 false)
        :exit  false))))

;; ============================================================================
;; Next-steps footer (post-success)
;; ============================================================================

(defn- print-next-steps-footer [config-path]
  (println)
  (println (ansi/style "Next steps:" ansi/bold))
  (println (str "  " (ansi/style "by" ansi/bright-cyan)
                "                                start an interactive session"))
  (println (str "  " (ansi/style "by ask \"...\"" ansi/bright-cyan)
                "                      one-shot question"))
  (println (str "  " (ansi/style "by config --re-bootstrap" ansi/bright-cyan)
                "          re-evaluate the ladder (switch provider/model)"))
  (println (str "  " (ansi/style "by run -a config-agent" ansi/bright-cyan)
                "             LLM-assisted re-configuration"))
  (println (str "  " (ansi/style "by models" ansi/bright-cyan)
                "                          list all known models"))
  (println (ansi/muted (str "  edit " config-path " for advanced keys (:agent.config.*, :mcp.servers, …)"))))

;; ============================================================================
;; The orchestrator
;; ============================================================================

(defn- non-tty-without-auto? [opts]
  (and (not (:auto opts))
       (nil? (System/console))))

(defn- ensure-tty-or-exit [opts]
  (when (non-tty-without-auto? opts)
    (binding [*out* *err*]
      (println "Non-interactive stdin detected. Use --auto for non-interactive runs."))
    (System/exit 2)))

(defn- validate-profile-or-exit [opts]
  (when-let [p (:profile opts)]
    (let [k (keyword p)]
      (when-not (contains? boot/builtin-profiles k)
        (binding [*out* *err*]
          (println (ansi/warning (str "Unknown profile: " p)))
          (println (str "  Known profiles: "
                        (str/join ", " (sort (map name (keys boot/builtin-profiles)))))))
        (System/exit 2)))))

(defn run!
  "Run the bootstrap pipeline.
   Returns the final config map on success, or nil if the wizard stopped early.
   Opts: {:auto :profile :skip-handoff :re-bootstrap :dry-run :log}"
  ([] (run! {}))
  ([opts]
   (ensure-tty-or-exit opts)
   (validate-profile-or-exit opts)
   (println)
   (println (ansi/style "  Brainyard Environment Bootstrap" ansi/bold ansi/bright-cyan))
   (println (ansi/style "  ================================" ansi/dim))
   (when (:auto opts)
     (println (ansi/muted (str "  --auto mode (" (or (:profile opts) "dev") " profile)"))))

   (let [dirs     (agent/init-dirs!)
         existing (agent/read-edn-config dirs)
         _        (if (seq existing)
                    (println (str "Loaded existing config from "
                                  (agent/project-config-dir dirs) "/config.edn"))
                    (println (ansi/muted "(No existing config found — starting fresh.)")))
         detection (run-detect-phase!)
         _         (print-detection-summary detection)

         ;; Smoke-failure fall-through: if a rung's smoke-test fails (e.g. an
         ;; expired API key), exclude that rung and retry the ladder so a
         ;; lower-priority alternative can still succeed. Cap at 4 attempts
         ;; (5 rungs available below (a)) to bound the loop on pathological
         ;; environments.
         {:keys [chosen result delta-config ok?]}
         (loop [excluded #{} attempts 0]
           (let [r (run-bootstrap-phase! detection existing opts excluded)
                 original (:original r)
                 failed-rung (:rung original)]
             (cond
               (:ok? r) r
               (or (>= attempts 4) (= :g failed-rung)) r
               :else
               (do (println (ansi/warning
                             (str "  → smoke-test failed for rung "
                                  (name failed-rung)
                                  (when (:provider original)
                                    (str " (" (name (:provider original)) ")"))
                                  "; trying next rung")))
                   (recur (conj excluded failed-rung) (inc attempts))))))

         filled (fill-non-llm-defaults delta-config dirs)]

     (print-summary filled dirs)
     (write-bootstrap-log!
      (log-path dirs opts)
      (boot/bootstrap-log-entry detection chosen result))

     (cond
       (:dry-run opts)
       (do
         (println)
         (println (ansi/style "--dry-run: not writing config.edn." ansi/dim))
         (println (with-out-str (clojure.pprint/pprint filled)))
         filled)

       (not ok?)
       (do
         (println)
         (println (ansi/warning "Bootstrap did not produce a reachable LLM."))
         (doseq [s (:next-steps result)]
           (println (str "  " s)))
         (agent/write-edn-config! dirs filled)
         (System/exit 1))

       :else
       (let [path (agent/write-edn-config! dirs filled)]
         (println)
         (println (ansi/style (str "Config written to " path) ansi/bright-green))
         (run-handoff-phase! filled dirs opts)
         (print-next-steps-footer path)
         filled)))))

;; ============================================================================
;; Back-compat: existing callers (e.g. older cmd-config or REPL) still work.
;; ============================================================================

(defn run-wizard!
  "Legacy entry point. Equivalent to `(run! {})`."
  []
  (run! {}))
