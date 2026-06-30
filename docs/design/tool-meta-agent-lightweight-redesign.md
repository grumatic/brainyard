# Tool-Agent & Meta-Agent — Lightweight Redesign (the user-artifact lifecycle pair)

> **Status:** Proposal. Applies the [series](./agent-lightweight-redesign-synthesis.md)
> argument to the two near-identical **user-artifact lifecycle specialists**:
> tool-agent (authors persistent user-defined *tools*, `user$tool$<name>`) and
> meta-agent (authors persistent user-defined *agents/personas*,
> `user$agent$<name>`). meta-agent's own docstring says it "mirrors tool-agent,"
> so one doc covers both.
>
> The honest finding: **these are the lightest cases in the series.** Their
> substantive content is already code/markdown the LLM writes (a tool's
> `(fn [args] …)` body; an agent's instruction + tool-context), and their helpers
> (`*$validate`, `*$create`, `*$delete`) are the *good* kind of mechanism —
> deterministic dry-run + eval-smoke + runtime **register**. So the series'
> argument largely **confirms** their design and contributes two things: it makes
> them the cleanest **"keep the mechanism"** exemplar, and it explains — via the
> substrate theory — **why these correctly have no substrate** (unlike skills).
>
> **Scope:** `tool_agent.clj` / `user-tools.clj`, `meta_agent.clj` /
> `user-agents.clj`. Minimal code change; mostly codified discipline + analysis.

---

## 1. Why these are the lightest cases

Both agents are thin: a CoAct-derived instruction + a curated `*-agent$*` roster.
Look at where the work actually goes:

**tool-agent** authors a tool = `.brainyard/tools/<name>.edn` (metadata: name /
description / **`:input-schema`** Malli map) + `<name>.clj` (the **verbatim body
source**, a `"(fn [args] …)"` string). The body is code the model writes; the
metadata is a name, a one-line description, and a small Malli `[:map …]`.

**meta-agent** authors an agent = `.brainyard/agents/user$agent/<name>/`
(agent.edn: name / description) + **`instruction.md`** + **`tool-context.md`** —
both **markdown the model writes**. There are *no bound tools* to construct; the
persona is shaped entirely by its prose.

So the substantive artifacts are already in the model's native media (code and
markdown). The only "structured construction" anywhere is tool-agent's small
`:input-schema` Malli vector — load-bearing (it drives coercion, the sandbox
arglist, and docs) and validated on create. meta-agent has essentially none.

