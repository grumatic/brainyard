;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.todo-test
  "Tests for persistent todo management (file-based, mirrors plan.clj)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.brainyard.agent.common.todo :as todo]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.agent.core.protocol :as proto]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "todo-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    (binding [*test-dir* (.getPath dir)]
      (try
        (f)
        (finally
          (doseq [f (reverse (file-seq dir))]
            (.delete f)))))))

(use-fixtures :each temp-dir-fixture)

(defn test-dirs []
  {:project-dir *test-dir*})

;; ============================================================================
;; Markdown Round-Trip
;; ============================================================================

(deftest test-todo-md-roundtrip
  (let [td {:id "test-uuid"
            :title "Ship TUI fix"
            :slug "ship-tui-fix"
            :scope :project
            :status :draft
            :created "2026-04-22T00:00:00Z"
            :updated "2026-04-22T00:00:00Z"
            :goal "Unblock QA by fixing the Ctrl+B binding regression."
            :items [{:description "Reproduce on main" :done false}
                    {:description "Write failing test" :done true}
                    {:description "Patch handler" :done false}]}
        md (todo/todo->md td)
        parsed (todo/md->todo md)]
    (testing "frontmatter preserved"
      (is (= "test-uuid" (:id parsed)))
      (is (= "Ship TUI fix" (:title parsed)))
      (is (= :project (:scope parsed)))
      (is (= :draft (:status parsed))))
    (testing "goal preserved"
      (is (str/includes? (:goal parsed) "Unblock QA")))
    (testing "items preserved"
      (is (= 3 (count (:items parsed))))
      (is (= "Reproduce on main" (:description (first (:items parsed)))))
      (is (false? (:done (first (:items parsed)))))
      (is (true? (:done (second (:items parsed))))))))

(deftest test-todo-md-no-goal
  (testing "todo with no goal still round-trips cleanly"
    (let [td {:id "u" :title "Quick todo" :slug "quick-todo"
              :scope :project :status :draft
              :created "2026-04-22T00:00:00Z" :updated "2026-04-22T00:00:00Z"
              :items [{:description "do a thing" :done false}]}
          parsed (todo/md->todo (todo/todo->md td))]
      (is (nil? (:goal parsed)))
      (is (= 1 (count (:items parsed)))))))

;; ============================================================================
;; CRUD Operations
;; ============================================================================

(deftest test-create-and-read-todo
  (let [dirs (test-dirs)
        result (todo/create-todo dirs :project "Deploy service"
                                 "Ship v2 to prod"
                                 [{:description "Run tests"}
                                  {:description "Build image"}
                                  {:description "Deploy"}])]
    (testing "create returns todo map with random slug"
      (is (string? (:id result)))
      (is (= "Deploy service" (:title result)))
      (is (string? (:slug result)))
      (is (= :draft (:status result)))
      (is (= 3 (count (:items result)))))
    (testing "goal is stored"
      (is (= "Ship v2 to prod" (:goal result))))
    (testing "file-path is absolute and exists"
      (is (string? (:file-path result)))
      (is (.isAbsolute (io/file (:file-path result))))
      (is (.exists (io/file (:file-path result)))))
    (testing "read-todo returns same data"
      (let [slug (:slug result)
            read-result (todo/read-todo dirs slug)]
        (is (= "Deploy service" (:title read-result)))
        (is (= 3 (count (:items read-result))))
        (is (str/includes? (:goal read-result) "Ship v2"))
        (is (string? (:file-path read-result)))))))

(deftest test-create-todo-skips-items-without-description
  (testing "items missing :description are dropped (not saved as nil)"
    (let [dirs (test-dirs)
          result (todo/create-todo dirs :project "Guard" ""
                                   [{:description "keep me"}
                                    {:wrong-key "ignore me"}])]
      (is (= 1 (count (:items result))))
      (is (= "keep me" (:description (first (:items result))))))))

(deftest test-create-todo-blank-goal-omitted
  (let [dirs (test-dirs)
        result (todo/create-todo dirs :project "No goal todo" ""
                                 [{:description "Step 1"}])]
    (is (nil? (:goal result)))
    (let [read-back (todo/read-todo dirs (:slug result))]
      (is (nil? (:goal read-back))))))

(deftest test-read-todo-not-found
  (let [result (todo/read-todo (test-dirs) "nonexistent")]
    (is (contains? result :error))
    (is (str/includes? (:error result) "not found"))))

(deftest test-list-todos
  (let [dirs (test-dirs)
        _ (todo/create-todo dirs :project "T1" "goal1" [{:description "a"}])
        _ (todo/create-todo dirs :project "T2" "goal2" [{:description "b"} {:description "c"}])
        todos (todo/list-todos dirs :scope :project)]
    (testing "list-todos returns lightweight summaries"
      (is (= 2 (count todos)))
      (is (every? :slug todos))
      (is (every? :title todos))
      (is (every? :item-progress todos))
      (is (every? :file-path todos)))))

