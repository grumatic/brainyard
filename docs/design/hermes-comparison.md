# Hermes Agent vs. Brainyard — Feature Comparison & Adoption Recommendations

> Status: **analysis** (2026-06-24). A repo-grounded read of
> [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent)
> ("the agent that grows with you") against Brainyard's current bricks, agents,
> and memory stack, isolating capabilities Brainyard should consider adopting
> for advanced agentic engineering. Nothing here is committed work — it's an
> opinionated backlog for discussion.
>
> Related: `docs/core/memory.md`, `docs/design/skills.md`,
> `docs/design/meta-agent-design.md`, `docs/design/memory-agent-design.md`,
> `docs/design/acp-design.md`, `docs/reference/RLM-RESEARCH.md`.

## 1. TL;DR

Brainyard and Hermes are converging on the same thesis — *a self-improving,
memory-bearing agent you talk to from a terminal* — from opposite engineering
cultures. Hermes is a Python/`uv` project optimized for **reach and the
learning loop**: it lives on every messaging platform, runs on six execution
backends (including serverless), and closes an autonomous skill/memory loop.
Brainyard is a Clojure/Polylith/GraalVM project optimized for **rigor and
substrate**: a layered memory store with FTS5 + BM25 + RRF recall, a 22-agent
CoAct roster, a hardened MCP-OAuth client, an in-process SCI sandbox, and
native-image performance.

The upshot: Brainyard's *substrate is at parity or ahead*; the gaps are at the
**edges** (where the agent lives, where it runs) and in **closing the loops**
the substrate already makes possible. The five recommendations below are ordered
by leverage-to-effort.

## 2. Side-by-side

| Capability | Hermes | Brainyard today | Verdict |
|---|---|---|---|
| Terminal UI | Full TUI, slash autocomplete, interrupt-and-redirect | `agent-tui` base, slash commands, input queue, web-share via ttyd | **Parity** |
| Multi-agent / subagents | Spawn isolated subagents for parallel workstreams | 22 CoAct-derived agents; `meta-agent` mints user-defined agents at runtime | **Brainyard ahead** |
| Layered memory | Agent-curated memory, user profiles, FTS5 session search + LLM summarization | L1/L2/L3 `IMemoryStore`, FTS5+BM25+RRF recall, capture pipeline, `memory-agent` steward | **Brainyard ahead** (substrate) |
| Tools-from-scripts (RPC) | Python scripts call tools via RPC, "zero-context-cost" pipelines | SCI sandbox auto-binds every visible tool as a callable fn (`sandbox_bindings.clj`); nREPL eval | **Parity** (different lang) |
| MCP integration | Connect any MCP server | MCP client **with full OAuth** (device flow, loopback redirect, DCR) | **Brainyard ahead** |
| Skills | Static + `SKILL.md`; **autonomous creation after tasks**, **self-improve during use**; agentskills.io standard; Skills Hub | Static `defskill` + dynamic `SKILL.md` via skill-agent; `skills$write`; manual authoring | **Hermes ahead** (the *loop*, the *ecosystem*) |
| Messaging presence | Telegram, Discord, Slack, WhatsApp, Signal, Email — one gateway; voice transcription; cross-platform continuity | Terminal + browser share only (`web-share`) | **Hermes ahead** — Brainyard gap |
| Execution backends | local, Docker, SSH, Daytona, Singularity, Modal; **serverless hibernation** | Local process; OS seatbelt sandbox; in-process SCI sandbox | **Hermes ahead** — Brainyard gap |
| Scheduled automations | Built-in cron with delivery to any platform | No first-class scheduler | **Hermes ahead** — Brainyard gap |
| Persona / personality | `/personality`, SOUL.md persona file, model switching | Per-agent instructions; `meta-agent` user agents; no user-facing persona switch | **Hermes ahead** (minor) |
| RL / research pipeline | Batch trajectory generation, Atropos RL envs, trajectory compression for training | RLM research + `analytics`/`mulog` trajectory capture; `eval-agent` | **Mixed** — Brainyard captures, Hermes exports |

