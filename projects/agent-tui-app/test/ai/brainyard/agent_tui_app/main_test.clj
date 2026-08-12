;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-app.main-test
  "Unit tests for the project entry-point's pure CLI helpers.

   NOTE: `main.clj` lives in the project `src`, which Polylith's `poly test`
   does NOT cover (it tests bricks only). Run these via the project test alias:

       cd projects/agent-tui-app && clojure -M:test

   or load this ns in the project's dev nREPL and `(run-tests)`."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.brainyard.agent-tui-app.main :as main]
   [ai.brainyard.agent-tui-persist.interface :as persist]))

(def ^:private inject @#'main/inject-bare-resume-sentinel)
(def ^:private sentinel @#'main/resume-pick-sentinel)
(def ^:private latest-session-id @#'main/latest-session-id)
(def ^:private parse-layer @#'main/parse-layer)

(deftest inject-bare-resume-sentinel-test
  (testing "bare --resume / -r (no value) gets the sentinel spliced in"
    (is (= ["run" "--resume" sentinel]      (inject ["run" "--resume"])))
    (is (= ["run" "-r" sentinel]            (inject ["run" "-r"])))
    (is (= ["run" "--resume" sentinel "-i"] (inject ["run" "--resume" "-i"]))
        "next token is another flag → treated as bare")
    (is (= ["run" "-r" sentinel "--inline"] (inject ["run" "-r" "--inline"]))))

  (testing "--resume <id> is left untouched (id does not start with '-')"
    (is (= ["run" "--resume" "foo"] (inject ["run" "--resume" "foo"])))
    (is (= ["run" "-r" "agt-123"]   (inject ["run" "-r" "agt-123"]))))

  (testing "--resume=<id> equals-form is left untouched"
    (is (= ["run" "--resume=foo"] (inject ["run" "--resume=foo"]))))

  (testing "no resume flag → args unchanged"
    (is (= ["run" "-a" "coact-agent"] (inject ["run" "-a" "coact-agent"]))))

  (testing "multiple resume tokens each handled independently"
    (is (= ["-r" sentinel "-r" "id"] (inject ["-r" "-r" "id"]))
        "first -r is bare (followed by a flag), second takes the id")))

(deftest sentinel-cannot-collide-with-real-session-id
  ;; Real session ids are timestamp/uuid-shaped (e.g. "agt-1780236629321-928")
  ;; — the sentinel's leading dashes guarantee no overlap.
  (is (re-find #"^--" sentinel)))

(deftest latest-session-id-test
  (testing "picks the newest by last-attached-at among existing ids"
    (with-redefs [persist/summarise-sessions
                  (fn [] [{:session-id "a" :last-attached-at 100 :started-at 1}
                          {:session-id "b" :last-attached-at 300 :started-at 2}
                          {:session-id "c" :last-attached-at 200 :started-at 3}])]
      (is (= "b" (latest-session-id #{"a" "b" "c"})))))

  (testing "filters to existing ids — the newest overall (b) is excluded, c wins"
    (with-redefs [persist/summarise-sessions
                  (fn [] [{:session-id "a" :last-attached-at 100}
                          {:session-id "b" :last-attached-at 300}
                          {:session-id "c" :last-attached-at 200}])]
      (is (= "c" (latest-session-id #{"a" "c"})))))

  (testing "falls back to started-at when last-attached-at is absent"
    (with-redefs [persist/summarise-sessions
                  (fn [] [{:session-id "a" :started-at 50}
                          {:session-id "b" :started-at 90}])]
      (is (= "b" (latest-session-id #{"a" "b"})))))

  (testing "nil when nothing matches → caller starts a fresh session"
    (with-redefs [persist/summarise-sessions (fn [] [])]
      (is (nil? (latest-session-id #{"a"}))))
    (with-redefs [persist/summarise-sessions
                  (fn [] [{:session-id "x" :last-attached-at 1}])]
      (is (nil? (latest-session-id #{"a"}))
          "x exists on disk but isn't in the live id set → nil"))))

;; --- Phase 1 memory read verbs --------------------------------------------

(deftest parse-layer-test
  (testing "canonicalises valid layer strings (case/whitespace-insensitive)"
    (is (= :l1 (parse-layer "l1")))
    (is (= :l2 (parse-layer "L2")))
    (is (= :l3 (parse-layer "  l3  "))))
  (testing "rejects anything else → nil (caller errors with usage)"
    (is (nil? (parse-layer nil)))
    (is (nil? (parse-layer "")))
    (is (nil? (parse-layer "l4")))
    (is (nil? (parse-layer "graph")))))

(defn- memory-subcommands []
  (->> (:subcommands main/cli-config)
       (filter #(= "memory" (:command %)))
       first :subcommands
       (map (juxt :command :runs))
       (into {})))

(deftest memory-verbs-registered
  (testing "every Phase 1 read + Phase 2 write verb is wired into the `memory` subcommand tree"
    (let [subs (memory-subcommands)]
      (doseq [[cmd expected] {;; Phase 1 — reads
                              "list"    #'main/cmd-memory-list
                              "get"     #'main/cmd-memory-get
                              "search"  #'main/cmd-memory-search
                              "explain" #'main/cmd-memory-explain
                              "status"  #'main/cmd-memory-status
                              "graph"   #'main/cmd-memory-graph
                              ;; Phase 2 — writes
                              "forget"  #'main/cmd-memory-forget
                              "edit"    #'main/cmd-memory-edit
                              "keep"    #'main/cmd-memory-keep
                              "archive" #'main/cmd-memory-archive
                              "promote" #'main/cmd-memory-promote
                              "sweep"   #'main/cmd-memory-sweep
                              "prune"   #'main/cmd-memory-prune
                              "reembed" #'main/cmd-memory-reembed}]
        (is (contains? subs cmd) (str "memory " cmd " is registered"))
        (is (= @expected (get subs cmd)) (str "memory " cmd " runs its cmd fn"))))))

;; ============================================================================
;; sessions list --all-projects
;; ============================================================================

(defn- sessions-list-opts []
  (->> (:subcommands main/cli-config)
       (filter #(= "sessions" (:command %)))
       first :subcommands
       (filter #(= "list" (:command %)))
       first :opts
       (map (juxt :option identity))
       (into {})))

(deftest all-projects-flag-registered
  (testing "--all-projects is wired into `sessions list` as an off-by-default flag"
    (let [opt (get (sessions-list-opts) "all-projects")]
      (is (some? opt) "the flag is registered")
      (is (= :with-flag (:type opt)) "a boolean flag, so --no-all-projects also parses")
      (is (false? (:default opt))
          "OFF by default — widening the default scope would change what every
           existing `by sessions list` call answers")))

  (testing "the project-scoped flags it composes with are still there"
    (let [opts (sessions-list-opts)]
      (is (contains? opts "live"))
      (is (contains? opts "json")))))

(deftest newest-first-test
  (let [newest-first @#'main/newest-first]
    (testing "orders by last-attached-at, falling back to started-at"
      (is (= [3 2 1]
             (map :n (newest-first [{:n 1 :last-attached-at 10}
                                    {:n 3 :last-attached-at 30}
                                    {:n 2 :started-at 20}])))))
    (testing "last-attached-at wins over started-at on the same row"
      (is (= [:fresh :stale]
             (map :id (newest-first [{:id :stale :last-attached-at 1  :started-at 99}
                                     {:id :fresh :last-attached-at 50 :started-at 2}])))))
    (testing "a row with neither timestamp sorts last rather than blowing up"
      (is (= [:dated :undated]
             (map :id (newest-first [{:id :undated} {:id :dated :started-at 5}])))))))

(deftest all-projects-summaries-test
  (let [all-projects-summaries @#'main/all-projects-summaries
        ;; Where the reader is currently pointed. The real `enriched-summaries`
        ;; resolves the sessions root through this on every call, which is what
        ;; the stub below imitates.
        pointed (atom nil)]
    (with-redefs [ai.brainyard.agent.interface/init-dirs! (constantly {})
                  ai.brainyard.agent.interface/list-projects
                  (constantly [{:slug "alpha" :path "/p/alpha" :missing? false}
                               {:slug "gone"  :path "/p/gone"  :missing? true}
                               {:slug "beta"  :path "/p/beta"  :missing? false}])
                  ai.brainyard.agent.interface/set-working-dir-override!
                  (fn [p] (reset! pointed p))
                  ai.brainyard.agent-tui.session-summary/enriched-summaries
                  (fn [] [{:session-id (str "s-" @pointed) :started-at 1}])]

      (testing "skips registry entries whose directory is gone"
        (is (= #{"alpha" "beta"} (set (map :project-slug (all-projects-summaries))))
            "a `missing?` project has nothing to read, so it contributes no rows"))

      (testing "every row is tagged with the project it came from"
        (let [rows (all-projects-summaries)]
          (is (every? :project-slug rows))
          (is (= #{"/p/alpha" "/p/beta"} (set (map :project-path rows))))))

      (testing "each project's rows are read while THAT project is installed"
        ;; The stub stamps the session id with wherever the reader was pointed
        ;; when it ran, so this pins read-time attribution rather than just the
        ;; tags (which close over the loop bindings and would look right even if
        ;; the read had drifted).
        ;;
        ;; Note on its strength, so nobody reads more into a green run than is
        ;; there: `project-sessions` has redundant guarantees (argument-position
        ;; read, eager accumulation, `mapv`), so removing any ONE of them still
        ;; passes — verified by mutation. It catches a restructuring that defers
        ;; the read past the next override, not the loss of a single guard.
        (doseq [{:keys [session-id project-path]} (all-projects-summaries)]
          (is (= (str "s-" project-path) session-id)
              (str "row " session-id " was read while pointed at " project-path)))))))
