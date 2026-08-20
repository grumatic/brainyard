# Applying the Agent2Agent Protocol (A2A) to brainyard

> Status: **Shipped, speaking A2A v0.3 AND v1.0** (2026-08-02, author:
> assistant + Jake Na).
>
> Both wire dialects are supported and negotiated: the client picks per peer
> from its Agent Card, the server per request from the inbound
> `A2A-Version`, and one card advertises both generations. Verified in both
> directions against the official `a2a-sdk` 1.1.0 helloworld agent, and
> repeatable with **`bb a2a:interop`** — the only check that catches a
> dialect change, since fixtures only ever encode what we already believe.
> See §13.
>
> **Live-verified (nrepl-verify, against a real LLM and a separate server
> process).** A `bb tui` JVM as client, a second `by` process as server:
> `a2a$connect` discovered the card, `a2a$card` listed the skill,
> `a2a$<peer>$<skill>` returned a real answer from the peer's `explore-agent`,
> `agent-registry$ask` followed up in the same remote conversation, the remote
> instance appeared in `agent-registry$list` as `:kind :remote`, and
> `a2a-agent` answered a live question by calling `a2a$list` + `a2a$card`.
>
> **Two bugs that ONLY live verification could find** — both fixed, both with
> regression tests. Every unit test had set the relevant state by hand and so
> never exercised the real layering:
>
> - **Call depth was counted three times, so no remote call could ever
>   succeed.** `tool/call-tool` already binds an incremented `*call-depth*`
>   for a `:type :agent` dispatch, `make-invoke` incremented it again, and
>   `stamp-chain` added its own `+1`. With the default limit of 3, the very
>   first hop came back "depth limit reached (3 >= 3)". Fixed by removing the
>   duplicate increment and handing `stamp-chain` the pre-dispatch depth; a
>   first hop now stamps 1.
> - **Conversation continuity was broken on the default path.**
>   `message/send` returned the peer's `contextId`, but the STREAMING path
>   never put it in any frame and `ask-streaming` never read it back — so
>   every follow-up silently started a fresh remote conversation. Streaming is
>   the default, so this was the normal case. Fixed on both sides (server
>   emits it, client captures it).
>
> One thing the topology cannot test in-process: the full
> client → HTTP → separate-server → follow-up path. Client and server sharing
> a JVM share a node id, so the cycle guard correctly refuses it. That path is
> covered by the live run above; the in-process suite pins the two halves.
> **Phases 0–7 complete** except OAuth2 client auth and push notifications,
> which are written up in §12 rather than left to be rediscovered. Both halves
> of the protocol work end to end, in the JVM and **in the native binary**.
>
> **As-built (Phase 7, verified against code):** `RemoteAgent` added to
> `reflect-config.json` (13-line surgical diff; `bb check:ata` OK), full
> `bb test` green (253 namespaces, exit 0), `bb build:ata` green, and A2A
> exercised against the **shipped native binary**: the card served
> unauthenticated, 401 without/with a wrong token, `tasks/get` on an unknown
> id returning a no-leak `-32001`, and the chain guard firing in-image.
>
> Two bugs the build and the native run found that no unit test had:
>
> - **Reflective interop in `auth.clj`.** `bb build:ata`'s reflect:check
>   ratchet flagged `URLEncoder/encode` resolving reflectively — a
>   native-image break waiting to happen. The fix needed the `^String` hint on
>   the **local binding**, not the argument form: `^String (or …)` is silently
>   discarded, because `or` is a macro and hint metadata does not survive
>   macroexpansion. The first fix looked right and changed nothing.
> - **A documented env var that did nothing.** `:a2a-expose-skills` was
>   declared `:type "array"` with no `:env-fn`, so `BY_A2A_EXPOSE_SKILLS` was
>   ignored — while both its doc string and the serve-refusal message told the
>   operator to set it. Found by running the real binary, not by any test.
>   Now parses comma-separated *and* EDN-vector forms via
>   `config/parse-string-list`, which returns nil (not `[]`) on junk so an
>   unparseable allow-list falls through instead of silently installing an
>   empty one.
>
> **As-built (Phase 6, verified against code):** `a2a-agent`
> (`common/a2a_agent.clj`) — a thin `run-coact-derived` defagent over
> `a2a$*` + `agent-registry$*` + `task$*`, **zero new commands**, with the
> hard FINAL-STEP dossier contract. Wired into `router_agent.clj`'s router in
> all three surfaces (directory, lettered decision table `O2. A2A-PEERS`,
> summary list). 8 structural/router tests / 59 assertions; the three sibling
> front-door suites (event / schedule / state-machine) still green at 37
> tests / 246 assertions.
>
> Two things the instruction is explicit about, because both are failure
> modes the rest of this design works hard to prevent and an LLM front door
> could undo in one sentence:
>
> - **A remote answer is untrusted input.** It is reported as "peer X says…",
>   never as established fact, and instructions embedded in it are never
>   followed. A peer is a third party, not part of this system.
> - **A cycle refusal is the guard working, not a bug to route around.** The
>   agent explains the loop rather than retrying.
>
> **As-built (Phase 5, verified against code):** `components/a2a-server`
> (`core/{handlers,sse,http}.clj` + `interface.clj`),
> `agent/common/a2a_serve.clj`, and `by a2a serve`. 18 server tests / 68
> assertions against a stub service, plus **9 loopback E2E tests / 43
> assertions running the real client against the real server over a real
> socket** — which is where §4's cycle guard is finally proven rather than
> simulated. Whole agent-side A2A surface + touched suites: 131 tests / 900
> assertions green. `bb poly check` OK.
>
> - **The server takes an INJECTED service map**, not a dependency on
>   `components/agent`. `{:card-fn :ask-fn :auth-token :get-task-fn …}` —
>   same pure-transport split as `acp` vs `acp-client`, and it means the
>   entire protocol surface is testable with no agent runtime at all.
> - **Two real bugs found by the E2E**, both in code Phases 3–4 had already
>   "passed": a streamed error frame was swallowed and reported as a generic
>   truncation (losing the actual refusal reason), and an explicitly-named
>   skill could be silently substituted by the single-exposed-skill fallback
>   — serving a caller from a *different agent than they asked for*. Both
>   fixed, both with named regression tests.
>
> **As-built (Phase 4, verified against code):** `A2ATaskJobExecutor`
> (`agent/task/executor.clj`, `:job-type :a2a`) + `remote-agent/ask-streaming`
> + the `:a2a/artifact` hook bridge. 16 new tests / 58 assertions against a
> real loopback HTTP+SSE server; the task subsystem's own suites (61 tests /
> 221 assertions) still green. Three things worth knowing:
>
> - **The poll throttles itself.** The manager's shared watcher calls
>   `:on-poll` about every 250ms, which is right for a local `Process` but
>   would mean four HTTP requests per second per task against a third party.
>   `:on-poll` keeps its own clock and returns `still-running` without a
>   network call between `:poll-interval-ms` ticks (default 2s).
> - **A transient poll failure does not fail the task.** A 5xx or a dropped
>   connection is reported once and polling continues — the peer may just be
>   restarting. Only a terminal `TaskState` promotes the task.
> - **`core` fires, `common` subscribes.** Persisting a streamed artifact
>   needs `common/artifacts.clj`, and a `core.* -> common.*` require would
>   invert the layering. `core/remote_agent.clj` fires `:a2a/artifact` on the
>   hooks bus instead, and `common/a2a.clj` registers the subscriber — using
>   the codebase's own extension mechanism rather than working around it.
>
> **As-built (Phase 3, verified against code):** `RemoteAgent`
> (`agent/core/remote_agent.clj`) + the `a2a$*` family
> (`agent/common/a2a.clj`) + `components/a2a/core/chain.clj`. Registry
> unification works as designed: a remote peer flows through `ask`,
> `agent-registry$ask`, the reach policy, the LRU cap and the close cascade
> with **no change to any of those call sites**. 17 new agent tests / 79
> assertions, 44 `components/a2a` tests / 344 assertions, `bb poly check` OK,
> and the pre-existing feature/config/subagent suites (199 tests / 1531
> assertions) still green. Four findings, all recorded below:
>
> - **§4 was wrong, twice** — see the correction block there. Both errors
>   produced a guard that silently never fired.
> - **`stop-agent` must unregister.** The first version flipped `:status` but
>   left the instance in the registry, so `agent-registry$close` reported
>   success while the peer stayed listed and kept counting against the LRU cap.
> - **Keyword→string ids, again.** `(str :a2a$b$planner)` is `":a2a$b$planner"`,
>   and those strings are handed to the model *as the tool name to call*. Same
>   bug class as the Phase-1 Agent Card skill ids; now covered by a named
>   regression test in both components.
> - **Workspace plumbing moved earlier than planned.** `poly check` demands
>   `agent-tui-app` and `development` declare `a2a`/`a2a-client` as soon as
>   `components/agent` requires them statically — the same thing that happened
>   to `acp-client`, and it is documented in `projects/agent-tui-app/deps.edn`.
>
> **As-built (Phase 2, verified against code):** `components/a2a-client` exists —
> `interface.clj` plus `core/{auth,transport,discovery,client,registry,events}.clj`,
> with five test namespaces (44 tests / 246 assertions green, `bb poly check` OK).
> Thirteen of those run against a **real in-process HTTP server on an ephemeral
> loopback port** (`e2e_loopback_test.clj`), covering the SSE reader thread,
> `stop!`, EOF, HTTP error mapping, auth headers and task polling — the parts
> that only fail over a socket. The stub uses `com.sun.net.httpserver`, the same
> listener Phase 5 will build on, so it doubles as an early proving ground for
> that decision. Three findings changed the design; see the corrected §5.2 event
> table, and the resolved/added entries in §11.
>
> **As-built (Phase 1, verified against code):** `components/a2a` exists —
> `interface.clj` plus `core/{methods,schema,errors,card}.clj`, with
> `test/.../{schema,card,errors}_test.clj` (29 tests / 259 assertions green;
> `bb poly check` OK). Three things differ from the original Phase-1 sketch:
>
> - A fourth namespace, **`core/methods.clj`**, was split out for the wire
>   constants (method names, `PROTOCOL_VERSION`, the well-known path, the
>   enumerations). Folding these into `schema.clj` would have made the
>   protocol version — the one literal that must not be duplicated — hard to
>   find.
> - The **TaskState and Part shapes in §2/§5.5 were corrected** after checking
>   the v0.3.0 spec directly: the wire uses lowercase-kebab state strings and a
>   `kind` (not `type`) Part discriminator. The protobuf enum names that appear
>   in much of A2A's documentation do not travel on the JSON-RPC wire, and the
>   schema tests now assert that they are *rejected*.
> - **`->id-str` was added to `core/card.clj`** after a test caught
>   `(str :explore-agent)` yielding `":explore-agent"`. Brainyard tool-def ids
>   are keywords, so without it every generated skill id and every call-chain
>   token would have carried a leading colon.
>
> Decisions confirmed with the user before this doc was written:
>
> - **Direction: both halves, client first.** Design the symmetric mechanism;
>   implement the client (brainyard consumes remote A2A agents) in the early
>   phases and the server (brainyard is consumed) from Phase 5. This is
>   deliberately *not* the ACP shape — ACP shipped client-only and its
>   asymmetry is intrinsic to that protocol. A2A is symmetric by design and
>   half of its value is being reachable.
> - **Server listener: the JDK's `com.sun.net.httpserver`.** Zero new
>   dependencies, already inside the native image via the JDK, minimal
>   reflection surface. Enough for JSON-RPC POST plus SSE. Keeps
>   `by a2a serve` in the shipping binary rather than exiling it to a
>   JVM-only base.
> - **Remote peers ARE agent-registry entries**, not a parallel vocabulary.
>   One `agent-registry$ask` for local and remote alike.

