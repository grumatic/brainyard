# Session channel extensions — discovery, data connectors, event triggers, display sinks

> Companion to [`ask-attach-channel.md`](ask-attach-channel.md). That doc defines the
> per-session AF_UNIX socket (`<project>/.brainyard/sessions/<sid>/ask.sock`) and the
> one-shot `{:op :ask …}` RPC. This doc generalizes it into a **session control
> channel** that an external environment can use to enumerate live sessions, push data
> in, trigger turns, and stream events/display out.

## 0. Status

Design / research, with one prerequisite already landed. The baseline (`:op :ask`)
ships today. Everything in §2–§6 is additive and `:op`-dispatched, so `{:op :ask}` is
unchanged. **§1 (single-owner) is a prerequisite** — without it the new surfaces inherit
a pre-existing multi-process race.

- ✅ **§1 fix 3 (shipped)** — the per-session ownership lock is now acquired at
  session-open, `:pid` is stamped into `meta.edn`, and `by run --resume <id>` refuses a
  session another live `by` process owns (read-only PID-checked probe; a stale lock from
  a crashed process does not block). Lock is released on tab-close, `stop!`, and the JVM
  shutdown hook.
- ✅ **§1 fixes 1 & 2 (shipped)** — `start-listener!` refuses to bind over a live owner
  (bare AF_UNIX connect probe → throws `{:reason :live-owner}`; only a stale file is
  unlinked before rebind), and `stop-listener!` unlinks only the socket it bound
  (fileKey identity), so a closing orphan can't sever a successor. The connect probe
  needs no protocol change.
- ✅ **§3a op-dispatch + §3b `:op :status` (shipped)** — the transport no longer gates
  `:ask`; `handle-fn` owns dispatch (`ask-handle-fn` `case` on `:op`). `:op :status`
  returns a non-blocking snapshot (`:state` idle/running, `:pending-turns`,
  `:provider`/`:model`/`:agent`/`:pid`). New verbs hang off the same dispatcher.
- ✅ **§2 discovery (shipped)** — `enriched-summaries` rows now carry the full connect
  descriptor `:live?` / `:owner-pid` / `:ask-socket-path` / `:ops`; `by sessions list`
  gained `--live` and a `●live` table marker; `--json` surfaces it all; `meta.edn`
  advertises `:ops`. (`by sessions <list|show|label|prune>` accept `-C/--working-dir` to
  target a specific project, same as `run`.) **Socket path fallback:** AF_UNIX paths are
  capped (~104 bytes, macOS), so for deep project trees `persist/file-of :ask-sock` —
  the single choke point both the listener and the attach client derive through —
  relocates the socket to a short deterministic `<tmpdir>/by-<sha256-16>.sock` when the
  natural `<session-dir>/ask.sock` would overflow. The resolved path is recorded in
  `:ask-socket-path`; clients must use that, not reconstruct the natural path.
- ✅ **§4 `:op :inject` data connector (shipped)** — three sinks: `:as :artifact`
  (explicit-agent `agent/add-artifact!`, seen next turn, no forced turn — the canonical
  connector), `:as :turn` (`:await? false` fire-and-forget event trigger, else blocks
  like `:ask`), `:as :memory` (writes `<project-config-dir>/memory/<slug>.md`). The
  artifact path uses an explicit-agent API because `proto/*current-agent*` is unbound on
  the listener thread.
- ✅ **§3c `:op :cancel` (shipped)** — cancels the running turn for the socket's session
  (`input/cancel-ask-for-agent!`, shared with Ctrl-C); returns `{:cancelled bool}`.
- ✅ **§5a `:op :subscribe` (shipped)** — Mode B streaming. The transport gained a
  `stream-response` path: `handle-connection!` keeps the connection open and runs the
  handler's `(fn [emit! alive?] …)`, with a daemon watcher flipping `alive?` on client
  EOF. `handle-subscribe-op` registers a hooks listener per requested event (bulk
  teardown via `unregister-source!` on disconnect — no leak), session-scoped, payloads
  sanitized to EDN via `edn-safe` (drops the `:agent`), backpressure = a bounded queue
  that drops for a slow consumer. `:ops` now `[:ask :status :inject :cancel :subscribe]`.
