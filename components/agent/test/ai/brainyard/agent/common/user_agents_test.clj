;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.user-agents-test
  "Tests for runtime-defined (LLM-authored) agents.

   Exercises the lifecycle against the REAL tool registry: define-from-prose ->
   persist (directory of companion files) -> register as a :type :agent ->
   list/read/delete -> re-register from disk after a simulated restart. The
   rehydration test is the load-bearing one. No LLM is exercised (the optional
   :sample behavioral smoke is opt-in and needs a live agent)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.agent.common.user-agents :as ua]
            [ai.brainyard.agent.common.coact-agent]   ;; so run-coact-derived resolves
            [ai.brainyard.agent.core.agent :as agent]
            [ai.brainyard.agent.core.tool :as tool]))

(def ^:private test-dirs
  {:project-dir (str (System/getProperty "java.io.tmpdir") "/by-user-agents-test")})

(def ^:private our-ids
  [:user$agent$tf-reviewer :user$agent$copy-editor :user$agent$release-captain
   :user$agent$__sample])

(defn- agents-base-file []
  (io/file (str (:project-dir test-dirs) "/.brainyard/agents")))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (rm-rf! c)))
  (.delete f))

(defn- clean! []
  (ua/reset-loaded!)
  (apply swap! tool/!tool-defs dissoc our-ids)
  (rm-rf! (agents-base-file)))

(use-fixtures :each (fn [f] (clean!) (try (f) (finally (clean!)))))

(def ^:private instr "You are a Terraform reviewer. Read the diff, check tags, report violations.")

(deftest define-persists-and-registers
  (testing "define-agent writes the companion directory and registers :type :agent"
    (let [r (ua/define-agent :name "tf-reviewer"
              :description "Review Terraform diffs for tagging-policy compliance."
              :instruction instr
              :tool-context "Reach for bash (git diff), read-file, search."
              :dirs test-dirs)]
      (is (= :user$agent$tf-reviewer (:id r)))
      (is (= "tf-reviewer" (:name r)))
      (let [dir (:persisted r)]
        (is (.exists (io/file dir "agent.edn")))
        (is (.exists (io/file dir "instruction.md")))
        (is (.exists (io/file dir "tool-context.md"))))
      ;; agent.edn is stamped with created/updated (define-agent defaults :now)
      (let [rec (ua/read-user-agent test-dirs "tf-reviewer")]
        (is (string? (:created rec)))
        (is (string? (:updated rec))))
      (let [td (get @tool/!tool-defs :user$agent$tf-reviewer)]
        (is (= :agent (:type td)))
        (is (fn? (:fn td)))
        (is (true? (get-in td [:meta :user-defined])))
        (is (= "Review Terraform diffs for tagging-policy compliance."
               (get-in td [:meta :description])))
        ;; The whole design: a user agent binds NO tools — it rides the
        ;; inherited CoAct palette via run-coact-derived.
        (is (nil? (get-in td [:meta :agent-tools])))
        (is (fn? (get-in td [:meta :bt-factory])))))))

(deftest instance-id-is-namespaced
  (testing "generate-instance-id yields :user$agent$<name>/<suffix>"
    (ua/define-agent :name "tf-reviewer" :instruction instr :dirs test-dirs)
    (let [iid (agent/generate-instance-id :user$agent$tf-reviewer)]
      (is (= "user$agent$tf-reviewer" (namespace iid)))
      (is (string? (name iid))))))