And the helpers are the *opposite* of the dossier-frontmatter brittleness the
series retires. `*$validate` runs a **dry-run** (tool-agent: parse + eval-smoke-
test the body in a throwaway sandbox fork, with a `:sample` behavior check;
meta-agent: structural checks + unknown-tool check + optional sample LLM run).
`*$create` does **validate → persist → register** — and the *register* step is a
runtime side effect (the artifact isn't live until the registry knows it), which
a file write alone cannot accomplish. These are deterministic acts the model
should never hand-roll or skip.

## 2. Thesis

For tool/meta-agent the series argument mostly **confirms** the design, plus:

1. **Keep — and centre — the mechanism.** `*$validate` and `*$create` are the
   *good* micro-tools (the `edit$apply` archetype). Make **validate-before-create
   mandatory** in the instruction (codify the discipline already described), and
   keep `*$create`'s persist+register as the only path to a live artifact.
2. **Author content file-inherently where it's already natural.** The tool body
   (`<name>.clj`) and the agent's `instruction.md` / `tool-context.md` are
   files/strings the model writes; the redesign just makes that explicit and
   keeps the small metadata (description, Malli schema) as the validated input to
   `*$create`.
3. **No substrate — and that's correct.** A created tool/agent is **registered**,
   so *using* it is already ambient: any agent can call `user$tool$<name>` /
   `user$agent$<name>`, and `list-tools`/`get-tool-info` already discover them.
   There is nothing to push down to the base (§6).

One line: **the content is already prose/code; the helpers are already the right
mechanism — keep both, mandate the dry-run, and note that use is free because the
registry already makes it so.**

## 3. The "keep the mechanism" exemplar

The edit doc introduced the rule: *retire micro-tools that make the LLM construct
artifacts; keep micro-tools that do deterministic work.* tool/meta-agent are the
cleanest illustration of the *keep* side:

- **`*$validate` is the dry-run.** Like `edit$apply`'s probe/verify, it tells the
  model whether the artifact is sound **before** it goes live — eval-smoke-testing
  a tool body in a throwaway fork (tool-agent) or structurally checking an agent
  draft + sample-running it (meta-agent). A model cannot reliably self-assess
  "does this `(fn …)` eval and behave?" by reading its own code; the fork can.
  **Keep, and make it mandatory before create.**
- **`*$create`'s register is irreducibly a tool.** Persisting `<name>.edn` +
  `<name>.clj` (or the agent's three files) makes bytes on disk; **registering**
  `user$tool$<name>` / `user$agent$<name>` makes it a *callable*. The model can
  `write-file` the bytes but cannot register the runtime entry — so create stays
  the seam, even if the content arrives as files.
- **`*$list` / `*$read` are read seams.** Enumerate + read existing artifacts for
  the mandatory dup-check before authoring. Keep.

So unlike plan/exec/eval (where the helper *constructed* an artifact the model
could have written), here the helper *does something the model can't* — validate
in a fork, register in the runtime. That is exactly what the series keeps.

## 4. What stays, what goes

**tool-agent**

| Concern | Today | Redesign |
| --- | --- | --- |
| Author the tool body | `tool-agent$create :body "(fn [args] …)"` | **Keep** (already verbatim source) — optionally `write-file` `<name>.clj` then create-from-file. |
| `:input-schema` (Malli) | passed to validate/create | **Keep** — small, load-bearing, validated. The one structured bit; not worth retiring. |
| Dry-run | `tool-agent$validate` (eval-smoke in a fork) | **Keep + mandate** before create. The good mechanism. |
| Persist + register | `tool-agent$create` | **Keep** — register is irreducibly a tool. |
| Discover / read (dup-check) | `tool-agent$list` / `tool-agent$read` | **Keep** — read seams. |
| Remove | `tool-agent$delete` | **Keep** (unregister + delete) — or `rm` + reload; keep the guarded helper. |

**meta-agent**

| Concern | Today | Redesign |
| --- | --- | --- |
| Author the persona | `meta-agent$create :instruction "<md>" :tool-context "<md>"` | **Keep** (already markdown) — optionally `write-file` `instruction.md` / `tool-context.md` then create-from-files. |
| Metadata (name/description) | flat args | **Keep** — trivial; no nested object. |
| Dry-run | `meta-agent$validate` (structural + unknown-tools + sample) | **Keep + mandate** before create. |
| Persist + register | `meta-agent$create` | **Keep** — register is irreducibly a tool. |
| Discover / read (dup-check) | `meta-agent$list` / `meta-agent$read` | **Keep** — read seams. |
| Remove | `meta-agent$delete` | **Keep** — unregister + delete the dir. |

Net: **almost nothing retires.** No dossier-frontmatter chain exists here to
remove. The only adjustments are (a) make validate mandatory, and (b) optionally
let the model `write-file` the body/instruction/tool-context directly and point
`*$create` at the files — a convenience, since those are already file-shaped.

## 5. File-inherent authoring (the modest touch)

Both artifacts are file collections on disk, so the model *may* author them with
file tools and then register:

```clojure
;; meta-agent — write the persona's files, then register:
(write-file {:path ".brainyard/agents/user$agent/adr-writer/instruction.md" :content "<role + decision-flow + safety, imperative>"})
(write-file {:path ".brainyard/agents/user$agent/adr-writer/tool-context.md" :content "<which tools to reach for + typical flows>"})
(meta-agent$validate :name "adr-writer" :instruction "…" :tool-context "…")  ; dry-run (mandatory)
(meta-agent$create   :name "adr-writer" :description "Writes ADRs" :instruction "…" :tool-context "…")  ; persist + REGISTER
```

But note: passing the markdown as `:instruction`/`:tool-context` strings to
`create` is *already* file-inherent in spirit (the model writes the markdown; the
helper persists it). The file-first form is only a readability convenience for
large prose. Either way, **validate and create stay** — they're the mechanism.
The point of the series here isn't to remove a constructor (there isn't a brittle
one); it's to confirm the content is prose and the helpers are the right kind.

## 6. Why there is no substrate — and what it tells us about the substrate theory

The skill doc added a base **skill substrate** so any agent could *use* skills.
A natural question: should tool/meta-agent get a "use" substrate too? **No — and
the reason completes the substrate theory.**

A substrate is worth installing when *using* a thing is a **multi-step
LLM-inherent procedure** that should be ambient:

- A **skill** is a *procedure* (a SKILL.md of steps). Using it = **discover →
  read → follow** — three LLM acts. A substrate makes that *habit* ambient. ✓
- A **user tool** is a *callable function*. Using it = **call it**. Once
  `tool-agent$create` registers `user$tool$<name>`, it's a first-class tool any
  agent can invoke, and `list-tools`/`get-tool-info` already discover it. There's
  no "follow" step to make ambient — **the registry already is the substrate.** ✗
- A **user agent** is a *callable specialist*. Using it = **dispatch to it**.
  Registration + main-agent's decision table already make it ambient. ✗

So the rule the series converges on:

> **Install a substrate when "use" is a procedure the model performs; rely on the
> registry when "use" is a call the runtime performs.**

skills get a substrate (use is *read+follow*); tools/agents don't (use is a
*registry call*). And the **lifecycle** (create/validate/register/delete) stays
the specialist's in all three cases — authoring a persistent, registered,
sandbox-running artifact is a deliberate, validated act, not something to scatter
into every agent. The two-kinds split here *degenerates cleanly*: **USE is free
(the registry), MANAGE is the specialist.**

