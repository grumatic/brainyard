;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.memory-agent.commands-test
  "Tests for memory$* primitive commands and the write-guard hook."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.agent.common.memory-agent.commands :as cmds]
            [ai.brainyard.agent.common.memory-agent.hooks :as ma-hooks]
            [ai.brainyard.agent.common.memory-agent.working-area :as wa]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.memory.core.manager :as manager]
            [ai.brainyard.memory.interface :as mem])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; ============================================================================
;; Stub agent — just enough of proto/IAgent for memory$* to find the manager
;; ============================================================================

(defrecord StubAgent [agent-id !state !session]
  proto/IAgent
  (agent-id [_] agent-id)
  (agent-name [_] (str agent-id))
  (agent-description [_] "stub")
  (user-id [_] (some-> !session deref :user-id))
  (session-id [_] (some-> !session deref :session-id))
  (defagent-type [_]
    (if-let [ns (and (keyword? agent-id) (namespace agent-id))]
      (keyword ns)
      agent-id))
  (process [_ _ _] nil)
  (get-tools [_] [])
  (get-state [_] @!state))

(defn- make-stub
  [agent-id mm session-id]
  (->StubAgent agent-id
               (atom {:memory-manager mm :config {:name (str agent-id)}})
               (atom {:user-id (:user-id mm) :session-id session-id})))

;; ============================================================================
;; Fixtures
;; ============================================================================

(def ^:dynamic *mm* nil)
(def ^:dynamic *tmp* nil)

(defn- make-tmp-dir []
  (-> (Files/createTempDirectory "memory-agent-cmds-" (into-array FileAttribute []))
      .toFile .getAbsolutePath))

(defn- delete-recursive [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursive c)))
  (.delete f))

(defn- with-test-mm [f]
  (let [mm  (manager/create-memory-manager (str "user-" (random-uuid))
                                           :in-memory true)
        tmp (make-tmp-dir)]
    (try
      (binding [*mm* mm
                *tmp* tmp]
        (with-redefs [config/project-dir (constantly tmp)]
          (f)))
      (finally
        (delete-recursive (io/file tmp))
        (when-let [ds (:ds mm)] (try (.close ds) (catch Exception _)))))))

(use-fixtures :each with-test-mm)

;; ============================================================================
;; Registration
;; ============================================================================

(deftest registration-test
  (testing "every primitive is registered in !tool-defs as :command"
    (doseq [id cmds/all-tool-ids]
      (let [td (tool/get-tool-defs :id id)]
        (is (some? td) (str id " must be registered"))
        (is (= :command (:type td))
            (str id " must be :type :command"))))))

;; ============================================================================
;; entry-id-for — content-addressable id helper
;; ============================================================================

(deftest entry-id-for-test
  (testing "stable: same content → same id"
    (let [a (cmds/entry-id-for :l3 "User prefers polylith")
          b (cmds/entry-id-for :l3 "User prefers polylith")]
      (is (= a b))))

  (testing "casing / whitespace normalization → same id"
    (is (= (cmds/entry-id-for :l3 "User prefers Polylith")
           (cmds/entry-id-for :l3 "user  prefers   polylith"))))

  (testing "punctuation normalization → same id"
    (is (= (cmds/entry-id-for :l3 "User prefers polylith.")
           (cmds/entry-id-for :l3 "User; prefers, polylith!"))))

  (testing "different content → different id"
    (is (not= (cmds/entry-id-for :l3 "polylith")
              (cmds/entry-id-for :l3 "monorepo"))))

  (testing "layer is prefixed"
    (let [id (cmds/entry-id-for :l3 "anything")]
      (is (str/starts-with? id "l3/"))
      (is (= 19 (count id))                                  ; "l3/" + 16 hex chars
          "id format is <layer>/<sha-16>"))))

;; ============================================================================
;; memory$stats — composite
;; ============================================================================

(deftest memory$stats-empty-db-test
  (testing "stats on a fresh in-memory db return zero counts"
    (proto/with-agent (make-stub :memory-agent/test *mm* "s-empty")
      (let [{:keys [stats error]} (cmds/memory$stats)]
        (is (nil? error))
        (is (map? stats))
        (is (zero? (get-in stats [:l2 :total])))
        (is (zero? (get-in stats [:l3 :total])))
        (is (= "s-empty" (get-in stats [:l1 :session-id])))
        (is (= :ok (get-in stats [:health :status])))
        (is (false? (get-in stats [:capture :running?])))
        (is (= :heuristic (get-in stats [:capture :reducer])))))))

