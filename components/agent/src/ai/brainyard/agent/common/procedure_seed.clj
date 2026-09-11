;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.procedure-seed
  "Seed a procedural graph by MINING recorded trajectories.

   The seed is mined, never authored, and that is the single most important
   decision in this feature. Measured on MultiChallenge:

     unguided baseline .................. 87.50
     hand-crafted expert graph .......... 58.93   <- 28.57 points BELOW no graph
     expert + one static update ......... 53.57
     scratch + STATIC build ............. 89.29   <- also frozen, and it WINS

   Modes 1 and 4 are both static and un-evolved, so the discriminator is not
   hand-written-versus-generated. It is *authored from a human's model of the
   domain* versus *built from observed execution*. Writing a procedure graph
   from what we believe the tool order to be is the configuration that measured
   worst; counting what the tool order actually was is the one that beat the
   baseline.

   So this reads `<project>/.brainyard/sessions/*/trajectory.edn`, counts
   consecutive procedure transitions, and writes pairs at or above a support
   threshold as `:precedes` edges with `origin 'seed'` and **empty Φ**. Blank
   `condition`/`guidance`/`pitfalls` is the point: a bare topology asserts only
   'this order was observed', which is true. Inventing prose for it is exactly
   Mode 1.

   `:enable-trajectory-recording` defaults true, so every existing session
   directory is already training data.

   Design: docs/design/procedural-graph-implementation.md §4.6"
  (:require [ai.brainyard.agent.common.trajectory :as traj]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

(defn- session-ids
  "Every session directory under the project-scoped sessions root."
  []
  (let [^File root (io/file (str (config/sessions-root)))]
    (when (.isDirectory root)
      (->> (.listFiles root)
           (filter #(.isDirectory ^File %))
           (map #(.getName ^File %))
           sort
           vec))))

(def ^:private tool-call-re
  "A brainyard tool invocation inside a code block: `(family$verb …)`.

   Deliberately requires the `$`. The SCI sandbox binds EVERY visible tool as a
   callable fn, including `$`-less ones like `list-tools`, but matching a bare
   `(search …)` would also match clojure.core fns and local bindings — and a
   FALSE POSITIVE is strictly worse than a miss here. A missed procedure yields
   a node the localizer never finds, so guidance simply does not fire; an
   invented one yields a node the localizer DOES find, and then guides from it
   wrongly. The `$` family convention is unambiguous, so that is where the line
   goes."
  #"\(([a-zA-Z][a-zA-Z0-9_.*+!<>=-]*\$[a-zA-Z0-9_.*+!<>=-]+)")

(defn- tool-in-code
  "The LAST `family$verb` call appearing across a code iteration's blocks."
  [code-blocks]
  (some->> code-blocks
           (keep identity)
           (mapcat #(map second (re-seq tool-call-re (str %))))
           last
           not-empty))

(defn iteration->procedure
  "The procedure name an iteration performed, or nil.

   Mirrors `procedure-nudge/probe-for-iteration` so the graph is keyed the same
   way at build time and at localization time. If these two ever disagree,
   every seeded edge becomes unreachable — the graph is populated and `Match`
   never hits — the quiet failure this feature is most exposed to.

   **A code iteration that calls a tool keys on the TOOL.** At runtime the
   localizer reads the `:agent.tool-use/post` hook, and code-channel tool calls
   route through `tool/call-tool` into that same chain — so an iteration whose
   block is `(aws$whoami)` localizes on `aws$whoami`. But the trajectory record
   for it is `{:channel \"code\" :code [\"(aws$whoami)\"] :lang [\"clojure\"]}`
   with NO `:tools` key, because `project-iteration` only populates `:tools`
   for the tool CHANNEL. Keying it `code:clojure` would seed a graph the
   localizer can never reach. Measured on this repo's 87 recorded turns before
   the fix: every tool called from a Clojure block was invisible to the seeder.

   **A code iteration that calls NOTHING names no procedure.** It used to key
   `code:<lang>`; measured, that carried no signal — 23 of 41 mined transitions
   were `code:bash -> code:bash`, a retry loop rather than a transition. Since
   `transitions` chains across iterations that name nothing, dropping it
   REWIRES rather than deletes: `evo$tasks -> code:bash -> evo$stats` becomes
   `evo$tasks -> evo$stats`, which is the transition worth recording. See
   `memory.core.procedure/node-kinds`, which no longer has a `:code` kind.

   Trajectory records are v2 or v3 (append-only files hold both)."
  [{:keys [channel tools code] :as _iteration}]
  (cond
    (seq tools)        (some-> (last tools) :name str not-empty)
    (= "code" channel) (tool-in-code code)
    :else nil))

(defn transitions
  "Consecutive `(from -> to)` procedure pairs across a turn's iterations.

   Iterations that performed no nameable procedure are DROPPED rather than
   breaking the chain: a pure-reasoning step, or a shell block that invoked no
   tool, between two tool calls does not make the second stop following the
   first. This is what makes excluding `code:<lang>` a rewiring rather than a
   loss.

   The bridge is unbounded — a turn with twenty intervening shell blocks still
   pairs the tools on either side. The min-support threshold is the guard: a
   spuriously long-range pair has to recur before it becomes an edge, which an
   accident does not."
  [record]
  (let [names (keep iteration->procedure (:iterations record))]
    (map vector names (rest names))))

(defn mine-transitions
  "Count transitions across every session's trajectory, plus the procedures
   seen. Returns `{:counts {[from to] n} :procedures #{name} :turns n
   :sessions n}`. Pure — contacts no database."
  []
  (reduce
   (fn [acc sid]
     (let [records (try (traj/read-trajectories sid) (catch Exception _ nil))]
       (reduce (fn [a rec]
                 (-> a
                     (update :turns inc)
                     (update :procedures into (keep iteration->procedure (:iterations rec)))
                     (update :counts #(reduce (fn [m t] (update m t (fnil inc 0)))
                                              % (transitions rec)))))
               (update acc :sessions inc)
               records)))
   {:counts {} :procedures #{} :turns 0 :sessions 0}
   (session-ids)))

(defn- kind-for
  "Every mined name is a tool call except the `Start` marker. There is no
   `:code` kind any more — see `memory.core.procedure/node-kinds`."
  [nm]
  (if (= nm "Start") :state :tool))

(defn seed-procedures!
  "Write mined transitions into the procedural graph.

   Only pairs with `support >= min-support` become edges. Idempotent: re-running
   over an overlapping set of sessions refreshes `support` on the live edge
   rather than accumulating duplicates (`idx_proc_edges_live`).

   Returns `{:nodes n :edges n :skipped n :turns n :sessions n :min-support n}`."
  [memory-manager {:keys [graph-id min-support] :or {min-support 3}}]
  (let [gid    (or graph-id mem/default-procedure-graph-id)
        {:keys [counts turns sessions]} (mine-transitions)
        kept   (filter (fn [[_ n]] (>= n min-support)) counts)
        names  (into #{} (mapcat key) kept)
        nodes  (into {} (map (fn [nm]
                               [nm (:id (mem/procedure-upsert-node!
                                         memory-manager gid
                                         {:name nm :kind (kind-for nm)}))]))
                     names)
        n-edge (reduce (fn [n [[from to] support]]
                         ;; A procedure that follows itself is not an admissible
                         ;; transition, it is a retry loop; the store rejects
                         ;; self-loops so filter here rather than throwing.
                         (if (= from to)
                           n
                           (do (mem/procedure-upsert-edge!
                                memory-manager gid
                                {:src-id (nodes from) :dst-id (nodes to)
                                 :relation :precedes
                                 :support  support
                                 :origin   :seed
                                 ;; Phi stays EMPTY. See the ns docstring.
                                 :confidence (min 0.99 (+ 0.5 (/ support 100.0)))})
                               (inc n))))
                       0 kept)
        result {:nodes (count nodes) :edges n-edge
                :skipped (- (count counts) (count kept))
                :turns turns :sessions sessions :min-support min-support}]
    (mulog/log ::procedure-seeded result)
    result))
