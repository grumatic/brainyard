# ACP-agent management — connections, not subagents

> Every acp-agent instance is a session-shared external **connection** (a
> subprocess + one model-pinned ACP session + live conversation), not a
> throwaway owned subagent. An acp-aware overlay — a per-instance descriptor,
> the `acp$*` CRUD/ask family, and a dedicated per-session cap — layers over the
> generic agent registry so callers can answer "who's for what, when useful".

## 0. Status

**Implemented.** Builds on the generic instance registry described in
[agent-lifecycle-management.md](./agent-lifecycle-management.md) (`!agent-registry`,
`:lifecycle`, the `agent-registry$*` family) and the ACP bridge in
[acp-design.md](./acp-design.md).

## 1. The problem

`acp-agent` (`components/agent/.../common/acp_agent.clj`) bridges to an external
ACP backend (`:stub`, `:claude-code`, `:gemini`, `:codex`, or a registered
custom key). On first ask it spawns an `AcpClient` subprocess, opens **one**
long-lived ACP session with a **fixed model**, and caches both on the record's
`!state`. The instance lands in the flat `!agent-registry` as
`:acp-agent/<suffix>` and — per the lifecycle design — stays alive, askable, and
LRU-evictable like any subagent.

That generic treatment loses everything that distinguishes one acp-agent from
another:

1. **Opacity.** `agent-registry$list` shows `:acp-agent/silver-otter-7` with
   generic lifecycle fields only. It cannot say which **backend**, which
   **model**, which **ACP session**, whether the **subprocess is alive**, or
   what the connection is **for**. Two claude-code/opus instances are
   indistinguishable.
2. **No reuse lookup.** Before spawning, a caller can't ask "do I already have a
   claude-code/opus connection?" — so it spawns redundant subprocesses.
3. **CRUD fused to dispatch.** The only way to make one is to ask it a question;
   there is no acp-aware create/update/delete. Model is fixed at session open, so
   "change model" is really a session recycle the generic family can't express.
4. **Backend-blind eviction.** The generic LRU can silently kill an acp-agent —
   an OS subprocess + minutes of conversation + a paid external session — exactly
   as if it were a throwaway in-proc explore-agent.

## 2. The reframe: shared connection, not owned subagent

An acp-agent is modeled as a **session-scoped shared connection**:

- Registered with **`:owner nil`** (root-like) so it is automatically **exempt
  from the generic LRU** (`evict-subagents-to-cap!` only evicts `subagent?`
  instances — non-nil `:owner`) and from parent-close cascade. It dies only on
  explicit `acp$close`, `/agent close`, task-cancel, or session teardown.
- Governed by a dedicated `acp$*` family layered over the same registry, so acp
  connections get acp-aware reads/CRUD while the LRU leaves them alone. `acp$ask`
  keeps the **same topology fence as `agent-registry$ask`** (a root may ask a
  sibling root or a subagent in its own session; a subagent may ask only what it
  dispatched) — it exists as its own verb for the acp-scoped surface, not to relax
  reach.

A dispatched `(acp-agent {:question …})` subagent (non-nil `:owner`) still
works and is still managed by `agent-registry$*`; the `acp$*` family recognizes
it (via `acp$list`/`acp$ask`) but routes its teardown to `agent-registry$close`.

## 3. The descriptor

`::descriptor` on `!state`, stamped in `open-session!` and refreshed per turn:

```clojure
{:backend      :claude-code
 :backend-opts {:model "opus"}
 :model-label  "opus"
 :model-id     "claude-opus-…"   ; resolved id set on the session
 :session-id   "<ACP session id>"
 :purpose      "refactor payments" ; the "who's for what" (derived <backend>/<model> when unset)
 :spawned-at   <ms>
 :prompts      n                   ; ACP turns driven
 :provisioned? true}               ; acp$create-provisioned (vs TUI root / subagent)
```

`descriptor` derives `:purpose` (falls back to `<backend>/<model>`) and probes
`:health` live (`:open`/`:dead`/`:unconnected`) from the cached client.
Public API in `acp_agent.clj`: `acp-instance?`, `descriptor`, `set-purpose!`,
`mark-provisioned!`, `advertised-models`, `ensure-connected!` (eager provision),
`recycle-session!` (model switch).

## 4. The `acp$*` family

