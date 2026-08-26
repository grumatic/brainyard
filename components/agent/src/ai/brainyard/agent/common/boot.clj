;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.boot
  "Process-boot registration of everything USER-AUTHORED, so the shared tool
   registry is complete before the first agent exists.

   Built-in tools/agents register at class-load time via `deftool`. Everything
   the user authors did not, and had grown three different regimes:

     - skills      — registered only by the TUI's `start!`, so headless
                     `by ask` never saw a project skill at all
     - user tools  — registered inside `coact-init`, on the first turn
     - user agents — likewise

   Loading inside a turn means no CLI subcommand can see a user def: `by agents`
   listed built-ins only, and a positional `user$agent$<name>` could not resolve
   at dispatch. The stated blocker was that loading 'needs an agent instance for
   its dirs', which is not so — `sandbox-bindings/get-dirs` and
   `skills/current-dirs` both already fall back to `config/init-dirs!` when no
   agent is bound.

   What genuinely needs an agent is narrower than a whole load: only the SCI
   eval of a user tool's BODY, because SCI resolves symbols during analysis and
   a body calling `(bash :command …)` cannot eval without the agent's tool
   palette bound. So the work splits by what it needs, not by what it is:

     PHASE 1 (here, at boot)   metadata -> !tool-defs        no agent needed
     PHASE 2 (coact-init)      bodies   -> tools sandbox     needs the palette

   Bodies are deliberately NOT installed here against a nil-agent palette. The
   palette's `call-tool` closures capture the agent for permission gating, and a
   body analyzed against a nil-agent binding could keep the ungated fn — trading
   a discovery bug for a permission bug. Phase 2 stays where the real agent is.

   USER HOOKS ARE DELIBERATELY ABSENT. A hook has no discovery or dispatch
   surface — nothing lists it at the CLI, nothing resolves it by name — so it
   gains nothing from phase 1, while registering a handler whose body is not yet
   installed opens a window where a firing event hits an unresolvable
   `__uh_<id>`. Hooks stay whole in `coact-init`, where registration and body
   installation happen together. The known cost is unchanged by this ns: a user
   hook on `:agent.instance/created` still misses the first agent's own
   creation, because that event fires before the first turn.

   NATIVE-IMAGE DISCIPLINE: registration must never be a namespace-load
   `defonce` side effect. The native-image policy initializes `ai.brainyard.*`
   namespaces at BUILD time, so a load-time scan would bake the build machine's
   skill and tool directories into the image heap and never read the user's own.
   The guard below is a `defonce` ATOM holding `false` — safe to bake — with the
   scan behind a runtime `compare-and-set!`. See the long note at the foot of
   `agent.common.skills`."
  (:require [ai.brainyard.agent.common.skills :as skills]
            [ai.brainyard.agent.common.user-agents :as user-agents]
            [ai.brainyard.agent.common.user-tools :as user-tools]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.mulog.interface :as mulog]))

(defonce ^{:private true
           :doc "Process-once guard for the dynamic skill scan. A defonce ATOM
  (not a defonce side effect): safe to bake at native-image build time because
  it only ever stores `false` — the FS scan happens at runtime behind the CAS."}
  !skills-registered?
  (atom false))

(defn- register-skills-once!
  "Scan every skill backend (~/.brainyard, ~/.claude, ~/.agents, project
   .brainyard) and register each as a `:skill$<name>` tool — exactly once per
   process. Resets the guard on failure so a transient FS error can retry."
  []
  (when (compare-and-set! !skills-registered? false true)
    (try
      (skills/reload-skills!)
      (catch Throwable e
        (reset! !skills-registered? false)
        (mulog/warn ::skills-register-failed :error (ex-message e))
        nil))))

(defn boot-registries!
  "Register every user-authored definition into the shared tool registry.

   Call from a process entry point AFTER `install-working-dir!`, so `-C` /
   `BY_PROJECT_DIR` are in effect and `init-dirs!` resolves to the project the
   user is actually in — the same ordering constraint `register-project!` has.

   Each sub-loader carries its own idempotency guard, so this is safe to call
   more than once per process and from more than one entry point.

   `:skills` controls the one expensive step — the skill scan slurps and parses
   every SKILL.md across four roots:

     :sync  (default) block until skills are registered. Correct for a one-shot
            `by ask`, where a background scan would race the single turn and the
            skill would silently not exist for it.
     :async scan on a future. Right for the TUI, where a skill landing a moment
            after the prompt appears simply shows up on the next turn (the agent
            reads the registry per-turn) and boot must not stall.
     :skip  don't scan. For entry points that read the registry but cannot use a
            skill, e.g. `by agents`, which renders `:type :agent` only.

   User tools and user agents are always synchronous: a handful of small files
   under `.brainyard/`, and they are exactly what a CLI listing needs to be
   correct at the moment it prints.

   Returns `{:skills … :tools [names] :agents [names]}`; `:skills` is the future
   under `:async` and nil under `:skip`. Never throws — a registry that fails to
   populate must not take down the process that was merely booting."
  [& {:keys [dirs skills] :or {skills :sync}}]
  (let [dirs (or dirs (config/init-dirs!))
        safe (fn [label f]
               (try (f)
                    (catch Throwable e
                      (mulog/warn ::boot-register-failed :registry label
                                  :error (ex-message e))
                      nil)))
        result {:skills (case skills
                          :sync  (safe :skills register-skills-once!)
                          :async (future (safe :skills register-skills-once!))
                          :skip  nil)
                :tools  (safe :tools  #(user-tools/ensure-registered! :dirs dirs))
                :agents (safe :agents #(user-agents/ensure-loaded! :dirs dirs))}]
    (mulog/info ::boot-registries
                :tools (count (:tools result))
                :agents (count (:agents result))
                :skills-mode skills)
    result))
