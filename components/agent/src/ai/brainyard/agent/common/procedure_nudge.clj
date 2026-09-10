;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.procedure-nudge
  "Procedural-graph guidance: locate, extract, generate, inject.

   The CoAct loop chooses its next action by unconstrained generation over an
   accumulating iteration history, so whatever procedural knowledge exists —
   read before edit, detach before `task$wait`, verify before reporting done —
   lives in prompt prose or nowhere. This namespace answers *what to do next*
   from an explicit graph instead, and pushes the answer to the model at the
   moment of need.

   Per decision step (following the Procedural Graph paper, arXiv 2609.09153):

     u_t = Match(a_{t-1}, V)          ; exact-match the last procedure to a node
     G_t = N_h(u_t)                   ; DIRECTED out-neighborhood, h = 2
     g_t = render(G_t)                ; situational guidance
     a_t ~ solver(q, T_t, g_t)        ; APPENDED to the prompt; biases, never dictates

   Three implementation choices are load-bearing:

   - **Localization comes from the HOOK, not the iteration record.** Code-channel
     tool calls route through `tool/call-tool` into the same hook chain, so a
     tool invoked from inside a Clojure block never appears in `:tool-results`.
     `usage-nudge` already learned this.
   - **On a `Match` MISS we emit NOTHING** — deliberately diverging from the
     paper's Equation (2), which falls back to the full graph. That fallback
     measured -18.10 points on ALFWorld, the benchmark whose shape (ordered tool
     sequences with hard preconditions) is closest to ours. With ~200 tool defs
     plus user-authored and MCP ids the miss is the HOT PATH, not the edge case,
     so it must be free and silent.
   - **Rendering is a TEMPLATE, not an LLM call.** A sampled guidance step would
     make the Phase-2 validation gate measure the guidance model's variance
     rather than the graph's quality, and the paper runs everything greedy for
     exactly that reason. It also keeps the hot path free of an extra round
     trip.

   Injection reuses the `:notices` field on the iteration record — an existing,
   model-visible advisory slot with two producers already (`usage-nudge`,
   `self-improve-nudge`). No new prompt zone, no signature change.

   Design: docs/design/procedural-graph-implementation.md §4
   Related work: docs/design/procedural-graph-comparison.md"
  (:require [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

(def ^:private !get-config
  ;; requiring-resolve: core.config requires common namespaces transitively.
  (delay (requiring-resolve 'ai.brainyard.agent.core.config/get-config)))

(defn- cfg [agent k default]
  (try (let [v (@!get-config agent k)] (if (nil? v) default v))
       (catch Exception _ default)))

(defn enabled?
  "Gate. Independent of `:enable-graph-memory` — the procedural graph lives in
   its own tables and needs nothing from the entity graph."
  [agent]
  (boolean (cfg agent :enable-procedure-guidance false)))

(defn- bt-atom [agent] (some-> agent proto/get-bt-st-memory))

;; =====================================================
;; Locate — Match(a_{t-1}, V)
;; =====================================================

(defn note-procedure!
  "Record a procedure the agent just performed. Called from the tool-use hook
   and from the code-eval path.

   OVERWRITES rather than accumulates: an iteration emitting N tool calls
   localizes on the LAST one. Unioning N neighborhoods is un-localizing (the
   very thing measured harmful), and taking the first would guide from a step
   already superseded within the same iteration. The multiplicity is counted so
   the divergence from the paper's one-action-per-step assumption is
   measurable rather than assumed away."
  [agent probe]
  (when-let [a (bt-atom agent)]
    (when-not (str/blank? (str probe))
      (swap! a (fn [m]
                 (-> m
                     (assoc :last-procedure (str probe))
                     (update :procedure-action-count (fnil inc 0)))))))
  nil)

(defn- on-tool-post [{:keys [agent tool-name]}]
  (try (when (and tool-name (enabled? agent))
         (note-procedure! agent (name tool-name)))
       (catch Exception e
         (mulog/warn ::on-tool-post-error :error (.getMessage e))))
  nil)

(defn register-hooks!
  "(Re)register the procedure observer. Idempotent — `register-hook!` dedupes
   by [event-key handler-id].

   A fn rather than a `defonce` latch, for the reason `usage-nudge` records: a
   latch makes registration a one-shot for the life of the JVM, so anything
   clearing the hook table (`hooks/reset-hooks!` is on the public interface and
   ~24 test namespaces call it) permanently and silently disarms this."
  []
  (hooks/register-hook! :agent.tool-use/post ::procedure-nudge on-tool-post
                        :source :procedure-nudge)
  nil)

(register-hooks!)

(defn ensure-global-hooks!
  "Install the observer at RUNTIME; called from coact-init each turn."
  []
  (register-hooks!))

(defn probe-for-iteration
  "Resolve the node name to localize on, from the per-turn state.

   | situation                    | probe          |
   |------------------------------|----------------|
   | a tool ran this iteration    | the tool id    |
   | a code block ran, no tool    | `code:<lang>`  |
   | first iteration, nothing yet | `Start`        |
   | anything else                | nil            |"
  [st-memory]
  (let [m (some-> st-memory deref)]
    (or (:last-procedure m)
        (when-let [lang (some-> (:last-code-results m) last :lang not-empty)]
          (str "code:" lang))
        (when (= 1 (:iteration-count m)) "Start"))))

;; =====================================================
;; Generate — deterministic rendering of N_h(u)
;; =====================================================

(defn- edge-line
  "One transition, with whichever Φ fields the edge actually carries. A seeded
   edge has none — a bare topology asserts only 'this order was observed',
   which is true, and inventing prose for it is the failure mode the whole
   design avoids."
  [{:keys [src_name dst_name relation condition guidance pitfalls]}]
  (let [head (str "  → `" src_name "` —" (name relation) "→ `" dst_name "`")
        sub  (->> [(when-not (str/blank? condition) (str "      when:  " condition))
                   (when-not (str/blank? guidance)  (str "      do:    " guidance))
                   (when-not (str/blank? pitfalls)  (str "      avoid: " pitfalls))]
                  (remove nil?))]
    (str/join "\n" (cons head sub))))

(defn render-guidance
  "Render `N_h(u)` as situational guidance, or nil when there is nothing to say.

   Depth 0 edges are the immediate options; deeper rings collapse to a single
   `then:` line — enough to expose the procedural prerequisite the paper's
   motivating example is about (retrieving `submit` without the preceding
   `check_answer` omits the verification that makes submission appropriate)
   without pasting a subgraph.

   Truncated to `max-chars` on an EDGE boundary, so a cut never leaves a
   dangling `when:` whose sentence the model has to guess at."
  [probe edges max-chars]
  (when (seq edges)
    (let [{d0 0 :as by-depth} (group-by :depth edges)
          deeper (->> (dissoc by-depth 0) vals (apply concat))
          header (str "▸ Procedure — observed after `" probe "`:")
          lines  (map edge-line d0)
          then   (when (seq deeper)
                   (str "  then: "
                        (str/join ", " (distinct (map #(str "`" (:dst_name %) "`") deeper)))))
          cap    (or max-chars 600)]
      (loop [acc header, [l & more] (concat lines (when then [then]))]
        (cond
          (nil? l) (when-not (= acc header) acc)
          (> (+ (count acc) 1 (count l)) cap)
          (when-not (= acc header) acc)
          :else (recur (str acc "\n" l) more))))))

;; =====================================================
;; Extract + inject
;; =====================================================

(defn drain-iteration-notice!
  "Localize on the last procedure, extract its directed out-neighborhood, render
   it, and clear the per-turn probe. Returns the guidance string or nil.

   Called once per iteration as the record is assembled — so one graph query
   per iteration rather than one per tool call, and the guidance rides the very
   next iteration the model reads. That is `g_t` reaching the solver exactly
   where the paper puts it, on plumbing that already exists.

   Every failure mode returns nil. A guidance layer must never be able to break
   a turn."
  [agent st-memory]
  (try
    (when (and st-memory (enabled? agent))
      (let [probe (probe-for-iteration st-memory)
            n-act (:procedure-action-count @st-memory)]
        ;; Clear the per-turn probe whether or not it resolves: a stale probe
        ;; would re-guide from an action two iterations old.
        (swap! st-memory dissoc :last-procedure :procedure-action-count)
        (when (and n-act (> n-act 1))
          (mulog/log ::proc-multi-action :n n-act :chosen probe))
        (when probe
          (if-let [mm (some-> agent proto/get-memory-manager)]
            (let [gid  (cfg agent :procedure-graph-id mem/default-procedure-graph-id)
                  node (mem/procedure-find-node mm gid probe)]
              (if-not node
                ;; The hot path. One debug log, no query, no allocation.
                (do (mulog/debug ::proc-miss :probe probe) nil)
                (let [hops  (cfg agent :procedure-guidance-hops 2)
                      lim   (cfg agent :procedure-guidance-max-edges 12)
                      cap   (cfg agent :procedure-guidance-max-chars 600)
                      edges (mem/procedure-out-neighborhood
                             mm gid (:id node) {:max-hops hops :limit lim})
                      text  (render-guidance probe edges cap)]
                  (mulog/log ::proc-located :node (:name node) :kind (:kind node))
                  (when text
                    (mulog/log ::proc-guided
                               :node    (:name node)
                               :edges   (count edges)
                               :chars   (count text)
                               :depth-1 (count (filter #(= 0 (:depth %)) edges))
                               :depth-2 (count (remove #(= 0 (:depth %)) edges))))
                  text)))
            (do (mulog/debug ::proc-no-memory-manager) nil)))))
    (catch Exception e
      (mulog/warn ::drain-error :error (.getMessage e))
      nil)))
