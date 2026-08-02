;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.nrepl-bindings
  "Auto-binding of registered tools into the live nREPL image — the `:nrepl`
   `:clj-backend`'s counterpart to the SCI sandbox's `:bindings`.

   WHY. `coact-init` builds ONE bindings map (sandbox-bindings/make-tool-bindings)
   and the system prompt renders it as `### Function Directory` / `### Sandbox
   Categories` plus the `### Hot-path primitives` table — `(read-file …)`,
   `(bash …)`, `(<tool-id> {…})`. On the `:sandbox` backend those symbols resolve
   because the map is injected into the SCI `user` ns. On `:nrepl` the clojure
   fences were routed to a bare socket eval where NOTHING was interned, so the
   model was handed a catalog of callables that could not resolve, and had to be
   taught a second, backend-specific API (`tool/call-tool` + a manual
   `*current-agent*` dance) to compensate. This ns closes that gap: the SAME
   binding map is interned into the live image, so one prompt is true on both
   backends.

   HOW. The nREPL server is IN-PROCESS (loopback — see clj-nrepl.core.server), so
   the binding closures are ordinary Clojure fns reachable from the eval thread —
   no serialization involved. `install!` interns them into a namespace derived
   from the agent instance id (`by.tools.<type>.<suffix>`), and the eval path
   prefixes every block with `(in-ns 'by.tools.…)` so unqualified tool symbols
   resolve.

   Two properties worth stating:

   - **Per-agent namespace, never `user`.** The closures capture their agent (so
     `call-tool` binds `*current-agent*` — the reason memory$*/session tools
     needed an explicit `:agent` on this backend). With two live nREPL agents
     interning into a shared `user`, the last writer's captured agent would win
     and tool calls would silently run as the WRONG agent. A namespace per
     instance also leaves `user` clean for a CIDER client attached to the same
     server.
   - **Local endpoint only.** A non-loopback `:nrepl-host` (R4 remote exec) is a
     different JVM that cannot hold our closures; `install!` no-ops there and the
     `call-tool` route documented in the `:nrepl` usage guide remains the way in.

   Stateless by construction — the namespace name is derived from the agent id
   and interned vars are tagged with `::tool-binding`, so refresh/teardown need
   no registry atom (which would also bake build-time state under native-image)."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

(def ^:private binding-marker
  "Var-meta key stamped on every auto-bound tool var. Distinguishes OUR interns
   from the LLM's own `(def …)`s in the same namespace, so a stale-tool sweep
   never unmaps the model's working state."
  ::tool-binding)

