;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.doc
  "Polymorphic `doc$*` commands that unify the `todo$*` and `plan$*`
   surfaces. Each command takes `:kind :todo` or `:kind :plan` and
   dispatches into the existing helper fns in `common/todo` and
   `common/plan`.

   Five commands cover the full lifecycle:

   - `doc$list`   — list todos or plans (filter by :scope, :status)
   - `doc$read`   — read one doc by slug; for todos, the active todo is
                    mirrored into st-memory and the result includes
                    a `:progress` map. Returns `{:not-found true ...}`
                    instead of an error when the doc is absent so
                    callers can treat 'check existence' as 'try to read'.
   - `doc$create` — create a new todo (with :goal + :items) or plan
                    (with :body)
   - `doc$update` — sub-op dispatch via the input fields:
                      :status :draft|:in-progress|:completed|:abandoned|:reopen
                      :goal (todo only) | :body (plan only)
                      :item-idx N + :item-done bool (todo only)
                      :add-item desc + :after-idx? (todo only)
                    Exactly one sub-op per call.
   - `doc$delete` — delete by slug

   The doc$* commands delegate into the plain-fn API in
   `ai.brainyard.agent.common.todo` and `ai.brainyard.agent.common.plan`."
  (:require [ai.brainyard.agent.common.todo :as todo]
            [ai.brainyard.agent.common.plan :as plan]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [clojure.string :as str]))

;; ============================================================================
;; Shared helpers (mirror todo.clj / plan.clj private versions)
;; ============================================================================

