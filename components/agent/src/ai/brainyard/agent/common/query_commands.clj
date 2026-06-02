;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.query-commands
  "SQL query generation and execution commands for agents.
   Ported from cloudcast.backend.agent.common.query-agent.

   Provides natural-language-to-SQL capabilities with:
   - Query generation from natural language
   - SQL execution via JDBC
   - Error analysis and self-correcting retry
   - Schema introspection"
  (:require [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

;; =====================================================
;; JDBC Execution (Soft Dependency)
;; =====================================================

(defn- require-jdbc []
  (try
    (require 'next.jdbc)
    (require 'next.jdbc.result-set)
    true
    (catch Exception _
      (mulog/debug ::jdbc-not-available :message "next.jdbc not available on classpath")
      false)))

(defn- execute-sql
  "Execute SQL query via JDBC. Returns {:columns [...] :rows [...]} or {:error ...}."
  [datasource sql & {:keys [max-rows] :or {max-rows 100}}]
  (if-not (require-jdbc)
    {:error "next.jdbc not available on classpath"}
    (try
      (let [execute-fn (resolve 'next.jdbc/execute!)
            opts {:builder-fn (resolve 'next.jdbc.result-set/as-unqualified-maps)}
            results (execute-fn datasource [sql] opts)
            rows (take max-rows results)
            columns (when (seq rows) (vec (keys (first rows))))]
        {:columns columns
         :rows (mapv (fn [row] (mapv #(get row %) columns)) rows)
         :row-count (count rows)
         :truncated (> (count results) max-rows)})
      (catch Exception e
        {:error (.getMessage e)
         :sql sql}))))

;; =====================================================
;; Schema Introspection
;; =====================================================

(defonce !datasources
  (atom {}))  ;; {name -> {:datasource ds :schema-info {...}}}

(defn register-datasource
  "Register a named datasource for query commands.
   schema-info: {:tables [{:name :columns [{:name :type}]}]}"
  [name datasource & {:keys [schema-info]}]
  (swap! !datasources assoc name
         {:datasource datasource
          :schema-info (or schema-info {})}))

;; =====================================================
;; Commands
;; =====================================================

(defcommand query-command$execute
  "Execute SQL against a registered datasource; returns columns+rows limited to :max-rows."
  (fn [& {:keys [datasource-name sql max-rows]}]
    (let [ds-entry (get @!datasources (or datasource-name "default"))]
      (cond
        (not ds-entry)
        {:error (str "Datasource not found: " (or datasource-name "default")
                     ". Available: " (str/join ", " (keys @!datasources)))}

        (str/blank? sql)
        {:error "SQL query is required"}

        :else
        (execute-sql (:datasource ds-entry) sql
                     :max-rows (or max-rows 100)))))
  :input-schema  [:map
                  [:datasource-name [:string {:desc "Registered datasource name (default: 'default')"}]]
                  [:sql             [:string {:desc "SQL query to execute"}]]
                  [:max-rows        [:int {:desc "Max rows to return (default 100)"}]]]
  :output-schema [:map
                  [:columns   [:vector :string]]
                  [:rows      [:vector [:vector :any]]]
                  [:row-count [:int]]])

(defcommand query-command$list-datasources
  "List registered datasources and their schema info."
  (fn [& _]
    {:datasources
     (mapv (fn [[name {:keys [schema-info]}]]
             {:name name
              :tables (count (:tables schema-info))
              :has-schema (boolean (seq schema-info))})
           @!datasources)})
  :input-schema  [:map]
  :output-schema [:map
                  [:datasources [:vector [:map
                                          [:name :string]
                                          [:tables :int]
                                          [:has-schema :boolean]]]]])

(defcommand query-command$get-schema
  "Get schema information for a registered datasource."
  (fn [& {:keys [datasource-name]}]
    (let [ds-entry (get @!datasources (or datasource-name "default"))]
      (if-not ds-entry
        {:error (str "Datasource not found: " (or datasource-name "default"))}
        {:datasource datasource-name
         :schema (:schema-info ds-entry)})))
  :input-schema  [:map
                  [:datasource-name [:string {:desc "Datasource name"}]]]
  :output-schema [:map
                  [:schema [:any]]])

(defcommand query-command$validate-sql
  "Validate SQL syntax without executing. Uses EXPLAIN if supported."
  (fn [& {:keys [datasource-name sql]}]
    (let [ds-entry (get @!datasources (or datasource-name "default"))]
      (cond
        (not ds-entry)
        {:error "Datasource not found"}

        (str/blank? sql)
        {:error "SQL is required"}

        :else
        (let [explain-sql (str "EXPLAIN " sql)
              result (execute-sql (:datasource ds-entry) explain-sql)]
          (if (:error result)
            {:valid false :error (:error result) :sql sql}
            {:valid true :sql sql})))))
  :input-schema  [:map
                  [:datasource-name [:string {:desc "Datasource name"}]]
                  [:sql             [:string {:desc "SQL to validate"}]]]
  :output-schema [:map
                  [:valid [:boolean]]])
