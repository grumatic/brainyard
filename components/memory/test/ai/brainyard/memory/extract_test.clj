;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.extract-test
  "Phase 2 (CR-MEM-22) LLM graph extraction: apply-to-graph + async sidecar.
  Uses stub extract-fns (no live LLM)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.memory.core.unified-store :as us]
            [ai.brainyard.memory.core.extract :as extract]
            [ai.brainyard.memory.core.episodic :as episodic]
            [ai.brainyard.memory.core.capture.extractor :as extractor]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.memory.interface.protocol :as proto]))

(def ^:dynamic *store* nil)

(defn with-store [f]
  (sqlite/reset-vec-extension!)
  (let [ds (sqlite/create-datasource ":memory:")]
    (sqlite/init-schema! ds)
    (binding [*store* (us/create-unified-store :user-id "u1" :ds ds)]
      (try (f) (finally (.close ds))))))

(use-fixtures :each with-store)

;; =====================================================
;; process-extraction! — apply a result to the graph
;; =====================================================

(deftest process-creates-nodes-and-edges-test
  (let [result {:entities [{:name "BY_SANDBOX_INTEROP" :type "config-key"
                            :summary "SCI interop knob" :aliases ["SANDBOX_INTEROP"]}
                           {:name "clj-sandbox" :type "component"}]
                :relations [{:src "BY_SANDBOX_INTEROP" :relation "configures"
                             :dst "clj-sandbox" :fact "the knob configures clj-sandbox"
                             :confidence 0.9}]}
        r (extract/process-extraction! *store* result "ep-1")]
    (testing "counts returned"
      (is (= 2 (:nodes r)))
      (is (= 1 (:edges r))))
    (testing "nodes are resolvable by name and type"
      (let [n (proto/find-node *store* :config-key "BY_SANDBOX_INTEROP")]
        (is (= "SCI interop knob" (:summary n)))
        (is (= ["SANDBOX_INTEROP"] (:aliases n)))))
    (testing "edge exists with relation + provenance"
      (let [src  (proto/find-node *store* :config-key "BY_SANDBOX_INTEROP")
            [nb] (proto/neighbors *store* (:id src) {:direction :out})]
        (is (= "clj-sandbox" (-> nb :node :name)))
        (is (= :configures (-> nb :edge :relation)))
        (is (= ["ep-1"] (-> nb :edge :source-entry-ids)))))))

(deftest relation-resolves-via-alias-test
  (testing "a relation referencing an alias binds to the canonical node (no dup)"
    (extract/process-extraction!
     *store* {:entities [{:name "BY_SANDBOX_INTEROP" :type "config-key" :aliases ["sci"]}
                         {:name "code-eval" :type "component"}]
              ;; relation uses the alias "sci", not the canonical name
              :relations [{:src "sci" :relation "part_of" :dst "code-eval"}]}
     "ep-2")
    (let [src (proto/find-node *store* :config-key "BY_SANDBOX_INTEROP")
          nbs (proto/neighbors *store* (:id src) {:direction :out})]
      (is (= 1 (count nbs)) "alias resolved to the existing node, no phantom src")
      (is (= "code-eval" (-> nbs first :node :name))))))

(deftest functional-relation-supersession-test
  (testing ":prefers is single-valued — a new target invalidates the old edge"
    (extract/process-extraction!
     *store* {:entities [{:name "jake" :type "person"} {:name "tabs" :type "concept"}]
              :relations [{:src "jake" :relation "prefers" :dst "tabs"}]} "e1")
    (extract/process-extraction!
     *store* {:entities [{:name "jake" :type "person"} {:name "spaces" :type "concept"}]
              :relations [{:src "jake" :relation "prefers" :dst "spaces"}]} "e2")
    (let [jake (proto/find-node *store* :person "jake")
          nbs  (proto/neighbors *store* (:id jake) {:direction :out :relation :prefers})]
      (is (= 1 (count nbs)) "only the current preference is a valid neighbor")
      (is (= "spaces" (-> nbs first :node :name)) "latest preference wins")
      ;; supersession invalidates the old edge but never deletes the node
      (is (some? (proto/find-node *store* :concept "tabs"))
          "the superseded target node is retained"))))

