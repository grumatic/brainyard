;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.user-hooks-test
  "Tests for runtime-defined (LLM-authored) hooks.

   Exercises the full path against the REAL clj-sandbox + hook registry:
   define-from-source -> persist -> register via hooks/register-hook! -> fire
   the matching event and observe the side effect -> rehydrate from disk after a
   simulated restart. Also covers the v1 guardrails: gated events are rejected,
   :match is required, scope filtering works, and the dry-run touches nothing.

   The handler bodies enact their side effect by calling a `record!` symbol
   supplied via :extra-bindings — the same composition mechanism real bodies use
   for (bash {…}) / (read-file {…}). The bound fn pushes into a test-local atom
   we can observe, since the sandbox is forked (and discarded) per fire."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.agent.common.user-hooks :as uh]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.tool :as tool]))

(def ^:private test-dirs
  {:project-dir (str (System/getProperty "java.io.tmpdir") "/by-user-hooks-test")})

(defn- hooks-dir-file []
  (io/file (str (:project-dir test-dirs) "/.brainyard/hooks")))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (rm-rf! c)))
  (.delete f))

(defn- clean! []
  (uh/reset-hooks-sandbox!)
  (hooks/unregister-source! :user-hook)
  (rm-rf! (hooks-dir-file)))

(use-fixtures :each (fn [f] (clean!) (try (f) (finally (clean!)))))

;; A test-local sink + the :extra-bindings that expose it to handler bodies as
;; `(record! x)` and `(refire!)`. `refire!` lets the re-entrancy test trigger a
;; nested fire of the same event from inside a handler.
(def ^:private sink (atom []))

