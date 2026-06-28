;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.analytics-commands
  "LLM-accessible, on-demand session analytics.

   `session$analytics` reads the current session's append-only `trajectory.edn`
   (all turns up to now) and runs the full analyzer suite — the only entry point
   that does session I/O. The analyzers themselves stay pure in the analytics
   component; this command is the I/O + config + persistence glue.

   Replaces the old per-turn push pipeline (`bt.clj/run-analytics-async!`)."
  (:require [ai.brainyard.agent.common.trajectory :as trajectory]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.analytics.interface :as analytics]))

(defcommand session$analytics
  "Analyze this session from its trajectory log (all turns up to now) and
   report Prompt Quality, Token/Cost Efficiency, iteration convergence, tool
   reliability, latency, cache efficiency, outcome quality, and a composite
   Session Health Score. Reads the session's trajectory.edn — no per-turn cost.
   Use when the user asks how the session is going, where time/tokens went, or
   how to prompt more efficiently."
  (fn [{fmt :format :keys [deep persist trends]}]
    (let [agent proto/*current-agent*]
      (if-not agent
        {:error "No current agent bound"}
        (let [session-id (proto/session-id agent)
              user-id    (proto/user-id agent)
              records    (trajectory/read-trajectories session-id)]
          (if (empty? records)
            {:error "No trajectory recorded for this session yet."
             :session-id (str session-id) :turns 0}
            (let [lm-config (when deep
                              (config/resolve-analytics-lm agent))
                  weights   (config/get-config agent :analytics-shs-weights)
                  result    (analytics/analyze-trajectory
                             records
                             :lm-config lm-config
                             :skip-llm-analysis (not deep)
                             :shs-weights weights)
                  level     (keyword (or fmt "summary"))
                  mm        (proto/get-memory-manager agent)
                  ;; :trends implies :persist for this run (a run that isn't
                  ;; persisted can't be compared or contribute).
                  do-persist (and mm (or persist trends))
                  _          (when do-persist
                               (analytics/persist-analytics!
                                mm (assoc result :session-id session-id :user-id user-id)))
                  trend-data (when (and mm trends)
                               (analytics/get-analytics-trends mm user-id :fact-type :pqs-score))]
              (cond-> (assoc (select-keys result
                                          [:turns :health-score :pqs :cost :iteration
                                           :tools :latency :cache :outcome :waste])
                             :session-id (str session-id)
                             :summary (analytics/format-session-analytics result level))
                (seq trend-data) (assoc :trends (vec trend-data)))))))))
  :input-schema
  [:map
   [:format  {:optional true} [:enum {:desc "summary (default) | full | raw"} "summary" "full" "raw"]]
   [:deep    {:optional true} [:boolean {:desc "Enable LLM-enhanced (RLM) refinement of heuristic metrics. Costs tokens. Uses :analytics-lm-config (falling back to the agent :lm-config). Default false (heuristics only)."}]]
   [:persist {:optional true} [:boolean {:desc "Store this run as a coaching trend point in memory (default false). Independent of the report — the report is always returned."}]]
   [:trends  {:optional true} [:boolean {:desc "Include a PQS coaching-habit comparison against prior persisted runs. Implies :persist for this run. Default false."}]]]
  :output-schema
  [:map
   [:session-id   [:string]]
   [:turns        [:int {:desc "Turns analyzed"}]]
   [:health-score {:optional true} [:map {:desc "Composite Session Health Score + grade"}]]
   [:pqs          {:optional true} [:map]]
   [:cost         {:optional true} [:map]]
   [:iteration    {:optional true} [:map]]
   [:tools        {:optional true} [:map]]
   [:latency      {:optional true} [:map]]
   [:cache        {:optional true} [:map]]
   [:outcome      {:optional true} [:map]]
   [:waste        {:optional true} [:map]]
   [:summary      {:optional true} [:string {:desc "Human-readable formatted report"}]]
   [:trends       {:optional true} [:vector :any]]
   [:error        {:optional true} [:string]]])

(def analytics-commands
  "On-demand session analytics commands for agent-tools binding."
  [#'session$analytics])
