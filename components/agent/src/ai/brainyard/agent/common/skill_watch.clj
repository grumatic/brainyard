;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.skill-watch
  "Skill-registry coherence — closes open question #2 of
   docs/design/skill-agent-design.md.

   Authoring a skill is file-inherent: `write-file` its SKILL.md, `write-file`
   each script, then `skills$reload` to register it as an invocable
   `:skill$<name>` tool. That final step was **model-remembered** — nothing
   enforced it — so a skill written without it stayed undiscoverable until the
   next explicit reload or a process restart.

   This installs a cheap safety net:

     1. `:agent.tool-use/post` — when a tool call's ARGUMENTS mention a
        `.brainyard/skills/` path, mark the session's registry dirty. Scanning
        ARGS (not results) is what makes this precise: `write-file`,
        `update-file` and `bash rm -r …` all carry the path as an argument,
        while `skills$read` / `skills$list` / `skills$find` carry it only in
        their RESULTS — so reads never trip the flag. Known mutation commands
        that name a skill rather than a path are matched by name instead.
     2. `:agent.ask/post` — at end of turn, if the flag is set, run
        `reload-skills!` once and clear it.

   End-of-turn rather than per-write is deliberate: a single create writes
   SKILL.md plus N scripts, and `reload-skills!` re-walks every backend and
   re-interns a var per skill. Coalescing to one reload per turn collapses that
   burst without a timer or a background thread.

   This is a NET, not a replacement: an agent that needs its new skill callable
   *within the same turn* must still call `skills$reload` itself. The documented
   authoring flow keeps that step.

   Hooks install at RUNTIME via `ensure-global-hooks!` (a `compare-and-set!`
   atom, called from coact-init) so native-image bakes `false` and the first
   real turn installs — never a build-time registration."
  (:require [ai.brainyard.agent.common.skills :as skills]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

;; ============================================================================
;; Dirty detection (deterministic, free)
;; ============================================================================

(def ^:const skills-path-marker
  "Substring identifying a path inside a brainyard skills root — matches both
   `<project>/.brainyard/skills/…` and `~/.brainyard/skills/…`."
  ".brainyard/skills/")

(def mutation-commands
  "Skill commands that change what is on disk but name a SKILL rather than a
   path, so the arg scan cannot see them."
  #{:skills$write :skills$import :skills$install :skills$sync
    :skill-proposal$accept :skill-proposal$reject})

(defn args-touch-skills-dir?
  "True when any string anywhere in `args` names a path under a skills root.
   Walks nested collections so a tool taking `{:files [{:path …}]}` is covered."
  [args]
  (boolean
   (and (map? args)
        (some (fn [v] (and (string? v) (str/includes? v skills-path-marker)))
              (tree-seq coll? seq (vals args))))))

(defn registry-dirtying?
  "True when a finished tool call may have changed the set of skills on disk.
   Errors are ignored — a failed write changed nothing."
  [tool-name args result]
  (and (not (and (map? result)
                 (or (:error result) (:error-message result))))
       (boolean
        (or (contains? mutation-commands (keyword tool-name))
            (args-touch-skills-dir? args)))))

;; ============================================================================
;; Per-session dirty flag
;; ============================================================================

(defonce ^:private !dirty (atom #{}))

(defn dirty? [sid] (contains? @!dirty (str sid)))

(defn mark-dirty! [sid] (swap! !dirty conj (str sid)) nil)

(defn clear-dirty! [sid] (swap! !dirty disj (str sid)) nil)

;; ============================================================================
;; Handlers
;; ============================================================================

(defn touch-handler
  "`:agent.tool-use/post` handler — flag the session when a call may have
   changed skills on disk. Never throws."
  [{:keys [agent tool-name args result]}]
  (try
    (when (and agent (registry-dirtying? tool-name args result))
      (mark-dirty! (proto/session-id agent)))
    (catch Exception e
      (mulog/warn ::touch-handler-failed :tool tool-name :exception e)))
  nil)

(defn reload-handler
  "`:agent.ask/post` handler — one coalesced `reload-skills!` per turn that
   touched a skills path. Never throws; a reload failure leaves the flag
   cleared so it cannot retry forever."
  [{:keys [agent]}]
  (when agent
    (let [sid (proto/session-id agent)]
      (when (dirty? sid)
        (try
          (let [r (skills/reload-skills!)]
            (mulog/info ::auto-reloaded
                        :session (str sid)
                        :total (:total r)
                        :unregistered (count (:unregistered r))))
          (catch Exception e
            (mulog/warn ::auto-reload-failed :session (str sid) :exception e))
          (finally
            (clear-dirty! sid))))))
  nil)

;; ============================================================================
;; Runtime install (idempotent, never at build time)
;; ============================================================================

(defonce ^:private !installed (atom false))

(defn ensure-global-hooks!
  "Install the registry-coherence observers once per process at RUNTIME
   (guarded by a runtime atom so native-image bakes `false` and the first real
   turn installs). Safe to call every turn. Tagged `:source :skill-watch`."
  []
  (when (compare-and-set! !installed false true)
    (hooks/register-hook! :agent.tool-use/post ::skill-watch-touch touch-handler
                          :source :skill-watch :priority 30)
    (hooks/register-hook! :agent.ask/post ::skill-watch-reload reload-handler
                          :source :skill-watch :priority 30)
    (mulog/info ::global-hooks-installed))
  nil)