- ✅ **§5b display sink (shipped)** — two paths: file-tail (`tail -F
  <session>/scrollback.stream.txt`, zero code) and a real-time socket sink. The latter
  is the new `:display` hook event, fired from `persist_bridge/tee-scrollback!` (the
  single choke point both emit paths funnel through) with `{:session-id :text}`; an
  ask-socket `:subscribe [:display]` streams it. The subscribe session-filter now scopes
  on `:session-id` or `:agent`. `:text` is the raw rendered chunk (may contain ANSI).

---

## 1. Prerequisite — enforce a single owner per session

### 1.1 The gap

`ask.sock` and every session snapshot assume **one process owns a session**. Nothing
enforces it today:

- The per-session PID lock (`agent-tui-persist .../core/lock.clj`, `by-host.lock`) is
  implemented, re-exported on the persist interface (`try-acquire-lock!`,
  `with-session-lock`), and unit-tested — but has **zero live callers**. It is a relic
  of the retired `by-host`/`by-ui` daemon split (`tmux-based-agent-tui.md R-6`).
- `by run --resume <id>` has **no liveness guard**.

So two `by` processes can open the same project-scoped session-id concurrently. The
result is silent, not a clean error:

1. **Snapshot corruption.** Both read at open and write on their own cadence to the same
   `meta.edn` / `session.edn` / `messages.log` / `scrollback.*` / `trajectory.edn` /
   `queue.edn` / `usage-tracker.edn`. `persist/update-snap!` is explicitly *"not atomic
   across concurrent updaters — caller is responsible for serialisation (typically by
   holding a per-session ReentrantLock)."* That lock is **in-process only** — it does
   nothing across processes. → clobbered EDN snapshots + interleaved append-only logs.
2. **`ask.sock` clobber, last-opener-wins.** `start-listener!` calls `delete-quietly!`
   on the path **before** `.bind` (`ask-channel/core/server.clj:81`). Process B unlinks
   A's socket and binds its own at the same path. `--attach` now reaches **B only**; A's
   `ServerSocketChannel` is a ghost on the unlinked inode (keeps existing clients, gets
   no new connections). Both stamped the same `:ask-socket-path` into `meta.edn`.
3. **First-closer breaks the survivor.** `stop-listener!` deletes the socket **by path,
   not by identity** (`server.clj:98`). When the orphaned process A later exits, its
   shutdown hook unlinks **B's** live socket.

### 1.2 The model the architecture already wants

> **Exactly one process OWNS a session** (holds its files, binds its `ask.sock`).
> Everyone else is a **CLIENT** over that socket. `by ask --attach` is precisely a
> non-owning client.

This yields the rule the rest of this doc relies on:

| interaction | owners | who may do it |
|---|---|---|
| Mode A turn-producing (`:ask`, `:inject :as :turn`, `:cancel`) | mutate state | routed through the **single owner** |
| Mode A read-only (`:status`) | none | any client |
| Mode B subscription (`:subscribe`) | none | **many** clients concurrently |

Many external envs may subscribe and read; only the owner mutates.

### 1.3 Fixes (cheapest first — all reuse existing infra)

1. **Make `ask.sock` bind the liveness token.** ✅ *Implemented.* `start-listener!`
   replaces the unconditional `delete-quietly!` with a `live-owner?` probe — a bare
   AF_UNIX **connect** (a live listener accepts; a crashed process's leftover file is
   refused). A live owner → throw `{:reason :live-owner}` (never clobber); only a stale
   file is unlinked before rebind. `start-ask-listener!` catches the refusal and logs
   `::ask-socket-owned-by-live-process` (session opens without an attach socket). The
   connect probe needs no `:op :status` verb — that's deferred to §3 where it's consumed.
2. **Stop-by-identity.** ✅ *Implemented.* The handle records the socket's `fileKey`
   (device+inode) at bind; `stop-listener!` unlinks only when the file still at the path
   has that identity (`should-unlink?`), so a closing orphan can't delete a successor's
   rebound socket. (Inode-reuse caveat documented in `file-identity`.)