(defn- bindings-with-sink []
  {'record! (fn [x] (swap! sink conj x) nil)
   'refire! (fn [] (hooks/fire! :agent.iteration/post {:iteration 1}))})

;; ---------------------------------------------------------------------------

(deftest define-and-fire
  (reset! sink [])
  (testing "define-hook persists source and registers under :user-hook/<id>"
    (let [r (uh/define-hook
              :id "rec-bash"
              :event :agent.tool-use/post
              :match {:tool-name "bash"}
              :doc "Record tool-name on every bash call."
              :body "(fn [event] (record! (:tool-name event)))"
              :dirs test-dirs
              :extra-bindings (bindings-with-sink))]
      (is (= "rec-bash" (:id r)))
      (is (= :agent.tool-use/post (:event r)))
      (is (.exists (io/file (:persisted r))))
      (is (some #(= :user-hook/rec-bash (:id %))
                (hooks/list-hooks :agent.tool-use/post)))))
  (testing "firing a MATCHING event runs the body (side effect observed)"
    (hooks/fire! :agent.tool-use/post {:tool-name "bash" :args {} :call-id "c1"})
    (is (= ["bash"] @sink)))
  (testing "firing a NON-matching event (different tool) does nothing (scope works)"
    (hooks/fire! :agent.tool-use/post {:tool-name "read-file" :args {} :call-id "c2"})
    (is (= ["bash"] @sink)))
  (testing "the sanitized event also carries :event-key"
    ;; rec-event records the whole event so we can inspect injected keys
    (uh/define-hook :id "rec-event" :event :agent.iteration/post
      :match {:global true}
      :body "(fn [event] (record! (:event-key event)))"
      :dirs test-dirs :extra-bindings (bindings-with-sink))
    (reset! sink [])
    (hooks/fire! :agent.iteration/post {:iteration 1 :max-iterations 10})
    (is (= [:agent.iteration/post] @sink))))

(deftest rejects-gated-event
  (testing "a gated event (:agent.tool-use/pre) is rejected — observer-only v1"
    (is (thrown-with-msg? Exception #"gated"
                          (uh/define-hook :id "nope" :event :agent.tool-use/pre
                            :match {:global true}
                            :body "(fn [event] nil)" :dirs test-dirs)))
    (is (not (some #(= :user-hook/nope (:id %))
                   (hooks/list-hooks :agent.tool-use/pre))))))

(deftest requires-match
  (testing "an empty/missing :match is rejected (explicit-scope rule)"
    (is (thrown-with-msg? Exception #":match is required"
                          (uh/define-hook :id "noscope" :event :agent.iteration/post
                            :match {} :body "(fn [event] nil)" :dirs test-dirs)))
    (is (thrown-with-msg? Exception #":match"
                          (uh/define-hook :id "noscope" :event :agent.iteration/post
                            :match {:bogus true} :body "(fn [event] nil)" :dirs test-dirs)))))

(deftest rejects-bad-definitions
  (testing "invalid id"
    (is (thrown? Exception
                 (uh/define-hook :id "Bad Id" :event :agent.iteration/post
                   :match {:global true} :body "(fn [event] nil)" :dirs test-dirs))))
  (testing "unknown event"
    (is (thrown? Exception
                 (uh/define-hook :id "okid" :event :no.such/event
                   :match {:global true} :body "(fn [event] nil)" :dirs test-dirs))))
  (testing "body that does not eval"
    (is (thrown? Exception
                 (uh/define-hook :id "okid" :event :agent.iteration/post
                   :match {:global true} :body "(this is not valid clojure" :dirs test-dirs)))))

(deftest rehydrates-after-restart
  (reset! sink [])
  (testing "persisted source survives a simulated restart (sandbox + registry wiped)"
    (uh/define-hook :id "rec-iter" :event :agent.iteration/post
      :match {:global true}
      :body "(fn [event] (record! (:iteration event)))"
      :dirs test-dirs :extra-bindings (bindings-with-sink))
    ;; wipe live state — the SCI var/closure is gone, only the .edn remains
    (uh/reset-hooks-sandbox!)
    (hooks/unregister-source! :user-hook)
    ;; Only assert OUR source is gone — other components (e.g. task.manager's
    ;; :task-progress hooks) may legitimately register on this event during a
    ;; full-suite run, so a global-emptiness check is fragile.
    (is (empty? (filter #(= :user-hook (:source %))
                        (hooks/list-hooks :agent.iteration/post))))
    ;; reload from disk and confirm the hook fires again
    (is (= ["rec-iter"] (vec (uh/load-user-hooks! :dirs test-dirs
                                                  :extra-bindings (bindings-with-sink)))))
    (hooks/fire! :agent.iteration/post {:iteration 7 :max-iterations 100})
    (is (= [7] @sink))))

(deftest ensure-loaded-idempotent
  (testing "hooks persist project-scoped under .brainyard/hooks"
    (let [r (uh/define-hook :id "rec-iter" :event :agent.iteration/post
              :match {:global true} :body "(fn [event] nil)" :dirs test-dirs)]
      (is (str/ends-with? (:persisted r) "/.brainyard/hooks/rec-iter.edn"))))
  (testing "ensure-loaded! loads once then no-ops for the same project dir"
    (uh/reset-hooks-sandbox!)                       ;; also clears the loaded-dirs set
    (hooks/unregister-source! :user-hook)
    (is (= ["rec-iter"] (vec (uh/ensure-loaded! :dirs test-dirs))))
    (is (some #(= :user-hook/rec-iter (:id %))
              (hooks/list-hooks :agent.iteration/post)))
    (is (nil? (uh/ensure-loaded! :dirs test-dirs)))))

(deftest management-list-read-delete
  (uh/define-hook :id "rec-iter" :event :agent.iteration/post
    :match {:global true} :doc "Demo hook."
    :body "(fn [event] nil)" :dirs test-dirs)
  (testing "registered-user-hooks + hook-agent$list surface the active hook (dir-independent)"
    (is (some #(= "rec-iter" (:id %)) (uh/registered-user-hooks)))
    (is (some #(= "rec-iter" (:id %)) (:hooks (tool/invoke-tool :hook-agent$list {})))))
  (testing "list-user-hooks reads the persisted record at an explicit dir"
    (is (some #(= "rec-iter" (:id %)) (uh/list-user-hooks test-dirs))))
  (testing "read-user-hook returns the persisted record + body"
    (let [r (uh/read-user-hook test-dirs "rec-iter")]
      (is (= "rec-iter" (:id r)))
      (is (= :agent.iteration/post (:event r)))
      (is (= {:global true} (:match r)))
      (is (str/includes? (:body r) "fn [event]"))))
  (testing "hook-agent$read / hook-agent$delete require :id (registry Malli guard)"
    (is (str/includes? (:error-message (tool/call-tool :hook-agent$read {})) "missing required key"))
    (is (str/includes? (:error-message (tool/call-tool :hook-agent$delete {})) "missing required key")))
  (testing "delete-user-hook! unregisters and removes the persisted source"
    (let [edn (io/file (str (:project-dir test-dirs) "/.brainyard/hooks/rec-iter.edn"))]
      (is (.exists edn))
      (is (= {:deleted "rec-iter"} (uh/delete-user-hook! test-dirs "rec-iter")))
      (is (not (some #(= :user-hook/rec-iter (:id %))
                     (hooks/list-hooks :agent.iteration/post))))
      (is (not (.exists edn)))))
  (testing "deleting a missing hook errors"
    (is (str/includes? (:error (uh/delete-user-hook! test-dirs "nope")) "no user hook"))))

(deftest hooks-create-command
  (testing "hook-agent$create is registered as a command"
    (is (contains? (tool/get-tool-defs) :hook-agent$create)))
  (testing "hook-agent$create routes through define-hook (bad id -> :error)"
    (let [r (tool/call-tool :hook-agent$create
                            {:id "Bad Id" :event "agent.iteration/post"
                             :match "{:global true}" :body "(fn [event] nil)"})]
      (is (str/includes? (:error r) "hook-agent$create failed"))))
  (testing "hook-agent$create rejects a gated event (observer-only)"
    (let [r (tool/call-tool :hook-agent$create
                            {:id "gated-x" :event "agent.tool-use/pre"
                             :match "{:global true}" :body "(fn [event] nil)"})]
      (is (str/includes? (:error r) "gated")))))

(deftest hooks-events-command
  (testing "hook-agent$events lists the catalog with gated/available flags"
    (let [evs (:events (tool/invoke-tool :hook-agent$events {}))
          by  (into {} (map (juxt :event identity)) evs)]
      (is (true?  (:available? (get by :agent.iteration/post))))
      (is (false? (:gated?     (get by :agent.iteration/post))))
      (is (true?  (:gated?     (get by :agent.tool-use/pre))))
      (is (false? (:available? (get by :agent.tool-use/pre))))
      (is (contains? (set (:payload-keys (get by :agent.tool-use/post))) :tool-name)))))

(deftest hooks-validate-dry-run
  (testing "hook-agent$validate is registered as a command"
    (is (contains? (tool/get-tool-defs) :hook-agent$validate)))
  (testing "a valid draft reports :valid true and PERSISTS/REGISTERS NOTHING"
    (let [edn (io/file (str (:project-dir test-dirs) "/.brainyard/hooks/never-made.edn"))
          r   (tool/invoke-tool :hook-agent$validate
                                {:id "never-made"
                                 :event "agent.iteration/post"
                                 :match "{:global true}"
                                 :body "(fn [event] {:n (:iteration event)})"
                                 :sample {:iteration 3}})]
      (is (true? (:valid r)))
      (is (true? (:id-ok r)))
      (is (true? (:event-ok r)))
      (is (false? (:event-gated r)))
      (is (true? (:match-ok r)))
      (is (true? (:body-ok r)))
      (is (false? (:collision r)))
      (is (= {:n 3} (:sample-result r)))
      (is (empty? (:errors r)))
      ;; the load-bearing dry-run guarantee: live state is untouched
      (is (not (some #(= :user-hook/never-made (:id %))
                     (hooks/list-hooks :agent.iteration/post))))
      (is (not (.exists edn))))))

(deftest hooks-validate-checks
  (testing "gated event flips :event-gated and populates :errors"
    (let [r (tool/invoke-tool :hook-agent$validate
                              {:id "x" :event "agent.tool-use/pre"
                               :match "{:global true}" :body "(fn [event] nil)"})]
      (is (false? (:valid r)))
      (is (true? (:event-gated r)))
      (is (some #(str/includes? % "gated") (:errors r)))))
  (testing "missing :match flips :match-ok"
    (let [r (tool/invoke-tool :hook-agent$validate
                              {:id "x" :event "agent.iteration/post"
                               :body "(fn [event] nil)"})]
      (is (false? (:valid r)))
      (is (false? (:match-ok r)))
      (is (some #(str/includes? % ":match is required") (:errors r)))))
  (testing "uncompilable body flips :body-ok with the eval message"
    (let [r (tool/invoke-tool :hook-agent$validate
                              {:id "x" :event "agent.iteration/post"
                               :match "{:global true}" :body "(this is not valid"})]
      (is (false? (:valid r)))
      (is (false? (:body-ok r)))
      (is (some #(str/includes? % "body failed to eval") (:errors r))))))

(deftest reentrancy-guard
  (reset! sink [])
  (testing "a handler firing the SAME event again does not recurse infinitely"
    ;; The body records once, then re-fires :agent.iteration/post from inside
    ;; itself via (refire!). The depth guard must skip the nested firing (at
    ;; hook-depth 2), so the sink gets exactly ONE entry per top-level fire.
    (uh/define-hook :id "recur" :event :agent.iteration/post
      :match {:global true}
      :body "(fn [event] (record! :x) (refire!))"
      :dirs test-dirs :extra-bindings (bindings-with-sink))
    (hooks/fire! :agent.iteration/post {:iteration 1})
    (is (= [:x] @sink))))

(deftest sidecar-layout
  (testing "define-hook writes metadata .edn (no :body) + verbatim .clj sidecar"
    (let [body "(fn [event]\n  (record! (:iteration event)))"]
      (uh/define-hook :id "rec-iter" :event :agent.iteration/post
        :match {:global true} :doc "demo"
        :body body :dirs test-dirs :extra-bindings (bindings-with-sink))
      (let [edn (io/file (str (:project-dir test-dirs) "/.brainyard/hooks/rec-iter.edn"))
            clj (io/file (str (:project-dir test-dirs) "/.brainyard/hooks/rec-iter.clj"))]
        (is (.exists edn))
        (is (.exists clj))
        (is (not (str/includes? (slurp edn) ":body")) "body lives in the .clj, not the .edn")
        (is (str/includes? (slurp clj) "record!"))
        ;; body round-trips verbatim through read-user-hook
        (is (= body (:body (uh/read-user-hook test-dirs "rec-iter"))))))))