## 1. Context

Brainyard already has a **complete in-process agent-to-agent mechanism**. It is
not a gap in the runtime — it is one of the more carefully fenced parts of it:

- `agent-registry$list / $detail / $ask / $close`
  (`components/agent/src/.../common/commands.clj:97-283`) — dispatch, inspect,
  follow-up-ask and reclaim live instances.
- A **reach policy** (`authorize-ask`, `commands.clj:191-226`): a root may ask a
  sibling root or a subagent in its own session; a subagent may ask only
  instances it directly dispatched — never upward, because a subagent asking
  its own root is how you build an infinite loop.
- **Depth and cycle guards** — `proto/*call-depth*` and `proto/*call-chain*`
  (`core/protocol.clj:55-74`), enforced in `agent-core/ask-agent`
  (`core/agent.clj:1191-1222`) against `:max-agent-call-depth`.
- A **per-session LRU cap** with eviction (`core/agent.clj:1263-1290`), a
  parent-close cascade, and a single kill-switch (`:agents/subagents`).

What it cannot do is cross a process boundary. Today the only ways out of the
process are:

| Mechanism | Shape | Limitation |
|---|---|---|
| **ACP** (`components/acp`, `components/acp-client`, `common/acp_agent.clj`) | JSON-RPC 2.0 over **stdio**, brainyard-as-client only | One-way. Stdio only, so same-host and subprocess-only. Modelled on *coding* agents (plans, file edits, permissions). Cannot be pointed at a remote service, and nothing can call in. |
| **MCP** (`agent/mcp/integration.clj`) | Server tools auto-registered as `:mcp$<server>$<tool>` | Carries **tools**, not agents. No task lifecycle, no delegation, no agent identity. |
| **`clj-http-native`** | `java.net.http` wrapper | **Client only.** The shipping binary has no HTTP server at all. (`bases/playground-server` has http-kit, but it is a separate, untracked base.) |

So: a `by` agent cannot talk to an agent in another process, on another host,
or built on another framework — and no external orchestrator (ADK, LangGraph,
Semantic Kernel, Bedrock AgentCore, …) can delegate *into* `by`.

The **Agent2Agent Protocol (A2A)** is the standard that closes exactly this
gap. Open-sourced by Google in 2025, donated to the **Linux Foundation**, now
at **v1.x** with a public RFC process, 150+ backing organizations, first-party
integration in Google Cloud / Azure / AWS, and production SDKs in Python,
JavaScript, Java, Go and .NET. Where ACP is "LSP for coding agents", A2A is
"HTTP for agent delegation".

The reason it is worth adopting here is not just that it is a standard. It is
that **its data model already matches the brainyard runtime**, almost
one-to-one — see §3. Most of this design is wiring, not new machinery.

## 2. A2A — protocol summary (the parts that matter for this design)

Source: <https://a2a-protocol.org/latest/specification/>. The normative artifact
is `spec/a2a.proto`; the JSON-RPC binding is what we implement.

- **Transport.** Three official bindings — **JSON-RPC 2.0**, gRPC, and
  HTTP+JSON/REST — all "functionally equivalent representations" of one
  canonical protobuf data model. We implement **JSON-RPC 2.0 over HTTP(S)**,
  with **SSE** (`text/event-stream`) for streaming. gRPC is explicitly out of
  scope: it would drag protobuf + netty into a native image for no
  interoperability we don't already get.
- **Discovery.** `GET /.well-known/agent-card.json` returns a public
  **AgentCard** with no authentication. An authenticated *extended* card is
  available at a separate endpoint when
  `capabilities.extendedAgentCard` is true.
- **Core objects.**
  - **AgentCard** — `id`, `provider`, `capabilities` (`streaming`,
    `pushNotifications`, `extendedAgentCard`), `skills[]`, `securitySchemes`,
    `interfaces[]` (protocol + URL per binding), `extensions[]`, `signature`.
  - **AgentSkill** — `id`, `name`, `description`, `inputSchema`,
    `outputSchema`. This is the unit a client actually invokes.
  - **Task** — `id` (server-generated), `contextId` (groups related
    tasks/messages), `status` (a **TaskStatus**: `state` + `message` +
    `timestamp`), `artifacts[]`, `history[]`, `metadata`.
  - **Message** — `messageId`, `contextId`, `taskId`, `role`
    (`ROLE_USER` | `ROLE_AGENT`), `parts[]`, `metadata`, `referenceTaskIds`,
    `extensions`.
  - **Part** — discriminated on **`kind`** (not `type` — that is ACP's
    spelling, and the two protocols differ here): `"text"` (with `text`),
    `"file"` (with a `file` object carrying `bytes`/`uri`/`mimeType`/`name`),
    or `"data"` (arbitrary JSON), plus `metadata`. The v1.0 protobuf models
    this as a `text | raw | url | data` one-of; the JSON binding is the
    three-way `kind` form above.
  - **Artifact** — `id`, `parts[]`, `metadata`, `createdAt`.