(deftest test-list-todos-ignores-plan-files
  (testing "list-todos only picks up files with file-type: todo, skipping plans (file-type: plan) and loose .md files in the same directory"
    (let [dirs (test-dirs)
          dir (io/file (:project-dir dirs) ".brainyard" "plans")
          _ (.mkdirs dir)
          ;; plant a plan-shaped .md file (file-type: plan)
          plan-file (io/file dir "mixed-plan.md")
          _ (spit plan-file "---\nid: p\nfile-type: plan\ntitle: P\nscope: project\nstatus: draft\ncreated: x\nupdated: x\n---\n# P\n## Context\nplan body\n")
          ;; plant a loose markdown file (no file-type)
          readme (io/file dir "README.md")
          _ (spit readme "# Just a README\n")
          ;; create a real todo
          _ (todo/create-todo dirs :project "T" "goal" [{:description "x"}])
          todos (todo/list-todos dirs :scope :project)]
      (is (= 1 (count todos)))
      (is (= "T" (:title (first todos)))))))

(deftest test-todo-exists
  (let [dirs (test-dirs)
        t (todo/create-todo dirs :project "Exists?" "" [{:description "x"}])]
    (is (true? (todo/todo-exists? dirs (:slug t))))
    (is (false? (todo/todo-exists? dirs "nope")))))

(deftest test-delete-todo
  (let [dirs (test-dirs)
        t (todo/create-todo dirs :project "To delete" "" [{:description "x"}])
        slug (:slug t)
        del (todo/delete-todo dirs slug)]
    (is (= slug (:deleted del)))
    (is (false? (todo/todo-exists? dirs slug)))))

;; ============================================================================
;; update-todo CRUD: field preservation + error paths
;; ============================================================================

(deftest test-update-todo-preserves-id-and-created
  (testing "update-todo keeps :id and :created, refreshes :updated"
    (let [dirs (test-dirs)
          t (todo/create-todo dirs :project "Keep meta" "g" [{:description "a"}])
          orig-id (:id t)
          orig-created (:created t)
          _ (Thread/sleep 10)
          updated (todo/update-todo dirs (assoc t :title "Keep meta (renamed)"))
          read-back (todo/read-todo dirs (:slug t))]
      (is (= orig-id (:id read-back)))
      (is (= orig-created (:created read-back)))
      (is (= "Keep meta (renamed)" (:title read-back)))
      (is (not= orig-created (:updated updated)))
      (is (string? (:file-path updated))))))

(deftest test-update-todo-status-transitions
  (testing "update-todo persists status changes"
    (let [dirs (test-dirs)
          t (todo/create-todo dirs :project "Status" "" [{:description "x"}])]
      (is (= :draft (:status t)))
      (todo/update-todo dirs (assoc t :status :in-progress))
      (is (= :in-progress (:status (todo/read-todo dirs (:slug t)))))
      (todo/update-todo dirs (assoc t :status :completed))
      (is (= :completed (:status (todo/read-todo dirs (:slug t))))))))

(deftest test-delete-todo-not-found
  (let [dirs (test-dirs)
        result (todo/delete-todo dirs "does-not-exist")]
    (is (contains? result :error))
    (is (str/includes? (:error result) "not found"))))

(deftest test-delete-todo-explicit-scope
  (testing "delete-todo honors :scope kwarg — wrong scope doesn't find it"
    (let [dirs (test-dirs)
          t (todo/create-todo dirs :project "Scoped" "" [{:description "x"}])]
      (is (contains? (todo/delete-todo dirs (:slug t) :scope :user) :error))
      (is (true? (todo/todo-exists? dirs (:slug t))))
      (is (= (:slug t) (:deleted (todo/delete-todo dirs (:slug t) :scope :project)))))))

;; ============================================================================
;; list-todos: status + scope filters
;; ============================================================================

(deftest test-list-todos-filters-by-status
  (let [dirs (test-dirs)
        t1 (todo/create-todo dirs :project "Draft1" "" [{:description "a"}])
        t2 (todo/create-todo dirs :project "Draft2" "" [{:description "b"}])
        _  (todo/update-todo dirs (assoc t2 :status :completed))
        ;; Scope to :project so the user's real `~/.brainyard/todos/`
        ;; (legacy fallback location) doesn't leak into the count.
        drafts (todo/list-todos dirs :scope :project :status :draft)
        done   (todo/list-todos dirs :scope :project :status :completed)]
    (is (= 1 (count drafts)))
    (is (= "Draft1" (:title (first drafts))))
    (is (= 1 (count done)))
    (is (= "Draft2" (:title (first done))))))