## 3. Recommendations (ranked)

### R1 — Close the self-improvement loop (highest leverage, low-to-medium effort)

> **Implementation plan:** `docs/design/self-improve-design.md`.

**What Hermes does:** after a complex task it *autonomously distills a skill*,
and skills *self-improve during use*; it *nudges itself to persist knowledge*.
The loop is automatic, not operator-invoked.

**Where Brainyard stands:** the hard parts are already built. `skills.md`
documents `skills$write` and the skill-agent; `memory-agent-design.md` ships
end-of-turn essence capture, consolidation, and L3 fact verification; `CLAUDE.md`
literally states the "self-improving agent" thesis. What's missing is the
*trigger* — these are LLM/operator-invoked, not fired automatically off
trajectory signals.

**Proposal:** add an experience-triggered loop on top of existing machinery —
no new subsystem.
- A post-task hook (reuse the `:agent.*/post` lifecycle events that already feed
  the capture pipeline) scores a trajectory for "novel reusable procedure" and,
  past a threshold, calls the skill-agent to draft a `SKILL.md` candidate — gated
  by a review step, matching the `meta-agent` authoring pattern.
- A skill *refinement* pass: when a skill is invoked and the outcome diverges
  from its documented steps, queue a memory-agent-style revision proposal.
- Surface memory "nudges" in the TUI status bar (the memory-agent already
  computes `memory$stats`) rather than relying on the user to run `memory$remember`.

**Why first:** it's the feature Hermes leads on that maps directly onto bricks
Brainyard already shipped — mostly wiring triggers and a review gate, not new
substrate.

### R2 — A first-class scheduler with channel delivery (medium effort)

**What Hermes does:** built-in cron — "daily reports, nightly backups, weekly
audits, all in natural language, running unattended" — with delivery to whatever
platform the user is on.

**Where Brainyard stands:** no scheduler today. But the `task` subsystem
(project-scoped `.brainyard/tasks/<id>/`, lifecycle `meta.edn`, retention GC) is
the natural substrate, and the agents to *run* the scheduled work already exist.

**Proposal:** a `schedule` brick that persists cron-or-`fireAt` specs alongside
tasks, wakes a headless `by` run (`ask`/`run`) at fire time, and routes output
to a delivery sink. Start with the sinks Brainyard already has (file artifact,
stdout, web-share); make delivery pluggable so R3's channels slot in later.
Reuse the GC/retention discipline already designed for tasks.

### R3 — Messaging gateway / ambient presence (highest reach, higher effort)

**What Hermes does:** one gateway process exposes the agent on Telegram,
Discord, Slack, WhatsApp, Signal, and Email, with voice-memo transcription and
cross-platform conversation continuity. "Lives where you do," not tied to a
laptop.

**Where Brainyard stands:** reach stops at the terminal and browser share
(`web-share`/ttyd). This is the single biggest product gap.

**Proposal:** a `gateway` base (sibling to `agent-tui`) that maps an inbound
platform message to a session turn and streams the reply back.
- Brainyard's session/channel architecture is already an abstraction
  (`docs/design/session-channel-extensions.md`, `ask-channel`,
  `acp`/`acp-client`) — a messaging adapter is "another channel," not a rewrite.
- Sessions are **project-scoped** today (`persist/set-root!`); a remote chat user
  has no cwd, so the gateway needs a default project root per paired user — a
  deliberate design decision to make, not an accident to stumble into.
- Start with **one** platform (Telegram has the simplest bot API + voice) to
  prove the channel mapping, then generalize.
- Cross-platform continuity wants user-scoped session lookup; today only *memory*
  is user-partitioned (`BY_USER_ID`) while sessions are project-scoped — reconcile
  these before promising continuity.

### R4 — Remote & serverless execution backends (high effort, strategic)

**What Hermes does:** six backends (local, Docker, SSH, Daytona, Singularity,
Modal); Daytona/Modal give *serverless persistence* — the environment hibernates
when idle and wakes on demand, "costing nearly nothing between sessions." Decouples
the agent from the laptop.