Rides `default-agent-roster` (inherited by every coact/react-derived agent),
gated on `:enable-subagent-calls`.

| Verb | Behavior |
|---|---|
| `acp$create {:backend :model :purpose :backend-opts}` | Eager provision: prereq-checked spawn + `initialize!` + open session (model pinned), `:owner nil`, `:provisioned? true`. Refuses at the per-session cap. Returns `:acp-id` + descriptor. |
| `acp$list {:backend? :model?}` | ACP-only enriched rows (`:backend :model :purpose :session-id :health :prompts :status :owner :provisioned? :idle-ms`); optional filter for the reuse lookup. |
| `acp$detail {:id}` | Descriptor + advertised models (what `acp$update :model` can switch to) + last answer. |
| `acp$ask {:id :question}` | Follow-up ask reusing the connection's context. Reach = `agent-registry$ask`'s topology fence (**sibling-root / owned-subagent**). |
| `acp$update {:id :purpose? :model?}` | `:purpose` relabels. `:model` **recycles the session** (fresh session, new model, **conversation context reset**); persisted as a per-agent override only. Ownership-fenced + idle-only. |
| `acp$close {:id}` | Reaps the subprocess. Ownership-fenced; only tears down **provisioned** roots — a TUI-attached root goes through `/agent close`, an owned subagent through `agent-registry$close`. |

Reach fence (`authorize-acp`): kill-switch throughout. `acp$ask` applies a
not-self check plus the `agent-registry$ask` topology fence (`reach-ask-acp`);
`acp$update`/`close` use the `agent-registry$close` topology fence
(`reach-manage-acp` — same-session, and a subagent may act only on connections it
dispatched) and are idle-only. `nil` caller
(programmatic/TUI colon-command dispatched *as* the root) is unrestricted.

## 5. The cap

`:max-acp-agents-per-session` (default **3**) counts **all** acp instances in the
session — TUI roots and `acp$create`-provisioned alike (each is a real
subprocess). `acp$create` **refuses** at the cap rather than evicting, so a paid
external session is never silently killed by an unrelated dispatch. (ACP roots
are `:owner nil`, hence already outside the generic subagent LRU.)

## 6. Root-attached (TUI) vs provisioned (headless) — same registry entry

Two front-ends create acp instances; both produce the same kind of thing (an
`:owner nil` acp connection with a descriptor):

- **Provisioned (headless):** `acp$create` — flagged `:provisioned? true`,
  eager, closeable via `acp$close`.
- **Root-attached (TUI):** `/agent new acp-agent <backend> [model]` or
  `/session new acp-agent <backend> [model]`. Without the reframe, these could
  only produce a `:stub` agent because the command carried no backend; the
  tokens now seed `:acp-backend`/`:acp-backend-opts` per-agent config overrides
  through `create-tui-agent!` (the same mechanism `-n`/`-v` use). The TUI drives
  it as an ordinary root (a plain typed turn becomes the acp-agent's
  `:question`); its single-turn BT fires the iteration hooks the TUI already
  renders. It appears in `acp$list` (descriptor is stamped on connect regardless
  of front-end), but `acp$close` refuses it (no `:provisioned?` flag) — it is
  closed with `/agent close`, which already guards the attached/last root.

So `acp$*` and the human `/agent`/`/session` verbs are two views over one
registry, mirroring the generic-registry parity in the lifecycle design.

## 7. Design notes

- **Why `acp$ask` is its own verb.** It gives the acp-scoped surface its own ask
  entry point (acp-aware errors, paired with `acp$list`/`detail`) while keeping
  the *same* topology fence as `agent-registry$ask` — a subagent still can't ask
  upward and loop. The verb is separate for cohesion, not to relax reach.
- **Why refuse-at-cap, not LRU.** Silently closing a paid external session on an
  unrelated dispatch is the worst outcome; making creation fail loudly puts the
  choice in the caller's hands.
- **Model switch = session recycle.** ACP pins the model per session, so the only
  honest "change model" resets the conversation. `acp$update :model` says so in
  its result rather than pretending to mutate in place.
- **Descriptor outlives close.** The cleanup hook drops the client/session cache
  (health → `:unconnected`) but leaves the descriptor for post-mortem, matching
  the task-output retention decision in `CLAUDE.md`.