- **TaskState** — on the JSON-RPC wire these are lowercase-kebab strings:
  `"submitted"`, `"working"`, `"completed"`, `"failed"`, `"canceled"`,
  `"rejected"` (terminal), `"unknown"`, plus two *interrupted* states,
  `"input-required"` and `"auth-required"`.

  > **Two spellings of the same protocol — and which one travels depends on
  > the version.** A2A's normative artifact is a protobuf schema whose enum
  > constants are spelled `TASK_STATE_INPUT_REQUIRED`. **In v0.3** those names
  > do *not* travel on the JSON-RPC wire — a v0.3 server sends
  > `"input-required"` — and everything in this design is that v0.3 JSON wire
  > form. `components/a2a`'s schema tests assert the protobuf spelling is
  > *rejected*, which is correct for v0.3.
  >
  > **This does not hold for v1.0.** Its JSON-RPC binding is ProtoJSON, so a
  > v1.0 server sends exactly `TASK_STATE_COMPLETED` and `ROLE_USER` — the
  > spellings these tests reject. Those assertions therefore encode the right
  > rule for the dialect we implement and the wrong one for the dialect the
  > ecosystem now defaults to; they belong behind the dialect split in **§13**
  > rather than being deleted.
  >
  > Note also that A2A spells it **`"canceled"`** (US, one L) while
  > brainyard's task manager uses `:cancelled` (two Ls). The bridge lives in
  > exactly one place, `a2a/kw->state`, which accepts both.
- **Methods.** `SendMessage`, `SendStreamingMessage`, `GetTask`, `ListTasks`,
  `CancelTask`, `SubscribeToTask`, the four
  `*PushNotificationConfig` methods, and `GetExtendedAgentCard`.
- **Streaming.** A `StreamResponse` one-of carrying `task` | `message` |
  `statusUpdate` (**TaskStatusUpdateEvent**) | `artifactUpdate`
  (**TaskArtifactUpdateEvent**). Events are delivered in generation order;
  multiple clients may subscribe to the same task independently; closing one
  stream does not affect others.
- **Push notifications.** A webhook `url` + `token` +
  `AuthenticationInfo` per task; the server POSTs `StreamResponse` JSON with
  `Content-Type: application/a2a+json`.
- **Security schemes.** API key, HTTP basic/bearer, OAuth2 (authorizationCode /
  clientCredentials / deviceCode), OpenID Connect, mTLS. Servers **must** scope
  results per client and **must not** reveal the existence of resources the
  caller cannot access.
- **Versioning.** An `A2A-Version` service parameter (Major.Minor) accompanies
  every request; unknown versions get `VersionNotSupportedError`. Patch
  versions never appear in requests or Agent Cards.

### What A2A deliberately does *not* give us

Worth stating, because it bounds the design: A2A is explicitly built for
**opaque** agents. Peers do not share memory, tools, or internal state. There
is no way to hand a remote agent your sandbox, your L1/L2/L3 memory, or your
live artifacts — and no attempt should be made to simulate one. A remote peer
gets a `Message` and returns `Task`/`Message`; everything else stays local.
This is a feature: it is what makes the trust boundary tractable (§8).

## 3. The mapping — why this is mostly wiring

| A2A concept | Brainyard equivalent | Where |
|---|---|---|
| `AgentCard` | The agent roster | `common/agent_roster.clj` |
| `AgentSkill` | A `!tool-defs` entry of `:type :agent` | `core/tool.clj:110` (`!tool-defs`) |
| skill `inputSchema` / `outputSchema` | `deftool` `:input-schema` / `:output-schema` (malli) | converted by `core/tool.clj/schema->type` → `clj-llm/malli->json-schema` |
| `Task` + `TaskState` | `agent/task/protocol.clj` `Task` record + `:pending→:running→:completed\|:failed\|:cancelled` | `task/{protocol,manager,executor}.clj` |
| `returnImmediately` + `GetTask` polling | The task manager's **detach handler** (`{:status :detached :on-poll … :on-cancel …}`) | `task/protocol.clj:38-52` |
| `Artifact` | Live artifacts | `common/artifacts.clj/add-artifact!` |
| `Message` / `Part` | Ask input and answer | `core/agent.clj/ask` |
| `contextId` | Agent-session id | `core/session.clj` |
| `TaskStatusUpdateEvent`, `TaskArtifactUpdateEvent` | The hooks bus | `core/hooks.clj` catalog |
| `INPUT_REQUIRED` | Ask-channel HITL | `components/ask-channel` |
| `AUTH_REQUIRED` | Permission/auth bridge | `common/auth.clj` |
| Push notification config | Event bus + reactor | `common/{events,reactor}.clj` |
| Agent-to-agent delegation | `agent-registry$ask` | `common/commands.clj:228` |

The last row is the whole point of §5.

## 4. The one genuinely new invariant: the call chain must survive a network hop

Everything else in this design is adaptation. This part is new, and it is the
thing most likely to be got wrong.

In-process, cycle detection is a **dynamic binding**: `proto/*call-chain*`, a
vector of agent-ids, conj'd on each hop and checked before dispatch
(`core/agent.clj:1216-1222`). A dynamic binding does not cross a socket. Point
two brainyard instances at each other and `A → B → A → B …` recurses until
something times out — burning tokens the whole way, because each hop is a real
LLM turn.

**Therefore: every outbound A2A request carries the chain in
`Message.metadata`, and every inbound request checks it.** Implemented in
`components/a2a/core/chain.clj`.

```clojure
;; Message.metadata on the wire (string keys — it crosses JSON)
{"ai.brainyard/call-chain" ["by-node:5f2c…" "by-node:9a11…"]
 "ai.brainyard/call-depth" 2
 "ai.brainyard/context-id" "agt-0193…"}
```

> **Two corrections made while implementing Phase 3.** The original version of
> this section specified both of these the other way round. Each wrong version
> *looked* correct and would have shipped a guard that silently never fired —
> the worst outcome for a safety check, because nothing appears broken.
>
> **1. Entries are NODE ids, not agent or skill ids.** A brainyard instance has
> two unrelated names for itself: locally it is `router-agent/lime-mole-8966`, to
> a remote peer it is `https://a.example/a2a#main`. The draft above put the
> URL-scoped skill id in the chain — so `A → B → A` would have compared
> `"router-agent/lime-mole-8966"` against `"https://a.example/a2a#main"`, never
> matched, and never fired. Each node now stamps ONE stable identity
> (`a2a/node-id`, a per-process UUID) used both when calling out and when
> checking inbound. It is URL-independent, so it survives proxies, multiple
> interfaces, and a peer reachable at more than one address.
>
> **1a. That id MUST be minted at runtime, not in a `defonce` initializer.**
> Under GraalVM native-image a `defonce` runs at BUILD time, so the UUID was
> computed once during compilation and interned into the image heap as a
> constant: **every process launched from a given binary reported the same node
> id**. That made brainyard-to-brainyard A2A impossible rather than merely
> mis-detected — the caller stamps its own id into the chain, the callee finds
> it already present, and refuses with `-32004 call cycle refused` on the very
> first hop, on loopback and across hosts alike. Only a non-brainyard peer,
> which sends no chain, could get through. The id is now minted lazily on first
> use, with the `or` inside the `swap!` keeping it idempotent under CAS retry.
>
> No unit test can catch a regression here: the defect is introduced by the
> compiler, not the code, so `chain_test` passes either way. The guard lives in
> the build instead — a section of `bin/smoke-native.sh` greps the image for
> `by-node` followed by a full UUID (never the bare prefix, which is a source
> literal legitimately present in a correct binary).
>
> **2. The CALLER appends itself, not the callee.** Appending the callee (as
> the draft said) puts the receiver's own id in the chain it receives, so a
> membership check would refuse the very first hop.

Rules, as built:

1. **Outbound** (`remote-agent/outbound-metadata`): append THIS node's id to
   the chain we are servicing, and take the depth from `proto/*call-depth*` so
   local and remote hops share one budget — otherwise a chain could launder
   depth by alternating local and remote.