**Where Brainyard stands:** execution is local, wrapped by an OS seatbelt sandbox
(`os-sandbox`) and an in-process SCI sandbox (`clj-sandbox`). Strong *isolation*,
no *remote/hibernating compute*.

**Proposal:** abstract an execution-backend protocol behind the current
local+sandbox path (the `--sandbox`/`--web` re-exec launchers already prove
Brainyard can hand a child process a different execution envelope). Add a Docker
backend first (most portable), then SSH. Serverless (Daytona/Modal-style) is a
larger bet — worth it only if R3 lands, since "agent that isn't on your laptop"
is the scenario that makes remote compute matter.

### R5 — Skill portability + trajectory export (low effort, ecosystem leverage)

> **Status: implemented (2026-06-24).** Both wins shipped.

Two smaller, independent wins:

**5a — Skill interop.** Hermes skills follow the
[agentskills.io](https://agentskills.io) open standard and share via a Skills
Hub. Brainyard's dynamic skills are already `SKILL.md` documents — align the
front-matter/schema with the open standard so skills are portable in/out, then
consider an import command. Low cost, real ecosystem upside.
> **Shipped:** `parse-skill-md` now accepts the open-standard `name` front-matter
> (alongside legacy `title`) and surfaces it as `:fm-name`; new `skills$import`
> command reads an external `SKILL.md` (file or dir) and creates it in the
> brainyard backend, deriving the name from front-matter `name` and requiring
> `name + description`. Also fixed a latent NPE in `parse-skill-md` on
> heading-only bodies. (`components/agent/.../skills.clj`.)

**5b — Trajectory export for training.** Hermes ships batch trajectory
generation, Atropos RL environments, and trajectory compression "for training
the next generation of tool-calling models." Brainyard *already captures*
high-fidelity trajectories via `analytics`/`mulog` and the memory capture
pipeline, and has the `RLM-RESEARCH` line of work. The missing piece is an
**export** path: a command that serializes captured sessions into a
training-ready trajectory format. Brainyard is closer to this than to anything
else on the list — it's productizing data it already records.
> **Shipped:** new ns `agent.common.trajectory-export` + `trajectory$export`
> command. Reads `sessions/<id>/trajectory.edn` and writes **OpenAI
> tool-calling JSONL** (tool/code iterations → `tool_calls` + `tool` messages;
> question/answer bracket each turn) or lossless **edn**. A secret-redaction
> pass (default ON) scrubs api-key/bearer-style tokens from content before
> writing. Args: `:session-id`/`:all`, `:format`, `:out`, `:redact`. Bound into
> `all-common-commands`. ShareGPT and a CLI-subcommand wrapper deferred.

## 4. What Brainyard should *not* copy

- **Python/`uv` packaging** — Brainyard's native-image + uberjar story is a
  deliberate strength; don't dilute it.
- **A second skills execution model** — Brainyard's unified `!tool-defs` registry
  (one source of truth for commands/skills/agents/MCP) is cleaner than bolting on
  a parallel path. Adopt Hermes' *loop* and *standard*, not its plumbing.
- **OpenClaw migration tooling** — Hermes-specific lineage, irrelevant here.

## 5. Suggested sequencing

> **Progress (2026-06-24):** ✅ **R1** (self-improvement loop — see
> `docs/design/self-improve-design.md`) and ✅ **R5** (skill interop +
> trajectory export) are implemented. Remaining: **R2** → **R3** → **R4**.

1. **R1** — closes the loop with bricks already in the tree; proves the
   "self-improving" claim in `CLAUDE.md`.
2. **R5b** then **R5a** — cheap, independent, and R5b leverages existing capture.
3. **R2** — scheduler; unlocks "unattended" and sets up delivery sinks.
4. **R3** — messaging gateway; the big reach win, and the reason R2's delivery
   sinks exist.
5. **R4** — remote/serverless backends; highest effort, most valuable *after* R3
   makes off-laptop usage real.