(One could imagine a "create a tool inline" substrate, but it's the wrong call:
the body runs in a sandbox with the agent's capabilities, and a persona can
social-engineer around safety — exactly the deliberate, validated act that
belongs to a specialist behind the mandatory dry-run, not to every agent.)

## 7. Validate-before-create as the safety contract

The one behavioral firming-up: today the instructions *recommend* validate before
create ("Run before `*$create`; iterate until `:valid` is true"). Make it a
**hard rule**, because it's the cheap dry-run that prevents a broken tool/persona
going live:

- tool-agent: never `tool-agent$create` without a passing `tool-agent$validate`
  (`:valid true`, `:collision` consciously accepted on a refine). Then **verify**
  by calling `user$tool$<name>` once. Report success only after it runs.
- meta-agent: never `meta-agent$create` without a passing `meta-agent$validate`
  (`:valid true`, `:unknown-tools []`). Then verify by asking `user$agent$<name>`
  one question. Report success only after it answers sensibly.

This mirrors edit-agent's "branch on `:ok?`": the dry-run is mandatory, and
success is claimed only after the artifact actually works.

## 8. Instruction & tool-context (minimal changes)

- Promote validate-before-create + verify-after-create from "disciplined path" to
  **hard rules** (§7).
- Add the optional file-first authoring note (§5): you *may* `write-file` the body
  / instruction / tool-context, then validate + create — but validate and create
  are never skipped.
- Otherwise unchanged: the dup-check-first flow, the `:brainyard`/project-scope
  facts, and the safety blocks (no secret-exfiltration body; a persona grants no
  new capability) all stay.

## 9. Code changes

- **`user-tools.clj` / `user-agents.clj`**: no helper retires. Optionally accept a
  `:body-path` / `:instruction-path` / `:tool-context-path` on `*$create` so the
  model can author files then point at them (convenience; the string form stays).
- **`tool_agent.clj` / `meta_agent.clj`**: instruction edits only (§8). Rosters
  unchanged (`user-tools/tools-commands`, `user-agents/meta-agent-commands`).