(deftest memory$stats-counts-after-writes-test
  (testing "after writing two L2 + one L3, stats reflect the counts"
    (mem/write-entry *mm* :l2
                     {:kind :observation :content "saw a thing"
                      :session-id "s1" :user-id (:user-id *mm*)})
    (mem/write-entry *mm* :l2
                     {:kind :action :content "did a thing"
                      :session-id "s1" :user-id (:user-id *mm*)})
    (mem/write-entry *mm* :l3
                     {:kind :fact :content "deploy uses prod scripts"
                      :user-id (:user-id *mm*) :confidence 0.9})
    (proto/with-agent (make-stub :memory-agent/test *mm* "s1")
      (let [{:keys [stats]} (cmds/memory$stats)]
        (is (= 2 (get-in stats [:l2 :total])))
        (is (= 2 (get-in stats [:l2 :current-session])))
        (is (= 1 (get-in stats [:l2 :sessions-known])))
        (is (= 1 (get-in stats [:l3 :total])))
        (is (= 1 (get-in stats [:l3 :confidence-buckets :high])))))))

(deftest memory$stats-no-agent-test
  (testing "stats without a current agent returns :error"
    (proto/with-agent nil
      (let [{:keys [error]} (cmds/memory$stats)]
        (is (string? error))
        (is (str/includes? error "memory manager"))))))

;; ============================================================================
;; memory$read — raw layer read
;; ============================================================================

(deftest memory$read-test
  (mem/write-entry *mm* :l3
                   {:kind :fact :content "user prefers polylith"
                    :user-id (:user-id *mm*) :tags ["arch"]})
  (mem/write-entry *mm* :l3
                   {:kind :fact :content "ci runs on linux"
                    :user-id (:user-id *mm*) :tags ["ci"]})

  (proto/with-agent (make-stub :memory-agent/test *mm* "s1")
    (testing "read :l3 with empty query returns both facts"
      (let [{:keys [layer count entries]} (cmds/memory$read :layer "l3" :query {})]
        (is (= "l3" layer))
        (is (= 2 count))
        (is (= 2 (clojure.core/count entries)))))

    (testing "read with :text filter narrows results"
      (let [{:keys [count entries]} (cmds/memory$read :layer "l3"
                                                      :query {:text "polylith"})]
        (is (= 1 count))
        (is (str/includes? (-> entries first :content) "polylith"))))

    (testing "invalid layer surfaces a clean :error"
      (let [{:keys [error]} (cmds/memory$read :layer "l9" :query {})]
        (is (string? error))
        (is (str/includes? error "invalid layer"))))))

;; ============================================================================
;; memory$keywords
;; ============================================================================

(deftest memory$keywords-test
  (testing "extracts distinctive keywords from a sentence"
    (let [{:keys [keywords]} (cmds/memory$keywords :text "Deploy to staging via the github action")]
      (is (vector? keywords))
      (is (pos? (clojure.core/count keywords)))))

  (testing "blank input → error"
    (is (= ":text is required" (:error (cmds/memory$keywords :text ""))))))

;; ============================================================================
;; Write primitives — direct (var-call) path; bypasses hook
;; ============================================================================

(deftest memory$write-roundtrip-test
  (proto/with-agent (make-stub :memory-agent/test *mm* "s1")
    (testing "write :l2 entry, read it back"
      (let [r (cmds/memory$write :layer "l2"
                                 :entry {:kind :observation
                                         :content "build is green"})]
        (is (some? (:entry-id r)))
        (is (= "l2" (:layer r))))
      (let [{:keys [entries]} (cmds/memory$read :layer "l2"
                                                :query {:session-id "s1"})]
        (is (= 1 (clojure.core/count entries)))
        (is (= "build is green" (-> entries first :content)))))

    (testing "write :l3 mints a content-addressable entry-id when omitted"
      (let [r1 (cmds/memory$write :layer "l3"
                                  :entry {:kind :fact
                                          :content "ci runs on linux"
                                          :user-id (:user-id *mm*)})
            r2 (cmds/memory$write :layer "l3"
                                  :entry {:kind :fact
                                          :content "CI runs on Linux"   ; same modulo case
                                          :user-id (:user-id *mm*)})]
        (is (= (:entry-id r1) (:entry-id r2))
            "two equivalent L3 writes converge on one entry-id")))))