2. **Inbound** (server handler, Phase 5): `a2a/check-chain` **before doing any
   work**, refusing when this node's id is already in the chain (`:reason
   :cycle`) or `call-depth ≥ :max-agent-call-depth` (`:reason :depth`). Then
   bind the received chain for the duration of the ask, so any further hop
   inherits it. Do **not** append ourselves on receipt — the next outbound
   stamp does that, and doing both would make a two-hop chain look like four.
3. **Absent metadata** (a non-brainyard client — the common case) is not an
   error: `chain []`, `depth 0`. The guard degrades to "this server allows
   `:max-agent-call-depth` hops of its own", which is right for a stranger.
4. The metadata keys are **namespaced** (`ai.brainyard/…`) because A2A
   `metadata` is a free-for-all shared with every other extension. They are
   also read under **both** their string and keyword forms: `a2a/decode`
   keywordizes with `:key-fn keyword`, so a handler sees
   `:ai.brainyard/call-chain`, and writing strings while reading only strings
   would silently never match.

The invariant is:

```
chain = every node ALREADY on the stack, most recent last
cycle = my node id is in that list

A stamps  [nodeA]          → B: nodeB not in chain  → OK
B stamps  [nodeA nodeB]    → A: nodeA IS in chain   → REFUSED
```

**Granularity is deliberately coarse.** Cycles are per NODE, so
`A#planner → B → A#reviewer` is refused even though the skills differ. The
recursion it prevents costs far more than the callback pattern it forbids, and
node level is the only level at which the identity is unambiguous. Depth
remains the finer control.

**Two guards, neither subsuming the other.** The near side
(`remote/cycle-target?`) compares remote-skill tokens against the local
`*call-chain*`, catching an agent that recursively dispatches the same remote
skill inside this process. The wire side (`a2a/check-chain`) works on node ids
and catches recursion that leaves and comes back. The two vocabularies are kept
strictly separate — mixing them is exactly what broke the first draft.

**This is a cooperation protocol, not a security control.** A remote caller
controls its own metadata and can understate depth or forge a chain. It stops
accidental recursion between well-behaved peers, which is the real failure
mode; it does not stop a hostile one. That job belongs to authentication, the
skill allow-list, and the OS sandbox (§8).

**Loopback is the test, not the accident.** Pointing a `by` instance at its own
served card and confirming the second hop is *rejected* is the E2E proof (§9).
Until Phase 5 lands, `chain_test.clj` proves it by simulating the hops
directly — which is how both bugs above were caught, since every unit-level
assertion passed while `A → B → A` went undetected.

## 5. Recommended architecture

```
components/a2a           protocol core: malli schemas, error map, AgentCard, version negotiation
components/a2a-client    HTTP + SSE transport, discovery, peer registry, auth, event translation
components/a2a-server    JDK HttpServer, JSON-RPC dispatch, SSE writer, card generation
components/agent         RemoteAgent record, a2a$* commands, :a2a task executor, config, feature gate
projects/agent-tui-app   `by a2a serve` subcommand
```

Three components rather than two (the ACP split) because the server half has a
genuinely different dependency profile — it is the only thing that opens a
socket — and keeping it separate means a client-only deployment never links it.

### 5.1 `components/a2a` — pure protocol, no agent semantics

Mirrors `components/acp`'s role exactly: data and codecs, no I/O, no agent
concepts. `deps.edn` matches `components/acp`'s
(`data.json`, `malli`, `util`, `mulog`) plus `ai.brainyard/acp`.

- `core/schema.clj` — malli schemas for AgentCard, AgentSkill,
  AgentCapabilities, AgentInterface, SecurityScheme, Task, TaskStatus,
  TaskState, Message, Part, Artifact, TaskStatusUpdateEvent,
  TaskArtifactUpdateEvent, StreamResponse, PushNotificationConfig,
  SendMessageConfiguration. Malli (not spec) because that is what `deftool`
  schemas already are, so skill conversion is a schema-to-schema hop.
- `core/errors.clj` — the A2A error catalog (`TaskNotFoundError`,
  `TaskNotCancelableError`, `PushNotificationNotSupportedError`,
  `UnsupportedOperationError`, `ContentTypeNotSupportedError`,
  `InvalidAgentResponseError`, `ExtendedAgentCardNotConfiguredError`,
  `ExtensionSupportRequiredError`, `VersionNotSupportedError`) ↔ brainyard's
  `{:error "…"}` convention, in both directions.
- `core/card.clj` — build / parse / validate an AgentCard; `A2A-Version`
  negotiation. **The protocol version literal lives here and nowhere else** —
  A2A is on a public RFC cadence and version strings scattered across a
  codebase are how you end up shipping two of them.

**JSON-RPC is reused, not rewritten.** `ai.brainyard.acp.interface` already
exports a generic, native-image-proven JSON-RPC 2.0 codec — `request`,
`notification`, `response`, `error-response`, `encode`, `decode`, `classify`,
`request?`/`response?`/`notification?`/`error?`
(`components/acp/src/.../core/jsonrpc.clj`). `components/a2a` depends on it
rather than duplicating ~100 lines.

> **Note on the dependency direction.** `a2a → acp` reads oddly: these are
> sibling protocols, and neither is a layer of the other. The coupling is
> narrow (the codec only) and deliberate — duplicating a codec is worse than an
> awkward arrow. If it starts to grate, extract
> `components/clj-jsonrpc` and have both depend on *that*. It is a rename, not
> a rewrite, and it is explicitly deferred rather than forgotten.

### 5.2 `components/a2a-client` — brainyard as caller

`deps.edn`: `ai.brainyard/{a2a, clj-http-native, clj-oauth, util, mulog}`.

- `core/transport.clj` — HTTP POST + SSE. `clj-http-native` already supports
  `:as :reader`, which is what an SSE loop needs; the streaming path reads
  `data:` lines off the reader rather than buffering the body.
- `core/discovery.clj` — fetch and cache `/.well-known/agent-card.json`;
  fall back to the authenticated extended card when the public one advertises
  it and we hold credentials.
- `core/client.clj` — the RPC surface: `send-message!`, `stream-message!`,
  `get-task`, `list-tasks`, `cancel-task!`, `subscribe-task!`, and the
  push-notification config methods.
- `core/registry.clj` — named peers, mirroring
  `acp-client/core/registry.clj`: a peer name resolves to
  `{:url … :auth … :timeout-ms …}`, with `register-peer!` for runtime addition.
- `core/auth.clj` — bearer / API-key for v1; OAuth2 via `clj-oauth` in Phase 7.
- `core/events.clj` — **pure translation**, A2A stream events → brainyard hook
  *descriptors*.

> **`core/events.clj` must not depend on `components/agent`.** This is not a
> style preference; it is the shape `acp-client/core/events.clj` already
> enforces, and its ns docstring explains why: translating *to* hook keywords
> is fine, but calling `agent.core.hooks/fire!` would create an
> `acp-client → agent` dependency. Instead it returns `{:event ::kw :data {…}}`
> descriptors and the *dispatcher* hands them to a caller-supplied `:on-event`
> callback; the defagent supplies a callback that fires real hooks. `a2a-client`
> copies this exactly, including duplicating the hook keywords as `^:const`
> defs with an integration test guarding against catalog drift.

Event mapping (the contract):

Event mapping (the contract, as built):

| A2A stream frame | Descriptor emitted |
|---|---|
| `status-update` carrying message text | `:agent.dspy-action/chunk` (a real brainyard hook) |
| `status-update` state change | `:a2a/task-state` |
| terminal state (`completed`/`failed`/`canceled`/`rejected`) | `:a2a/task-terminal` |
| `input-required` | `:a2a/input-required` → ask-channel HITL |
| `auth-required` | `:a2a/auth-required` → `common/auth.clj` |
| `artifact-update` | `:a2a/artifact` → `artifacts/add-artifact!` |

> **Correction to an earlier draft of this table.** It previously mapped a
> "`TaskStatusUpdateEvent` carrying a tool-call `Part`" to
> `:agent.tool-use/pre|post`. **A2A has no tool-call vocabulary** — that is
> ACP's, and the two are easy to conflate. A2A's entire streaming vocabulary is
> `status-update` and `artifact-update`: no tool calls, no plan/todo frames, no
> reasoning channel. Synthesizing `:agent.tool-use/*` or `:todo/updated` from
> A2A traffic would put **fabricated tool activity in the user's transcript**,
> so we deliberately do not, and a test asserts it. Remote agents are opaque by
> design (§2); the transcript should reflect that rather than invent detail.

