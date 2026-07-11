;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.chart-commands
  "Chart/visualization commands for agents.
   Ported from cloudcast.backend.agent.common.chart-agent.

   Generates Plotly-compatible JSON chart specifications that can be
   rendered in the web app or exported as HTML files."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [ai.brainyard.mulog.interface :as mulog]))

;; =====================================================
;; Chart Types
;; =====================================================

(def chart-types
  "Supported Plotly chart types with descriptions."
  {:bar         "Bar chart — categorical comparisons"
   :line        "Line chart — trends over time"
   :scatter     "Scatter plot — relationship between variables"
   :pie         "Pie chart — proportional composition"
   :area        "Area chart — cumulative trends"
   :heatmap     "Heatmap — matrix/grid data"
   :histogram   "Histogram — distribution"
   :box         "Box plot — statistical distribution"
   :treemap     "Treemap — hierarchical proportions"
   :table       "Table — tabular data display"})

;; =====================================================
;; Plotly HTML Template
;; =====================================================

(def ^:private plotly-html-template
  "<!DOCTYPE html>
<html><head>
<script src='https://cdn.plot.ly/plotly-2.32.0.min.js'></script>
<style>body{margin:0;padding:16px;font-family:sans-serif;background:#1a1a2e;}
#chart{width:100%%;height:90vh;}</style>
</head><body>
<div id='chart'></div>
<script>
var spec = %s;
Plotly.newPlot('chart', spec.data, spec.layout, {responsive: true});
</script></body></html>")

;; =====================================================
;; Commands
;; =====================================================

(defcommand chart-command$create-plotly
  "Create a Plotly chart from a JSON spec; returns artifact for the web app."
  (fn [& {:keys [data layout title]}]
    (let [layout (merge {:template "plotly_dark"
                         :paper_bgcolor "rgba(0,0,0,0)"
                         :plot_bgcolor "rgba(0,0,0,0)"
                         :font {:color "#ccc"}}
                        layout
                        (when title {:title title}))
          spec {:data (or data [])
                :layout layout}]
      {:artifact-type :chart
       :artifact-data {:chart-spec spec
                       :format "plotly"}
       :summary (str "Created " (or title "chart")
                     " with " (count data) " trace(s)")}))
  :input-schema [:map
                 [:data [:vector {:desc "Plotly data array [{type, x, y, ...}]"} [:map-of :any :any]]]
                 [:layout {:optional true} [:map-of {:desc "Plotly layout object (optional)"} :any :any]]
                 [:title [:string {:desc "Chart title"}]]]
  :output-schema [:map
                  [:artifact-type :keyword]
                  [:artifact-data :map]])

(defn- default-chart-path
  "Default chart export path under `<project>/.brainyard/charts/<ts>.html`.
   Falls back to `/tmp/` only when the project-scope dir can't be created."
  []
  (let [ts (System/currentTimeMillis)]
    (if-let [d (config/brainyard-subdir! (config/init-dirs!) "charts" :project)]
      (str d "/chart-" ts ".html")
      (str "/tmp/chart-" ts ".html"))))

(defcommand chart-command$export-html
  "Export a Plotly chart spec to an HTML file. Default location:
   `<project>/.brainyard/charts/chart-<ts>.html`."
  (fn [& {:keys [chart-spec filepath]}]
    (let [filepath (or filepath (default-chart-path))
          spec-json (json/write-str chart-spec)
          html (format plotly-html-template spec-json)]
      (spit filepath html)
      {:success true
       :filepath filepath
       :size (count html)}))
  :input-schema [:map
                 [:chart-spec [:map-of {:desc "Plotly chart spec {data, layout}"} :any :any]]
                 [:filepath {:optional true} [:string {:desc "Output file path (optional; defaults to <project>/.brainyard/charts/)"}]]]
  :output-schema [:map
                  [:success :boolean]
                  [:filepath :string]])

(defcommand chart-command$recommend-type
  "Recommend a chart type from a data description; returns type and explanation."
  (fn [& {:keys [description data-shape]}]
    (let [desc (str (or description "") " " (or data-shape ""))]
      (cond
        (re-find #"(?i)time|trend|series|over time" desc)
        {:recommended "line" :reason "Time-series data is best shown as a line chart"}

        (re-find #"(?i)compar|categor|group|versus|vs" desc)
        {:recommended "bar" :reason "Categorical comparisons work well with bar charts"}

        (re-find #"(?i)proportion|percent|share|composition|breakdown" desc)
        {:recommended "pie" :reason "Proportional data is best shown as a pie chart"}

        (re-find #"(?i)distribut|spread|range|quartile" desc)
        {:recommended "box" :reason "Distribution data is best shown as a box plot"}

        (re-find #"(?i)correlat|relationship|scatter|xy" desc)
        {:recommended "scatter" :reason "Variable relationships work well with scatter plots"}

        (re-find #"(?i)matrix|grid|heat" desc)
        {:recommended "heatmap" :reason "Matrix data is best shown as a heatmap"}

        (re-find #"(?i)hierarch|tree|nested" desc)
        {:recommended "treemap" :reason "Hierarchical data is best shown as a treemap"}

        (re-find #"(?i)cumulat|stack|area" desc)
        {:recommended "area" :reason "Cumulative data works well with area charts"}

        :else
        {:recommended "bar" :reason "Bar chart is a versatile default for most data"})))
  :input-schema [:map
                 [:description [:string {:desc "Description of the data"}]]]
  :output-schema [:map
                  [:recommended :string]
                  [:reason :string]])