(defn- current-dirs
  "Resolve dirs from the current agent session, falling back to init-dirs!."
  []
  (or (when-let [a (some-> (requiring-resolve 'ai.brainyard.agent.core.protocol/*current-agent*)
                           deref)]
        (some-> (:!session a) deref
                ((or (requiring-resolve 'ai.brainyard.agent.core.session/get-session-config)
                     (constantly nil))
                 :dirs)))
      ((or (requiring-resolve 'ai.brainyard.agent.core.config/init-dirs!)
           (constantly {})))))

(defn- coerce-kw
  [v]
  (cond
    (nil? v)     nil
    (keyword? v) v
    (string? v)  (when-not (str/blank? v) (keyword v))
    :else        nil))

(defn- coerce-kind
  "Normalize `:kind` to :todo or :plan, or nil if invalid."
  [v]
  (let [k (coerce-kw v)]
    (when (#{:todo :plan} k) k)))

(defn- parse-int-arg
  [v]
  (cond
    (integer? v) v
    (string? v)  (try (Long/parseLong (str/trim v)) (catch Exception _ nil))
    :else        nil))

(defn- kind-error []
  {:error ":kind is required (:todo or :plan)"})

(defn- attach-progress
  "For todos, attach a `:progress` map (completed/pending/total/percent/next-item)
   to the result so callers always get progress alongside the read."
  [todo]
  (if (or (:error todo) (nil? (:items todo)))
    todo
    (assoc todo :progress (todo/todo-progress todo))))

;; ============================================================================
;; doc$list
;; ============================================================================

(defcommand doc$list
  "List todos or plans with metadata."
  (fn [& {:keys [kind scope status]}]
    (let [k (coerce-kind kind)
          s (coerce-kw scope)
          st (coerce-kw status)
          dirs (current-dirs)]
      (case k
        :todo (todo/list-todos dirs :scope s :status st)
        :plan (plan/list-plans dirs :scope s :status st)
        (kind-error))))
  :input-schema [:map
                 [:kind [:enum {:desc "Document kind: todo | plan"} "todo" "plan"]]
                 [:scope {:optional true} [:string {:desc "project | user (omit for both)"}]]
                 [:status {:optional true} [:string {:desc "Filter by status: draft | in-progress | completed | abandoned"}]]]
  :output-schema [:map
                  [:result {:optional true} [:any {:desc "Vector of doc summaries"}]]
                  [:error {:optional true} [:string {:desc "Error message if failed"}]]])

;; ============================================================================
;; doc$read
;; ============================================================================

(defcommand doc$read
  "Read a todo or plan by slug. Returns {:not-found true ...} when absent (not an error)."
  (fn [& {:keys [kind slug scope]}]
    (let [k (coerce-kind kind)
          s (coerce-kw scope)
          dirs (current-dirs)]
      (cond
        (nil? k)              (kind-error)
        (str/blank? slug)     {:error "slug is required"}
        :else
        (let [result (case k
                       :todo (todo/read-todo dirs slug :scope s)
                       :plan (plan/read-plan dirs slug :scope s))]
          (cond
            ;; "not found" → return as :not-found (replaces the old exists-check shims)
            (and (:error result)
                 (re-find #"not found" (str (:error result))))
            {:not-found true :slug slug :kind (name k)}

            (:error result) result

            (= k :todo)
            (do (todo/mirror-to-st-memory! result)
                (attach-progress result))

            :else result)))))
  :input-schema [:map
                 [:kind [:enum {:desc "Document kind: todo | plan"} "todo" "plan"]]
                 [:slug [:string {:desc "Doc slug"}]]
                 [:scope {:optional true} [:string {:desc "project | user (omit to auto-detect)"}]]]
  :output-schema [:map
                  [:id {:optional true} [:string {:desc "Doc id (UUID)"}]]
                  [:title {:optional true} [:string {:desc "Doc title"}]]
                  [:slug {:optional true} [:string {:desc "Doc slug"}]]
                  [:scope {:optional true} [:string {:desc "Doc scope"}]]
                  [:status {:optional true} [:string {:desc "Doc status"}]]
                  [:created {:optional true} [:string {:desc "Creation timestamp"}]]
                  [:updated {:optional true} [:string {:desc "Last update timestamp"}]]
                  [:goal {:optional true} [:string {:desc "Todo goal (todos only)"}]]
                  [:items {:optional true} [:any {:desc "Todo items (todos only)"}]]
                  [:body {:optional true} [:string {:desc "Plan body (plans only)"}]]
                  [:progress {:optional true} [:any {:desc "Todo progress map (todos only)"}]]
                  [:file-path {:optional true} [:string {:desc "Absolute file path"}]]
                  [:not-found {:optional true} [:boolean {:desc "True when no doc exists at this slug"}]]
                  [:error {:optional true} [:string {:desc "Error message if read failed for another reason"}]]])

;; ============================================================================
;; doc$create
;; ============================================================================

(defcommand doc$create
  "Create a new todo or plan."
  (fn [& {:keys [kind title scope goal items body]}]
    (let [k     (coerce-kind kind)
          sc    (or (coerce-kw scope) :project)
          dirs  (current-dirs)]
      (cond
        (nil? k)              (kind-error)
        (str/blank? title)    {:error "title is required"}

        (= k :todo)
        (let [t (todo/create-todo dirs sc title (or goal "") (or items []))]
          (when-not (:error t) (todo/mirror-to-st-memory! t))
          t)

        :else
        (plan/create-plan dirs sc title (or body "")))))
  :input-schema [:map
                 [:kind [:enum {:desc "Document kind: todo | plan"} "todo" "plan"]]
                 [:title [:string {:desc "Doc title"}]]
                 [:scope {:optional true} [:string {:desc "project (default) | user"}]]
                 [:goal {:optional true} [:string {:desc "Todo goal (todos only)"}]]
                 [:items {:optional true} [:vector {:desc "Todo items (todos only) — vector of {:description} maps"} :any]]
                 [:body {:optional true} [:string {:desc "Plan body (plans only) — free-form markdown"}]]]
  :output-schema [:map
                  [:id {:optional true} [:string {:desc "Doc id (UUID)"}]]
                  [:slug {:optional true} [:string {:desc "Generated slug"}]]
                  [:title {:optional true} [:string {:desc "Doc title"}]]
                  [:scope {:optional true} [:string {:desc "Doc scope"}]]
                  [:status {:optional true} [:string {:desc "Doc status"}]]
                  [:created {:optional true} [:string {:desc "Creation timestamp"}]]
                  [:file-path {:optional true} [:string {:desc "Absolute path to doc file"}]]
                  [:error {:optional true} [:string {:desc "Error message if failed"}]]])

;; ============================================================================
;; doc$update — polymorphic sub-op dispatcher
;; ============================================================================

(defn- update-status
  "Apply a :status sub-op to either kind. :reopen is special — for todos
   it resets all items + status :draft; for plans it just flips status to
   :draft. Other status keywords are simple flips, plus a :completed guard
   for todos that have pending items."
  [kind dirs slug status]
  (let [reader  (case kind :todo todo/read-todo  :plan plan/read-plan)
        writer  (case kind :todo todo/update-todo :plan plan/update-plan)
        d       (reader dirs slug)]
    (cond
      (:error d) d

      (= status :reopen)
      (let [updated (case kind
                      :todo (todo/reopen-todo d)
                      :plan (plan/reopen-plan d))
            saved   (writer dirs updated)]
        (when (= kind :todo) (todo/mirror-to-st-memory! saved))
        saved)

      (and (= kind :todo) (= status :completed))
      (let [pending (count (remove :done (:items d)))]
        (if (pos? pending)
          {:error (str "Cannot complete todo: " pending " item(s) still pending. "
                       "Mark all items done first, or set :status :abandoned to abandon.")}
          (let [saved (writer dirs (assoc d :status :completed))]
            (todo/mirror-to-st-memory! saved)
            {:status :completed :progress (todo/todo-progress saved)})))

      :else
      (let [saved (writer dirs (assoc d :status status))]
        (when (= kind :todo) (todo/mirror-to-st-memory! saved))
        (case kind
          :todo saved
          :plan {:slug (:slug saved) :status (:status saved)})))))

(defn- update-goal-todo
  [dirs slug goal]
  (let [d (todo/read-todo dirs slug)]
    (if (:error d)
      d
      (todo/update-todo dirs (todo/update-goal d (or goal ""))))))

(defn- update-body-plan
  [dirs slug body]
  (let [d (plan/read-plan dirs slug)]
    (if (:error d)
      d
      (plan/update-plan dirs (plan/update-body d (or body ""))))))

(defn- update-item-todo
  [dirs slug item-idx item-done]
  (let [idx (parse-int-arg item-idx)
        d   (todo/read-todo dirs slug)]
    (cond
      (:error d) d
      (nil? idx) {:error "item-idx is required (int)"}
      (or (neg? idx) (>= idx (count (:items d))))
      {:error (format "item-idx %d out of range — valid: 0..%d"
                      idx (dec (count (:items d))))}
      :else
      (let [op       (if item-done todo/mark-item-done todo/reset-item)
            updated  (-> d (op idx) (assoc :status :in-progress))
            saved    (todo/update-todo dirs updated)]
        (todo/mirror-to-st-memory! saved)
        saved))))

(defn- add-item-todo
  [dirs slug description after-idx tags]
  (let [d (todo/read-todo dirs slug)]
    (cond
      (:error d)              d
      (str/blank? description) {:error "add-item description is required"}
      :else
      (let [updated (-> (todo/add-item d description
                                       :after-idx (parse-int-arg after-idx)
                                       :tags tags)
                        (assoc :status :in-progress))
            saved   (todo/update-todo dirs updated)]
        (todo/mirror-to-st-memory! saved)
        saved))))

(defcommand doc$update
  "Update a todo or plan via exactly ONE sub-op: :status, :goal (todo), :body (plan), :item-idx+:item-done (todo), or :add-item (todo)."
  (fn [& {:keys [kind slug status goal body item-idx item-done add-item after-idx tags]}]
    (let [k    (coerce-kind kind)
          dirs (current-dirs)
          st   (coerce-kw status)
          sub-ops (cond-> []
                    st                         (conj :status)
                    (some? goal)               (conj :goal)
                    (some? body)               (conj :body)
                    (some? item-idx)           (conj :item)
                    (and (some? add-item)
                         (not (str/blank? (str add-item))))
                    (conj :add-item))]
      (cond
        (nil? k)            (kind-error)
        (str/blank? slug)   {:error "slug is required"}
        (empty? sub-ops)
        {:error "no sub-op set — provide one of :status, :goal (todo), :body (plan), :item-idx + :item-done (todo), or :add-item (todo)"}
        (> (count sub-ops) 1)
        {:error (str "exactly one sub-op per call; got " (vec sub-ops))}
        (and (= k :plan) (#{:goal :item :add-item} (first sub-ops)))
        {:error (str (name (first sub-ops)) " is a todo-only sub-op")}
        (and (= k :todo) (= :body (first sub-ops)))
        {:error ":body is a plan-only sub-op (use :goal for todos)"}

        :else
        (case (first sub-ops)
          :status   (update-status k dirs slug st)
          :goal     (update-goal-todo dirs slug goal)
          :body     (update-body-plan dirs slug body)
          :item     (update-item-todo dirs slug item-idx (boolean item-done))
          :add-item (add-item-todo dirs slug add-item after-idx tags)))))
  :input-schema [:map
                 [:kind [:enum {:desc "Document kind: todo | plan"} "todo" "plan"]]
                 [:slug [:string {:desc "Doc slug"}]]
                 [:status {:optional true} [:string {:desc "draft | in-progress | completed | abandoned | reopen"}]]
                 [:goal {:optional true} [:string {:desc "Todo goal (todos only)"}]]
                 [:body {:optional true} [:string {:desc "Plan body (plans only)"}]]
                 [:item-idx {:optional true} [:int {:desc "Todo item index (0-based) — pair with :item-done"}]]
                 [:item-done {:optional true} [:boolean {:desc "true = mark done, false = reset to pending"}]]
                 [:add-item {:optional true} [:string {:desc "New todo item description (todos only)"}]]
                 [:after-idx {:optional true} [:int {:desc "Insert after this 0-based item index (default = append)"}]]
                 [:tags {:optional true} [:map {:desc "Per-item routing/coverage tags for :add-item (todos only): {:via #{:edit-agent :bash :mcp :manual :explore-agent :read-only} :covers [<criterion strings>]}"}]]]
  :output-schema [:map
                  [:slug {:optional true} [:string {:desc "Doc slug"}]]
                  [:status {:optional true} [:string {:desc "Updated status"}]]
                  [:items {:optional true} [:any {:desc "Items vector (todos)"}]]
                  [:goal {:optional true} [:string {:desc "Goal text (todos)"}]]
                  [:body {:optional true} [:string {:desc "Body markdown (plans)"}]]
                  [:updated {:optional true} [:string {:desc "Update timestamp"}]]
                  [:progress {:optional true} [:any {:desc "Progress map (todos)"}]]
                  [:error {:optional true} [:string {:desc "Error message if failed"}]]])

;; ============================================================================
;; doc$delete
;; ============================================================================

(defcommand doc$delete
  "Delete a todo or plan by slug."
  (fn [& {:keys [kind slug scope]}]
    (let [k    (coerce-kind kind)
          sc   (coerce-kw scope)
          dirs (current-dirs)]
      (cond
        (nil? k)            (kind-error)
        (str/blank? slug)   {:error "slug is required"}
        :else
        (let [result (case k
                       :todo (todo/delete-todo dirs slug :scope sc)
                       :plan (plan/delete-plan dirs slug :scope sc))]
          (when (and (= k :todo) (:deleted result))
            (todo/clear-st-memory-if-active! slug))
          result))))
  :input-schema [:map
                 [:kind [:enum {:desc "Document kind: todo | plan"} "todo" "plan"]]
                 [:slug [:string {:desc "Doc slug"}]]
                 [:scope {:optional true} [:string {:desc "project | user (omit to auto-detect)"}]]]
  :output-schema [:map
                  [:deleted {:optional true} [:string {:desc "Deleted doc slug"}]]
                  [:error {:optional true} [:string {:desc "Error message if failed"}]]])

;; ============================================================================
;; Export vector
;; ============================================================================

(def doc-commands
  "Polymorphic doc$* commands — the canonical todo/plan/note surface."
  [#'doc$list #'doc$read #'doc$create #'doc$update #'doc$delete])