Only the text-chunk row is an existing brainyard hook — it is what makes streamed
output render in the TUI with no new code. The rest are namespaced `:a2a/*`
descriptors because they describe things only a *remote* agent can report, and
the agent layer dispatches on them explicitly rather than pattern-matching a
payload shape. **No `bases/agent-tui` changes.**

Two details the implementation had to get right, both covered by tests:

- **Chunk deltas are diffed, not forwarded.** A2A status messages are not
  guaranteed to be incremental — some servers resend the full text each time.
  Emitting each one raw would duplicate the answer in the transcript, so the
  translator diffs against the accumulated text and emits only the new suffix.
- **Accumulation is threaded, not hidden in an atom.** `translate` takes and
  returns an accumulator, which is what keeps the namespace pure and
  independently testable.

### 5.3 Remote peers ARE agent-registry entries

This is the design's central bet, and the codebase makes it cheap.

`core/agent.clj/ask` (line 716) is **not** hardcoded to the behaviour tree. It
validates `:!state` / `:!session`, resets runtime, does lifecycle bookkeeping
(`mark-ask-start!`, turn counters, session messages), fires `:agent.ask/pre`,
and then calls the **polymorphic** `proto/process`. The BT lives entirely
behind that protocol method.

So a **`RemoteAgent` defrecord** (`agent/core/remote_agent.clj`) that carries
those two atoms and implements `IAgent` / `IAgentLifecycle` / `IAgentState` /
`IAgentBTIntegration` flows through `ask`, `ask-agent`, `agent-registry$ask`,
`authorize-ask`'s reach policy, the depth guard, LRU eviction and the
parent-close cascade **without modifying any of them**. Its `process` issues an
A2A `message/send` instead of ticking a tree.

Requirements the existing call sites impose on the record:

- **`:!state`** atom shaped `{:status :idle :lifecycle {…} :st-memory-init nil}`.
  `instance-summary` (`commands.clj:77-95`) reads `:status`, `lifecycle`,
  `instance-idle-ms`, and calls `proto/get-st-memory-init` /
  `proto/get-bt-st-memory` — both call sites are `some->`-guarded, so returning
  `nil` is safe and correct (a remote peer has no local short-term memory, and
  pretending otherwise would be a lie in `agent-registry$detail`).
- **`:!session`** shared with the creating agent, so `session/inc-total-turns!`
  and the message log behave as for any subagent.
- **`lifecycle`** with `:owner` set — **a remote peer is always a subagent,
  never a root**. A session has exactly one root and it is local, by
  definition. `:share-parent-session?` is `false`: a remote peer is a
  dispatched worker, not a second model in the user's own session (the ACP
  case).
- `defrecord` ⇒ a `reflect-config.json` entry for native-image.

`agent-registry$list` / `$detail` gain a **`:kind :local | :remote`** field so
the LLM can tell the difference — the reach policy is identical, but the
failure modes are not (a remote peer can be *unreachable*, which no local
instance can be).

### 5.4 Skills become callable tool-defs

On `a2a$connect`, each skill on the peer's card is registered into
`tool/!tool-defs` as `:a2a$<peer>$<skill>` with `:type :agent` — the exact
shape `user-agents/register-agent!` (`common/user_agents.clj:141-186`) and
`mcp/integration` already use, **including the
`user-tools/bind-into-live-sandbox!` call** so the peer is callable from a
clojure code block in the *same* turn it was connected, not the next one.
`a2a$disconnect` unwinds the registration.

Commands (`common/a2a.clj`): `a2a$connect`, `a2a$list`, `a2a$card`,
`a2a$disconnect`. Deliberately small — connection management only. The *ask*
path is `agent-registry$ask`, per §5.3, and `a2a$*` must not grow a second one.

#### 5.4a Peer CRUD without a turn — the `:a2a` ask-channel op

Those four are tool-defs, so **every route to them runs through the model**. An
external driver that already holds a peer name and URL was therefore paying an
LLM to retype them into a tool call, and hoping the retyping was faithful.

`peers-op` is the machine-facing face of the same commands — same `:enable-a2a`
gate, same name regex, same per-session peer cap, same registry, no turn.
`bases/agent-tui` exposes it as `{:op :a2a :action …}` on the session ask socket
and advertises it in the session `:ops` metadata alongside the other
non-blocking ops (see [`../session-channel.md`](../session-channel.md)).

Two asymmetries are deliberate, because this is an **API** rather than a person
at a keyboard:

- `:add` refuses a name that already exists and `:update` refuses one that does
  not. A caller that believes it is creating a peer while actually replacing a
  colleague's has no way to notice; a human driving `a2a$connect` would.
- `:update` is **disconnect-then-connect**, so a changed URL cannot leave the
  previous endpoint's skills registered under the same peer name.

The peer registry is a process-wide `defonce`, so in a shared host one connect
is visible to every co-hosted session; `:list` reports `:host-wide? true`
rather than letting a caller assume otherwise. Redaction is tested on this door
too — a redaction that only holds on one door is not a redaction.

### 5.5 Long-running remote work rides the existing task manager

A2A's `returnImmediately: true` + `tasks/get` polling is the same shape as the
task manager's detach handler, so an `A2ATaskJobExecutor` (`:job-type :a2a`) in
`agent/task/executor.clj` returns:

```clojure
{:status    :detached
 :on-poll   (fn [] ;; tasks/get → tp/still-running | {:result …} | {:error …}
              …)
 :on-cancel (fn [] ;; tasks/cancel
              …)}
```

That buys `task$wait`, `task$cancel`, `task$detail`, the iteration hold, and
the TUI task block with no new UI code.

`TaskState` mapping:

| A2A `TaskState` (wire) | Brainyard task status | Note |
|---|---|---|
| `"submitted"` | `:pending` | |
| `"working"` | `:running` | |
| `"completed"` | `:completed` | |
| `"failed"` | `:failed` | |
| `"rejected"` | `:failed` | the refusal text goes in `:result`, not swallowed |
| `"canceled"` | `:cancelled` | **one L on the wire, two locally** — bridged in `a2a/kw->state` |
| `"unknown"` | `:running` | a peer on a newer minor version; keep polling, don't guess |
| `"input-required"` | stays `:running` | **interrupted, not finished** — route to ask-channel |
| `"auth-required"` | stays `:running` | **interrupted, not finished** — route to `common/auth.clj` |

The two interrupted states are the easy thing to get wrong: they are *not*
terminal, and mapping either to `:failed` would abandon a task the remote peer
is still holding open for us.

### 5.6 `components/a2a-server` — brainyard as callee

`com.sun.net.httpserver.HttpServer`. Routes:

```
GET  /.well-known/agent-card.json     public AgentCard (no auth)
POST /a2a                             JSON-RPC: message/send, message/stream,
                                      tasks/get, tasks/list, tasks/cancel,
                                      tasks/resubscribe,
                                      tasks/pushNotificationConfig/*
GET  /a2a/tasks/{id}/subscribe        SSE (text/event-stream)
GET  /a2a/agent-card                  authenticated extended card
```

- **Card generation from the live roster.** Every `!tool-defs` entry of
  `:type :agent` whose id appears in `:a2a-expose-skills` becomes an
  `AgentSkill`; `:input-schema` / `:output-schema` convert via
  `core/tool.clj/schema->type`. The allow-list is **explicit and empty by
  default** — see §8.