- **No base-agent change.** No substrate, no roster add — use is ambient via the
  registry (§6).

## 10. Migration

Effectively none. The artifact formats, helpers, and registry are unchanged;
existing user tools/agents keep working. The changes are instruction-level
(mandatory validate) + an optional file-path arg. Independent of every other doc
in the series.

## 11. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| Model skips the dry-run and ships a broken tool/persona. | validate-before-create becomes a hard rule (§7); create still eval-smoke-tests on its own as a backstop. |
| File-first authoring writes a half-artifact that isn't registered. | `*$create` remains the only path to a live artifact; a written-but-not-created file is inert (and discoverable as such). |
| Someone proposes a "create inline" substrate. | §6: authoring a sandboxed, registered artifact is a deliberate validated act — it stays the specialist's, behind the mandatory dry-run. |
| `:input-schema` Malli mis-constructed (tool-agent). | `tool-agent$validate` `:schema-ok` catches it pre-create; it's the one small structured input, validated. |

## 12. Verification

- **Validate gating** — `tool-agent$create` / `meta-agent$create` is only reached
  after a passing validate; a failing validate blocks create (hard rule honored).
- **Verify-after** — success is reported only after `user$tool$<name>` runs /
  `user$agent$<name>` answers.
- **File-first (optional)** — author `instruction.md` via `write-file`, then
  validate + create from it; the agent registers and is callable next turn.
- **Use is ambient** — a freshly created `user$tool$<name>` is callable by a
  *different* agent the next turn with no substrate; `list-tools` discovers it.
- **No regressions** — list/read/delete behave as before.

## 13. Open questions

1. **Add `:*-path` args to `*$create`** for file-first authoring, or keep
   string-only? Files read better for large prose; strings are simpler. Lean: add
   the path args as an optional convenience.
2. **Should `*$validate` be enforced by a hook** (`:agent.tool-use/pre` on
   `*$create` requiring a recent passing validate) rather than prompt-only? Same
   prompt-vs-hook question as the substrates; here a hook is attractive because
   the safety stakes (a live sandboxed tool) are higher. Lean: prototype the hook.
3. **Unify tool-agent and meta-agent** into one "user-artifact" specialist (they
   mirror each other), or keep separate for clarity? Separate is clearer to the
   user ("make me a tool" vs "make me an agent"); the shared validate→create→
   register machinery already lives in `user-tools`/`user-agents`. Lean: keep
   separate agents, shared mechanism.

---

## Appendix — the shape, side by side

```clojure
;; tool-agent: content is CODE; helpers are the mechanism (dry-run + register)
(tool-agent$list)                                  ; dup-check (read seam)
(tool-agent$validate :name "wc-clj" :body "(fn [args] (count (clojure.string/split (:s args) #\"\\s+\")))"
                     :input-schema [:map [:s [:string {:desc \"text\"}]]]
                     :sample {:s "a b c"})          ; eval-smoke in a fork → {:valid true …}
(tool-agent$create   :name "wc-clj" :body "…" :input-schema [:map …])  ; persist + REGISTER
(user$tool$wc-clj {:s "one two three"})            ; VERIFY (now ambient — any agent can call it)

;; meta-agent: content is MARKDOWN; helpers are the mechanism (dry-run + register)
(meta-agent$list)                                  ; dup-check (read seam)
(meta-agent$validate :name "adr-writer" :instruction "<md role+flow>" :tool-context "<md>")  ; structural + sample
(meta-agent$create   :name "adr-writer" :description "Writes ADRs" :instruction "…" :tool-context "…")  ; persist + REGISTER
(user$agent$adr-writer {:question "Draft an ADR for choosing Kafka over SQS"})  ; VERIFY (now ambient)
```

The model writes the media it's fluent in (a `(fn …)` body, a markdown persona),
the helpers do the two things it can't (validate in a fork, register in the
runtime), and the result is **ambient by registration** — which is exactly why
these two agents need no substrate and almost no change. They were already what
the series is arguing for.