(deftest memory$promote-test
  (proto/with-agent (make-stub :memory-agent/test *mm* "s1")
    (let [w (cmds/memory$write :layer "l2"
                               :entry {:kind :observation
                                       :content "user said polylith preferred"})
          l2-entry (:entry w)
          p (cmds/memory$promote :entry l2-entry :from "l2" :to "l3")]
      (testing "promote returns a new L3 entry-id"
        (is (some? (:entry-id p)))
        (is (= "l3" (:to p))))

      (testing "the L3 row carries :sources back to the L2 origin"
        (let [{:keys [entries]} (cmds/memory$read :layer "l3" :query {})
              promoted (first entries)
              sources (or (:sources promoted) [])]
          (is (some #(= "promotion" (some-> (:type %) name)) sources)
              "at least one source row has :type :promotion (JSON roundtrip strings)"))))))

(deftest memory$forget-test
  (proto/with-agent (make-stub :memory-agent/test *mm* "s1")
    (let [w (cmds/memory$write :layer "l3"
                               :entry {:kind :fact :content "stale fact"
                                       :user-id (:user-id *mm*)})
          eid (:entry-id w)]
      (testing "forget tombstones the entry; default read excludes it"
        (let [r (cmds/memory$forget :layer "l3" :entry-id eid :reason "test")]
          (is (true? (:ok r))))
        (let [{:keys [count]} (cmds/memory$read :layer "l3" :query {})]
          (is (zero? count)))))))

(deftest memory$keep-and-archive-test
  (proto/with-agent (make-stub :memory-agent/test *mm* "s1")
    (let [w (cmds/memory$write :layer "l3"
                               :entry {:kind :fact :content "stable"
                                       :user-id (:user-id *mm*)})
          eid (:entry-id w)]
      (testing "keep! flips keep_flag"
        (let [r (cmds/memory$keep! :layer "l3" :entry-id eid :value true)]
          (is (true? (:ok r)))
          (is (true? (:value r)))))

      (testing "archive! flips archived_flag and hides from default reads"
        (let [r (cmds/memory$archive! :layer "l3" :entry-id eid :value true)]
          (is (true? (:ok r))))
        (let [{:keys [count]} (cmds/memory$read :layer "l3" :query {})]
          (is (zero? count) "archived entries are excluded from default read"))
        (let [{:keys [count]} (cmds/memory$read :layer "l3" :query {} :include-archived true)]
          (is (= 1 count)))))))

(deftest memory$sweep-l2-test
  (proto/with-agent (make-stub :memory-agent/test *mm* "s1")
    (testing "sweep with retention-days 0 tombstones non-kept episodes"
      (cmds/memory$write :layer "l2"
                         :entry {:kind :observation :content "fresh thing"})
      (let [r (cmds/memory$sweep-l2 :retention-days 0)]
        (is (number? (:tombstoned r)))
        (is (>= (:tombstoned r) 0))
        (is (= 0 (:retention-days r)))))))

;; ============================================================================
;; Working-area primitives via the defcommand path
;; ============================================================================

(deftest memory$state-read-write-test
  (proto/with-agent (make-stub :memory-agent/test *mm* "s1")
    (testing "state-write + state-read round-trip"
      (let [w (cmds/memory$state-write :slot "stats.edn"
                                       :content {:l2 {:total 42}})]
        (is (string? (:path w))))
      (let [r (cmds/memory$state-read :slot "stats.edn")]
        (is (= {:l2 {:total 42}} (:content r)))))

    (testing "rejected slot surfaces a clean :error"
      (let [r (cmds/memory$state-write :slot "../escape" :content {})]
        (is (string? (:error r)))))))

(deftest memory$essence-append-test
  (proto/with-agent (make-stub :memory-agent/test *mm* "s1")
    (testing "append produces a path and the file contains NDJSON"
      (let [r (cmds/memory$essence-append :turn-id 1
                                          :agent-id "memory-agent/test"
                                          :essences [])]
        (is (true? (:appended? r)))
        (is (string? (:path r)))
        (is (.isFile (io/file (:path r)))))
      ;; Append a second record and assert via read-essence-log helper.
      (cmds/memory$essence-append :turn-id 2 :agent-id "x"
                                  :essences [{:kind "fact" :content "y"}])
      (let [records (wa/read-essence-log (:user-id *mm*))]
        (is (= 2 (clojure.core/count records)))
        (is (= [1 2] (mapv :turn-id records)))))))

;; ============================================================================
;; Write-guard hook — exercised via call-tool
;; ============================================================================

(defn- block?
  "True when a tool result is a hook-blocked envelope."
  [r]
  (true? (:hook-blocked r)))

(deftest write-guard-blocks-non-memory-agent-test
  (let [non-mem (make-stub :coact-agent/test-x *mm* "s1")]
    (testing "memory$write from a non-memory-agent is blocked"
      (let [r (tool/call-tool :memory$write
                              {:layer "l2"
                               :entry {:kind :observation :content "x"}}
                              :agent non-mem)]
        (is (block? r))
        (is (str/includes? (:reason r) "memory-agent"))
        ;; The answer points the LLM at the proper invocation shape —
        ;; `(memory-agent {:op ...})`. Asserting on `:op` is stable across
        ;; future tweaks to the exact recommended call syntax (the prior
        ;; assertion against "call-tool" predated the move to the op-based
        ;; form and silently rotted).
        (is (str/includes? (:answer r) ":op"))))

    (testing "memory$state-write is also gated"
      (let [r (tool/call-tool :memory$state-write
                              {:slot "stats.edn" :content {}}
                              :agent non-mem)]
        (is (block? r))))))

(deftest write-guard-allows-memory-agent-test
  (let [mem-ag (make-stub :memory-agent/abc *mm* "s1")]
    (testing "memory$write from memory-agent is allowed"
      (let [r (tool/call-tool :memory$write
                              {:layer "l2"
                               :entry {:kind :observation :content "ok"}}
                              :agent mem-ag)]
        (is (false? (block? r)))
        (is (some? (:entry-id r))
            "the write actually happened (entry-id minted)")))))

(deftest write-guard-allows-reads-test
  (let [non-mem (make-stub :coact-agent/test-y *mm* "s1")]
    (testing "memory$read is NOT gated — any agent can read"
      (let [r (tool/call-tool :memory$read
                              {:layer "l2" :query {}}
                              :agent non-mem)]
        (is (false? (block? r)))
        (is (= "l2" (:layer r)))))

    (testing "memory$stats is NOT gated"
      (let [r (tool/call-tool :memory$stats {} :agent non-mem)]
        (is (false? (block? r)))
        (is (map? (:stats r)))))

    (testing "memory$keywords is NOT gated"
      (let [r (tool/call-tool :memory$keywords {:text "hello world"}
                              :agent non-mem)]
        (is (false? (block? r)))
        (is (vector? (:keywords r)))))))

(deftest write-guard-allows-when-no-agent-bound-test
  (testing "REPL / test direct calls with no agent bound: guard gets out of the way"
    ;; This path matters for tests and REPL — many of the tests above call the
    ;; defcommand var directly without going through call-tool (no agent in
    ;; the event-map). Those work because the var-call path bypasses hooks.
    ;; This test verifies the hook itself returns nil when agent is missing.
    (is (nil? (ma-hooks/write-guard-decision
               {:agent nil :tool-name "memory$write" :args {}}))
        "no-agent → nil (= :allow) per hook contract")
    (is (nil? (ma-hooks/write-guard-decision
               {:agent (make-stub :memory-agent/x *mm* "s") :tool-name "memory$read" :args {}}))
        "non-guarded tool → nil regardless of agent")))

;; ============================================================================
;; Hook self-registration
;; ============================================================================

(deftest write-guard-registered-test
  (testing "write-guard is registered on :agent.tool-use/pre at namespace load"
    ;; Other test ns's may reset hooks between fixtures — reinstall first so
    ;; this assertion is independent of polylith test ordering.
    (ma-hooks/install-write-guard!)
    (let [entries (hooks/list-hooks :agent.tool-use/pre)
          ids (set (map :id entries))]
      (is (contains? ids :ai.brainyard.agent.common.memory-agent.hooks/write-guard)))))