- **`message/send`** → resolve-or-create an agent-session keyed by `contextId`,
  then `agent-core/ask-agent`. With `returnImmediately: true`, create a task and
  return `SUBMITTED` immediately.

  **Reuse is bounded** (`common/a2a_serve.clj`, the context registry). The
  instance behind a `contextId` is kept warm so a follow-up continues the same
  conversation — for a subprocess-backed skill (an exposed `acp-agent` is one
  Claude Code session per context) that is also an expensive backend not worth
  rebuilding per turn. But a remote caller invents `contextId`s freely, so
  keying live state on them needs a ceiling:

  | Bound | Behaviour |
  |---|---|
  | `:a2a-max-contexts` (8) | Past the cap, the least-recently-used **idle** context is evicted and its instance closed. **0 disables reuse** — every turn dispatches and reclaims, the behaviour that shipped first. |
  | `:a2a-context-ttl-ms` (30 min) | Contexts idle longer are swept on the next turn. Idle is measured from the END of a turn, so a long turn never expires under itself. |
  | Failed turn | The context is dropped and its instance closed, so a wedged backend cannot poison every later turn on that id. |
  | Concurrent turn | **Refused**, not queued — the stance `agent-registry$ask` already takes on a `:running` instance. Queueing would let a caller pin instances against the cap by holding turns open. |
  | Skill change | Re-addressing one `contextId` to a different skill retires the old instance rather than handing agent B agent A's history. |

  LRU order comes off a monotonic counter, not the wall clock: `currentTimeMillis`
  is coarse enough that several turns share a millisecond, and tied keys make the
  victim whichever way an unordered map happened to seq.
- **`message/stream`** → subscribe to the hooks bus for that session and write
  SSE frames.
- **Inbound call-chain enforcement** per §4, before any work is done.

### 5.7 `a2a-agent` — the conversational front door

A thin `coact/run-coact-derived` `defagent` (`common/a2a_agent.clj`) over the
`a2a$*` family with **zero new commands** — the same minimal-diff pattern as
`config-agent` / `mcp-agent` / `event-agent` / `schedule-agent`, including:

- reading gates (`:enable-a2a`) via `agent-runtime$config` but **never writing
  them** — a gate change is a config write, handed to `config-agent` by name;
- the hard **`FINAL-STEP CHECKLIST` dossier contract** ("a write that ends
  without a dossier is an INCOMPLETE turn"), writing to
  `.brainyard/agents/a2a-agent/dossiers/<ts>-<slug>.md` + `INDEX.md`;
- registration in `agent/interface.clj`'s side-effecting require list and
  wiring into `common/router_agent.clj`'s router **in three places** (directory,
  lettered decision table, summary list).

Boundary against the neighbours: `mcp-agent` owns external *tools*; `acp-agent`
owns external *coding agents over stdio*; `a2a-agent` owns external *peer
agents over HTTP*, in both directions (it is the one that can also talk about
`by a2a serve`).

## 6. Configuration

New keys in `core/config.clj`'s `config-schema` (same entry shape as the
`:acp-*` block at lines 454-470, `:env-fn` where an env var applies):

```
:enable-a2a                 boolean  false          BY_ENABLE_A2A
:a2a-peers                  object   {}             peer-name → {:url :auth :timeout-ms}
:a2a-timeout-ms             integer  600000         BY_A2A_TIMEOUT_MS
:a2a-stream?                boolean  true
:a2a-max-peers-per-session  integer  8
:a2a-serve-host             string   "127.0.0.1"    BY_A2A_SERVE_HOST
:a2a-serve-port             integer  41241          BY_A2A_SERVE_PORT
:a2a-serve-token            string   nil            BY_A2A_SERVE_TOKEN   (required to serve)
:a2a-expose-skills          array    []             BY_A2A_EXPOSE_SKILLS
:a2a-max-contexts           integer  8              BY_A2A_MAX_CONTEXTS   (0 = no reuse)
:a2a-context-ttl-ms         integer  1800000        BY_A2A_CONTEXT_TTL_MS
```

New feature family in `core/feature.clj`, mirroring `:agents/acp` (line 366):

```clojure
:agents/a2a
{:title     "A2A peers"
 :family    :agents
 :gate      :enable-a2a
 :keys      [:a2a-peers :a2a-timeout-ms :a2a-stream? :a2a-max-peers-per-session
             :a2a-serve-host :a2a-serve-port :a2a-serve-token :a2a-expose-skills]
 :requires  #{:agents/subagents}
 :lifecycle :session
 :doc       "Remote agents reached over the Agent2Agent protocol, and the local A2A server."}
```

`:requires #{:agents/subagents}` matters: turning off subagent calls must also
stop remote traffic, since a remote peer *is* a subagent. `feature/off-reason`
gets checked in the same places `authorize-ask` already checks
`:agents/subagents` (`commands.clj:177, 205`), so there is **one** kill-switch.

## 7. Files created and touched

### Created

```
docs/design/a2a-design.md                                    (this file)
components/a2a/deps.edn                                      [Phase 1 — done]
components/a2a/src/ai/brainyard/a2a/interface.clj            [Phase 1 — done]
components/a2a/src/ai/brainyard/a2a/core/{methods,schema,errors,card}.clj  [Phase 1 — done]
components/a2a/test/ai/brainyard/a2a/{schema,card,errors}_test.clj         [Phase 1 — done]
components/a2a-client/deps.edn                               [Phase 2 — done]
components/a2a-client/src/ai/brainyard/a2a_client/interface.clj              [Phase 2 — done]
components/a2a-client/src/ai/brainyard/a2a_client/core/{auth,transport,discovery,client,registry,events}.clj [Phase 2 — done]
components/a2a-client/test/ai/brainyard/a2a_client/{auth,sse,events_translation,registry,e2e_loopback}_test.clj [Phase 2 — done]
components/a2a-server/deps.edn
components/a2a-server/src/ai/brainyard/a2a_server/interface.clj
components/a2a-server/src/ai/brainyard/a2a_server/core/{http,handlers,sse,card}.clj
components/a2a-server/test/ai/brainyard/a2a_server/{handlers,card}_test.clj
components/agent/src/ai/brainyard/agent/core/remote_agent.clj
components/agent/src/ai/brainyard/agent/common/{a2a,a2a_agent}.clj
components/agent/test/ai/brainyard/agent/{a2a_registry,a2a_task,a2a_agent}_test.clj
```

### Modified (small, surgical)

| File | Change |
|---|---|
| `components/agent/deps.edn` | **static** deps on `a2a`, `a2a-client`, `a2a-server` |
| `components/agent/src/.../interface.clj` | side-effecting require of `common.a2a` + `common.a2a-agent`; export the new fns |
| `components/agent/src/.../core/config.clj` | the `:a2a-*` config block (§6) |
| `components/agent/src/.../core/feature.clj` | the `:agents/a2a` family (§6) |
| `components/agent/src/.../common/commands.clj` | `:kind :local\|:remote` in `instance-summary` + `agent-registry$detail` |
| `components/agent/src/.../task/executor.clj` | `A2ATaskJobExecutor` |
| `components/agent/src/.../common/router_agent.clj` | router wiring, three places |
| `projects/agent-tui-app/src/.../main.clj` | `"a2a"` in `known-subcommands` (line 2672) + a cli-matic subcommand beside `events` (~line 2653) |
| `projects/agent-tui-app/resources/.../reflect-config.json` | `RemoteAgent` + any new defrecords |
| `workspace.edn` | `"a2a" "a2a-client" "a2a-server"` in `agent-tui-app`'s `:necessary`, if Polylith can't infer them |
| `CLAUDE.md` | a design-decisions entry pointing here |

### Untouched (deliberately)

- **`bases/agent-tui`** — the whole point of translating to existing hook
  events is that the TUI needs no changes.
- **`components/acp`, `components/acp-client`, `common/acp_agent.clj`** — A2A is
  strictly additive. ACP is not deprecated; it does a different job (local
  coding agents over stdio) and does it well.
- **`components/clj-llm`** — no `:a2a` LLM provider. A2A peers are *agents*, not
  completion endpoints; the ACP design added an `:acp` provider because ACP
  backends genuinely double as models, and A2A's do not.

## 8. Security posture

**Inbound A2A is remote code execution against the local workspace, by design.**
A remote caller sends a prompt; a local agent runs it with tools, a sandbox,
and disk access. This is exactly what it is for, and the containment has to be
explicit rather than implied.

- **Off by default.** `:enable-a2a` is `false`. Nothing listens, nothing dials.
- **Loopback by default.** `:a2a-serve-host` is `127.0.0.1`. Exposing the
  server beyond the host is a deliberate act.
- **Token required to serve.** No `:a2a-serve-token`, no listener — the server
  refuses to start rather than binding unauthenticated.
- **Empty skill allow-list.** `:a2a-expose-skills` defaults to `[]`. A skill is
  reachable only when named. There is no deny-list mode; an allow-list that
  defaults to "everything except…" is how an internal agent leaks.