(deftest process-prunes-unrelated-entities-test
  (testing "an extracted entity never wired into a relation is pruned by default"
    (let [result {:entities [{:name "alpha" :type "component"}
                             {:name "beta" :type "component"}
                             {:name "loner" :type "entity" :summary "mentioned once"}]
                  :relations [{:src "alpha" :relation "relates_to" :dst "beta"}]}
          r (extract/process-extraction! *store* result "ep-orphan")]
      (is (= 3 (:nodes r)) "all three entities are upserted")
      (is (= 1 (:orphaned r)) "the unrelated 'loner' is pruned")
      (is (some? (proto/find-node *store* :component "alpha")))
      (is (some? (proto/find-node *store* :component "beta")))
      (is (nil? (proto/find-node *store* :entity "loner"))
          "edgeless extracted entity removed")))
  (testing ":prune-orphans? false keeps the unrelated entity"
    (let [result {:entities [{:name "gamma" :type "component"}
                             {:name "delta" :type "component"}
                             {:name "keeper" :type "entity"}]
                  :relations [{:src "gamma" :relation "relates_to" :dst "delta"}]}
          r (extract/process-extraction! *store* result "ep-keep" {:prune-orphans? false})]
      (is (= 0 (:orphaned r)) "pruning disabled")
      (is (some? (proto/find-node *store* :entity "keeper"))
          "edgeless entity retained when the flag is off"))))

;; =====================================================
;; Async extractor sidecar
;; =====================================================

(defn- wait-for [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 20) (recur))))))

(deftest extractor-sidecar-end-to-end-test
  (testing "enqueued L2 entries are extracted into the graph asynchronously"
    (let [stub (fn [_text]
                 {:entities [{:name "GraalVM" :type "concept" :summary "native-image"}
                             {:name "native-image.properties" :type "file"}]
                  :relations [{:src "native-image.properties" :relation "configures"
                               :dst "GraalVM"}]})
          ex (extractor/start! *store* stub)]
      (try
        (extractor/enqueue! ex {:id "ep-9"
                                :content "We tuned native-image.properties for the GraalVM build at length."})
        ;; Wait for the EDGE (written after the nodes) so we don't race the
        ;; node-then-edge ordering inside process-extraction!.
        (let [edge-present? (fn []
                              (when-let [g (proto/find-node *store* :concept "GraalVM")]
                                (seq (proto/neighbors *store* (:id g) {:direction :in}))))]
          (is (wait-for edge-present? 5000)
              "node + edge materialized from the async pipeline")
          (let [g    (proto/find-node *store* :concept "GraalVM")
                [nb] (proto/neighbors *store* (:id g) {:direction :in})]
            (is (= "native-image.properties" (-> nb :node :name)))
            (is (= ["ep-9"] (-> nb :edge :source-entry-ids)) "provenance carried through")))
        (finally (extractor/stop! ex))))))

(deftest extractor-skips-trivial-content-test
  (testing "content below the min length is not sent for extraction"
    (let [calls (atom 0)
          stub  (fn [_t] (swap! calls inc) {:entities [] :relations []})
          ex    (extractor/start! *store* stub)]
      (try
        (extractor/enqueue! ex {:id "tiny" :content "hi"})
        (Thread/sleep 150)
        (is (zero? @calls) "short episode skipped before the LLM call")
        (finally (extractor/stop! ex))))))

(deftest make-extract-fn-nil-without-lm-test
  (is (nil? (extract/make-extract-fn nil))
      "no lm-config ⇒ no extract-fn ⇒ extraction disabled"))

;; =====================================================
;; Incremental graph-build watermark (extract-l2-graph!)
;; =====================================================

(defn- write-l2! [ds sid content]
  (episodic/append-episode! ds {:session-id sid :user-id "u1"
                                :episode-type "conversation"
                                :role "user" :content content}))

(deftest incremental-graph-build-session-test
  (let [calls (atom 0)
        stub  (fn [_t] (swap! calls inc) {:entities [{:name "X" :type "concept"}] :relations []})
        ds    (:ds *store*)
        mm    {:store *store* :extract-fn stub}]
    (write-l2! ds "sA" "first episode about GraalVM native-image build tuning, long enough")
    (write-l2! ds "sA" "second episode about sqlite-vec extension loading paths, long enough")
    (testing "first run extracts all existing session episodes and records the watermark"
      (let [r (mem/extract-l2-graph! mm :session-id "sA")]
        (is (= 2 (:attempted r)))
        (is (= 1 @calls) "2 episodes batched into 1 windowed LLM call")
        (is (= 1 (:calls r)))
        (is (:incremental r))
        (is (pos? (long (:through-id r))))))
    (testing "re-running with nothing new is a no-op (watermark blocks re-extraction)"
      (reset! calls 0)
      (let [r (mem/extract-l2-graph! mm :session-id "sA")]
        (is (= 0 (:attempted r)))
        (is (= 0 (:total r)))
        (is (zero? @calls) "no LLM calls on the second run — the slow path is gone")))
    (testing "only the newly-appended tail is extracted"
      (reset! calls 0)
      (write-l2! ds "sA" "third episode discussing the community reducer summaries, long enough")
      (let [r (mem/extract-l2-graph! mm :session-id "sA")]
        (is (= 1 (:attempted r)) "only the new episode")
        (is (= 1 @calls))))
    (testing "--rebuild ignores the watermark and re-extracts everything"
      (reset! calls 0)
      (let [r (mem/extract-l2-graph! mm :session-id "sA" :rebuild? true)]
        (is (= 3 (:attempted r)))
        (is (= 1 @calls) "3 episodes → 1 window → 1 call")
        (is (not (:incremental r)))))))

