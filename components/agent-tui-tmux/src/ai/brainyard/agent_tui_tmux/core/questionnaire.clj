;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui-tmux.core.questionnaire
  "The single popup data primitive — see docs/tmux-based-agent-tui.md §10.2.

   Shape:
     {:id <string>
      :title <string>
      :timeout-ms <long>?           ; optional
      :tabs
        [{:id <kw>
          :label <string>?           ; defaults to (name id)
          :prompt <string>
          :type :radio | :checkbox | :text | :password
          :options [{:value <any>
                     :label <string>
                     :shortcut <char>?
                     :default? <bool>?
                     :free-input? <bool>?}]   ; :radio/:checkbox only
          :placeholder <string>?     ; :text/:password only
          :default <any>?            ; pre-selected value
          :required? <bool>?}]}

   Reply (delivered as :popup-result):
     {:id <string>
      :status :submitted | :cancelled | :timeout
      :answers {<tab-id> {:value <any>? :input <string>?}}}

   The answer for a single tab can carry both `:value` (chosen option) and
   `:input` (free-input text or text/password buffer).  Permission popups are
   the trivial one-tab case (§10.4)."
  (:require [clojure.string :as str])
  (:import [java.util UUID]))

(def tab-types #{:radio :checkbox :text :password})
(def reply-statuses #{:submitted :cancelled :timeout})

(defn- valid-tab? [{:keys [id type prompt options]}]
  (and (keyword? id)
       (string? prompt)
       (contains? tab-types type)
       (case type
         (:radio :checkbox) (and (sequential? options) (seq options)
                                 (every? #(and (contains? % :value)
                                               (string? (:label %)))
                                         options))
         (:text :password) true)))

(defn validate
  "Throw `ex-info` if `q` is not a well-formed questionnaire; return `q`
   otherwise."
  [q]
  (let [errors (cond-> []
                 (not (string? (:id q)))
                 (conj "missing :id (string)")
                 (not (string? (:title q)))
                 (conj "missing :title (string)")
                 (not (sequential? (:tabs q)))
                 (conj "missing :tabs (vector)")
                 (and (sequential? (:tabs q)) (empty? (:tabs q)))
                 (conj ":tabs must be non-empty")
                 (and (sequential? (:tabs q))
                      (not (apply distinct? :_ (mapv :id (:tabs q)))))
                 (conj ":tabs must have unique :id values")
                 (and (sequential? (:tabs q))
                      (not (every? valid-tab? (:tabs q))))
                 (conj "every tab must have :id, :prompt, :type and (for radio/checkbox) :options"))]
    (if (seq errors)
      (throw (ex-info (str "Invalid questionnaire: " (str/join ", " errors))
                      {:errors errors :questionnaire q}))
      q)))

(defn- ensure-tab-defaults [tab]
  (cond-> tab
    (nil? (:label tab)) (assoc :label (str/capitalize (name (:id tab))))))

(defn make
  "Build a questionnaire map.  `:id` defaults to a fresh UUID string."
  [{:keys [id title tabs timeout-ms] :as opts}]
  (-> opts
      (assoc :id (or id (.toString (UUID/randomUUID))))
      (assoc :title (or title ""))
      (assoc :tabs (mapv ensure-tab-defaults tabs))
      (cond-> (some? timeout-ms) (assoc :timeout-ms timeout-ms))
      validate))

(defn single-tab?
  "True when `q` has exactly one tab — the tab strip is hidden in this case."
  [q]
  (= 1 (count (:tabs q))))

(defn default-answers
  "Build the initial answer map from each tab's `:default`/`:default?` options.
   Useful for re-displaying a questionnaire after detach: the pending-dialogs
   snapshot stores the partial answers the user had typed so far."
  [q]
  (into {}
        (for [{:keys [id type default options]} (:tabs q)]
          (let [opt-default (some #(when (:default? %) (:value %)) options)
                v           (or default opt-default)]
            [id (case type
                  :radio    (when v {:value v})
                  :checkbox (when v {:value v})
                  :text     (when v {:input v})
                  :password nil)]))))

(defn ^:private complete-tab?
  [{:keys [id type required?]} answers]
  (let [{:keys [value input]} (get answers id)]
    (or (not required?)
        (case type
          :radio    (some? value)
          :checkbox (or (some? value) (some? input))
          :text     (and (string? input) (seq input))
          :password (and (string? input) (seq input))))))

(defn ready-to-submit?
  "True when every required tab has an answer."
  [q answers]
  (every? #(complete-tab? % answers) (:tabs q)))

(defn submitted-reply
  "Build a `:popup-result` reply for a successful submit."
  [q answers]
  {:id      (:id q)
   :status  :submitted
   :answers (or answers {})})

(defn cancelled-reply
  "Build a `:popup-result` reply for an Esc/cancel.
   Carries any `partial-answers` so by-host can persist them and re-display the
   form on re-attach."
  ([q] (cancelled-reply q {}))
  ([q partial-answers]
   {:id      (:id q)
    :status  :cancelled
    :answers (or partial-answers {})}))

(defn timeout-reply
  ([q] (timeout-reply q {}))
  ([q partial-answers]
   {:id      (:id q)
    :status  :timeout
    :answers (or partial-answers {})}))

;; -- Specialised constructors -------------------------------------------------

(defn permission-questionnaire
  "Build the permission popup specialisation of a questionnaire (§10.4).

   Required keys on `req`: `:tool` and either `:path` or `:summary`."
  [{:keys [tool path summary id]}]
  (let [target (or summary path)
        prompt (cond-> (str "Allow " tool)
                 (some? target) (str " to access " target)
                 true (str " ?"))]
    (make
     {:id    id
      :title "Permission required"
      :tabs  [{:id     :decision
               :label  "Decision"
               :prompt prompt
               :type   :radio
               :required? true
               :options [{:value :yes    :label "Yes"    :shortcut \y :default? true}
                         {:value :no     :label "No"     :shortcut \n}
                         {:value :always :label "Always" :shortcut \a}
                         {:value :never  :label "Never"  :shortcut \N}]}]})))

(defn confirm-questionnaire
  "Build the confirm-dialog specialisation (`/clear`, `/quit`, `/session
   close`).  `:title` is required."
  [{:keys [title prompt id]}]
  (make
   {:id    id
    :title title
    :tabs  [{:id     :decision
             :label  "Confirm"
             :prompt (or prompt (str title " — proceed?"))
             :type   :radio
             :required? true
             :options [{:value :confirm :label "Confirm" :shortcut \y}
                       {:value :cancel  :label "Cancel"  :shortcut \n :default? true}]}]}))

(defn feedback-questionnaire
  "Build the legacy feedback-popup shape from `{:question :options}`.
   `options` is a vector of strings or maps with `:label` and optional `:value`.
   Returns a one-tab radio questionnaire."
  [{:keys [question options id]}]
  (let [opts (mapv (fn [i o]
                     (cond
                       (map? o)    (merge {:value (or (:value o) i)} o)
                       :else       {:value i :label (str o)}))
                   (range)
                   options)]
    (make
     {:id    id
      :title "Feedback"
      :tabs  [{:id      :feedback
               :label   "Feedback"
               :prompt  (or question "")
               :type    :radio
               :required? true
               :options opts}]})))

(defn permission-decision
  "Pull the user's decision keyword (`:yes`, `:no`, `:always`, `:never`,
   `:cancel`) from a permission popup reply."
  [reply]
  (case (:status reply)
    :submitted (get-in reply [:answers :decision :value])
    :cancelled :cancel
    :timeout   :cancel
    :cancel))
