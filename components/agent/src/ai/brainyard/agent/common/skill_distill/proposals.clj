;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.skill-distill.proposals
  "Staged skill proposals — the review gate for the self-improvement loop
   (R1 — docs/design/self-improve-design.md).

   The distillation hook never writes a live skill. It stages a *proposal*
   under `<project>/.brainyard/skills/proposals/<name>/`:

     SKILL.md       — the drafted skill document
     proposal.edn   — metadata {:name :score :rationale :session :turn
                                 :source-question :kind :created-ts}

   A proposal is inert until a human runs `skill-proposal$accept`, which calls
   `skills$write :op :create` (the real skill writer) and then clears the
   staging dir. `skill-proposal$reject` just removes it. This mirrors the
   meta-agent `validate → create` split: stage first, persist on approval.

   Store functions take an explicit `project-dir` so the hook (whose
   `*current-agent*` is unbound inside its `future`) can pass the resolved
   root; the commands resolve it via `config/project-dir`. EDN is written with
   `pr-str` and read with `clojure.edn/read-string` — no eval, native-image
   safe."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.tool :as tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

;; ============================================================================
;; Layout
;; ============================================================================

(def ^:const proposal-meta-file "proposal.edn")
(def ^:const proposal-skill-file "SKILL.md")

(def ^:private name-re #"^[a-z][a-z0-9-]*$")

(defn valid-name?
  "True when `name` is a safe skill/proposal slug (lowercase-kebab, leading
   letter). Guards against path traversal and collisions with the dir layout."
  [name]
  (boolean (and (string? name) (re-matches name-re name))))

(defn ^File proposals-root
  "The `.brainyard/skills/proposals` dir for a project root (a File or string).
   Not created on demand."
  [project-dir]
  (io/file (str project-dir) ".brainyard" "skills" "proposals"))

(defn ^File proposal-dir
  "The staging dir for one proposal `name` under `project-dir`."
  [project-dir name]
  (io/file (proposals-root project-dir) name))

;; ============================================================================
;; Store
;; ============================================================================

(defn write-proposal!
  "Stage a skill proposal under `project-dir`. `m` carries:
     :name (required, valid-name?)  :skill-md (required, non-blank)
     :score :rationale :session :turn :source-question :kind
   Writes SKILL.md + proposal.edn. Returns {:name :path} or {:error}.
   Overwrites an existing proposal of the same name (latest wins)."
  [project-dir {:keys [name skill-md] :as m}]
  (cond
    (not (valid-name? name)) {:error (str "invalid proposal name: " (pr-str name))}
    (str/blank? skill-md)    {:error "skill-md is required"}
    :else
    (try
      (let [^File dir (proposal-dir project-dir name)
            meta (-> (select-keys m [:score :rationale :session :turn
                                     :source-question :kind :evidence])
                     (assoc :name name
                            :kind (or (:kind m) :distillation)
                            :created-ts (System/currentTimeMillis)))]
        (.mkdirs dir)
        (spit (io/file dir proposal-skill-file) skill-md)
        (spit (io/file dir proposal-meta-file) (pr-str meta))
        (mulog/log ::proposal-staged :name name :score (:score m) :kind (:kind meta))
        {:name name :path (str dir)})
      (catch Exception e
        (mulog/warn ::write-proposal-failed :name name :exception e)
        {:error (ex-message e)}))))

(defn read-proposal
  "Read one proposal by `name`: {:meta {...} :skill-md \"…\" :path \"…\"} or nil
   when absent / malformed."
  [project-dir name]
  (when (valid-name? name)
    (let [^File dir (proposal-dir project-dir name)
          ^File mf (io/file dir proposal-meta-file)
          ^File sf (io/file dir proposal-skill-file)]
      (when (.exists mf)
        (try
          {:meta     (edn/read-string (slurp mf))
           :skill-md (when (.exists sf) (slurp sf))
           :path     (str dir)}
          (catch Exception e
            (mulog/warn ::read-proposal-failed :name name :exception e)
            nil))))))

