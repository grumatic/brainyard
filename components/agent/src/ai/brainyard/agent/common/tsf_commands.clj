;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.tsf-commands
  "TSF commands — time-series forecasting over the project's datasets.

   These call the `tsf-backend` HTTP service (FastAPI over PyTorch /
   NeuralForecast) rather than doing any modelling here. Same reasoning as
   `rag-commands`: the backend owns training, cross-validation and the metric
   definitions, and a second implementation of any of those would drift from
   the one the operator console shows. Agent and UI call the same endpoint, so
   they cannot disagree about what a model scored.

   Base URL comes from `TSF_API_URL`, which the workspace section puts on this
   agent's owner process (SECTION_CONTEXTS.tsf gates) so one project's agent
   talks to that project's backend.

   THE THING THAT MAKES THESE DIFFERENT FROM rag$*: training is SLOW. `tsf$run`
   queues a run and returns an id immediately; it does not block for the
   minutes the training takes. An agent that wants the result polls `tsf$run-status`.
   That is why there is no single 'train and tell me the answer' command — it
   would be a command that times out on exactly the inputs worth running.

   Every command returns `{:error \"...\"}` rather than throwing when the
   backend is unreachable, so the agent can say so instead of failing the turn."
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

(def ^:private default-base-url "http://127.0.0.1:8100")

(defn base-url
  "The TSF backend this agent talks to. Trailing slashes trimmed."
  []
  (let [raw (str/trim (or (System/getenv "TSF_API_URL") ""))]
    (str/replace (if (str/blank? raw) default-base-url raw) #"/+$" "")))

(defn- encode ^String [v]
  (URLEncoder/encode (str v) (.name StandardCharsets/UTF_8)))

(defn- query-string
  [params]
  (->> params
       (keep (fn [[k v]]
               (when (and (some? v) (not (and (string? v) (str/blank? v))))
                 (str (name k) "=" (encode v)))))
       (str/join "&")))

(defn- parse-body
  [body]
  (try
    (json/read-str (str body) :key-fn keyword)
    (catch Exception _ nil)))

(defn- unreachable-error
  [ex]
  {:error (str "TSF backend is not reachable at " (base-url) " (" (.getMessage ^Exception ex) "). "
               "Start it with `npm run dev -w @brainyard/tsf-backend`, or — under "
               "Brainyard Desktop — from the Backend tab. Note that a FIRST start "
               "installs PyTorch and takes several minutes. Nothing was read or written.")})

(defn- request
  "One HTTP call to the backend, normalised to data. Never throws."
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
                                    :delete (http/delete url opts))
            parsed (parse-body body)]
        (if (<= 200 status 299)
          (or parsed {})
          {:error (str "TSF backend returned " status ": "
                       (or (:detail parsed)
                           (some-> body (subs 0 (min 300 (count (str body)))))
                           "no detail"))}))
      (catch Exception e
        (mulog/log ::tsf-request-failed :url url :error (.getMessage e))
        (unreachable-error e)))))

;; =====================================================
;; Health & inventory
;; =====================================================

(defcommand tsf$health
  "Check whether the forecasting backend is reachable, which models it serves, and what accelerator it will train on."
  (fn [& _]
    (request :get "/health" {:timeout-ms 15000}))
  :input-schema  [:map]
  :output-schema [:map
                  [:ok          {:optional true} [:boolean {:desc "Backend is up and its library imports"}]]
                  [:project     {:optional true} [:string]]
                  [:models      {:optional true} [:any {:desc "Model names this backend will train"}]]
                  [:accelerator {:optional true} [:any {:desc "requested vs resolved — cpu/mps/gpu"}]]
                  [:defaults    {:optional true} [:any {:desc "horizon, input_size, max_steps, confidence_level"}]]
                  [:worker      {:optional true} [:any {:desc "running: the run id being trained, or null"}]]
                  [:error       {:optional true} [:string]]])

(defcommand tsf$stats
  "How many datasets, runs and fitted models this project has, and how much disk they use."
  (fn [& _]
    (request :get "/stats" {:timeout-ms 30000}))
  :input-schema  [:map]
  :output-schema [:map
                  [:datasets    {:optional true} [:int]]
                  [:runs        {:optional true} [:any {:desc "Counts by status"}]]
                  [:checkpoints {:optional true} [:int {:desc "Fitted models available to predict from"}]]
                  [:disk_bytes  {:optional true} [:int]]
                  [:error       {:optional true} [:string]]])