(def ^:private loopback-hosts
  "Hosts that mean 'the in-process server'. Mirrors clj-nrepl.core.client."
  #{"127.0.0.1" "localhost" "::1" "0:0:0:0:0:0:0:1"})

(defn local-endpoint?
  "True when `agent`'s nREPL endpoint is the in-process (loopback) server — the
   only case where interning local closures is meaningful. An unset `:nrepl-host`
   is loopback (client/eval-string defaults to 127.0.0.1)."
  [agent]
  (let [host (if agent
               (config/get-config agent :nrepl-host)
               (config/get-config :nrepl-host))]
    (or (str/blank? (str host))
        (contains? loopback-hosts (str host)))))

(defn- sanitize-segment
  "Reduce one agent-id segment to characters that are safe in a namespace name."
  [s]
  (let [cleaned (str/replace (str s) #"[^a-zA-Z0-9-]" "-")]
    (if (str/blank? cleaned) "anon" cleaned)))

(defn tool-ns-sym
  "The tool namespace symbol for `agent` — `by.tools.<type>.<suffix>`, derived
   purely from the agent instance id so the installer and the eval path agree
   without shared state. nil when no agent id is resolvable."
  [agent]
  (when-let [aid (try (when agent (proto/agent-id agent)) (catch Throwable _ nil))]
    (let [nsp (namespace aid)]
      (symbol (str "by.tools."
                   (when nsp (str (sanitize-segment nsp) "."))
                   (sanitize-segment (name aid)))))))

(defn- tool-var?
  [v]
  (boolean (get (meta v) binding-marker)))

(defn- unmap-stale!
  "Drop auto-bound vars for tools that are no longer in `bindings` (a tool
   deleted / hidden since the previous turn). Vars without the marker — the
   model's own `def`s — are left alone."
  [target bindings]
  (doseq [[sym v] (ns-interns target)]
    (when (and (tool-var? v) (not (contains? bindings sym)))
      (ns-unmap target sym))))

(defn- intern-binding!
  "Intern one [sym f] into `target`, carrying the binding's `:doc`/`:arglists`/
   `:category` meta onto the var so `(meta #'sym)` reads the same as it does in
   the sandbox. A pre-existing mapping owned by ANOTHER namespace (a
   `clojure.core` refer under the same name) is unmapped first — `intern` would
   otherwise print a replace WARNING to *err*, which would land in the model's
   captured output as noise."
  [^clojure.lang.Namespace target sym f]
  (let [existing (get (ns-map target) sym)]
    (when (and (var? existing)
               (not= target (.ns ^clojure.lang.Var existing)))
      (ns-unmap target sym)))
  (intern target
          (with-meta sym (assoc (meta f) binding-marker true))
          f))

(defn install!
  "Intern `bindings` (the same {symbol fn} map the SCI sandbox and the prompt's
   function directory are built from) into `agent`'s tool namespace, creating it
   on first use. Idempotent — call once per turn to pick up tools registered
   mid-session (user tools, MCP servers).

   Returns the namespace symbol on success, nil when skipped (no agent, empty
   bindings, remote endpoint) or on failure — binding is an enhancement, never a
   reason to fail a turn."
  [agent bindings]
  (when (and agent (seq bindings) (local-endpoint? agent))
    (when-let [ns-sym (tool-ns-sym agent)]
      (try
        (let [fresh? (nil? (find-ns ns-sym))
              target (create-ns ns-sym)]
          ;; Only on creation: a repeat `refer-clojure` would re-assert core
          ;; mappings OVER a tool var that shadows a core name.
          (when fresh?
            (binding [*ns* target] (refer-clojure)))
          (unmap-stale! target bindings)
          (doseq [[sym f] bindings]
            (intern-binding! target sym f))
          (mulog/log ::nrepl-tools-bound
                     :agent-id (proto/agent-id agent)
                     :ns       (str ns-sym)
                     :count    (count bindings))
          ns-sym)
        (catch Throwable t
          (mulog/warn ::nrepl-tool-binding-failed
                      :agent-id (try (proto/agent-id agent) (catch Throwable _ nil))
                      :ns       (str ns-sym)
                      :error    (.getMessage t))
          nil)))))

(defn active-ns
  "The tool namespace symbol for `agent` when it exists AND actually holds bound
   tools; nil otherwise. A stateless probe: the eval path uses it to decide
   whether to prefix `(in-ns …)`, so a skipped/failed `install!` degrades to
   plain `user`-ns eval rather than dropping blocks into an empty namespace with
   no `clojure.core` refers."
  [agent]
  (when-let [ns-sym (tool-ns-sym agent)]
    (when-let [n (find-ns ns-sym)]
      (when (some tool-var? (vals (ns-interns n)))
        ns-sym))))

(defn prefix-code
  "Prepend the namespace switch to a code block destined for the live session.

   Applied per block rather than once at session open, so it is self-healing: a
   block that ends with the model's own `(in-ns 'some.other.ns)` — legitimate,
   e.g. to reach a namespace's private vars — does not strand every LATER block
   outside the tool namespace. Within a block the model's own `in-ns` still wins,
   since it evaluates after this form.

   The prefix's return value is harmlessly discarded: nREPL's last-`:value`-wins
   harvest means the real code's result is what surfaces."
  [ns-sym code]
  (str "(clojure.core/in-ns '" ns-sym ")\n" code))

(defn uninstall!
  "Remove `agent`'s tool namespace — called on instance teardown so a long-lived
   JVM does not accumulate one namespace per closed agent."
  [agent]
  (when-let [ns-sym (tool-ns-sym agent)]
    (when (find-ns ns-sym)
      (try
        (remove-ns ns-sym)
        (mulog/log ::nrepl-tools-unbound
                   :agent-id (try (proto/agent-id agent) (catch Throwable _ nil))
                   :ns       (str ns-sym))
        ns-sym
        (catch Throwable _ nil)))))