(deftest windowed-batching-test
  (let [calls (atom 0)
        stub  (fn [_t] (swap! calls inc) {:entities [] :relations []})
        ds    (:ds *store*)
        mm    {:store *store* :extract-fn stub}]
    (dotimes [i 5]
      (write-l2! ds "w" (str "episode " i " with enough content to be extracted here")))
    (testing "many episodes in a session batch into ONE call by default"
      (let [r (mem/extract-l2-graph! mm :session-id "w")]
        (is (= 5 (:attempted r)) "all 5 episodes fed")
        (is (= 1 (:calls r)) "one window ⇒ one LLM call")
        (is (= 1 @calls))))
    (testing ":max-episodes-per-window bounds the window by episode count"
      (reset! calls 0)
      (let [r (mem/extract-l2-graph! mm :session-id "w" :rebuild? true :max-episodes-per-window 2)]
        (is (= 5 (:attempted r)))
        (is (= 3 (:calls r)) "5 episodes / 2 per window → 3 windows (2+2+1)")))
    (testing "a tiny char budget forces multiple windows but drops nothing"
      (reset! calls 0)
      (let [r (mem/extract-l2-graph! mm :session-id "w" :rebuild? true :max-input-chars 60)]
        (is (= 5 (:attempted r)) "windowing never drops episodes")
        (is (> (:calls r) 1) "small budget ⇒ multiple windows")
        (is (= (:calls r) @calls))))))

(deftest batched-cap-scaling-test
  (testing "per-episode caps are scaled by the window's episode count"
    (let [ds       (:ds *store*)
          captured (atom [])
          mm       {:store *store* :extract-fn (fn [_t] {:entities [] :relations []})}]
      (dotimes [i 4] (write-l2! ds "c" (str "episode " i " content long enough to extract ok")))
      (with-redefs [extract/process-extraction!
                    (fn [_s _r _sid limits] (swap! captured conj limits) {:nodes 0 :edges 0})]
        (mem/extract-l2-graph! mm :session-id "c"
                               :max-entities 12 :max-relations 24 :max-episodes-per-window 10))
      (is (= 1 (count @captured)) "4 episodes ≤ 10 ⇒ one window")
      (is (= {:max-entities 48 :max-relations 96}
             (select-keys (first @captured) [:max-entities :max-relations]))
          "12/24 per-episode caps scaled by the 4-episode window → 48/96"))))

(deftest incremental-graph-build-per-user-test
  (let [calls (atom 0)
        stub  (fn [_t] (swap! calls inc) {:entities [] :relations []})
        ds    (:ds *store*)
        mm    {:store *store* :extract-fn stub}]
    (write-l2! ds "s1" "episode one long enough to pass the min-content threshold ok")
    (write-l2! ds "s2" "episode two long enough to pass the min-content threshold ok")
    (testing "no --session sweeps across all sessions (one window per session), then watermarks"
      (is (= 2 (:attempted (mem/extract-l2-graph! mm))))
      (is (= 2 @calls) "s1 and s2 are separate sessions → 2 windows, never mixed into one blob")
      (reset! calls 0)
      (is (= 0 (:attempted (mem/extract-l2-graph! mm))) "second sweep finds nothing new")
      (is (zero? @calls)))
    (testing "per-user and per-session watermarks are independent"
      ;; sA has its own (unset) mark, so a session run still sees its episodes
      ;; even though the per-user sweep already extracted them.
      (reset! calls 0)
      (write-l2! ds "s3" "a third session episode, again over the min-content length ok")
      (is (= 1 (:attempted (mem/extract-l2-graph! mm))) "per-user sweep picks up only s3")
      (is (= 1 @calls)))))