(deftest list-read-delete
  (ua/define-agent :name "tf-reviewer" :description "Review TF diffs."
    :instruction instr :tool-context "bash, read-file." :dirs test-dirs)
  (testing "list-user-agents surfaces the registered agent"
    (is (some #(= "user$agent$tf-reviewer" (:id %)) (ua/list-user-agents)))
    (is (some #(= "user$agent$tf-reviewer" (:id %))
              (:agents (tool/invoke-tool :meta-agent$list {})))))
  (testing "read-user-agent returns the persisted prose"
    (let [r (ua/read-user-agent test-dirs "tf-reviewer")]
      (is (= "tf-reviewer" (:name r)))
      (is (= instr (:instruction r)))
      (is (str/includes? (:tool-context r) "read-file"))))
  (testing "delete-user-agent! unregisters and removes the directory"
    (let [dir (io/file (str (:project-dir test-dirs) "/.brainyard/agents/user$agent/tf-reviewer"))]
      (is (.exists dir))
      (is (= {:deleted "tf-reviewer"} (ua/delete-user-agent! test-dirs "tf-reviewer")))
      (is (not (contains? @tool/!tool-defs :user$agent$tf-reviewer)))
      (is (not (.exists dir)))))
  (testing "deleting a missing agent errors"
    (is (str/includes? (:error (ua/delete-user-agent! test-dirs "nope")) "no user agent"))))

(deftest re-create-overwrites
  (testing "re-creating the same name overwrites prose + bumps version"
    (ua/define-agent :name "tf-reviewer" :description "v1" :instruction instr :dirs test-dirs)
    (let [r (ua/define-agent :name "tf-reviewer" :description "v2"
              :instruction "Also check the environment tag." :dirs test-dirs)]
      (is (= :user$agent$tf-reviewer (:id r)))
      (is (= "v2" (get-in @tool/!tool-defs [:user$agent$tf-reviewer :meta :description])))
      (let [rec (ua/read-user-agent test-dirs "tf-reviewer")]
        (is (str/includes? (:instruction rec) "environment tag"))
        (is (= 2 (:version rec)))))))

(deftest rejects-bad-definitions
  (testing "invalid name"
    (is (thrown? Exception
                 (ua/define-agent :name "Bad Name" :instruction instr :dirs test-dirs))))
  (testing "blank instruction"
    (is (thrown? Exception
                 (ua/define-agent :name "okname" :instruction "  " :dirs test-dirs)))))

(deftest rehydrates-after-restart
  (testing "persisted directory survives a simulated restart (registry wiped)"
    (ua/define-agent :name "tf-reviewer" :instruction instr :dirs test-dirs)
    (ua/define-agent :name "copy-editor" :instruction "Edit for house style." :dirs test-dirs)
    ;; wipe live registry entries + the loaded-dirs set
    (apply swap! tool/!tool-defs dissoc our-ids)
    (ua/reset-loaded!)
    (is (not (contains? @tool/!tool-defs :user$agent$tf-reviewer)))
    (let [loaded (set (ua/load-user-agents! :dirs test-dirs))]
      (is (contains? loaded "tf-reviewer"))
      (is (contains? loaded "copy-editor")))
    (is (= :agent (:type (get @tool/!tool-defs :user$agent$tf-reviewer))))))

(deftest ensure-loaded-idempotent
  (testing "agents persist project-scoped under .brainyard/agents/user$agent"
    (let [r (ua/define-agent :name "tf-reviewer" :instruction instr :dirs test-dirs)]
      (is (str/ends-with? (:persisted r) "/.brainyard/agents/user$agent/tf-reviewer"))))
  (testing "ensure-loaded! loads once then no-ops for the same project dir"
    (apply swap! tool/!tool-defs dissoc our-ids)
    (ua/reset-loaded!)
    (is (= ["tf-reviewer"] (ua/ensure-loaded! :dirs test-dirs)))
    (is (contains? @tool/!tool-defs :user$agent$tf-reviewer))
    (is (nil? (ua/ensure-loaded! :dirs test-dirs)))))

(deftest read-falls-back-to-registry
  (testing "read-user-agent falls back to registry metadata when the dir is absent"
    (ua/register-agent! {:name "copy-editor" :description "Edit copy."
                         :instruction "Edit for house style." :tool-context "read-file."})
    (let [r (ua/read-user-agent test-dirs "copy-editor")]   ;; nothing on disk
      (is (= "copy-editor" (:name r)))
      (is (= "Edit copy." (:description r)))
      (is (str/includes? (:note r) "directory not on disk")))))

(deftest validate-dry-run
  (testing "a valid draft reports :valid true and REGISTERS NOTHING"
    (let [r (tool/invoke-tool :meta-agent$validate
                              {:name "tf-reviewer"
                               :instruction instr
                               :tool-context "Reach for bash, read-file, search."})]
      (is (true? (:valid r)))
      (is (true? (:name-ok r)))
      (is (true? (:instruction-ok r)))
      (is (false? (:collision r)))
      (is (empty? (:unknown-tools r)))
      (is (empty? (:errors r)))
      (is (not (contains? @tool/!tool-defs :user$agent$tf-reviewer))))))

(deftest validate-checks
  (testing "bad name flips :name-ok and populates :errors"
    (let [r (tool/invoke-tool :meta-agent$validate {:name "Bad Name" :instruction instr})]
      (is (false? (:valid r)))
      (is (false? (:name-ok r)))
      (is (some #(str/includes? % "^[a-z]") (:errors r)))))
  (testing "blank instruction flips :instruction-ok"
    (let [r (tool/invoke-tool :meta-agent$validate {:name "okname" :instruction "   "})]
      (is (false? (:valid r)))
      (is (false? (:instruction-ok r)))
      (is (some #(str/includes? % ":instruction") (:errors r)))))
  (testing ":name-ok is omitted when :name is not supplied"
    (let [r (tool/invoke-tool :meta-agent$validate {:instruction instr})]
      (is (true? (:valid r)))
      (is (not (contains? r :name-ok)))))
  (testing ":collision is true iff an agent with that name is already registered"
    (ua/define-agent :name "tf-reviewer" :instruction instr :dirs test-dirs)
    (is (true?  (:collision (tool/invoke-tool :meta-agent$validate
                                              {:name "tf-reviewer" :instruction instr}))))
    (is (false? (:collision (tool/invoke-tool :meta-agent$validate
                                              {:name "totally-fresh" :instruction instr})))))
  (testing ":unknown-tools lists $-command ids that don't resolve, ignores real ones"
    (let [r (tool/invoke-tool :meta-agent$validate
                              {:name "okname" :instruction instr
                               :tool-context "Use meta-agent$list and nope$missing."})]
      (is (contains? (set (:unknown-tools r)) "nope$missing"))
      (is (not (contains? (set (:unknown-tools r)) "meta-agent$list"))))))

(deftest command-error-paths
  (testing "meta-agent$create is registered and rejects a bad name without writing"
    (is (contains? (tool/get-tool-defs) :meta-agent$create))
    (let [r (tool/call-tool :meta-agent$create {:name "Bad Name" :instruction instr})]
      (is (str/includes? (:error r) "meta-agent$create failed"))))
  (testing "meta-agent$read / meta-agent$delete require :name"
    (is (str/includes? (:error (tool/invoke-tool :meta-agent$read {})) "name is required"))
    (is (str/includes? (:error (tool/invoke-tool :meta-agent$delete {})) "name is required"))))