(deftest test-list-todos-item-progress
  (testing "item-progress reflects done/total counts"
    (let [dirs (test-dirs)
          t (todo/create-todo dirs :project "Progress" ""
                              [{:description "a"} {:description "b"} {:description "c"}])
          _ (todo/update-todo dirs (todo/mark-item-done t 0))
          summary (first (todo/list-todos dirs))]
      (is (= "1/3" (:item-progress summary))))))

;; ============================================================================
;; Item Operations
;; ============================================================================

(deftest test-mark-item-done
  (let [td {:items [{:description "a" :done false}
                    {:description "b" :done false}]}
        updated (todo/mark-item-done td 0)]
    (is (true? (:done (first (:items updated)))))
    (is (false? (:done (second (:items updated)))))))

(deftest test-mark-item-done-out-of-bounds
  (let [td {:items [{:description "a" :done false}]}]
    (is (= td (todo/mark-item-done td 5)))
    (is (= td (todo/mark-item-done td -1)))))

(deftest test-add-item
  (let [td {:items [{:description "a" :done false}]}
        after (todo/add-item td "b")]
    (is (= 2 (count (:items after))))
    (is (= "b" (:description (second (:items after))))))
  (testing "insert after index"
    (let [td {:items [{:description "a" :done false}
                      {:description "c" :done false}]}
          after (todo/add-item td "b" :after-idx 0)]
      (is (= ["a" "b" "c"] (mapv :description (:items after)))))))

(deftest test-reset-item
  (let [td {:items [{:description "a" :done true}
                    {:description "b" :done true}]}
        r (todo/reset-item td 0)]
    (is (false? (:done (first (:items r)))))
    (is (true? (:done (second (:items r)))))))

(deftest test-update-goal
  (let [td {:title "T" :goal "old"}
        u (todo/update-goal td "new")]
    (is (= "new" (:goal u))))
  (testing "blank goal removes the key"
    (let [td {:title "T" :goal "old"}
          u (todo/update-goal td "")]
      (is (not (contains? u :goal))))))

