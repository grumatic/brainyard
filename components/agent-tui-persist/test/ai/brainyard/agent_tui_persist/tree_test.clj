;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-persist.tree-test
  (:require [ai.brainyard.agent-tui-persist.core.messages :as messages]
            [ai.brainyard.agent-tui-persist.core.tree :as tree]
            [ai.brainyard.agent-tui-persist.interface :as persist]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File]
           [java.nio.file Files]))

(def ^:dynamic *tmp-root* nil)

(defn- with-tmp-root [f]
  (let [tmp (.toFile (Files/createTempDirectory "tree-test"
                                                (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (binding [*tmp-root* tmp]
        (persist/with-root tmp (f)))
      (finally
        (doseq [^File f' (reverse (file-seq tmp))]
          (.delete f'))))))

(use-fixtures :each with-tmp-root)

;; ----------------------------------------------------------------------------
;; meta read / write / merge
;; ----------------------------------------------------------------------------

(deftest read-meta-returns-nil-for-fresh-session
  (persist/session-dir "fresh")
  (is (nil? (tree/read-meta "fresh"))))

(deftest merge-meta-creates-and-then-merges
  (tree/merge-meta! "s1" {:id "s1" :label "first"})
  (is (= {:id "s1" :label "first"} (tree/read-meta "s1")))
  (tree/merge-meta! "s1" {:label "renamed" :extra :keeps})
  (let [m (tree/read-meta "s1")]
    (is (= "s1" (:id m)))
    (is (= "renamed" (:label m)))
    (is (= :keeps (:extra m)))))

(deftest merge-meta-preserves-snapshot-fields
  ;; Snapshot side writes :agent-id / :started-at; tree side updates
  ;; :label / :last-active. Both must coexist on the same meta.edn.
  (persist/save-meta! "s1" {:agent-id "react-agent"
                            :working-dir "/tmp"
                            :model "claude-haiku"})
  (tree/merge-meta! "s1" {:label "my work"})
  (let [m (tree/read-meta "s1")]
    (is (= "react-agent" (:agent-id m)))
    (is (= "/tmp" (:working-dir m)))
    (is (= "claude-haiku" (:model m)))
    (is (= "my work" (:label m)))))

(deftest touch-bumps-last-active-and-creates-created-at
  (let [m1 (tree/touch! "s1")]
    (is (some? (:created-at m1)))
    (is (some? (:last-active m1)))
    (Thread/sleep 5)
    (let [m2 (tree/touch! "s1")]
      ;; created-at frozen, last-active advanced.
      (is (= (:created-at m1) (:created-at m2)))
      (is (> (.getTime ^java.util.Date (:last-active m2))
             (.getTime ^java.util.Date (:last-active m1)))))))

(deftest set-label-and-get-label
  (tree/set-label! "s1" "my session")
  (is (= "my session" (tree/get-label "s1")))
  (is (nil? (tree/get-label "missing"))))

;; ----------------------------------------------------------------------------
;; fork-session!
;; ----------------------------------------------------------------------------

(deftest fork-creates-child-meta-with-parent-pointer
  (tree/ensure-meta! "parent" "root work")
  (messages/append! "parent" {:kind :user :payload {:input "hi"}})
  (messages/append! "parent" {:kind :agent :payload {:answer "hello"}})
  (let [child (tree/fork-session! "parent" "child" {:label "side"})]
    (is (= "parent" (:parent-id child)))
    (is (= 2 (:fork-point child)) "fork-point = parent's event count at fork time")
    (is (= "side" (:label child)))
    (is (some? (:created-at child)))
    (is (= child (tree/read-meta "child")))))

(deftest fork-disallows-self-parent
  (tree/ensure-meta! "p")
  (is (thrown? clojure.lang.ExceptionInfo
               (tree/fork-session! "p" "p" {}))))

(deftest fork-with-explicit-at
  (tree/ensure-meta! "parent")
  (messages/append! "parent" {:kind :a})
  (messages/append! "parent" {:kind :b})
  (messages/append! "parent" {:kind :c})
  (let [child (tree/fork-session! "parent" "branch" {:at 1})]
    (is (= 1 (:fork-point child))
        "explicit :at overrides the parent's current event count")))

;; ----------------------------------------------------------------------------
;; tree-of + lineage
;; ----------------------------------------------------------------------------

(deftest tree-of-builds-multi-level-tree
  ;; root -> child-a, root -> child-b -> grandchild
  (tree/ensure-meta! "root" "root")
  (Thread/sleep 2)
  (tree/fork-session! "root" "child-a" {:label "A"})
  (Thread/sleep 2)
  (tree/fork-session! "root" "child-b" {:label "B"})
  (Thread/sleep 2)
  (tree/fork-session! "child-b" "grand" {:label "G"})
  (let [{:keys [roots nodes]} (tree/tree-of)]
    (is (= ["root"] roots))
    (let [root-children (:children (get nodes "root"))]
      (is (= ["child-a" "child-b"] root-children)
          "children sorted by created-at"))
    (is (= ["grand"] (:children (get nodes "child-b"))))
    (is (= "child-b" (:parent (get nodes "grand"))))))

(deftest tree-of-orphans-become-roots
  ;; A child whose parent meta doesn't exist becomes a top-level root.
  (tree/merge-meta! "orphan" {:id "orphan"
                              :parent-id "ghost"
                              :fork-point 0
                              :created-at (java.util.Date.)})
  (let [{:keys [roots]} (tree/tree-of)]
    (is (some #{"orphan"} roots) "orphan attaches at root level")))

(deftest lineage-walks-from-root-to-leaf
  (tree/ensure-meta! "root")
  (tree/fork-session! "root" "mid" {})
  (tree/fork-session! "mid" "leaf" {})
  (is (= ["root" "mid" "leaf"] (tree/lineage "leaf")))
  (is (= ["root" "mid"] (tree/lineage "mid")))
  (is (= ["root"] (tree/lineage "root")))
  (is (nil? (tree/lineage "missing"))))

;; ----------------------------------------------------------------------------
;; render-tree
;; ----------------------------------------------------------------------------

(defn- strip-ansi [s] (str/replace s #"\x1b\[[0-9;]*[A-Za-z]" ""))

(deftest render-tree-shape-and-active-marker
  (tree/ensure-meta! "root" "boot")
  (Thread/sleep 2)
  (tree/fork-session! "root" "a" {:label "branch a"})
  (tree/fork-session! "root" "b" {:label "branch b"})
  (let [t     (tree/tree-of)
        lines (tree/render-tree t {:active "a" :ascii? true})
        text  (str/join "\n" (map strip-ansi lines))]
    (is (str/includes? text "root"))
    (is (str/includes? text "boot"))
    (is (str/includes? text "branch a"))
    (is (str/includes? text "branch b"))
    (is (re-find #"`-\s.*\bb\b" text)
        "leaf 'b' (most recent child) uses the last-child glyph in :ascii? mode")
    (is (re-find #"\+-\s.*\ba\b" text)
        "leaf 'a' (older child) uses the branch glyph"))
  (let [t     (tree/tree-of)
        lines (tree/render-tree t {:active "a"})
        ;; Active row gets the ▸ marker; non-active rows get a space.
        active-line (some (fn [line]
                            (when (re-find #"^.*▸ a" (strip-ansi line))
                              line))
                          lines)]
    (is (some? active-line)
        "active session id is marked with ▸")))