(defn list-proposals
  "All staged proposals' metadata under `project-dir` (newest first), each with
   a :path. Returns [] when none. Skips malformed entries."
  [project-dir]
  (let [^File root (proposals-root project-dir)]
    (if-not (.isDirectory root)
      []
      (->> (.listFiles root)
           (filter #(.isDirectory ^File %))
           (keep (fn [^File d]
                   (some-> (read-proposal project-dir (.getName d))
                           (as-> p (assoc (:meta p) :path (:path p))))))
           (sort-by :created-ts >)
           vec))))

(defn delete-proposal!
  "Remove a proposal's staging dir. Returns true when something was removed."
  [project-dir name]
  (when (valid-name? name)
    (let [^File dir (proposal-dir project-dir name)]
      (when (.isDirectory dir)
        (doseq [^File f (.listFiles dir)] (.delete f))
        (.delete dir)
        true))))

(defn accept-proposal!
  "Promote a staged proposal to a live skill, then clear the staging dir. The
   proposal's `:kind` picks the write op:
     :refinement  → `skills$write :op :update` (revise an existing skill;
                    scope auto-detected unless overridden)
     :distillation (default) → `skills$write :op :create` (new skill, project
                    scope unless overridden)
   Returns the skills$write result on success, or {:error} when the proposal is
   missing or the write fails (the staging dir is left intact on failure so the
   user can retry)."
  [project-dir name & {:keys [scope]}]
  (if-let [{:keys [skill-md meta]} (read-proposal project-dir name)]
    (if (str/blank? skill-md)
      {:error (str "proposal '" name "' has no SKILL.md content")}
      (let [refinement? (= :refinement (:kind meta))
            op          (if refinement? "update" "create")
            ;; :create defaults to project scope; :update auto-detects the
            ;; existing skill's scope unless the caller forces one.
            args        (cond-> {:op op :skill-name name :content skill-md}
                          (or scope (not refinement?))
                          (assoc :scope (or scope "project")))
            res         (apply tool/invoke-tool :skills$write (mapcat identity args))]
        (if (:error res)
          res
          (do (delete-proposal! project-dir name)
              (mulog/log ::proposal-accepted :name name :op op :path (:path res))
              (assoc res :accepted true :op op)))))
    {:error (str "no staged proposal named '" name "'")}))

;; ============================================================================
;; Commands — the user/agent-facing review surface
;; ============================================================================

(defcommand skill-proposal$list
  "List staged skill proposals awaiting review (auto-distilled SKILL.md drafts)."
  (fn [& _]
    {:proposals (list-proposals (config/project-dir))})
  :input-schema  [:map]
  :output-schema [:map
                  [:proposals [:vector {:desc "Proposal metadata maps (newest first)"} :any]]])

(defcommand skill-proposal$read
  "Read one staged skill proposal — its metadata and the drafted SKILL.md."
  (fn [& {:keys [name]}]
    (or (read-proposal (config/project-dir) name)
        {:error (str "no staged proposal named '" name "'")}))
  :input-schema  [:map
                  [:name [:string {:desc "Proposal name (the proposed skill slug)"}]]]
  :output-schema [:map
                  [:meta     {:optional true} [:any    {:desc "Proposal metadata"}]]
                  [:skill-md {:optional true} [:string {:desc "Drafted SKILL.md"}]]
                  [:path     {:optional true} [:string {:desc "Staging dir path"}]]
                  [:error    {:optional true} [:string {:desc "Error if absent"}]]])

(defcommand skill-proposal$accept
  "Accept a staged skill proposal: write it as a live skill (skills$write :create) and clear the staging dir."
  (fn [& {:keys [name scope]}]
    (accept-proposal! (config/project-dir) name :scope scope))
  :input-schema  [:map
                  [:name  [:string {:desc "Proposal name to accept"}]]
                  [:scope {:optional true} [:string {:desc "brainyard scope: project (default) | user"}]]]
  :output-schema [:map
                  [:name     {:optional true} [:string {:desc "Created skill name"}]]
                  [:path     {:optional true} [:string {:desc "Path to the new skill dir"}]]
                  [:accepted {:optional true} [:boolean {:desc "True when promoted"}]]
                  [:error    {:optional true} [:string {:desc "Error if absent or create failed"}]]])

(defcommand skill-proposal$reject
  "Reject (discard) a staged skill proposal — removes its staging dir."
  (fn [& {:keys [name]}]
    (if (delete-proposal! (config/project-dir) name)
      {:rejected name}
      {:error (str "no staged proposal named '" name "'")}))
  :input-schema  [:map
                  [:name [:string {:desc "Proposal name to reject"}]]]
  :output-schema [:map
                  [:rejected {:optional true} [:string {:desc "Rejected proposal name"}]]
                  [:error    {:optional true} [:string {:desc "Error if absent"}]]])

(def skill-proposal-commands
  "The proposal-review command family. Bound into skill-agent's roster so the
   skill specialist can list/read/accept/reject distilled proposals; also
   globally registered (defcommand) for direct invoke / TUI surfacing."
  [#'skill-proposal$list
   #'skill-proposal$read
   #'skill-proposal$accept
   #'skill-proposal$reject])
