;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.artifacts
  "LLM-facing live-artifact management.

   Live artifacts are reference material the model keeps in its own user-context
   `## Live Artifacts` section: skill files, docs, or inline notes that help it
   understand the working context. Two streams compose that section each turn
   (see `coact-agent/coact-init-action`):

   - SYSTEM artifacts — reference files named by config `:reference-artifact-paths`
     (default CLAUDE.md / AGENTS.md). Re-seeded fresh every turn, pinned, and
     NOT removable by the LLM. Not stored here.
   - DYNAMIC artifacts — added by the tools below. Stored in the agent's
     st-memory-init so they survive across turns within a session (the BT turn
     reset copies st-memory-init into the per-turn st-memory). File-backed
     artifacts (`:source :file`) reload their content fresh each turn; inline
     artifacts carry their content verbatim.

   Descriptor shape:
     {:id \"file:/abs\" :name \"...\" :source :file|:inline
      :path \"/abs\" | :content \"...\" :origin :llm :pinned? bool}"
  (:require [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.agent.core.protocol :as proto]
            [clojure.string :as str])
  (:import [java.io File]))

;; ── persistent (cross-turn) registry: st-memory-init ──────────────────────

(defn- init-atom
  "The agent's st-memory-init atom — the cross-turn store. Dynamic artifacts
   live here so they survive each turn's BT reset."
  []
  (some-> proto/*current-agent* proto/get-st-memory-init))

(defn- effective-artifacts
  "Everything the LLM can see or act on: the UNION (deduped by :id, bt first)
   of the set rendered THIS turn (per-turn BT st-memory — system + already-merged
   dynamic) and the persistent registry (st-memory-init — includes dynamic
   artifacts added this turn that surface in the prompt only next turn). Without
   the union, a just-added artifact would be invisible to list/remove until the
   next turn even though it is already registered."
  []
  (let [bt  (some-> proto/*current-agent* proto/get-bt-st-memory deref :live-artifacts)
        reg (some-> (init-atom) deref :live-artifacts)]
    (->> (concat (or bt []) (or reg []))
         (reduce (fn [acc a]
                   (let [k (:id a)]
                     (if (contains? (:seen acc) k)
                       acc
                       (-> acc (update :seen conj k) (update :out conj a)))))
                 {:seen #{} :out []})
         :out)))

(defn- slug [s]
  (-> (str s) str/trim str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn- upsert-into!
  "Add or replace (by :id) a descriptor in the registry atom `a`. Returns the
   descriptor, or nil when `a` is nil."
  [a descriptor]
  (when a
    (swap! a update :live-artifacts
           (fn [arts]
             (let [arts (vec (or arts []))
                   id   (:id descriptor)]
               (if (some #(= (:id %) id) arts)
                 (mapv #(if (= (:id %) id) descriptor %) arts)
                 (conj arts descriptor)))))
    descriptor))

(defn- upsert!
  "Upsert into the running agent's persistent registry (proto/*current-agent*).
   Returns the descriptor, or nil when no agent is running."
  [descriptor]
  (upsert-into! (init-atom) descriptor))

(defn- build-descriptor
  "Build a live-artifact descriptor from {:path|:content :name :pinned}, or
   return {:error …} when neither source is given / the :path file is missing.
   Shared by the artifact$add tool and the explicit-agent `add-artifact!`."
  [{:keys [path content name pinned]}]
  (cond
    (not (str/blank? (str path)))
    (let [f (File. ^String path)]
      (if-not (.isFile f)
        {:error (str "file not found: " path " (use an absolute path)")}
        (let [abs (.getCanonicalPath f)]
          {:id      (str "file:" abs)
           :name    (or (not-empty name) (.getName f))
           :source  :file
           :path    abs
           :origin  :llm
           :pinned? (boolean pinned)})))

    (not (str/blank? (str content)))
    (let [nm (or (not-empty name) "note")]
      {:id      (str "note:" (slug nm))
       :name    nm
       :source  :inline
       :content content
       :origin  :llm
       :pinned? (boolean pinned)})

    :else {:error "provide :path or :content"}))

(defn add-artifact!
  "Programmatic, explicit-agent live-artifact insert — for callers OFF the BT
   thread (e.g. the ask socket's `:op :inject :as :artifact`) where
   `proto/*current-agent*` is unbound. Builds the same descriptor as the
   artifact$add tool and upserts it into `agent`'s cross-turn st-memory-init, so
   the next turn sees it in `## Live Artifacts` (a file-backed artifact reloads
   fresh each turn — the data-connector pattern). Returns `{:id … :name …}` or
   `{:error …}`. Never relies on the dynamic var."
  [agent opts]
  (let [a (some-> agent proto/get-st-memory-init)]
    (if (nil? a)
      {:error "agent has no cross-turn store"}
      (let [d (build-descriptor opts)]
        (if (:error d)
          d
          (do (upsert-into! a d)
              {:id (:id d) :name (:name d)}))))))

;; ── tools ─────────────────────────────────────────────────────────────────

(defcommand artifact$add
  "Pin reference material into your Live Artifacts context — a file by absolute :path (reloaded fresh each turn, e.g. a skill's SKILL.md) or inline :content. Persists across turns this session."
  (fn [& {:as opts}]
    (if (nil? (init-atom))
      {:error "current agent is not running"}
      (let [d (build-descriptor opts)]
        (if (:error d)
          d
          (do (upsert! d)
              {:result (str "Pinned " (if (= :file (:source d)) "file" "note")
                            " '" (:name d) "' to Live Artifacts.")
               :id (:id d)})))))
  :input-schema  [:map
                  [:path       {:optional true} [:string {:desc "Absolute path to a file to pin, e.g. a skill's SKILL.md (reloaded fresh each turn)"}]]
                  [:content    {:optional true} [:string {:desc "Inline text to pin"}]]
                  [:name       {:optional true} [:string {:desc "Display heading (defaults from the skill/file name)"}]]
                  [:pinned     {:optional true} [:boolean {:desc "Protect from budget eviction (default false)"}]]]
  :output-schema [:map
                  [:result {:optional true} [:string {:desc "Confirmation message"}]]
                  [:id     {:optional true} [:string {:desc "Artifact id (use with artifact$remove)"}]]
                  [:error  {:optional true} [:string {:desc "Error if no source given or agent not running"}]]])

(defcommand artifact$list
  "List the artifacts currently in your Live Artifacts context (system + your own)."
  (fn [& _]
    (let [arts (effective-artifacts)]
      {:count (count arts)
       :artifacts (mapv (fn [{:keys [id name origin source pinned? content]}]
                          {:id id
                           :name name
                           :origin (clojure.core/name (or origin :llm))
                           :source (clojure.core/name (or source :inline))
                           :pinned (boolean pinned?)
                           :size (count (str content))})
                        arts)}))
  :input-schema  [:map]
  :output-schema [:map
                  [:count [:int {:desc "Number of live artifacts"}]]
                  [:artifacts [:any {:desc "Vector of {:id :name :origin :source :pinned :size}"}]]])

(defcommand artifact$remove
  "Remove one of your own artifacts from Live Artifacts by :id (see artifact$list). System reference files cannot be removed. Effective next turn."
  (fn [& {:keys [id force]}]
    (cond
      (nil? (init-atom)) {:error "current agent is not running"}
      (str/blank? id)    {:error ":id is required (see artifact$list)"}
      :else
      (let [a       (init-atom)
            present (some #(= (:id %) id) (effective-artifacts))
            target  (some #(when (= (:id %) id) %) (effective-artifacts))]
        (cond
          (and target (= (:origin target) :system) (not force))
          {:error (str "'" id "' is a system reference file and cannot be removed")}

          (not present)
          {:error (str "no artifact with id '" id "'")}

          :else
          (do
            (swap! a update :live-artifacts
                   (fn [arts] (vec (remove #(= (:id %) id) (or arts [])))))
            ;; Mirror into the per-turn store for consistency; the rendered
            ;; prompt already assembled this turn, so removal shows next turn.
            (some-> proto/*current-agent* proto/get-bt-st-memory
                    (swap! update :live-artifacts
                           (fn [arts] (vec (remove #(= (:id %) id) (or arts []))))))
            {:result (str "Removed '" id "' (effective next turn).")})))))
  :input-schema  [:map
                  [:id    [:string {:desc "Artifact id to remove (from artifact$list)"}]]
                  [:force {:optional true} [:boolean {:desc "Allow removing a system reference file"}]]]
  :output-schema [:map
                  [:result {:optional true} [:string {:desc "Confirmation message"}]]
                  [:error  {:optional true} [:string {:desc "Error if id missing/not found or artifact is system-owned"}]]])

(defcommand artifact$pin
  "Toggle whether one of your artifacts is protected from budget eviction. Effective next turn."
  (fn [& {:keys [id pinned]}]
    (cond
      (nil? (init-atom)) {:error "current agent is not running"}
      (str/blank? id)    {:error ":id is required (see artifact$list)"}
      :else
      (let [a   (init-atom)
            hit (atom false)]
        (swap! a update :live-artifacts
               (fn [arts]
                 (mapv (fn [d]
                         (if (= (:id d) id)
                           (do (reset! hit true)
                               (assoc d :pinned? (boolean pinned)))
                           d))
                       (vec (or arts [])))))
        (if @hit
          {:result (str (if pinned "Pinned" "Unpinned") " '" id "' (effective next turn).")}
          {:error (str "no dynamic artifact with id '" id "'")}))))
  :input-schema  [:map
                  [:id     [:string {:desc "Artifact id (from artifact$list)"}]]
                  [:pinned [:boolean {:desc "true to protect from eviction, false to allow it"}]]]
  :output-schema [:map
                  [:result {:optional true} [:string {:desc "Confirmation message"}]]
                  [:error  {:optional true} [:string {:desc "Error if id missing or not a dynamic artifact"}]]])

(def artifact-commands
  "Live-artifact management commands for inclusion in agent rosters."
  [#'artifact$add
   #'artifact$list
   #'artifact$remove
   #'artifact$pin])