3. **Wire the existing lock.** ✅ *Implemented.* `create-tui-agent!` acquires the lock
   (`agent-tui core/acquire-session-lock!` → `persist/try-acquire-lock!`), registers the
   handle in `!session-locks`, and stamps `:pid` into `meta.edn`. `run-tui!`'s resume
   path refuses up front via the read-only `persist/held-by-other-live-process?` probe.
   Release is wired into the before-close hook, `stop!`, and the JVM shutdown hook.
   Tests: `agent-tui-persist persist_test/owner-pid-and-liveness-test`.

All three fixes are now implemented: fix 3 (PID-lock resume refusal) is the front-line
guard; fixes 1 (bind-as-token) and 2 (stop-by-identity) close the residual
pre-flight↔bind race at the socket layer. The single-owner invariant the §3–§5 surfaces
rely on now holds.

---

## 2. Discovery of live sessions (root-agents)

Today `cmd-ask-attach` only does `(.exists sock)` — stale after a crash, and silent on
capabilities. Add a liveness/capability descriptor; keep it file-based (no new socket
required).

### 2.1 `meta.edn` enrichment (stamped at `start-ask-listener!`)

```clojure
{:ask-socket-path "/…/sessions/<sid>/ask.sock"
 :pid            <owning-pid>          ; from lock.clj / ProcessHandle (§1 fix 3)
 :protocol       1                     ; ask.sock protocol version
 :ops            #{:ask :inject :subscribe :status :cancel}  ; capability set
 :agent          "coact-agent"
 :label          "main0"}
```

**Liveness predicate (pure):** a session is live ⇔ `meta.edn` has `:ask-socket-path`,
the file exists, **and** `(alive? (:pid meta))`. PID-checked, not existence-checked —
correct across crashes.

### 2.2 Surfaces

- **CLI / JSON (preferred, zero socket code for consumers):** extend
  `by sessions list --json` (already emits `enriched-summaries`) with `:live?` + `:ops`,
  or add `by sessions list --live`. External tools shell out and parse JSON.
- **Optional process broker:** one process hosts many tabs. A single
  `<project>/.brainyard/by.sock` answering `{:op :list-sessions}` →
  `{:sessions [{:sid :label :agent :ops}…]}` lets an external env enumerate in-process
  roots without scanning the filesystem. Mirrors the tmux `:list-sessions`/`:sessions`
  pair (`agent-tui-tmux/.../control/protocol.clj`).

---

## 3. The core move — `ask.sock` as a two-mode channel

Keep the socket, EDN newline-framing, `0600`, and the project trust boundary. Add a
second interaction mode. All `:op`-dispatched; unknown op already returns
`{:status :error :error "unknown op"}`.

### Mode A — Request/response (today's model, new verbs)

One frame in, one frame out, close.

```clojure
→ {:op :ask    :question "…" :timeout-ms 120000}        ; exists
→ {:op :inject :as :artifact|:turn|:memory … :await? false}   ; §4
→ {:op :status}                                          ; non-blocking snapshot
→ {:op :cancel}                                          ; cancel active turn
← {:status :ok …}                                        ; shape per-op
← {:status :error :error "…"}
```

`:status` → `{:status :ok :state :idle|:running :iteration N :pending-turns K
:provider … :tasks […]}`. `:cancel` wires to the per-tab `cancel-active-ask!` from the
multi-tab refactor.

### Mode B — Subscription / streaming (new)

One `:subscribe` frame in, **N event frames out** until the client disconnects.

```clojure
→ {:op :subscribe :events [:agent.iteration/post :display …] :filter {…}}
← {:status :ok :subscribed [...]}                        ; ack
← {:event :agent.iteration/post :sid "…" :ts … :payload {…}}   ; repeated
← {:event :display :sid "…" :ts … :text "…"}                   ; repeated
```

This is the **only** invasive change: `handle-connection!` currently reads one frame and
closes. For `:subscribe`, keep the connection open, register a hooks listener that
serializes each matching event to the connection's writer, and tear the listener down on
socket EOF. **Template: `agent-tui-tmux/.../control/server.clj`** — its persistent
`Connection` record + accept loop already solved this (retired daemon, intact code).