(deftest test-reopen-todo
  (let [td {:status :completed
            :items [{:description "a" :done true}
                    {:description "b" :done true}]}
        r (todo/reopen-todo td)]
    (is (= :draft (:status r)))
    (is (every? #(false? (:done %)) (:items r)))))

(deftest test-todo-progress
  (let [td {:items [{:description "a" :done true}
                    {:description "b" :done true}
                    {:description "c" :done false}
                    {:description "d" :done false}]}
        p (todo/todo-progress td)]
    (is (= 2 (:completed p)))
    (is (= 2 (:pending p)))
    (is (= 4 (:total p)))
    (is (= 50.0 (:percent p)))
    (is (= "c" (:description (:next-item p)))))
  (testing "empty todo is 100% complete"
    (is (= 100.0 (:percent (todo/todo-progress {:items []}))))))

;; ============================================================================
;; Full-cycle update
;; ============================================================================

;; ============================================================================
;; st-memory mirror via tool dispatcher
;; ============================================================================

(defn- fake-agent-with-st-memory [dirs st-memory]
  {:!session (atom {:config {:dirs dirs}})
   :!state   (atom {:behavior-tree {:context {:st-memory st-memory}}})})

(deftest test-tool-item-idx-out-of-range
  (testing "doc$update returns explicit errors for OOB item-idx"
    (let [dirs (test-dirs)
          st-memory (atom {})
          agent (fake-agent-with-st-memory dirs st-memory)]
      (binding [proto/*current-agent* agent]
        (let [t (tool/call-tool :doc$create
                                {:kind "todo" :title "OOB" :goal ""
                                 :items [{:description "a"} {:description "b"}]
                                 :scope "project"})
              slug (:slug t)]
          (testing "idx past last item"
            (let [r (tool/call-tool :doc$update {:kind "todo" :slug slug :item-idx 99 :item-done true})]
              (is (str/includes? (:error r) "out of range"))
              (is (str/includes? (:error r) "0..1"))))
          (testing "idx = count (off-by-one boundary)"
            (let [r (tool/call-tool :doc$update {:kind "todo" :slug slug :item-idx 2 :item-done true})]
              (is (str/includes? (:error r) "out of range"))))
          (testing "negative idx"
            (let [r (tool/call-tool :doc$update {:kind "todo" :slug slug :item-idx -1 :item-done false})]
              (is (str/includes? (:error r) "out of range"))))
          (testing "valid idx still works"
            (let [r (tool/call-tool :doc$update {:kind "todo" :slug slug :item-idx 0 :item-done true})]
              (is (nil? (:error r)))
              (is (true? (:done (first (:items r)))))))
          (testing "empty-items todo reports out-of-range error"
            (let [empty (tool/call-tool :doc$create {:kind "todo" :title "E" :goal "" :items [] :scope "project"})
                  r (tool/call-tool :doc$update
                                    {:kind "todo" :slug (:slug empty) :item-idx 0 :item-done true})]
              (is (str/includes? (:error r) "out of range")))))))))

(deftest test-st-memory-mirror-on-tool-calls
  (let [dirs (test-dirs)
        st-memory (atom {})
        agent (fake-agent-with-st-memory dirs st-memory)]
    (binding [proto/*current-agent* agent]
      (testing "doc$create mirrors items + active-slug"
        (let [created (tool/call-tool :doc$create
                                      {:kind "todo"
                                       :title "Mirror"
                                       :goal "g"
                                       :items [{:description "a"} {:description "b"}]
                                       :scope "project"})]
          (is (= ["a" "b"] (mapv :description (:todo-list @st-memory))))
          (is (= (:slug created) (:active-todo-slug @st-memory)))))
      (testing "doc$update :item-done mirrors updated done flags"
        (let [slug (:active-todo-slug @st-memory)]
          (tool/call-tool :doc$update {:kind "todo" :slug slug :item-idx 0 :item-done true})
          (is (= [true false] (mapv :done (:todo-list @st-memory))))))
      (testing "doc$update :add-item mirrors new item"
        (let [slug (:active-todo-slug @st-memory)]
          (tool/call-tool :doc$update {:kind "todo" :slug slug :add-item "c"})
          (is (= ["a" "b" "c"] (mapv :description (:todo-list @st-memory))))))
      (testing "doc$delete clears st-memory when deleting active slug"
        (let [slug (:active-todo-slug @st-memory)]
          (tool/call-tool :doc$delete {:kind "todo" :slug slug})
          (is (= [] (:todo-list @st-memory)))
          (is (nil? (:active-todo-slug @st-memory))))))))

(deftest test-update-todo-roundtrip
  (let [dirs (test-dirs)
        t (todo/create-todo dirs :project "Cycle" "goal"
                            [{:description "a"} {:description "b"}])
        slug (:slug t)
        marked (-> t (todo/mark-item-done 0) (assoc :status :in-progress))
        _ (todo/update-todo dirs marked)
        read-back (todo/read-todo dirs slug)]
    (is (= :in-progress (:status read-back)))
    (is (true? (:done (first (:items read-back)))))
    (is (false? (:done (second (:items read-back)))))))

;; ============================================================================
;; Item :tags schema (todo-agent v2)
;; ============================================================================

(deftest test-tagged-items-roundtrip
  (testing "items with :tags {:via :covers} round-trip through markdown"
    (let [dirs (test-dirs)
          t1 (todo/create-todo
              dirs :project "Tagged" "ship it"
              [{:description "Wire LD flag in src/checkout/flags.clj"
                :tags {:via :update-agent
                       :covers ["feature-flag toggleable"]}}
               {:description "Run bb test:component checkout"
                :tags {:via :bash
                       :covers ["all unit tests green"]}}
               {:description "Sample Grafana p99 manually"
                :tags {:via :manual
                       :covers ["p99 unchanged"]}}])
          slug (:slug t1)
          t2 (todo/read-todo dirs slug)]
      (is (= [:update-agent :bash :manual]
             (mapv #(get-in % [:tags :via]) (:items t2))))
      (is (= ["feature-flag toggleable"]
             (get-in (nth (:items t2) 0) [:tags :covers])))
      (is (= ["all unit tests green"]
             (get-in (nth (:items t2) 1) [:tags :covers])))))

  (testing "items WITHOUT :tags round-trip unchanged (no :tags key after read)"
    (let [dirs (test-dirs)
          t1 (todo/create-todo dirs :project "Legacy" ""
                               [{:description "do A"} {:description "do B"}])
          slug (:slug t1)
          t2 (todo/read-todo dirs slug)]
      (is (every? (complement :tags) (:items t2))
          "items spawned without :tags must NOT carry :tags after round-trip")))

  (testing "mixed tagged + untagged items in one todo"
    (let [dirs (test-dirs)
          t1 (todo/create-todo
              dirs :project "Mixed" ""
              [{:description "no tags"}
               {:description "tagged"
                :tags {:via :update-agent :covers ["x"]}}
               {:description "also untagged"}])
          [a b c] (:items (todo/read-todo dirs (:slug t1)))]
      (is (nil? (:tags a)))
      (is (= :update-agent (get-in b [:tags :via])))
      (is (= ["x"] (get-in b [:tags :covers])))
      (is (nil? (:tags c)))))

  (testing "add-item with :tags persists through update-todo"
    (let [dirs (test-dirs)
          t (todo/create-todo dirs :project "Growing" ""
                              [{:description "start"}])
          slug (:slug t)
          grown (todo/add-item (todo/read-todo dirs slug) "next"
                               :tags {:via :explore-agent
                                      :covers ["map area"]})
          _ (todo/update-todo dirs grown)
          reread (todo/read-todo dirs slug)]
      (is (= :explore-agent (get-in (last (:items reread)) [:tags :via])))
      (is (= ["map area"] (get-in (last (:items reread)) [:tags :covers]))))))

;; ============================================================================
;; Dual-read fallback (legacy `.brainyard/todos/` → new `.brainyard/agents/todo-agent/todos/`)
;; ============================================================================

(defn- write-legacy-todo-md!
  "Materialize a todo file under <base>/.brainyard/todos/<slug>.md
   (legacy path) with valid frontmatter. Used to simulate a pre-migration
   repo."
  [base slug title]
  (let [f (io/file base ".brainyard/todos" (str slug ".md"))]
    (.mkdirs (.getParentFile f))
    (spit f (str "---\n"
                 "id: 11111111-1111-1111-1111-111111111111\n"
                 "file-type: todo\n"
                 "title: " title "\n"
                 "scope: project\nstatus: draft\n"
                 "created: 2026-05-09T00:00:00Z\n"
                 "updated: 2026-05-09T00:00:00Z\n"
                 "---\n\n# " title "\n\n## Todo\n- [ ] legacy item\n"))))

(deftest test-dual-read-fallback
  (let [dirs (test-dirs)]
    (write-legacy-todo-md! *test-dir* "legacy-only" "Legacy Only")

    (testing "read-todo finds a legacy todo when the new path is absent"
      (let [t (todo/read-todo dirs "legacy-only")]
        (is (= "Legacy Only" (:title t)))
        (is (str/includes? (:file-path t) "/.brainyard/todos/")
            "file-path points at legacy until migration")))

    (testing "todo-exists? is true for legacy todos"
      (is (todo/todo-exists? dirs "legacy-only")))

    (testing "list-todos tags legacy entries with :layout :legacy"
      (let [entries (todo/list-todos dirs :scope :project)
            legacy  (some (fn [e] (when (= "legacy-only" (:slug e)) e)) entries)]
        (is (some? legacy))
        (is (= :legacy (:layout legacy)))))))

(deftest test-dual-read-new-wins-on-collision
  (let [dirs (test-dirs)
        ;; Set up the SAME slug at both layouts.
        _ (write-legacy-todo-md! *test-dir* "shared-slug" "Legacy Title")
        _ (todo/create-todo dirs :project "New Title" "" [{:description "x"}])
        new-shared (io/file *test-dir* ".brainyard/agents/todo-agent/todos/shared-slug.md")
        _ (do (.mkdirs (.getParentFile new-shared))
              (spit new-shared (str "---\n"
                                    "id: 22222222-2222-2222-2222-222222222222\n"
                                    "file-type: todo\n"
                                    "title: New Title\n"
                                    "scope: project\nstatus: draft\n"
                                    "created: 2026-05-10T00:00:00Z\n"
                                    "updated: 2026-05-10T00:00:00Z\n"
                                    "---\n\n# New Title\n\n## Todo\n- [ ] new item\n")))]

    (testing "read-todo returns the new-layout file when both exist"
      (let [t (todo/read-todo dirs "shared-slug")]
        (is (= "New Title" (:title t)))
        (is (str/includes? (:file-path t) "/.brainyard/agents/todo-agent/todos/"))))

    (testing "list-todos dedups: shared-slug appears once, tagged :new"
      (let [entries (todo/list-todos dirs :scope :project)
            shared  (filter (fn [e] (= "shared-slug" (:slug e))) entries)]
        (is (= 1 (count shared)))
        (is (= :new (:layout (first shared))))
        (is (= "New Title" (:title (first shared))))))))

(deftest test-create-todo-writes-to-new-path
  (let [dirs (test-dirs)
        t (todo/create-todo dirs :project "Path verify" "" [{:description "x"}])]
    (is (str/includes? (:file-path t) "/.brainyard/agents/todo-agent/todos/")
        "create-todo must write to the new path")
    (is (not (str/includes? (:file-path t) "/.brainyard/todos/"))
        "the legacy substring is mutually exclusive with the new substring")))

;; ============================================================================
;; todo$dossier-* helper unit tests
;; ============================================================================

(deftest test-todo-dossier-slug-determinism
  (testing "same question → same slug"
    (let [q "Spawn a todo for ship checkout v2"
          s1 (:slug (todo/todo$dossier-slug :question q))
          s2 (:slug (todo/todo$dossier-slug :question q))]
      (is (= s1 s2))
      (is (= "ship-checkout-v2" s1)
          "stopwords (incl. 'todo' and 'spawn') drop, leaving the verbs")))

  (testing "blank → fallback slug 'todo'"
    (is (= "todo" (:slug (todo/todo$dossier-slug :question ""))))
    (is (= "todo" (:slug (todo/todo$dossier-slug :question "todo todo")))))

  (testing "validation"
    (is (contains? (todo/todo$dossier-slug :question 123) :error))))

(deftest test-todo-dossier-frontmatter-roundtrip
  (let [tmp (.getCanonicalPath
             (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "todo-doss-rt-" (System/currentTimeMillis)))
               .mkdirs))]
    (try
      (let [{:keys [frontmatter]}
            (todo/todo$dossier-frontmatter
             :slug "ship-v2"
             :todo-path ".brainyard/agents/todo-agent/todos/ship-v2.md"
             :todo-status "in-progress"
             :source {:plan_dossier ".brainyard/agents/plan-agent/dossiers/x.md"
                      :plan_path ".brainyard/agents/plan-agent/plans/ship-v2.md"
                      :plan_slug "ship-v2"}
             :pre {:verdict :go
                   :checks {:c1_plan_dossier :pass :c2_plan_postflight :pass}
                   :acceptance ["feature-flag toggleable" "all unit tests green"]}
             :author {:action :spawned :item_count 4}
             :post {:verdict :pass
                    :rubric {:r1 :pass :r2 :pass}
                    :acceptance_coverage {"feature-flag toggleable" [0 1]
                                          "all unit tests green" [2 3]}
                    :item_count 4}
             :handoff {:next_agent "exec-agent"
                       :next_call "(call-tool ...)"})
            {:keys [path]} (todo/todo$dossier-write
                            :slug "ship-v2" :content (str frontmatter "\n# body\n")
                            :base-dir tmp)
            parsed (todo/todo$read-dossier :path path :base-dir tmp)]

        (testing "scalar keys"
          (is (= "ship-v2" (:slug parsed)))
          (is (= "todo-agent" (:agent parsed)))
          (is (= ".brainyard/agents/todo-agent/todos/ship-v2.md" (:todo_path parsed)))
          (is (= "in-progress" (:todo_status parsed))))

        (testing "source sub-block (plan reference)"
          (is (= ".brainyard/agents/plan-agent/dossiers/x.md"
                 (get-in parsed [:source :plan_dossier])))
          (is (= "ship-v2" (get-in parsed [:source :plan_slug]))))

        (testing "pre sub-block carries acceptance from plan"
          (is (= "go" (get-in parsed [:pre :verdict])))
          (is (= ["feature-flag toggleable" "all unit tests green"]
                 (get-in parsed [:pre :acceptance]))))

        (testing "author sub-block"
          (is (= "spawned" (get-in parsed [:author :action])))
          (is (= 4 (get-in parsed [:author :item_count]))))

        (testing "post sub-block + acceptance_coverage round-trips as raw flow-map string"
          (is (= "pass" (get-in parsed [:post :verdict])))
          (is (string? (get-in parsed [:post :acceptance_coverage])))
          ;; Criterion strings have spaces → keys get YAML-quoted.
          (is (str/includes? (get-in parsed [:post :acceptance_coverage])
                             "\"feature-flag toggleable\": [0, 1]"))
          (is (str/includes? (get-in parsed [:post :acceptance_coverage])
                             "\"all unit tests green\": [2, 3]")))

        (testing "handoff next_agent"
          (is (= "exec-agent" (get-in parsed [:handoff :next_agent])))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))]
          (.delete f))))))

(deftest test-todo-dossier-write-collision
  (let [tmp (.getCanonicalPath
             (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "todo-doss-coll-" (System/currentTimeMillis)))
               .mkdirs))]
    (try
      ;; dossier writers require YAML frontmatter on :content
      (let [fm "---\nslug: rerun\n---\n"
            r1 (todo/todo$dossier-write :slug "rerun" :content (str fm "x1") :base-dir tmp)
            r2 (todo/todo$dossier-write :slug "rerun" :content (str fm "x2") :base-dir tmp)
            r3 (todo/todo$dossier-write :slug "rerun" :content (str fm "x3") :base-dir tmp)]
        (is (= "rerun"   (:slug r1)))
        (is (= "rerun-2" (:slug r2)))
        (is (= "rerun-3" (:slug r3))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))]
          (.delete f))))))

(deftest test-todo-dossier-index-prepend-ordering
  (let [tmp (.getCanonicalPath
             (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "todo-doss-idx-" (System/currentTimeMillis)))
               .mkdirs))]
    (try
      (todo/todo$dossier-index-append :path "dossiers/a.md" :slug "alpha"
                                      :pre-verdict :go :post-verdict :pass
                                      :next-agent :exec-agent :base-dir tmp)
      (todo/todo$dossier-index-append :path "dossiers/b.md" :slug "beta"
                                      :pre-verdict :gather :next-agent :user
                                      :base-dir tmp)
      (todo/todo$dossier-index-append :path "dossiers/c.md" :slug "gamma"
                                      :pre-verdict :refuse :next-agent :none
                                      :base-dir tmp)
      (let [content (slurp (io/file tmp ".brainyard/agents/todo-agent/INDEX.md"))
            lines   (->> (str/split-lines content) (remove str/blank?))]
        (is (= 3 (count lines)))
        ;; Newest first
        (is (str/includes? (nth lines 0) "gamma"))
        (is (str/includes? (nth lines 0) "pre:refuse"))
        (is (str/includes? (nth lines 0) "→ none"))
        (is (str/includes? (nth lines 1) "beta"))
        (is (str/includes? (nth lines 1) "post:n/a"))
        (is (str/includes? (nth lines 2) "alpha"))
        (is (str/includes? (nth lines 2) "post:pass"))
        (is (str/includes? (nth lines 2) "→ exec-agent")))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))]
          (.delete f))))))

(deftest test-todo-next-handoff-variants
  (testing "PASS recommends exec-agent (key difference vs plan-agent's todo-agent)"
    (let [r (todo/todo$next-handoff :pre {:verdict :go} :post {:verdict :pass}
                                    :slug "x" :dossier-path "doss.md")]
      (is (= "exec-agent" (:next-agent r)))
      (is (str/includes? (:next-call r) "exec-agent"))
      (is (str/includes? (:next-call r) ":agent-context \"doss.md\""))))

  (testing "HOLD recommends user (resolve holds, then re-call todo-agent)"
    (let [r (todo/todo$next-handoff :pre {:verdict :go} :post {:verdict :hold})]
      (is (= "user" (:next-agent r)))
      (is (str/includes? (str/lower-case (:next-call r)) "hold"))))

  (testing "GATHER recommends user (typically run plan-agent first)"
    (let [r (todo/todo$next-handoff :pre {:verdict :gather})]
      (is (= "user" (:next-agent r)))
      (is (str/includes? (:next-call r) "plan-agent"))))

  (testing "REFUSE recommends none"
    (let [r (todo/todo$next-handoff :pre {:verdict :refuse})]
      (is (= "none" (:next-agent r))))))

;; ============================================================================
;; todo-auto-persist hook
;; ============================================================================

(defn- fake-todo-agent []
  (reify ai.brainyard.agent.core.protocol/IAgent
    (agent-id [_] :todo-agent/test-instance)
    (agent-name [_] "test")
    (agent-description [_] "test")
    (user-id [_] "u1")
    (session-id [_] "s1")
    (defagent-type [_] :todo-agent)
    (process [_ _ _] nil)
    (get-tools [_] [])
    (get-state [_] {})))

(defn- fake-other-agent []
  (reify ai.brainyard.agent.core.protocol/IAgent
    (agent-id [_] :plan-agent/test-instance)
    (agent-name [_] "test")
    (agent-description [_] "test")
    (user-id [_] "u1")
    (session-id [_] "s1")
    (defagent-type [_] :plan-agent)
    (process [_ _ _] nil)
    (get-tools [_] [])
    (get-state [_] {})))

(deftest test-materialize-todo-auto-dossier-pass
  (testing "PASS-shaped answer materializes dossier with → exec-agent"
    (let [tmp (.getCanonicalPath
               (doto (io/file (System/getProperty "java.io.tmpdir")
                              (str "todo-auto-pass-" (System/currentTimeMillis)))
                 .mkdirs))]
      (try
        (let [answer (str "## Todo spawned\n\n"
                          "Saved todo: .brainyard/agents/todo-agent/todos/ship-v2.md\n"
                          "Next: (call-tool \"exec-agent\" {...})\n")
              r (todo/materialize-todo-auto-dossier!
                 {:answer answer :question "Spawn ship-v2 todo" :base-dir tmp})]
          (is (= "ship-v2" (:slug r))
              "slug is derived from the Saved todo: filename")
          (is (= :go (:pre-verdict r)))
          (is (= :pass (:post-verdict r)))
          (let [content (slurp (io/file (:path r)))]
            (is (str/includes? content "agent: todo-agent"))
            (is (str/includes? content "verdict: pass"))
            (is (str/includes? content "(auto-persisted)")))
          (let [idx (slurp (io/file tmp ".brainyard/agents/todo-agent/INDEX.md"))]
            (is (str/includes? idx "→ exec-agent"))
            (is (str/includes? idx "post:pass"))))
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))]
            (.delete f)))))))

(deftest test-materialize-todo-auto-dossier-replaces-hallucinated-path
  (testing "claimed-but-not-on-disk dossier path triggers re-persistence"
    (let [tmp (.getCanonicalPath
               (doto (io/file (System/getProperty "java.io.tmpdir")
                              (str "todo-auto-halu-" (System/currentTimeMillis)))
                 .mkdirs))]
      (try
        (let [answer (str "Saved todo: .brainyard/agents/todo-agent/todos/ship-v2.md\n"
                          "Saved dossier: .brainyard/agents/todo-agent/dossiers/HALLUCINATED.md\n"
                          "Hold: missing acceptance coverage\n")
              r (todo/materialize-todo-auto-dossier!
                 {:answer answer :question "Q" :base-dir tmp})]
          (is (some? r))
          (is (= "ship-v2" (:slug r)))
          (is (= :hold (:post-verdict r)))
          (is (.isFile (io/file (:path r))))
          (is (not (.isFile (io/file tmp ".brainyard/agents/todo-agent/dossiers/HALLUCINATED.md"))))
          (let [idx (slurp (io/file tmp ".brainyard/agents/todo-agent/INDEX.md"))]
            (is (str/includes? idx "→ user")
                "HOLD recommends user, not exec-agent")))
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))]
            (.delete f)))))))

(deftest test-materialize-todo-auto-dossier-skip-when-real-on-disk
  (testing "answer naming an EXISTING dossier path → no-op"
    (let [tmp (.getCanonicalPath
               (doto (io/file (System/getProperty "java.io.tmpdir")
                              (str "todo-auto-skip-" (System/currentTimeMillis)))
                 .mkdirs))]
      (try
        (let [{:keys [path]} (todo/todo$dossier-write :slug "real" :content "---\nslug: real\n---\nx" :base-dir tmp)
              answer (str "Saved todo: x.md\nSaved dossier: " path "\nNext: ...")
              r (todo/materialize-todo-auto-dossier!
                 {:answer answer :question "Q" :base-dir tmp})]
          (is (nil? r))
          (let [dossiers (->> (io/file tmp ".brainyard/agents/todo-agent/dossiers/")
                              .listFiles
                              (filter #(.isFile %)))]
            (is (= 1 (count dossiers))
                "exactly one dossier — the pre-existing real one")))
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))]
            (.delete f)))))))

(deftest test-materialize-todo-auto-dossier-gather
  (testing "GATHER answer materializes dossier with todo_path: null"
    (let [tmp (.getCanonicalPath
               (doto (io/file (System/getProperty "java.io.tmpdir")
                              (str "todo-auto-gather-" (System/currentTimeMillis)))
                 .mkdirs))]
      (try
        (let [answer (str "## PRE-FLIGHT GATHER\n\n"
                          "I cannot spawn a todo without a plan-agent dossier.\n\n"
                          "Need: a plan-agent `Saved dossier:` for this slug\n"
                          "Suggested: (call-tool \"plan-agent\" {...})\n")
              r (todo/materialize-todo-auto-dossier!
                 {:answer answer :question "Spawn a todo" :base-dir tmp})]
          (is (= :gather (:pre-verdict r)))
          (is (nil? (:post-verdict r)))
          (let [content (slurp (io/file (:path r)))]
            (is (str/includes? content "verdict: gather"))
            (is (str/includes? content "todo_path: null"))))
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))]
            (.delete f)))))))

(deftest test-todo-auto-persist-agent-scoping
  (testing "non-todo-agent invocations are ignored"
    (let [tmp (.getCanonicalPath
               (doto (io/file (System/getProperty "java.io.tmpdir")
                              (str "todo-auto-other-" (System/currentTimeMillis)))
                 .mkdirs))]
      (try
        (with-redefs [todo/todo-dossier-default-base-dir (constantly tmp)]
          (todo/todo-auto-persist
           {:agent  (fake-other-agent)
            :input  "Q"
            :result {:answer "Saved todo: x\nNext: y"}}))
        (is (not (.exists (io/file tmp ".brainyard/agents/todo-agent")))
            "plan-agent invocation should not create todo-agent dirs")
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))]
            (.delete f))))))

  (testing "todo-agent invocation triggers materialization"
    (let [tmp (.getCanonicalPath
               (doto (io/file (System/getProperty "java.io.tmpdir")
                              (str "todo-auto-fake-" (System/currentTimeMillis)))
                 .mkdirs))]
      (try
        (with-redefs [todo/todo-dossier-default-base-dir (constantly tmp)]
          (todo/todo-auto-persist
           {:agent  (fake-todo-agent)
            :input  "Spawn ship-v2"
            :result {:answer (str "Saved todo: .brainyard/agents/todo-agent/todos/ship-v2.md\n"
                                  "Next: (call-tool \"exec-agent\" ...)")}}))
        (let [dossiers (->> (io/file tmp ".brainyard/agents/todo-agent/dossiers/")
                            .listFiles
                            (filter #(.isFile %)))]
          (is (= 1 (count dossiers)))
          (is (str/includes? (.getName (first dossiers)) "ship-v2")))
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))]
            (.delete f)))))))