- **One kill-switch.** `:agents/a2a` `:requires #{:agents/subagents}`, so the
  existing subagent kill-switch stops remote traffic too.
- **The OS sandbox is the real boundary.** `--sandbox` (seatbelt,
  `docs/sandboxing.md`) and `:sandbox-interop` contain what a served agent can
  actually do. Anyone exposing `by a2a serve` beyond loopback should be running
  it sandboxed, and the docs should say so.
- **No resource-existence leaks.** Per the spec, unauthorized and missing
  resources must return the *same* error. `core/errors.clj` enforces this at
  the mapping layer rather than leaving it to each handler.

## 9. Implementation phases

Each phase is independently mergeable and testable.

| Phase | Deliverable |
|---|---|
| **0** | This document. **Done.** |
| **1** | `components/a2a` — method constants, schemas, errors, card, version negotiation. Pure data. **Done** — 29 tests / 259 assertions green, `bb poly check` OK. |
| **2** | `components/a2a-client` — transport, discovery, client, peer registry, auth, event translation. **Done** — 44 tests / 246 assertions green, incl. 13 against a real loopback HTTP/SSE server. |
| **3** | `RemoteAgent` + `common/a2a.clj` + registry unification + `:kind` field + config/feature keys + **outbound** call-chain metadata. **Done** — 17 agent tests / 79 assertions; `components/a2a` 44 / 344; `bb poly check` OK. |
| **4** | `A2ATaskJobExecutor` + streaming ask + `:a2a/artifact` hook bridge + interrupted-state handling. **Done** — 16 tests / 58 assertions over real HTTP+SSE. |
| **5** | `components/a2a-server` + `by a2a serve` + **inbound** call-chain enforcement. **Done** — 18 server tests / 68 assertions + 9 loopback E2E / 43 assertions. |
| **6** | `a2a-agent` front door + router wiring + dossier contract. **Done** — 8 tests / 59 assertions; siblings still green. |
| **7** | reflect-config; `bb check:ata`; full `bb test`; `bb build:ata` + native A2A verification. **Done except OAuth2 + push notifications — see §12.** |

### Smallest viable first PR

Phases 0 + 1. A new component with no dependents, no behaviour change, and a
test suite — reviewable in one sitting, and it forces the schema decisions
(which is where the real design risk lives) before any wiring depends on them.

## 10. Verification

Per-phase, narrow (a full `bb test` is ~3 minutes; run it at the end, not after
each change):

```bash
bb repl:test ai.brainyard.a2a.schema-test              # Phase 1
bb repl:test ai.brainyard.a2a-client.client-test       # Phase 2
bb repl:test ai.brainyard.agent.a2a-registry-test      # Phase 3
bb repl:test ai.brainyard.agent.a2a-task-test          # Phase 4
bb repl:test ai.brainyard.a2a-server.handlers-test     # Phase 5
```

**Loopback E2E — the real proof, available from Phase 5.** A brainyard instance
pointed at its own served card is a free adversarial peer:

```bash
# terminal 1 — serve
BY_ENABLE_A2A=1 BY_A2A_SERVE_TOKEN=test BY_A2A_EXPOSE_SKILLS=explore-agent \
  projects/agent-tui-app/target/by a2a serve --port 41241

# terminal 2 — discover
curl -s http://127.0.0.1:41241/.well-known/agent-card.json | jq .
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:41241/a2a          # → 401
curl -s -o /dev/null -w '%{http_code}\n' -H 'Authorization: Bearer test' \
     -X POST http://127.0.0.1:41241/a2a                                      # → 200

# terminal 2 — call, then prove the cycle guard
BY_ENABLE_A2A=1 projects/agent-tui-app/target/by run
  #   (a2a$connect {:name "self" :url "http://127.0.0.1:41241" :token "test"})
  #   (a2a$card {:name "self"})                → skills[] contains explore-agent
  #   (agent-registry$list)                    → peer present with :kind :remote
  #   (agent-registry$ask {:id "…" :question "…"})
  #   → and a peer asking BACK must be REJECTED by the §4 cycle guard,
  #     not merely rate-limited or timed out.
```

JVM-mode parity check (catches reflection-config gaps — the `RemoteAgent`
defrecord is the likely offender):

```bash
BY_JAR=1 projects/agent-tui-app/target/by a2a serve --port 41241
```

Structural + hermetic pass-through suites under `components/agent/test/`,
matching the `schedule` / `event` / `state-machine` agent suites, for Phase 6.
Full `bb test` and `bb build:ata` at the end of Phase 7.

## 11. Known risks

- **Cross-process recursion** — §4. Mitigated by metadata call-chain
  propagation (outbound Phase 3, inbound Phase 5) and proven by loopback.
- **`requiring-resolve` under native-image** — the ACP integration shipped
  *broken in every released binary* for exactly this reason, and the story is
  worth re-reading before writing a line of Phase 3
  (`common/acp_agent.clj:17-38`): AOT only follows a static `:require`, so a
  namespace reached solely by `requiring-resolve` has no `.class` in the image
  and the resolve can never succeed. All verification had run on the JVM, where
  runtime source loading works, so it looked fine. **`components/agent` must
  `:require` `a2a-client` statically.**
- **Native-image, generally** — new `defrecord`s need `reflect-config` entries;
  avoid eager `(def x alias/x)` value-copies (they can freeze as unbound fns —
  use `#'alias/x` or thin `defn-` wrappers, as `acp_agent.clj:70-80`
  documents); `Thread/sleep` needs a `long`. `com.sun.net.httpserver` was
  chosen partly because the JDK already puts it in the image.
- ~~**Spec churn**~~ — **happened, and is now resolved.** v1.0 replaced the
  binding wholesale (ProtoJSON: different method names, enum spellings, `Part`
  shape, result envelope). Single-sourcing the version literal in
  `core/card.clj` was necessary but nowhere near sufficient — the risk was
  never a scattered string, it was a *dialect*. Both are now spoken (§13), and
  `bb a2a:interop` is what will tell us when it happens again.
- **OAuth2 client auth is NOT implemented** (see §12). Bearer, HTTP basic and
  API key are. A peer requiring OAuth2 cannot currently be reached — the one
  remaining gap.
- ~~**Streaming backpressure**~~ — **resolved in Phase 2, differently than
  planned.** The original mitigation was "bound the event queue and drop with a
  warning". That was wrong: dropping loses protocol frames, and an
  `artifact-update` is not resendable. The shipped design invokes `on-event`
  **inline on the reader thread**, so a slow consumer stalls `.readLine`, stops
  draining the socket, and applies **TCP backpressure** to the server. That is
  real flow control, it costs nothing, and it loses no frames. The trade is
  that `on-event` must not block indefinitely — a contract stated on
  `open-sse!`.
- **The SSE request-timeout trap** (found in Phase 2) — `clj-http-native`
  ALWAYS applies `:timeout-ms` (default 60s) to the whole request. The JDK
  client counts that against the entire exchange, not idle time, so a healthy
  long-lived stream is killed the moment it outlives the window — surfacing as
  a generic I/O error with nothing pointing at the timeout. `open-sse!`
  therefore uses a separate `:stream-timeout-ms` (default 24h). Any future code
  that opens a long-lived HTTP body must do the same.

## 12. Not implemented (deliberately, and what it would take)

Both were listed under Phase 7 in the original plan. Neither is done, and
neither is blocking — but they are gaps, not oversights, so they are written
down rather than left to be rediscovered.

### OAuth2 client authentication

**Status: not implemented.** `a2a-client/core/auth.clj` supports HTTP bearer,
HTTP basic, and API key (header or query). A peer whose card requires OAuth2
cannot be reached.

**Why it was not a small adapter.** The plan said "OAuth2 via `clj-oauth`",
which assumed that component already had what A2A needs. It does not.
`components/clj-oauth` implements **interactive user-login** flows — device
flow, authorization-code + PKCE, a loopback receiver, and a token store. What
server-to-server A2A auth wants is the **`client_credentials`** grant, which
clj-oauth does not implement at all. So this is a new grant type in
`clj-oauth`, not a wrapper in `a2a-client`.

**Two separable pieces of work, in order of value:**