(defcommand tsf$datasets
  "List the time series available to train on, or describe one in detail (columns, frequency, extent, preview)."
  (fn [& {:keys [id]}]
    (if (str/blank? (str id))
      (request :get "/datasets" {:timeout-ms 30000})
      (request :get (str "/datasets/" (encode id)) {:timeout-ms 30000})))
  :input-schema  [:map
                  [:id {:optional true} [:string {:desc "Dataset id. Omit to list all."}]]]
  :output-schema [:map
                  [:datasets {:optional true} [:any {:desc "When listing"}]]
                  [:rows     {:optional true} [:int {:desc "When describing one"}]]
                  [:freq     {:optional true} [:string {:desc "Inferred pandas offset alias"}]]
                  [:ds_col   {:optional true} [:string]]
                  [:y_col    {:optional true} [:string]]
                  [:preview  {:optional true} [:any]]
                  [:error    {:optional true} [:string]]])

;; =====================================================
;; Runs
;; =====================================================

(defcommand tsf$run
  "Queue a training run over one dataset. Returns immediately with a run id — training takes minutes; poll tsf$run-status."
  (fn [& {:keys [dataset_id models horizon input_size max_steps confidence_level freq n_windows]}]
    (if (str/blank? (str dataset_id))
      {:error "dataset_id is required. Call tsf$datasets to find one."}
      (request :post "/runs"
               {:timeout-ms 30000
                :body (cond-> {:dataset_id dataset_id}
                        (seq models)          (assoc :models models)
                        horizon               (assoc :horizon horizon)
                        input_size            (assoc :input_size input_size)
                        max_steps             (assoc :max_steps max_steps)
                        confidence_level      (assoc :confidence_level confidence_level)
                        n_windows             (assoc :n_windows n_windows)
                        (not (str/blank? (str freq))) (assoc :freq freq))})))
  :input-schema  [:map
                  [:dataset_id [:string {:desc "Which series to train on"}]]
                  [:models           {:optional true} [:any {:desc "Subset of DLinear/NHITS/AutoShaper. Omit for all three."}]]
                  [:horizon          {:optional true} [:int {:desc "Steps to forecast"}]]
                  [:input_size       {:optional true} [:int {:desc "Steps of history the model sees. input_size + horizon must not exceed the series length."}]]
                  [:max_steps        {:optional true} [:int {:desc "Training steps. More is slower and not always better."}]]
                  [:confidence_level {:optional true} [:int {:desc "Prediction-interval level, 1-99"}]]
                  [:n_windows        {:optional true} [:int {:desc "Calibration windows for the conformal prediction interval, 2-20. Default 2, which is the minimum: at 2 the interval width jitters between adjacent steps rather than widening with the horizon, because each step's width is a quantile over only two residuals. Raise it when the INTERVAL matters; it costs a cross-validation pass per window at fit time and does not change the point forecast."}]]
                  [:freq             {:optional true} [:string {:desc "pandas offset alias. Defaults to the dataset's inferred one."}]]]
  :output-schema [:map
                  [:run_id {:optional true} [:string]]
                  [:status {:optional true} [:string]]
                  [:error  {:optional true} [:string]]])

(defcommand tsf$runs
  "List past and current runs, newest first — what has already been tried on a dataset."
  (fn [& {:keys [dataset_id]}]
    (let [qs (query-string {:dataset_id dataset_id})]
      (request :get (str "/runs" (when (seq qs) (str "?" qs))) {:timeout-ms 30000})))
  :input-schema  [:map
                  [:dataset_id {:optional true} [:string {:desc "Filter to one dataset"}]]]
  :output-schema [:map
                  [:runs  {:optional true} [:any]]
                  [:error {:optional true} [:string]]])

(defcommand tsf$run-status
  "One run: its status, progress, and per-model metrics once it has finished."
  (fn [& {:keys [run_id]}]
    (if (str/blank? (str run_id))
      {:error "run_id is required."}
      (request :get (str "/runs/" (encode run_id)) {:timeout-ms 30000})))
  :input-schema  [:map [:run_id [:string]]]
  :output-schema [:map
                  [:status   {:optional true} [:string {:desc "queued|running|done|failed|cancelled"}]]
                  [:progress {:optional true} [:double {:desc "0..1, across the run's models"}]]
                  [:stage    {:optional true} [:string {:desc "What it is doing right now"}]]
                  [:results  {:optional true} [:any {:desc "Per model: metrics, seconds, whether a model was saved"}]]
                  [:error    {:optional true} [:string]]])