---

## 4. Data injector / data connector — `:op :inject` (Mode A)

Push data *into* a session. The verb chooses the **sink**, not just "send text":

```clojure
{:op :inject :as :artifact :name "db.orders" :content "…" :pin? true}  ; → artifacts.clj
{:op :inject :as :turn     :text "ETL finished, 4021 rows"}           ; → enqueue-input!
{:op :inject :as :memory   :slug "deploy-log" :content "…"}           ; → project file memory
```

- **`:as :artifact`** is the true *data connector* — a sensor / DB / webhook keeps a
  named artifact fresh; the agent sees current values on its **next turn** without being
  interrupted. Backbone exists: `agent/.../common/artifacts.clj` + the `## Live
  Artifacts` context section.
- **`:as :turn`** is `:ask` minus the reply promise — forces a turn.
- **`:as :memory`** writes a project-scoped memory slug.

All turn-producing variants route through the **single owner** (§1) and the per-root
input queue — one turn at a time per tab, unchanged.

---

## 5. Event triggers & display sinks (Mode B)

### Inbound event trigger (external → turn)

`{:op :inject :as :turn :source :event-trigger :await? false}` — fire-and-forget (no
`:reply`) so a cron/webhook pokes the session and disconnects; the existing
deferred-task / `task$wakeup` async machinery carries it.

### Outbound event trigger (brainyard → external)

`{:op :subscribe :events [:agent.tool-use/post :agent.iteration/post :task/completed]}`
→ a stream sourced straight from the hooks registry (`agent/.../core/hooks.clj`, rich
catalog already published). An external orchestrator reacts to agent lifecycle in real
time.

### Display sink

A specialized outbound subscription — the external env wants what the agent *renders*:

- **Socket stream (real-time):** `{:op :subscribe :events [:display]}`, fed by the
  `emit!` / answer path. Lowest latency (remote mirror, TTS reader, web overlay).
- **File tail (zero-protocol, pragmatic v1):** brainyard already persists
  `scrollback.stream.txt` / `scrollback.activity.txt` per session — an external sink can
  `tail -F` them today with no new code.

---

## 6. Consolidated wire format

```clojure
;; ── Mode A: RPC (one in, one out) ─────────────────────────────
→ {:op :ask     :question "…" :timeout-ms 120000}
→ {:op :inject  :as :artifact|:turn|:memory … :await? false}
→ {:op :status}
→ {:op :cancel}
← {:status :ok …}            ← {:status :error :error "…"}

;; ── Mode B: subscription (one in, N out, until disconnect) ────
→ {:op :subscribe :events [:agent.iteration/post :display …] :filter {…}}
← {:status :ok :subscribed [...]}
← {:event … :sid "…" :ts … :payload|:text …}     ; repeated
```

- `:op`-dispatched (compat preserved). Bump `:protocol` in `meta.edn`; advertise `:ops`
  so a client discovers capability without connecting.

---

## 7. Risks & notes

- **Streaming is the only invasive change.** Mode B needs a persistent-connection path +
  a hooks listener whose teardown is tied to socket EOF (leak risk if you don't
  `unregister-source!` on disconnect). Reuse the tmux `control/server.clj` lifecycle.
- **Backpressure.** A slow display/event consumer blocks the writer. Bound the
  per-connection queue and drop-or-disconnect — never let a stuck sink stall the agent's
  `emit!` path.
- **Serialization unchanged.** `:ask` / `:inject :as :turn` still funnel through the
  per-root input queue — one turn at a time per tab. Subscriptions are read-only.
- **Trust boundary** stays filesystem perms (`0600` inside `.brainyard`), same as
  `nrepl-port`. Not an authenticated remote API; cross-host needs a TCP+auth front (out
  of scope).
- **GraalVM.** AF_UNIX NIO is already validated on the native binary
  (`ask-attach-channel.md` §9). Persistent connections + extra daemon threads add no
  native-image risk beyond what's proven.
- **Naming.** `docs/tui/architecture.md:386` rejected a generic `control.sock` for the
  retired daemon. Keep the file named `ask.sock` (preserves the `--attach` contract);
  let `:op` carry the generality.