1. **Reuse an existing interactive session.** Add an `{:type :oauth :provider
   "…"}` auth spec whose token is resolved *per request* via
   `clj-oauth/get-valid-access-token` (per request, not captured once, so
   refresh is honoured). Covers "I logged into this provider, now let my agent
   call its A2A endpoint" with the component exactly as designed. Small.
2. **Add `client_credentials` to `clj-oauth`**, then expose it as an A2A auth
   type. This is the one that unlocks unattended server-to-server auth, and it
   belongs in `clj-oauth` where every other consumer benefits.

`auth/normalize` already passes unrecognized `:type` values through untouched,
so neither piece requires changing the existing shapes.

### Push notifications

**Status: declared unsupported, which is spec-correct.** The generated Agent
Card advertises `capabilities.pushNotifications: false`, and the four
`tasks/pushNotificationConfig/*` methods answer `UnsupportedOperationError` —
the right reply for an optional method a server does not offer (as opposed to
`MethodNotFound`, which would claim the method does not exist).

Bridging them to the event bus (§3) would let a remote peer POST task updates
to a webhook instead of us polling. It needs an inbound HTTP route we are
willing to expose, which is a bigger security question than the rest of the
server — worth doing deliberately rather than as a Phase-7 tail item.

## 13. A2A v1.0 — both dialects, negotiated

**Status: shipped.** Brainyard speaks **both** v0.3 and v1.0. The client picks
per peer from its Agent Card; the server answers per request from the inbound
`A2A-Version` (falling back to the method name, which is unambiguous). One
card advertises both generations, so a client of either era can discover us.

Verified against the official `a2a-sdk` 1.1.0 helloworld agent, in both
directions, and repeatable via **`bb a2a:interop`**.

### How this was missed

Worth recording, because the failure was in method rather than in code. The
first spec fetch for this design reported *"Method names: snake_case
(SendMessage, GetTask)"*. The label `snake_case` is wrong for those examples,
and that inconsistency was used to dismiss the whole line as garbled — throwing
away the correct method **names** along with the incorrect **label**. The next
fetch targeted v0.3.0 specifically, which confirmed the `message/send` shape
already being written. Contradicting evidence was discarded on a technicality
and then not sought again.

A consequence still in the tree: §2's note that protobuf enum names "do not
travel on the JSON-RPC wire" is true of v0.3 and **false of v1.0**, and
`schema_test.clj` asserts those names are *rejected*. Those assertions encode
the right rule for the dialect we implement and the wrong one for the dialect
the ecosystem now defaults to. They should move behind the dialect split
below rather than simply be deleted.

### The differences, verified against a running `a2a-sdk` 1.1.0

Established empirically — by driving the sample and reading the SDK's own
`METHOD_TO_MODEL` — not from documentation:

| | v0.3 (implemented) | v1.0 (official SDK) |
|---|---|---|
| method names | `message/send`, `tasks/get`, … | `SendMessage`, `GetTask`, … (gRPC service names) |
| `A2A-Version` header | optional | **required**; absent is read as `0.3` and rejected `-32009` |
| `Message.role` | `"user"` | `"ROLE_USER"` |
| `Part` | `{"kind":"text","text":…}` | `{"text":…}` — no discriminator |
| `TaskStatus.state` | `"completed"` | `"TASK_STATE_COMPLETED"` |
| result envelope | `{"result": <Task>}` | `{"result":{"task": <Task>}}` |
| card endpoint | `url` + `preferredTransport` | `supportedInterfaces[{protocolBinding}]` |
| error code | — | `-32009` VERSION_NOT_SUPPORTED (absent from our catalog) |

`SendMessageRequest` carries `tenant / message / configuration / metadata`, and
`Message` carries `messageId / contextId / taskId / role / parts / metadata /
extensions / referenceTaskIds`. In short, **v1.0's JSON-RPC binding is ProtoJSON
over the protobuf model**, which is what the spec means when it calls the
`.proto` normative.

Card endpoint resolution is already fixed (`373e294`), so a v1.0 peer is
**reachable but not callable**.

### Still to determine

Not guesses to be written down as fact:

- The full v1.0 `Part` variant set (the proto models a `text | raw | url | data`
  one-of; only `text` has been observed, and artifact parts carried `mediaType`).
- The streaming frame shape under v1.0 (`SendStreamingMessage` /
  `SubscribeToTask`), including whether the result stays wrapped.
- Whether artifacts are keyed `artifactId` or `id` in v1.0.
- Push-notification method naming — `METHOD_TO_MODEL` shows
  `CreateTaskPushNotificationConfig` while v0.3 used `…/set`.
- Whether our **server** must speak v1.0 to be callable by modern clients.
  Almost certainly yes: a v1.0 client will send `SendMessage` and our
  dispatcher answers `MethodNotFound`.

### As built

**`components/a2a/core/dialect.clj` is the only namespace that knows a second
dialect exists.** Everything inside brainyard works on one canonical form —
which equals the v0.3 shape — so v0.3 encode/decode is near-identity and all
real translation is confined to the v1.0 arms.

That paid off exactly as intended: `client/result->outcome`,
`events/translate`, `events/frame-kind` and the agent-side `ask-fn` were
**never touched**. A test now pins it, asserting the service receives plain
text and never a dialect-shaped payload.

Seams (7 in total): client — `make-peer` stores `:dialect`, `request-headers`
takes it, `rpc!` resolves the method and decodes **per method**, `open-sse!`
decodes frames, `connect!`/`describe-peer` surface it. Server —
`rpc-handler` resolves the dialect, `dispatch` routes by method keyword, and
the response/SSE encoders honour it.

Three things worth knowing:

- **`decode-result` dispatches on METHOD, not just dialect.** `SendMessage`
  returns the `Task | Message` one-of; `GetTask` and `CancelTask` return a
  bare `Task`. Decoding everything as a send-result would have left a
  `tasks/get` Task carrying raw `TASK_STATE_*`, which the task poller compares
  against canonical states — it would never observe a terminal state and would
  poll forever.
- **The method name beats the version header** when they disagree or the
  header is missing. The two vocabularies are disjoint, so the name is
  unambiguous; this is deliberately more liberal than the reference SDK, which
  rejects a missing header outright.
- **One card, both generations.** The v0.3 fields and v1.0
  `supportedInterfaces` are different fields, so a single card serves both
  with no negotiation. Ours lists v1.0 first, so brainyard-to-brainyard now
  runs over v1.0 — the loopback E2E exercises that.

### Two findings the live run produced

Neither was in the proto, and neither would have surfaced from fixtures:

- **`blocking` became `returnImmediately` — its logical NEGATION**, not a
  rename. It failed loudly here (`-32602`, unknown field), but against a
  server that tolerated unknown fields it would have silently inverted
  behaviour: fire-and-forget where the caller asked to wait. The round trip is
  tested in both senses, because a one-way inversion bug reads correctly in
  one direction.
- **Our error mapper was discarding the peer's diagnostic.** `data` is
  free-form: we emit `{:detail …}`, the Python SDK emits a bare string, the
  spec's examples use `@type`-tagged vectors. Reading only our own shape
  reduced a precise *"no field named blocking"* to `"Invalid params
  (-32602)"`, and cost a round of guessing. All three shapes are now surfaced.

Options deliberately **not** taken: v1.0-only (breaks our own server and any
deployed v0.3 peer; the SDK still ships a `JSONRPC03Adapter`), and client-side
v1.0 only (reaches modern peers but leaves us uncallable by them, discarding
half the symmetric design).

## 14. References

- [A2A Protocol specification](https://a2a-protocol.org/latest/specification/) —
  the JSON-RPC binding, data model, and error catalog implemented here.
- [Linux Foundation: A2A one-year adoption report](https://www.linuxfoundation.org/press/a2a-protocol-surpasses-150-organizations-lands-in-major-cloud-platforms-and-sees-enterprise-production-use-in-first-year)
- [A2A Java SDK 1.0.0.Final](https://quarkus.io/blog/a2a-java-sdk-1-0-0-final-released/) —
  useful as a reference implementation of the server half.
- `docs/design/acp-design.md` — the sibling protocol integration this document
  is structured after, and the source of several hard-won constraints (§11).
- `docs/design/agent-lifecycle-management.md` — subagent lifecycle, ownership,
  LRU eviction and the close cascade that `RemoteAgent` inherits.