(defcommand tsf$cancel-run
  "Cancel a queued or running run. A model already training finishes first — there is no mid-fit abort."
  (fn [& {:keys [run_id]}]
    (if (str/blank? (str run_id))
      {:error "run_id is required."}
      (request :post (str "/runs/" (encode run_id) "/cancel") {:timeout-ms 30000})))
  :input-schema  [:map [:run_id [:string]]]
  :output-schema [:map
                  [:status {:optional true} [:string]]
                  [:detail {:optional true} [:string {:desc "Says when the cancel actually takes effect"}]]
                  [:error  {:optional true} [:string]]])

(defcommand tsf$forecast
  "The forecast a finished run produced, with its prediction interval — the series a chart would draw."
  (fn [& {:keys [run_id]}]
    (if (str/blank? (str run_id))
      {:error "run_id is required."}
      (request :get (str "/runs/" (encode run_id) "/forecast") {:timeout-ms 60000})))
  :input-schema  [:map [:run_id [:string]]]
  :output-schema [:map
                  [:history          {:optional true} [:any {:desc "Tail of the actual series"}]]
                  [:forecasts        {:optional true} [:any {:desc "Per model: ds, yhat, lo, hi"}]]
                  [:confidence_level {:optional true} [:int]]
                  [:error            {:optional true} [:string]]])

(defcommand tsf$run-log
  "The training log of a run — where a failure says what actually went wrong."
  (fn [& {:keys [run_id lines]}]
    (if (str/blank? (str run_id))
      {:error "run_id is required."}
      (request :get (str "/runs/" (encode run_id) "/log"
                         "?" (query-string {:lines (or lines 200)}))
               {:timeout-ms 30000})))
  :input-schema  [:map
                  [:run_id [:string]]
                  [:lines {:optional true} [:int {:desc "Tail length. Default 200."}]]]
  :output-schema [:map
                  [:log   {:optional true} [:string]]
                  [:error {:optional true} [:string]]])

;; =====================================================
;; Predict
;; =====================================================

(defcommand tsf$predictors
  "Fitted models that can be forecast from — the saved output of finished runs."
  (fn [& _]
    (request :get "/predictors" {:timeout-ms 30000}))
  :input-schema  [:map]
  :output-schema [:map
                  [:predictors {:optional true} [:any {:desc "run_id + model pairs, with their metrics"}]]
                  [:error      {:optional true} [:string]]])

(defcommand tsf$predict
  "Forecast forward using a model fitted earlier, optionally against a different series than it was trained on."
  (fn [& {:keys [run_id model dataset_id confidence_level]}]
    (cond
      (str/blank? (str run_id)) {:error "run_id is required. Call tsf$predictors to find one."}
      (str/blank? (str model))  {:error "model is required — a run may hold several."}
      :else
      (request :post "/predict"
               {:timeout-ms 120000
                :body (cond-> {:run_id run_id :model model}
                        (not (str/blank? (str dataset_id))) (assoc :dataset_id dataset_id)
                        confidence_level (assoc :confidence_level confidence_level))})))
  :input-schema  [:map
                  [:run_id [:string {:desc "The run whose fitted model to use"}]]
                  [:model  [:string {:desc "Which model from that run"}]]
                  [:dataset_id       {:optional true} [:string {:desc "Predict against THIS series instead of the one it was trained on. It needs at least input_size points."}]]
                  [:confidence_level {:optional true} [:int]]]
  :output-schema [:map
                  [:forecast    {:optional true} [:any {:desc "ds, yhat, lo, hi"}]]
                  [:history     {:optional true} [:any {:desc "Tail of the input series, for context"}]]
                  [:trained_on  {:optional true} [:string {:desc "The dataset the model was fitted on"}]]
                  [:horizon     {:optional true} [:int]]
                  [:error       {:optional true} [:string]]])

(def all-tsf-commands
  "The tsf$* family, for a defagent's :agent-tools roster."
  [#'tsf$health #'tsf$stats #'tsf$datasets
   #'tsf$run #'tsf$runs #'tsf$run-status #'tsf$cancel-run #'tsf$forecast #'tsf$run-log
   #'tsf$predictors #'tsf$predict])
