# Exec-Agent — Gated, Audited Contract Execution + the Exec Substrate (CoAct-derived)

> **Status:** Shipped. The lightweight file-tool redesign + exec substrate shipped
> (2026-06); this doc is the as-built reference — the former
> `exec-agent-lightweight-redesign.md` has been folded in here and removed. The
> earlier "verbose `runs/` + dossier helper-chain" proposal (revision 2) was
> superseded before landing.
>
> **As-built (verify against `common/exec_agent.clj`, `common/exec.clj`,
> `common/agent_roster.clj`, `common/coact_agent.clj`, `common/react_agent.clj`):**
> - **Two pillars ship together:**
>   1. **An exec substrate installed in the base agents.** `exec-substrate-protocol`
>      (the `## Executing a checklist` system-context section in `agent_roster.clj`)
>      is wired into **both** `coact-system-context` (`coact_agent.clj`) and
>      `react-system-context` (`react_agent.clj`), next to `:todo-substrate`. So
>      *any* derived agent — `main-agent` included — can drive a checklist to
>      completion with **evidence-before-flip** and **safe-writes-via-edit-agent**,
>      with no `exec-agent` dispatch (§5, §8). The route → verify → record → flip
>      discipline is base guidance; the tools (`edit-agent`, `bash`, `mcp$tools`,
>      `update-file`, `todo$sync`) already ride `default-agent-roster`, so the
>      install was one guidance edit, no roster change.
>   2. **A contract-based exec-agent.** The gated/audited execution path that
>      produces the evidence dossier `eval-agent` consumes — pre-flight C1–C8 on
>      passed plan+todo dossiers, the R1–R7 rubric, and the per-item
>      `execute.evidence` + `post.acceptance_progress` maps. This is the "second
>      kind" of execution; the substrate is the first (§8.1).
> - **Authoring is direct markdown, not a helper chain.** The evidence dossier is a
>   markdown file; the model fills the DOSSIER TEMPLATE (§7.3) and `write-file`s it.
>   The write-side helpers `exec$dossier-slug` / `exec$dossier-frontmatter` /
>   `exec$dossier-write` / `exec$dossier-index-append` / `exec$next-handoff` are
>   **retired** (§12). Only two read seams survive in `exec-dossier-helpers`:
>   `exec$read-dossier` and `exec$find`.
> - **Checkbox flips are index-free.** A done item is recorded with
>   `update-file "- [ ] <text>" → "- [x] <text>"` (match on unique item TEXT, never
>   a drifting ordinal), then `todo$sync` to reconcile progress + refresh the TUI.
>   The old `doc$update :item-idx N :item-done true` flip path is **gone** (§4).
> - **`write-file` stays unbound; `update-file`/`read-file` are bound.** Exec-agent's
>   cardinal rule is "delegate every SOURCE write to edit-agent," so `write-file` is
>   stripped from the roster (the `remove` clause strips `:write-file` + `:fetch-url`).
>   `update-file` is bound for the **checkbox flip only** (and `read-file` for
>   in-loop reads) — never for source edits (Hard Rule 1, §8 Hard Rules).
> - **POST-FLIGHT verdict is PASS / HOLD only.** The earlier REVISE auto-retry (one
>   `reset-item` + re-execute round) is **deferred to v1.5**; HOLD covers all rubric
>   failures.
> - **No `runs/` directory and no separate run-record artifact.** Exec-agent emits a
>   **single dossier** under `.brainyard/agents/exec-agent/dossiers/<ts>-<slug>.md`;
>   per-item evidence rides in the dossier's `execute.evidence` map. There is **no
>   `exec$run-write` helper** and no `run_record` frontmatter key. The answer emits
>   only `Saved dossier:` (no `Saved run:` line). No
>   `exec$preflight`/`exec$postflight`/`exec$item-route` mega-helpers shipped.
> - **Cross-agent dispatch is direct kebab-case** — `(edit-agent {…})`,
>   `(explore-agent {…})`, `(mcp$tools …)`, `(todo-agent {…})`. Hard Rule 4 reads
>   "NO clone-self dispatch." The `(call-tool "<agent>" {…})` forms in the body
>   below are equivalent (the registry makes them reachable) but not how the shipped
>   prompt phrases it.
> - **An `:agent.ask/finalize` auto-persist hook** (gated to `:exec-agent`) writes a
>   minimal dossier from the answer text if the LLM skips PERSIST, injecting the
>   absent `Saved dossier:` line and catching a hallucinated path. It writes the
>   *same* one-file path the happy path uses (no divergence), via
>   `materialize-auto-dossier!`. A missing `Saved dossier:` line therefore does NOT
>   mean nothing was saved.
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/exec_agent.clj`,
> `exec.clj`, plus the exec-substrate section in the base agents
> (`agent_roster.clj` + `coact_agent.clj` + `react_agent.clj`).
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Sibling of:** `plan-agent`, `todo-agent`, `eval-agent`, `edit-agent`, `explore-agent`
> **Related reading:** `docs/design/plan-agent-design.md` (dossier schema is the
> template), `docs/design/todo-agent-design.md` (per-item routing tags + the todo
> substrate), `docs/design/edit-agent-design.md` (the safe-edit pipeline exec
> delegates to), `docs/design/explore-agent-design.md`,
> `docs/design/eval-agent-design.md`,
> `docs/design/agent-lightweight-redesign-synthesis.md` (the cross-agent
> separate-judgment-from-mechanism principle).

> **API note (2026-05):** the per-verb `todo$list/read/create/update-item/…` shims
> were removed. Todo CRUD is the polymorphic `doc$*` family with `:kind :todo`. The
> read-only dossier helpers (`todo$read-dossier`, `plan$read-dossier`, etc.) are NOT
> deprecated. The body below occasionally uses the old per-verb names for historical
> clarity; the shipped prompt uses `doc$*` + the index-free `update-file` flip.

---

## 1. Motivation

`exec-agent` drives a todo list to completion: pick the next pending item, do the
work, record evidence, flip the checkbox, advance. It exists so the
plan → todo → exec → eval pipeline has a **gated, audited** executor whose
output is a schema'd evidence dossier eval-agent can score. Three pressures
shaped the as-built design:

1. **A pre-flight guard on what the executor was handed.** A naive executor jumps
   straight into running. Exec-agent instead asks first: *Does the source plan
   exist, did its post-flight pass, did todo-agent's post-flight pass, do the
   items have routing tags I can act on, are the required tools/permissions
   present, is the working tree clean for items that will write to disk?* When any
   is false it STOPs with a structured GATHER/REFUSE rather than blundering ahead
   into an opaque "eval-agent says it failed; not sure why."
2. **Writes are delegated, never inlined.** With `edit-agent` landed as the
   specialist for safe single-file edits (`docs/design/edit-agent-design.md`),
   exec-agent dispatches `(edit-agent {…})` for every SOURCE write — getting the
   safety pipeline (probe → apply → verify → rollback → record) for free.
   `write-file` is deliberately left **unbound** so the rule can't be violated.
3. **Per-item evidence lives on disk, not in chat.** Each per-item action is
   recorded in a schema'd dossier (`execute.evidence` keyed by item index) that can
   be re-read independently of the chat trajectory. Multi-turn exec runs stay
   evaluable — turn N+1's eval-agent reads the dossier, not turn N's answer.

The lightweight redesign that this doc folds in corrected two layers of
brittleness in the pre-redesign implementation, and added a third pillar:

- **Layer 1 — the dossier helper chain.** Building `pre`/`execute`/`post`/`handoff`
  maps with exact keys and feeding them to `exec$dossier-frontmatter` /
  `exec$dossier-write` / `exec$dossier-index-append` / `exec$next-handoff` was
  precisely-keyed and easy to get wrong. **Retired** — the model now `write-file`s
  the markdown dossier directly from a template (§7.3).
- **Layer 2 — the checkbox-flip micro-tool.** Recording a completion with
  `doc$update :item-idx N :item-done true` carried the drifting-`:item-idx` hazard:
  across an inner loop where `add-item` insertions shift indices, "flip item idx N"
  could hit the wrong line. Exec-agent is where this bit hardest — flipping boxes
  *is* its core loop. **Retired** in favor of an index-free `update-file` text edit
  + `todo$sync` (§4), inheriting the todo substrate's editing model.
- **Pillar 3 — execution as a base substrate (NEW).** The route → verify → record →
  flip discipline, the evidence-before-flip rule, and safe-write-via-edit-agent are
  now a base system-context protocol that *any* agent inherits (§5, §8). `exec-agent`
  remains the **contract** path that adds gating + the eval dossier; the substrate
  is the **working** path every agent runs inline without a dispatch.

Everything *between* pick and flip — routing per `:tags.via`, delegating writes to
edit-agent, verifying — was sound and stays. The brittleness was only at the two
ends (recording the flip, persisting the dossier); both are markdown the model
writes directly.

**Thesis.** Exec-agent (the contract path) runs every turn through a fixed
three-phase pipeline; the substrate (the working path) runs the middle phase
inline for any agent.

1. **PRE-FLIGHT (sufficiency check, C1–C8)** — does the agent have everything it
   needs? Plan dossier + todo-agent dossier on hand, items routed, tools available,
   working tree clean (for items that will write). Output: GO / GATHER / REFUSE.
2. **EXECUTE** — pick next pending item; route per `:tags.via`; perform the work,
   delegating SOURCE writes to `edit-agent`, reads to `read-file`/`grep`/`query$llm`,
   MCP calls to `mcp$tools`, manual items to surfacing-and-stopping. Record evidence,
   then flip the checkbox **index-free** via `update-file` + `todo$sync`. Repeat per
   turn budget.
3. **POST-FLIGHT (confirmation check, R1–R7)** — for every item advanced this turn,
   did we have supporting evidence? Did delegated agents (e.g., edit-agent) succeed?
   Did any verification fail? Output: PASS / HOLD. (REVISE auto-retry deferred to
   v1.5.)

Every run produces a **single dossier** under
`.brainyard/agents/exec-agent/dossiers/<yyyyMMdd-HHmmss>-<slug>.md` — the handoff to
eval-agent, with links to per-item edit-agent records and a structured per-item
evidence map. (The earlier proposal's separate verbose `runs/` record was dropped;
the dossier body carries detail inline.)

Same minimal-diff principle. Sandbox, BT, DSPy untouched; the substrate install was
one guidance edit on the base agents (tools already inherited).

---

## 2. Design Principles

1. **Writes are LLM-inherent; reads stay deterministic.** The evidence dossier *is*
   a markdown document, so the model `write-file`s it from a fixed template — no
   frontmatter-construction helper chain. Reading and resume
   (`exec$read-dossier`, `exec$find`) stay typed and deterministic, exactly where a
   machine beats the model. (This is the cross-agent
   separate-judgment-from-mechanism principle in
   `docs/design/agent-lightweight-redesign-synthesis.md`.)
2. **Delegate SOURCE writes to edit-agent, always.** Exec-agent NEVER writes source
   files. `write-file` is left **unbound**; items tagged `:via :edit-agent` route
   through `(edit-agent {…})` and inherit its probe/verify/rollback pipeline. Items
   that need writes but lack the tag → STOP and ask todo-agent to retag. `update-file`
   is bound for **one** purpose: the index-free checkbox flip on the todo markdown.
3. **Index-free checkbox flips.** A done item is recorded with `update-file`
   matching unique line TEXT (`- [ ] <text>` → `- [x] <text>`), then `todo$sync`
   to reconcile progress + refresh the TUI. Items are addressed by description text,
   not ordinals — insertions can't misalign a flip. The `:item-idx`/`:item-done`
   path is retired.
4. **Per-item routing is data, not LLM judgement.** Route via `tags.via` from the
   todo-agent dossier. The allowed set: `:edit-agent` (writes), `:bash` (shell),
   `:mcp <server>:<tool>` (MCP), `:explore-agent` (mid-execution discovery),
   `:read-only` (just `read-file` / `grep` / `query$llm`), `:manual` (user must do
   this — STOP, surface).
5. **Evidence before flip — non-negotiable.** A box is only flipped after the item's
   work is *verified* (edit-agent record with `diff_match`, bash exit 0, recorded
   read excerpt). This is the one rule that makes eval-agent trustable. It is also
   the core rule of the exec substrate (§5), so every agent — not just exec-agent —
   inherits it.
6. **Evidence on disk, not in chat.** Every per-item action is recorded in the
   dossier's `execute.evidence` map (schema'd, re-readable independently of the chat
   trajectory). The dossier is what eval-agent reads.
7. **The substrate is the common path; the subagent is the contract path.** Any agent
   can execute a checklist inline via the §5 substrate (route/verify/record/flip, no
   dossier). `exec-agent` is reserved for gated, audited, eval-bound execution (§8).
8. **Stop conditions are explicit.** Hard blockers (missing creds, ambiguous spec,
   item lacks routing tag, would touch shared/production state without confirmation)
   STOP the loop with a structured handoff back to plan-agent / todo-agent / user.
   Soft stops (turn budget exhausted, user pause request) are recorded so the next
   turn can resume.
9. **POST-FLIGHT is PASS / HOLD (v1).** One or more rubric failures → HOLD, surfaced
   in the dossier and answer; no auto-retry. The REVISE auto-retry (one reset +
   re-execute round) is deferred to v1.5.
10. **Plans and todos are read-only upstream.** Exec-agent reads via
    `plan$read-dossier`, `todo$read-dossier`, and `doc$read :kind :todo`. It mutates
    only the todo checkbox (via `update-file`) plus the limited
    item-reset/add/abandon/complete surface. NEVER mutates plans; todo *authoring* is
    todo-agent's domain.
11. **No clone-self recursion.** No `query$clone`. Cross-agent dispatch is direct
    kebab-case — `(edit-agent {…})`, `(explore-agent {…})`, `(mcp$tools …)`,
    `(todo-agent {…})`.
12. **Resume cleanly across turns.** Every dossier carries enough state (items
    advanced, holds, pending-after) that a fresh exec-agent invocation in turn N+1
    can pre-flight, find the prior dossier via `exec$find` (C8), and pick up where it
    left off — re-attempting held items, skipping done ones.

---

## 3. Position in the Agent Stack

**Two kinds of execution.** Most interactive work is **working execution**: an agent
(often the root `main-agent`) just needs to do the items on its checklist, with
evidence and safe writes, but no audit dossier. That runs inline via the exec
substrate (§5, §8) — zero exec-agent dispatch. **Contract execution** is the audited
plan → todo → exec → eval handoff with an evidence dossier; that is what *this
agent* drives. The root agent dispatches `exec-agent` not to "do the items," but to
"do them under contract with an auditable evidence trail."

See `docs/design/plan-agent-design.md` §3 for the full pipeline diagram. Exec-agent
(the contract path) sits in the third slot:

```
plan-agent → ... → todo-agent → Saved dossier: <todo-agent dossier path>
                                              │
                                              ▼
exec-agent → Saved dossier: <exec-agent evidence dossier path>
                                              │
                                              ▼
eval-agent → ...
```

Within an exec turn, each item that needs a write fans out to edit-agent:

```
exec-agent (loop iter N)
  pick item K with :tags.via :edit-agent
  → (edit-agent {:question "..." :agent-context "<exec-agent dossier-so-far>"})
       → returns Saved edit: <edit-agent record>
                 Rollback:   <git checkout cmd>
  → record evidence in execute.evidence[K]
  → flip the box index-free (only if :ok? true):
       (update-file {:path "<todo>" :pattern "- [ ] <text>" :replacement "- [x] <text>"})
       (todo$sync {:path "<todo>"})
```

The link from exec evidence to the underlying edit-agent record means eval-agent can drill from "did acceptance criterion C pass?" → "yes, item 1 covers it" → "exec record 20260510-...md" → "edit-agent record 20260510-...md" → the actual diff. Full audit trail.

---

## 4. PRE-FLIGHT — Sufficiency Check (NEW)

Runs before any checkbox flip or cross-agent dispatch. Walks a fixed checklist; produces a `pre` map.

### 4.1 The Checklist

| Check | What it verifies                                                                                                            | How                                                                                                                                                                                                  | Fail → action                                                                                                                                |
| ----- | --------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| C1    | A todo-agent dossier was supplied.                                                                                         | Look for `Saved dossier: <path>` in `:agent-context`. Fallback: a todo slug + `:todo-ok? true` override (e.g., legacy todos pre-redesign).                                                          | GATHER — recommend `(todo-agent {…})` first.                                                                                     |
| C2    | The todo-agent dossier's post-flight passed.                                                                                | `todo$read-dossier :path <path>` → check `post.verdict = :pass`.                                                                                                                                     | If `:hold` → REFUSE with the holds; if `:revise` and not user-opted-in → GATHER.                                                             |
| C3    | The plan-agent dossier referenced by todo-agent's dossier exists and post.verdict = :pass.                                  | Read `source.plan_dossier` from todo dossier; `plan$read-dossier`; assert `post.verdict = :pass`.                                                                                                    | If missing or :hold → REFUSE (plan-agent re-do first).                                                                                       |
| C4    | The todo file is actually present and matches the dossier's slug.                                                           | `todo$exists` + `todo$status`; cross-check item count against dossier.                                                                                                                               | REFUSE — todo and dossier diverged; recommend a fresh todo-agent run.                                                                        |
| C5    | Every pending item has a `:tags.via` routing tag in the allowed set.                                                        | Iterate the items. Allowed: `:edit-agent` `:bash` `:mcp <server>:<tool>` `:explore-agent` `:read-only` `:manual`.                                                                                  | GATHER — recommend `(todo-agent {:question "Retag items K…M."})`.                                                                |
| C6    | Required external tools are available for the routed items.                                                                 | For `:bash` items, `bash "command -v <inferred binary>"` for the binaries the item description names. For `:mcp`, `mcp$server :op :list` confirms the server is :connected.                          | If a missing binary or disconnected MCP server is the ONLY blocker → GATHER ("install <binary>" / "start MCP server <X>"). Otherwise REFUSE. |
| C7    | If any pending item is `:via :edit-agent`, the working tree is clean (or `:dirty-ok? true`).                              | `bash "git status --porcelain"` — empty → clean. (Same gate edit-agent will check per-file; exec-agent surfaces it up-front so the user is not surprised mid-loop.)                                | GATHER — surface the dirty paths; user can stash or set `:dirty-ok? true`.                                                                   |
| C8    | If a prior exec dossier exists for this slug, parse its tail to know what's been attempted.                                 | `(exec$find :slug <slug>)` returns prior dossiers newest-first; load the latest's `post` to know which items were :ok? :hold :failed.                                                                | INFORMATIONAL — not a fail; populate `pre.resume-from` so EXECUTE knows to skip already-done items and re-attempt :hold items first.         |

Same short-circuit rule as the other agents: stop on first fail.

### 4.2 The `pre` Map

```clojure
(def pre
  {:verdict          :go             ; or :gather | :refuse
   :checks           {:c1 :pass :c2 :pass :c3 :pass :c4 :pass
                      :c5 :pass :c6 :pass :c7 :pass :c8 :informational}
   :todo-dossier     ".brainyard/agents/todo-agent/dossiers/...md"
   :todo-path        ".brainyard/agents/todo-agent/todos/<slug>.md"
   :todo-slug        "ship-v2-checkout"
   :plan-dossier     ".brainyard/agents/plan-agent/dossiers/...md"
   :plan-path        ".brainyard/agents/plan-agent/plans/<slug>.md"
   :plan-slug        "ship-v2-checkout"
   :acceptance       ["criterion 1" "criterion 2" "criterion 3"]
   :acceptance-cov   {"criterion 1" [0] "criterion 2" [1 2] "criterion 3" [3]}
   :resume-from      {:last-dossier ".brainyard/agents/exec-agent/dossiers/<prev>.md"
                      :prior-status {0 :done 1 :ok 2 :hold 3 :pending}}
   :gather-question  nil
   :refuse-reason    nil})
```

`pre.acceptance-cov` is read directly from the todo-agent dossier — exec-agent never re-derives it.

---

## 5. EXECUTE — Core Operation

Per turn, the agent runs an inner loop bounded by `:max-items-per-turn` (default 5; tunable per call). Each item iteration:

### 5.1 Pick

`doc$read :kind :todo` (or `todo$sync`) → next pending item. If `pre.resume-from`
shows held items, prioritize them (re-attempt) before pending items.

### 5.2 Route

Read `tags.via` for the picked item. Cross-agent dispatch is direct kebab-case
(the `(call-tool "<agent>" …)` form is equivalent but not how the shipped prompt
phrases it). Each route has a fixed call shape:

#### 5.2.1 `:via :edit-agent`

```clojure
(def edit-result
  (edit-agent
    {:question      (:description item)
     :agent-context (str "Saved dossier: " (:plan-dossier pre)
                         " — item idx " idx ", covers "
                         (clojure.string/join ", " (-> item :tags :covers)))
     :run-tests?    false      ; or true when the item description says "and verify"
     :dirty-ok?     (:dirty-ok? pre)}))
```

`edit-agent`'s answer carries `Saved edit: <path>` and `Rollback: <cmd>` (or `Rolled back: <reason>`). Exec-agent extracts both via grep and stashes:

```clojure
(def edit-record
  {:idx idx
   :update-edit-path (-> edit-result :answer (re-find #"Saved edit: (\S+)") second)
   :rollback         (-> edit-result :answer (re-find #"Rollback: (.+)") second)
   :ok?              (not (re-find #"Rolled back:" (:answer edit-result)))})
```

#### 5.2.2 `:via :bash`

```clojure
(def bash-result
  (bash {:command (:command item)        ; or inferred from :description
         :timeout 30000}))

(def bash-record
  {:idx idx
   :command (:command item)
   :exit (:exit-code bash-result)
   :stdout-tail (subs (or (:output bash-result) "") 0 (min 500 (count (or (:output bash-result) ""))))
   :ok? (zero? (:exit-code bash-result))})
```

#### 5.2.3 `:via :mcp`

```clojure
(def mcp-result
  (mcp$tools {:op :call
              :tool-calls [{:server-name (:server (:via (:tags item)))
                            :tool-name   (:tool (:via (:tags item)))
                            :tool-args   (:tool-args item)}]}))
```

Read-only MCP calls (search/list/get/show/read) proceed; write-side MCP calls (create/update/delete/send/post/execute) STOP and surface the proposed call to the user — same rule explore-agent uses.

#### 5.2.4 `:via :explore-agent`

When an item description like "discover where the X middleware lives" is best handled by mid-execution discovery:

```clojure
(def explore-result
  (explore-agent {:question      (:description item)
                  :agent-context (:plan-dossier pre)}))
```

The `Saved exploration: <path>` line is recorded as the item's evidence.

#### 5.2.5 `:via :read-only`

In-loop reads via `read-file`, `grep`, `query$llm`. No external dispatch.

#### 5.2.6 `:via :manual`

STOP the inner loop. Record the item as `:ok? :manual-pending`. The dossier surfaces it as a `Manual:` line in the answer with the user instruction.

### 5.3 Verify, then flip (index-free)

For routes that produced a write or external side-effect, sanity check **before**
flipping the checkbox:

- `:edit-agent` — already runs its own verify; trust the `:ok?` flag (no `Rolled back:`).
- `:bash` — non-zero exit code = NOT ok.
- `:mcp` — error in response = NOT ok.
- `:explore-agent` — always succeeds (read-only); only fails on agent error.
- `:read-only` / `:manual` — N/A.

If `:ok?` is true → flip the box **index-free** by matching the item's unique
description text, then reconcile:

```clojure
(update-file {:path        (:todo-path pre)
              :pattern     "- [ ] <unique item text>"
              :replacement "- [x] <unique item text>"})
(todo$sync {:path (:todo-path pre)})   ; reconcile progress + refresh the TUI
```

Match on enough of the item's description to be UNIQUE — never a drifting numeric
index. If `:ok?` is false → leave the box un-flipped, record the failure, and let
POST-FLIGHT decide. NEVER flip a box without supporting evidence.

> This route → verify → record → flip discipline is exactly the **exec substrate**
> (§5 of this section is its contract form; §5 elsewhere — the substrate protocol —
> is its base form). Any agent gets it inline from the base system context (§8);
> exec-agent additionally gates and audits it.

### 5.4 Record

Every per-item iteration appends a structured evidence block to the in-memory
`execute.evidence` map (keyed by item index; persisted in the dossier at the end of
the turn):

```clojure
(swap! !evidence assoc idx
  {:idx        idx
   :description (:description item)
   :via        (-> item :tags :via)
   :covers     (-> item :tags :covers)
   :ok?        (:ok? whatever-record)
   :evidence   {:type :edit-agent
                :path "<edit-agent record path>"
                :rollback "<cmd>"}
   :surprises  []                  ; LLM observation
   :follow-ups []})                ; LLM observation
```

### 5.5 Loop Termination

The inner loop stops when any of:

- `:max-items-per-turn` reached (soft stop — record `:budget-exhausted`).
- `:pending = 0` (terminal — recommend `doc$update :status :completed` in the `Done:` answer line).
- A hard blocker: missing creds, manual item surfaced, repeated failures on the same item, user halt request.

---

## 6. POST-FLIGHT — Confirmation Check (NEW)

Runs after EXECUTE. Re-reads the run record and grades it against a fixed rubric.

### 6.1 The Rubric

| Item | Rubric question                                                                                                                                                                              | Pass criterion                                                                                                              |
| ---- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| R1   | Every item that flipped done has a non-trivial evidence record (not just "ok").                                                                                       | Each `evidence.path` resolves OR `evidence.type ∈ {:bash :mcp :read-only}` with non-empty `:stdout-tail` / response excerpt. |
| R2   | No checkbox flip ran for items where the route returned `:ok? false`.                                                                                                                  | Cross-check `evidence.ok?` against the todo's checkbox state for items advanced this turn.                                   |
| R3   | For every `:via :edit-agent` item, the underlying edit-agent record's `verify.diff_match = true`.                                                                                       | `edit$read-record :path <evidence.path>` → check `verify.diff_match`.                                                     |
| R4   | Every advanced item's `:tags.covers` lists at least one criterion that this turn now has positive evidence for.                                                                              | Cross-reference with `pre.acceptance-cov`.                                                                                  |
| R5   | No item produced more than one rollback this turn (loop guard against thrashing).                                                                                                            | Count rollbacks per `idx`.                                                                                                  |
| R6   | If the loop hit a hard blocker, the dossier names the blocker AND a concrete handoff (plan-agent / todo-agent / user).                                                                        | Inspect the run record's tail.                                                                                              |
| R7   | No advanced item was an `:via :manual` item that secretly slipped past the manual gate (would mean the agent did the work without surfacing).                                                | Sanity check.                                                                                                                |

### 6.2 Verdict (v1 — PASS / HOLD)

- **PASS** — every advanced item has clean evidence, every applicable rubric item passes.
- **HOLD** — one or more rubric failures, or a hard-blocker route. Record the specific holds in the dossier; surface in the answer; STOP the agent.

> **As-built:** the shipped verdict is **PASS / HOLD only**. The REVISE auto-retry
> (one `reset-item` + re-execute round on a single failing item, then re-run R1–R7)
> is **deferred to v1.5** — in v1, any rubric failure lands in HOLD with no
> auto-retry. The `post` map below omits `:revision-applied?`/`:revision-summary` in
> practice (they stay nil when present).

### 6.3 The `post` Map

```clojure
(def post
  {:verdict           :pass
   :rubric            {:r1 :pass :r2 :pass :r3 :pass :r4 :pass
                       :r5 :pass :r6 :pass :r7 :pass}
   :revision-applied? false
   :revision-summary  nil
   :holds             []
   :advanced          [{:idx 0 :ok? true :evidence {…}}
                       {:idx 1 :ok? true :evidence {…}}]
   :pending-after     [2 3]
   :acceptance-progress
                      {"feature-flag checkout-v2 toggleable from staging admin" :evidence-recorded
                       "all checkout/* unit tests green"                         :partial   ; only item 1, item 2 still pending
                       "p99 checkout latency unchanged within ±5%"               :pending}})
```

`post.acceptance-progress` is the structured handoff to eval-agent.

---

## 7. Output Discipline — `.brainyard/agents/exec-agent/`

### 7.1 Directory Layout

```
.brainyard/
├── exec-agent/
│   ├── dossiers/                  ; one evidence dossier per execution turn — what eval-agent reads
│   │   ├── 20260510-110131-ship-v2-checkout.md
│   │   └── 20260510-114502-ship-v2-checkout.md
│   ├── INDEX.md                   ; one append-only line per dossier
│   └── README.md
```

**As-built:** there is **no `runs/` directory** and no separate verbose run-record
artifact. The earlier proposal split a verbose `runs/<…>.md` per-iteration log from
the `dossiers/<…>.md` handoff; that split was dropped before landing. Exec-agent
emits a **single dossier** per turn, whose body carries the per-item execution log
inline and whose `execute.evidence` map carries the structured evidence. (`exec.clj`
documents this v1 narrowing — "no separate verbose `runs/` dir".)

### 7.2 Dossier Schema (eval-agent's input) — authored directly as markdown

The instruction carries the DOSSIER TEMPLATE verbatim. The model fills the `<…>`
slots and `write-file`s it to `dossiers/<yyyyMMdd-HHmmss>-<slug>.md`. There is **no**
`exec$dossier-frontmatter` / `exec$dossier-write` construction step — the model
writes the markdown. The `pre`/`execute`/`post`/`handoff` blocks are INDENTED YAML
blocks; `checks`/`rubric`/`budget`/`evidence`/`acceptance_progress` are one-line flow
maps; `acceptance`/`holds`/`items_advanced`/`items_pending_after` are one-line flow
vectors — exactly what `exec$read-dossier` parses back for eval-agent.

```markdown
---
slug: ship-v2-checkout
agent: exec-agent
created: 2026-05-10T11:01:31Z
todo_path: .brainyard/agents/todo-agent/todos/ship-v2-checkout.md
plan_path: .brainyard/agents/plan-agent/plans/ship-v2-checkout.md
todo_dossier: .brainyard/agents/todo-agent/dossiers/20260510-105612-ship-v2-checkout.md
plan_dossier: .brainyard/agents/plan-agent/dossiers/20260510-104503-ship-v2-checkout.md

pre:
  verdict: go
  checks: {c1: pass, c2: pass, c3: pass, c4: pass, c5: pass, c6: pass, c7: pass, c8: informational}
  acceptance: ["feature-flag checkout-v2 toggleable from staging admin", "all checkout/* unit tests green", "p99 checkout latency unchanged within ±5%"]
  gather_question: null
  refuse_reason: null

execute:                       # OMIT this whole block when no EXECUTE ran (gather/refuse)
  budget: {max_items_per_turn: 5, used: 3, reason_for_stop: hard-blocker}
  items_advanced: [0, 1, 2]
  items_pending_after: [3]
  evidence: {0: "ok via edit-agent: .brainyard/agents/edit-agent/edits/20260510-110205-...md", 1: "ok via edit-agent: .brainyard/agents/edit-agent/edits/20260510-110318-...md", 2: "ok via bash: exit 0; Ran 18 tests, 0 failures"}

post:                          # OMIT this whole block when no EXECUTE ran
  verdict: pass
  rubric: {r1: pass, r2: pass, r3: pass, r4: pass, r5: pass, r6: n/a, r7: pass}
  holds: []
  acceptance_progress: {"feature-flag checkout-v2 toggleable from staging admin": evidence-recorded, "all checkout/* unit tests green": evidence-recorded, "p99 checkout latency unchanged within ±5%": pending}

handoff:
  next_agent: exec-agent          # not yet done — item 3 still pending
  next_call: '(exec-agent {:question "Continue from item 3." :agent-context "<this dossier path>"})'
---

# Exec dossier — Ship v2 checkout (turn 20260510-110131)

## Pre-flight summary
All hard checks passed; no resume-from prior dossier.

## Execution log
- 0 · edit-agent · ✅ → edit applied + verified · .brainyard/agents/edit-agent/edits/20260510-110205-...md
- 1 · edit-agent · ✅ → edit applied + verified; one follow-up surfaced
- 2 · bash · ✅ → 18 tests, 0 failures
- 3 · manual · ⏸ → sample p99 from Grafana dashboard <url>

## Post-flight notes
All rubric items passed. Acceptance progress: 2/3 criteria have evidence; the third (p99) waits on the manual item.

## Handoff
Item 3 is manual — surface to user. Once user supplies the p99 reading, re-invoke exec-agent with this dossier as `:agent-context` and item 3 will close. After that, eval-agent for the verdict.
```

Frontmatter contract (eval-agent depends on these; keys match `exec$read-dossier`'s
parse so eval-agent is unaffected by the redesign):

| Key                          | Type                       | Description                                                                                                |
| ---------------------------- | -------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `slug`                       | string                     | Shared with plan / todo when canonical pair.                                                               |
| `agent`                      | string                     | Always `exec-agent`.                                                                                       |
| `todo_dossier`               | string                     | Path to the consumed todo-agent dossier.                                                                   |
| `plan_dossier`               | string                     | Path to the source plan-agent dossier.                                                                     |
| `pre.*`                      | map                        | Pre-flight verdict + checks (§4.2). Block omitted of EXECUTE keys when GATHER/REFUSE.                       |
| `execute.items_advanced`     | flow vector of int         | Item idxs flipped this turn. (Block omitted entirely when no EXECUTE ran.)                                 |
| `execute.items_pending_after`| flow vector of int         | Item idxs still pending after this turn.                                                                   |
| `execute.evidence`           | flow map idx → evidence    | The structured per-item evidence — eval-agent's primary input.                                             |
| `post.*`                     | map                        | Post-flight verdict + rubric (§6.3). (Block omitted when no EXECUTE ran.)                                  |
| `post.acceptance_progress`   | flow map criterion → status| `evidence-recorded` \| `partial` \| `pending` \| `contradicted`.                                          |
| `handoff.next_agent`         | string                     | `exec-agent` (continue) when items remain; `eval-agent` when `:pending = 0`; `user` on HOLD/gather/manual. |
| `handoff.next_call`          | string                     | Exact direct kebab-case dispatch form.                                                                     |

There is **no `run_record` frontmatter key** (no run-record artifact ships). The
`handoff` block is filled from a fixed 4-case rule table in the instruction (no
`exec$next-handoff` helper). `evidence` keyed by item index is safe here — it is a
*record*, not an addressing scheme the model must mutate later; the index-drift
hazard was only in *flipping* by index, which §4 removed.

### 7.3 ANSWER Format

The shipped answer emits **only `Saved dossier:`** (no `Saved run:` line). `Next:`
uses direct kebab-case dispatch, e.g. `(eval-agent {…})`. Stable lines at the end:

```
Saved dossier: <dossier path>
Manual: <one line per :manual item surfaced this turn>     (when applicable)
Next: <handoff.next_call>
```

GATHER / REFUSE / HOLD variants follow the same family pattern (`Need:` / `Refused:`
/ `Hold:` + `Suggested:`).

When all items are done AND POST-FLIGHT PASS:

```
Saved dossier: <dossier path>
Done: <slug> — all <N> items advanced; recommend doc$update :status :completed + eval-agent.
Next: (eval-agent {:question "Score this todo against its plan." :agent-context "<dossier path>"})
```

---

## 7b. The Exec Substrate — Installed in the Base Agents (as-built)

The route → verify → record → flip discipline, the evidence-before-flip rule, and
safe-write-via-edit-agent are not exec-agent's alone. They ship as a base
system-context protocol so **any** derived agent can DO a checklist inline — no
exec-agent dispatch for routine ("working") execution.

**As-built wiring** (verify against `common/agent_roster.clj`,
`common/coact_agent.clj`, `common/react_agent.clj`):

- `agent_roster.clj` defines `exec-substrate-protocol` — the `## Executing a
  checklist (exec substrate)` system-context section.
- It is inserted as an `:exec-substrate` section into **both**
  `coact-system-context` (`coact_agent.clj`, alongside `:todo-substrate`) and
  `react-system-context` (`react_agent.clj`). One guidance edit per base agent.
- **No roster change was needed.** The tools the protocol references —
  `edit-agent`, `bash`, `mcp$tools`, `update-file`, `read-file`, `todo$sync` —
  already ride `default-agent-roster`, which `run-coact-derived` concatenates onto
  every derived agent. The tools half was already done; the redesign supplied the
  missing guidance half.

The substrate text (as shipped) layers execution over the **todo substrate** (which
supplies the checklist format + the index-free flip + `todo$sync`):

```text
## Executing a checklist (exec substrate)
To DO a checklist item (not just track it), follow route → verify → record → flip:

1. ROUTE — decide how the item gets done:
   • source edit  → DELEGATE to edit-agent: (edit-agent {:question "<item>"
                    :agent-context "<context>" :dirty-ok? "false"}). It diffs,
                    verifies, returns `Saved edit: <path>` + `Rollback: <cmd>`.
                    Prefer this over raw write-file for tracked source.
   • shell check  → (bash {:command "<cmd>"}); ok = exit 0.
   • external/MCP → (mcp$tools …); reads proceed, writes need user confirm.
   • lookup       → read-file / grep / query$llm; keep a short evidence excerpt.
   • manual       → STOP, surface it, do NOT flip.

2. VERIFY — confirm it actually worked (edit-agent diff_match, exit 0, recorded excerpt).
3. RECORD — note the evidence (edit path + rollback, command + exit, excerpt).
4. FLIP — only after VERIFY passes: update-file "- [ ] <text>" → "- [x] <text>"
   on the checklist (match line TEXT, never an index), then todo$sync to reconcile.

RULES:
- NEVER flip a box without supporting evidence (step 2).
- Prefer edit-agent for tracked-source writes (reversible + verified).
- Bound your work: a few items per turn, then summarize and continue.

This is working execution — yours to run inline. Reserve exec-agent for CONTRACT
execution: gated on passed plan+todo dossiers, bounded, audited with an evidence
dossier eval-agent consumes.
```

So a single base-level edit gives every agent — `main-agent` included — the ability
to execute its own checklist with evidence and safe writes, no dispatch. §8.1 below
draws the working-vs-contract line.

---

## 8. Instruction (System Prompt Body) — as-built

Layered on top of `coact-agent`'s instruction by `run-coact-derived`. The full
shipped prompt lives in `common/exec_agent.clj` (the `instruction` def); the sketch
below is the as-built shape. Key realities vs. the earlier proposal: it produces a
**single dossier** (no `Saved run:` line), flips boxes **index-free** via
`update-file` + `todo$sync` (no `:item-idx` flip), dispatches other agents **direct
kebab-case** (`(edit-agent {…})`, not `(call-tool "edit-agent" …)`), and its
POST-FLIGHT verdict is **PASS / HOLD** (no REVISE).

```text
You are an EXEC-agent. You drive a todo list to completion item-by-item.
You ALWAYS pre-flight before executing, and post-flight after. You
DELEGATE every write to edit-agent. You ALWAYS produce a dossier — even
when you refuse or stop early.

Dossiers at .brainyard/agents/exec-agent/dossiers/. Plans / todos at .brainyard/agents/plan-agent/
.brainyard/agents/todo-agent/ respectively.

────────────────────────────────────────────────────────────────────────────
THE THREE PHASES (every turn)
────────────────────────────────────────────────────────────────────────────
PRE-FLIGHT  — sufficiency check. Output: GO | GATHER | REFUSE.
EXECUTE     — only on GO. Inner loop bounded by :max-items-per-turn
              (default 5). Per item: pick → route → verify → record →
              flip checkbox (index-free).
POST-FLIGHT — only after EXECUTE. Self-critique against a 7-item rubric.
              Output: PASS | HOLD.   (REVISE auto-retry deferred to v1.5.)
PERSIST     — always. ONE dossier under .brainyard/agents/exec-agent/dossiers/.
ANSWER      — `Saved dossier:`, optional `Manual:`/`Done:`/`Hold:`, `Next:`.

────────────────────────────────────────────────────────────────────────────
PRE-FLIGHT CHECKLIST (short-circuit on first fail)
────────────────────────────────────────────────────────────────────────────
C1. TODO DOSSIER. :agent-context contains `Saved dossier: <path>` for a
    todo-agent dossier. Fallback: a todo slug + :todo-ok? true.

C2. TODO POST-FLIGHT PASSED. todo$read-dossier; assert post.verdict :pass.
    :hold → REFUSE. :revise → GATHER unless user opted in.

C3. PLAN POST-FLIGHT PASSED. Read source.plan_dossier from todo dossier;
    plan$read-dossier; assert post.verdict :pass.

C4. TODO PRESENT. todo$exists + todo$status; cross-check item count.
    Mismatch → REFUSE.

C5. ROUTING TAGS. Every pending item has :tags.via in
    {:edit-agent :bash :mcp :explore-agent :read-only :manual}.
    Missing → GATHER ("retag items K…M via todo-agent").

C6. TOOLS AVAILABLE.
    For :bash items: bash "command -v <inferred binary>".
    For :mcp items: mcp$server :op :list — confirm :connected.
    Missing → GATHER (single fix), or REFUSE.

C7. WORKING TREE. If any pending item is :via :edit-agent, run
    `bash "git status --porcelain"`. Empty → clean. Non-empty AND
    :dirty-ok? not set → GATHER ("commit/stash first or pass :dirty-ok?").

C8. RESUME. (exec$find :slug <slug>) for prior dossiers. Latest's
    post.advanced + post.holds populates pre.resume-from. INFORMATIONAL
    — never blocks. Drives the EXECUTE loop's prioritization (re-attempt
    held items first; skip already-done items).

Stash `pre` (§4.2 schema).

────────────────────────────────────────────────────────────────────────────
EXECUTE — only on GO
────────────────────────────────────────────────────────────────────────────
Inner loop: bounded by :max-items-per-turn (default 5).

For each pending item (re-attempting held items from pre.resume-from first):

  1. PICK — doc$read :kind :todo (or todo$sync) to confirm next-item;
     cross-check against the dossier's items list.

  2. ROUTE on item :tags.via (direct kebab-case dispatch):

     :edit-agent →
        (edit-agent {:question      <item description>
                     :agent-context (str "Saved dossier: " (:plan-dossier pre)
                                         " — item idx " idx ", covers " <covers>)
                     :run-tests?    false
                     :dirty-ok?     <pre :dirty-ok?>})
        Extract `Saved edit: <path>` and `Rollback: <cmd>` from
        edit-agent's answer. :ok? = no `Rolled back:` line.

     :bash →
        (bash {:command <command or inferred from :description>
               :timeout 30000})   ; :ok? = exit 0.

     :mcp →
        (mcp$tools {:op :call :tool-calls [<one call>]})
        Read-only tools: proceed. Write-side tools (create/update/
        delete/send/post/execute): STOP, surface to user, do NOT flip
        the checkbox.

     :explore-agent →
        (explore-agent {:question <description>
                        :agent-context (:plan-dossier pre)})
        Always :ok? true (exploration is read-only).

     :read-only →
        Inline reads via read-file / grep / query$llm. Stash a short
        excerpt as evidence.

     :manual →
        STOP. Record :ok? :manual-pending. Surface in the `Manual:`
        answer line. Do NOT flip the checkbox.

  3. VERIFY (per route, see above). If :ok? false → leave checkbox
     un-flipped, record failure, let POST-FLIGHT decide.

  4. RECORD — assoc a per-item evidence block into the in-memory
     execute.evidence map (keyed by idx).

  5. FLIP (index-free) — only on :ok? true:
        (update-file {:path "<todo path>"
                      :pattern "- [ ] <unique item text>"
                      :replacement "- [x] <unique item text>"})
        (todo$sync {:path "<todo path>"})   ; reconcile + refresh TUI
     Match on unique line TEXT — NEVER a drifting numeric index.

  6. ADVANCE — loop until :max-items-per-turn, :pending = 0, or a hard
     blocker (manual item, repeat failure, missing creds discovered
     mid-loop).

NEVER write SOURCE files directly — write-file is unbound; delegate every
source edit to edit-agent. update-file is bound ONLY for the checkbox flip
on the todo markdown (never source). NEVER call doc$update :body (plans).
NEVER call doc$create :kind :todo (todo-agent's domain).

Stash:
   (def execute
     {:budget {:max-items-per-turn N :used K
               :reason-for-stop :pending-complete | :budget | :hard-blocker}
      :items-advanced [0 1 2]
      :items-pending-after [3]
      :evidence {0 {…} 1 {…} 2 {…}}})

────────────────────────────────────────────────────────────────────────────
POST-FLIGHT RUBRIC
────────────────────────────────────────────────────────────────────────────
R1. EVIDENCE PRESENT — every flipped item has non-trivial evidence.
R2. NO FALSE FLIPS — only :ok? true items got flipped.
R3. DIFF MATCH — for :edit-agent items, the underlying record's
    verify.diff_match = true.
R4. PROGRESS — every advanced item's :tags.covers names a criterion
    that this turn now has positive evidence for.
R5. NO THRASHING — no item produced > 1 rollback this turn.
R6. BLOCKER NAMED — if loop hit a blocker, the dossier names it AND
    a concrete handoff.
R7. NO SILENT MANUAL — no :via :manual item was secretly executed.

VERDICT (v1 — REVISE auto-retry deferred to v1.5):
- All applicable pass → PASS.
- 1+ fail            → HOLD. Record specific holds in the dossier;
                       surface in the answer. Do NOT auto-retry.

Stash `post` (§6.3 schema). Build acceptance_progress map.

────────────────────────────────────────────────────────────────────────────
PERSIST + ANSWER
────────────────────────────────────────────────────────────────────────────
Fill the DOSSIER TEMPLATE and write-file ONE dossier:
   .brainyard/agents/exec-agent/dossiers/<yyyyMMdd-HHmmss>-<slug>.md
Then append ONE line to .brainyard/agents/exec-agent/INDEX.md
(write-file :append true). Do NOT construct frontmatter maps or call
dossier helpers — WRITE THE MARKDOWN.

ANSWER (PASS, items remain):
    Saved dossier: <dossier-path>
    Next: (exec-agent {:question "Continue." :agent-context "<dossier-path>"})

ANSWER (PASS, all done):
    Saved dossier: <dossier-path>
    Done: <slug> — all N items advanced; recommend doc$update :status :completed + eval-agent.
    Next: (eval-agent {:question "Score this todo." :agent-context "<dossier-path>"})

ANSWER (manual surfaced):
    Saved dossier: <dossier-path>
    Manual: item <idx> — <description>; supply result then re-invoke.
    Next: (exec-agent {…} with this dossier as :agent-context)

GATHER / REFUSE / HOLD follow the same `Saved dossier:` + Need/Refused/Hold +
Suggested family pattern.

────────────────────────────────────────────────────────────────────────────
HARD RULES
────────────────────────────────────────────────────────────────────────────
1. NO direct SOURCE writes. Every source edit delegated to edit-agent.
   write-file is NOT bound. update-file is bound ONLY for the index-free
   checkbox flip on the todo markdown — never on source.
2. NO mutating plans. Plans read-only.
3. NO mutating todos beyond {item flips, item resets, add-item, abandon,
   complete}. Authoring is todo-agent's domain (doc$create :kind :todo
   NOT bound here).
4. NO clone-self dispatch. Direct kebab-case dispatch to other registered
   agents is fine ((edit-agent {…}), (explore-agent {…}), (mcp$tools …),
   (todo-agent {…})).
5. NO writing outside .brainyard/agents/ (the dossier + the checkbox flip).
6. NO flipping a checkbox without supporting evidence. R2 enforces.
7. NO write-side MCP without explicit user confirmation.
8. NEVER skip the dossier — even REFUSE turns produce one. The auto-persist
   hook is a safety net, NOT a license to forget.
```

---

## 9. Tool-Context

```text
## Exec Tools — drive a todo and write evidence (as-built)

TODO CHECKLIST — flip boxes INDEX-FREE (the todo is a markdown file)
- update-file "- [ ] <unique text>" → "- [x] <unique text>"  -- flip a completed
              item by line TEXT (never a drifting index), then
              (todo$sync {:path "<todo path>"}) to reconcile + refresh the TUI.
              update-file is bound ONLY for this flip — never for source.
- doc$list :kind :todo                  -- enumerate.
- doc$read :kind :todo :slug <s>        -- load the active todo (or just read-file).
- doc$update :kind :todo :slug <s> :status :completed  -- only after :pending = 0
                                                          AND user confirms.
- doc$update :kind :todo :slug <s> :status :abandoned  -- confirm.

NOT BOUND (deliberate):
- write-file                            -- DELEGATE source writes to edit-agent.
- doc$create :kind :todo / :kind :plan  -- todo-agent / plan-agent.
- doc$update :kind :plan / :body        -- plans are read-only here.
- doc$delete                            -- destructive; lifecycle owners.

PLAN ACCESS (READ-ONLY)
- plan$read-dossier   -- Args: path. THE primary pre-flight tool for C3.
- doc$read :kind :plan :slug <s>

TODO DOSSIER ACCESS (READ-ONLY)
- todo$read-dossier   -- Args: path. THE primary pre-flight tool for C1/C2.

CROSS-AGENT DISPATCH (direct kebab-case — the core of execution)
- (edit-agent {…})    -- every source-write item; parse Saved edit / Rollback.
- (explore-agent {…})   -- :via :explore-agent items.
- (mcp$tools {…})       -- :via :mcp items (write-side requires user confirm).
- bash                  -- :via :bash items only.

DISCOVERY (in-loop reads only)
- read-file, grep, search, query$llm
- list-tools, get-tool-info

PERSISTENCE — write markdown directly (NO dossier-construction tools)
- Author the evidence dossier from the DOSSIER TEMPLATE with ONE write-file
  under dossiers/, then append one INDEX line (write-file :append true). There
  are NO exec$dossier-* / slug / frontmatter / next-handoff helpers — the
  handoff block comes from the 4-case rule table in the instruction.
- exec$read-dossier :path <p>  -- READ-ONLY frontmatter parse (downstream
  eval-agent + you inspecting a prior dossier).
- exec$find :slug <s>          -- READ-ONLY: prior dossiers newest-first, for
  resume (skip already-advanced items, retry held ones — see C8).

AUTO-PERSIST SAFETY NET
A gated :agent.ask/finalize hook (scoped to :exec-agent) fills the DOSSIER
TEMPLATE from your answer text, writes it, and injects the absent
`Saved dossier:` line if you skip PERSIST (it also replaces a hallucinated
path). A backstop — author your own dossier.

## Typical end-to-end flow per turn
1. Parse :question and :agent-context (a `Saved dossier:` for todo).
2. PRE-FLIGHT C1–C8. Stash `pre`. Short-circuit on first fail.
3. If GATHER/REFUSE → skip EXECUTE/POST-FLIGHT, jump to PERSIST + ANSWER.
4. EXECUTE inner loop bounded by :max-items-per-turn. Per item:
   pick → route per :tags.via → verify → record → flip the box index-free
   via update-file (match line text) + todo$sync.
5. POST-FLIGHT rubric R1–R7. v1 has no auto-retry — failures land in HOLD.
   Build acceptance_progress map. Stash `post`.
6. PERSIST one dossier; INDEX append.
7. ANSWER — `Saved dossier:` + (`Done:` | `Manual:` | `Hold:`) + `Next:`.
```

---

## 10. Behavior Tree — Inherited As-Is

Same as plan-agent / todo-agent. No new BT. `:bt-factory` is pinned explicitly to
`coact/coact-behavior-tree` (mirroring explore/plan/todo/edit-agent) so
direct-resolution entry points pick up the correct CoAct BT. Iteration shape:

| Iter         | Channel       | Body                                                                         |
| ------------ | ------------- | ---------------------------------------------------------------------------- |
| 1            | code          | PRE-FLIGHT C1–C8. `def pre`.                                                  |
| 2..2+K-1     | tool / code   | (only on GO) EXECUTE inner loop, K = items-this-turn (≤ max-items-per-turn). Per item: pick → route → verify → record → flip index-free. |
| 2+K          | code          | POST-FLIGHT rubric (PASS/HOLD; no auto-retry). `def post`.                    |
| 2+K+1        | code          | PERSIST one dossier; INDEX append.                                           |
| 2+K+2        | answer        | `Saved dossier:` + (`Manual:`\|`Done:`\|`Hold:`) + `Next:`.                  |

---

## 11. Demonstration: "Drive ship-v2-checkout to completion"

`:agent-context = "Saved dossier: .brainyard/agents/todo-agent/dossiers/20260510-105612-ship-v2-checkout.md"`. Suppose this is the first exec turn — pre.resume-from is nil; all 4 items pending.

### Iteration 1 — PRE-FLIGHT

```clojure
(def todo-dossier
  (todo$read-dossier
    {:path ".brainyard/agents/todo-agent/dossiers/20260510-105612-ship-v2-checkout.md"}))

(def plan-dossier
  (plan$read-dossier {:path (-> todo-dossier :source :plan_dossier)}))

(def pre
  {:verdict :go
   :checks {:c1 :pass :c2 :pass :c3 :pass :c4 :pass :c5 :pass :c6 :pass
            :c7 :pass :c8 :informational}
   :todo-dossier ".brainyard/agents/todo-agent/dossiers/...md"
   :todo-path    (:todo_path todo-dossier)
   :todo-slug    (:slug todo-dossier)
   :plan-dossier (-> todo-dossier :source :plan_dossier)
   :plan-path    (-> todo-dossier :source :plan_path)
   :plan-slug    (-> todo-dossier :source :plan_slug)
   :acceptance   (-> todo-dossier :pre :acceptance)
   :acceptance-cov (-> todo-dossier :post :acceptance_coverage)
   :resume-from nil})
```

### Iteration 2..4 — EXECUTE (3 items advanced this turn)

For each pending item idx in [0 1 2 3] (item 3 is `:via :manual` so loop stops at 3):

**idx 0 — `:via :edit-agent`**

```clojure
(def edit-0
  (edit-agent {:question "Wire LD flag `checkout-v2` in src/checkout/flags.clj"
               :agent-context (str "Saved dossier: " (:plan-dossier pre))
               :run-tests? false
               :dirty-ok? "false"}))

(def evid-0
  {:idx 0
   :via :edit-agent
   :covers ["feature-flag checkout-v2 toggleable from staging admin"]
   :ok? (not (re-find #"Rolled back:" (:answer edit-0)))
   :evidence {:type :edit-agent
              :path     (last (re-find #"Saved edit: (\S+)" (:answer edit-0)))
              :rollback (last (re-find #"Rollback: (.+)" (:answer edit-0)))}})

;; flip the box INDEX-FREE on the todo markdown, then reconcile:
(when (:ok? evid-0)
  (update-file {:path (:todo-path pre)
                :pattern "- [ ] Wire LD flag `checkout-v2` in src/checkout/flags.clj"
                :replacement "- [x] Wire LD flag `checkout-v2` in src/checkout/flags.clj"})
  (todo$sync {:path (:todo-path pre)}))
```

**idx 1 — `:via :edit-agent`** (similar)

**idx 2 — `:via :bash`**

```clojure
(def b-2 (bash {:command "bb test:component checkout"}))
(def evid-2
  {:idx 2 :via :bash :ok? (zero? (:exit-code b-2))
   :evidence {:type :bash
              :command "bb test:component checkout"
              :exit (:exit-code b-2)
              :stdout-tail (subs (:output b-2) 0 (min 500 (count (:output b-2))))}})
(when (:ok? evid-2)
  (update-file {:path (:todo-path pre)
                :pattern "- [ ] Run bb test:component checkout"
                :replacement "- [x] Run bb test:component checkout"})
  (todo$sync {:path (:todo-path pre)}))
```

**idx 3 — `:via :manual`** — STOP, record:

```clojure
(def evid-3
  {:idx 3 :via :manual :ok? :manual-pending
   :evidence {:type :manual
              :description "Sample p99 from Grafana dashboard <url>"
              :user-action "Read p99 (over 24h window) and reply with the value."}})
;; do NOT flip the checkbox.
```

`execute` map is assembled:

```clojure
(def execute
  {:budget {:max-items-per-turn 5 :used 4 :reason-for-stop :hard-blocker}
   :items-advanced [0 1 2]
   :items-pending-after [3]
   :evidence {0 evid-0 1 evid-1 2 evid-2 3 evid-3}})
```

### Iteration 5 — POST-FLIGHT

```clojure
(def post
  {:verdict :pass
   :rubric  {:r1 :pass :r2 :pass :r3 :pass :r4 :pass :r5 :pass
             :r6 :n/a :r7 :pass}
   :holds []
   :advanced [evid-0 evid-1 evid-2]
   :pending-after [3]
   :acceptance-progress
   {"feature-flag checkout-v2 toggleable from staging admin" :evidence-recorded
    "all checkout/* unit tests green"                         :evidence-recorded
    "p99 checkout latency unchanged within ±5%"               :pending}})
```

### Iteration 6 — PERSIST (write the dossier markdown directly)

The model fills the §7.2 DOSSIER TEMPLATE and `write-file`s it — no helper chain:

```clojure
(def ts (.format (java.text.SimpleDateFormat. "yyyyMMdd-HHmmss") (java.util.Date.)))
(def path (str ".brainyard/agents/exec-agent/dossiers/" ts "-ship-v2-checkout.md"))

(write-file
  {:path path
   :content (str
     "---\nslug: ship-v2-checkout\nagent: exec-agent\n"
     "created: " (java.time.Instant/now) "\n"
     "todo_path: " (:todo-path pre) "\nplan_path: " (:plan-path pre) "\n"
     "todo_dossier: " (:todo-dossier pre) "\nplan_dossier: " (:plan-dossier pre) "\n\n"
     "pre:\n  verdict: go\n  checks: {c1: pass, c2: pass, c3: pass, c4: pass, c5: pass, c6: pass, c7: pass, c8: informational}\n"
     "  acceptance: [" #_… "]\n  gather_question: null\n  refuse_reason: null\n\n"
     "execute:\n  budget: {max_items_per_turn: 5, used: 4, reason_for_stop: hard-blocker}\n"
     "  items_advanced: [0, 1, 2]\n  items_pending_after: [3]\n"
     "  evidence: {0: \"ok via edit-agent: …\", 1: \"ok via edit-agent: …\", 2: \"ok via bash: exit 0\"}\n\n"
     "post:\n  verdict: pass\n  rubric: {r1: pass, r2: pass, r3: pass, r4: pass, r5: pass, r6: n/a, r7: pass}\n"
     "  holds: []\n  acceptance_progress: {…}\n\n"
     "handoff:\n  next_agent: exec-agent\n"
     "  next_call: '(exec-agent {:question \"Continue from item 3.\" :agent-context \"" path "\"})'\n"
     "---\n\n# Exec dossier — Ship v2 checkout\n…")})

;; INDEX append (append-only)
(write-file
  {:path ".brainyard/agents/exec-agent/INDEX.md"
   :append true
   :content (str "- " (subs (str (java.time.Instant/now)) 0 16)
                 " [ship-v2-checkout](dossiers/" ts "-ship-v2-checkout.md)"
                 " — pre:go · post:pass · [+3 / -1] · → exec-agent\n")})
```

### Iteration 7 — ANSWER

```markdown
## Exec turn — Ship v2 checkout

PRE-FLIGHT: GO (all 8 checks; no resume-from)
EXECUTE: advanced items 0, 1, 2 (3 of 4 pending). Stopped at item 3 (manual).
POST-FLIGHT: PASS (rubric R1–R7 all pass; one item still pending)

Acceptance progress:
- "feature-flag checkout-v2 toggleable from staging admin" → evidence-recorded
- "all checkout/* unit tests green" → evidence-recorded
- "p99 checkout latency unchanged within ±5%" → pending (item 3 manual)

Saved dossier: .brainyard/agents/exec-agent/dossiers/20260510-110131-ship-v2-checkout.md
Manual: item 3 — Sample p99 from Grafana dashboard <url>; supply result then re-invoke.
Next: (exec-agent {:question "Continue from item 3 with this p99 reading: <value>." :agent-context ".brainyard/agents/exec-agent/dossiers/20260510-110131-ship-v2-checkout.md"})
```

When the user replies with the p99 reading, exec-agent's next turn pre-flights, uses `exec$find` (C8) to see items 0–2 already advanced, jumps directly to item 3, completes it (with evidence in the manual user reply), and the dossier ends with `Next: (eval-agent …)`.

---

## 12. `exec.clj` — Read Seams Only

`exec.clj` (sibling of `plan.clj` / `todo.clj`) ships exactly **two** read seams in
`exec-dossier-helpers`, auto-bound in the SCI sandbox:

| Helper | Signature | What it does |
|---|---|---|
| `exec$read-dossier` | `(exec$read-dossier :path …)` → parsed frontmatter map (`pre`/`execute`/`post`/`handoff` sub-blocks, incl. `acceptance_progress`, `items_advanced`) | Frontmatter-only parse — the downstream contract for eval-agent and for inspecting a prior dossier. |
| `exec$find` | `(exec$find :slug …)` → `{:matches [{:path :slug :created :pre_verdict :post_verdict :advanced :pending} …] :n-matches N}` | Prior exec dossiers for a slug, newest-first. Powers resume (C8): skip already-advanced items, retry held ones. |

**Retired** (do not exist as tools anymore): `exec$dossier-slug`,
`exec$dossier-frontmatter`, `exec$dossier-write`, `exec$dossier-index-append`,
`exec$next-handoff`. There is **no** `exec$run-write` / `exec$preflight` /
`exec$postflight` / `exec$item-route` — those were aspirational and never shipped.
Authoring is a direct `write-file` from the §7.2 template; the handoff block comes
from the instruction's 4-case rule table.

> Note: `exec.clj` still keeps the YAML *emitter* + parser internals **private** —
> not as agent tools, but to back the auto-persist hook (§12.1), which fills the
> same template and `spit`s one file. The pure `handoff-from-state` fn is likewise
> kept private to drive the hook's `Next:` line. Only the two readers above are
> exposed as tools.

### 12.1 Auto-Persist Backstop

`exec.clj` installs an `:agent.ask/finalize` hook (gated to `:exec-agent`) that
materializes a dossier when the LLM skips PERSIST. It is **not** the primary path —
a safety net — and writes the *same* one-file path the happy path uses:

- `materialize-auto-dossier!` reconstructs a §7.2-template dossier from regex-detected
  verdicts/paths (`detect-pre-verdict`, `detect-post-verdict`, `detect-done?`,
  `one-line-summary`) via `render-exec-dossier-md`, `spit`s **one** file under
  `dossiers/`, and appends one INDEX line.
- It injects the absent `Saved dossier: <path>` line into the answer (and replaces a
  hallucinated path that doesn't resolve on disk).
- It is idempotent (an on-disk-existence check) and self-installs at namespace load.

Because the hook is observe-only on the answer text, a missing `Saved dossier:` line
does **not** mean nothing was saved.

The former §12 "Optional `exec$*` helpers" — the `exec$item-route` /
`exec$preflight` / `exec$postflight` one-call mega-helpers and the inner-loop
collapse sketch they enabled — is removed: none of them shipped. The model writes
the route → verify → record → flip steps inline per §5.

---

## 13. Handoff Mechanics

Eval-agent reads the exec-agent dossier in `:agent-context`. Two read levels (cheap frontmatter via `exec$read-dossier`; full body via `read-file`). The frontmatter gives:

- `plan_dossier` and `todo_dossier` for cross-references.
- `execute.evidence` — the structured per-item map (edit-agent record paths, bash exit/excerpts).
- `post.acceptance_progress` — the criterion-status map. Eval-agent extends this with its verdict.
- The per-item evidence entries link back to edit-agent records for diff-level audit.

For multi-turn exec runs (item-3-manual case in §11), each turn produces its own dossier; eval-agent's pre-flight reads the latest. `exec$find` (C8) threads turns together — skip already-advanced items, retry held ones.

---

## 14. Migration — Complete

The redesign landed without breaking eval-agent or existing dossiers:

1. **New `exec_agent.clj` instruction/roster.** Three-phase pipeline kept; the
   EXECUTE recording step swapped to the index-free `update-file` + `todo$sync` flip,
   and PERSIST swapped to a direct `write-file` from the DOSSIER TEMPLATE.
   `write-file` stays unbound; `update-file`/`read-file` were added (the `remove`
   clause now strips only `:write-file` + `:fetch-url`).
2. **Slimmed `exec.clj`.** The four write-side dossier helpers + `exec$next-handoff`
   were removed from `exec-dossier-helpers` (now just `exec$read-dossier` +
   `exec$find`); the emitter/parser internals were kept private to back the
   simplified auto-persist hook.
3. **On-disk dossier schema unchanged** (keys match `exec-agent-design.md` §7.2), so
   existing exec dossiers stay readable and **eval-agent is untouched**.
4. **The exec substrate** was installed in the base agents behind the same mechanism
   as the todo substrate (a shared protocol string + an `:exec-substrate` section in
   `coact-system-context` and `react-system-context`), with no roster change since
   the tools already ride `default-agent-roster`.

The index-free flip depends on the todo redesign's substrate (the checklist format +
`todo$sync`), which co-landed.

---

## 15. Verification

| Benchmark                              | Shape                                                                                         | What it verifies                                                                                                                  |
| -------------------------------------- | --------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| Pre-flight GO happy path               | Valid todo dossier in :agent-context; all routing tags present; tree clean                    | All C1–C8 pass; EXECUTE runs; POST-FLIGHT PASS; one dossier with full evidence map.                                              |
| Pre-flight GATHER (no todo dossier)    | :agent-context = ""                                                                          | C1 fails; agent recommends todo-agent; dossier records GATHER.                                                                    |
| Pre-flight REFUSE (todo HOLD)          | Todo dossier post.verdict :hold                                                              | C2 fails; agent refuses with the holds.                                                                                           |
| Pre-flight GATHER (missing routing)    | One pending item lacks :tags.via                                                              | C5 fails; agent suggests todo-agent retag.                                                                                        |
| Pre-flight GATHER (dirty tree)         | git status non-empty; pending :edit-agent items                                             | C7 fails; agent suggests stash or :dirty-ok?.                                                                                     |
| Execute :edit-agent route happy path | One :via :edit-agent item                                                                  | (edit-agent …) invoked direct kebab-case; Saved edit / Rollback parsed; checkbox flipped index-free.                            |
| Execute :edit-agent rollback         | edit-agent rolls back due to verify failure                                                 | :ok? false; checkbox NOT flipped; POST-FLIGHT → HOLD (no auto-retry in v1).                                                       |
| Execute :bash route                    | One :via :bash item                                                                           | bash invoked; exit-code 0 → flip; non-zero → no flip.                                                                             |
| Execute :mcp read-only                 | One :via :mcp <s>:<read-tool>                                                                | Proceeds without confirmation.                                                                                                    |
| Execute :mcp write-side                | One :via :mcp <s>:<write-tool>                                                              | STOPS; surfaces the proposed call; checkbox NOT flipped.                                                                          |
| Execute :explore-agent route           | Mid-loop discovery item                                                                       | explore-agent invoked; Saved exploration: parsed; recorded as evidence; checkbox flipped.                                          |
| Execute :manual route                  | One :via :manual item                                                                        | Inner loop stops; `Manual:` line in answer; checkbox NOT flipped.                                                                  |
| **Flip — index-free**                  | Execute an item, flip its box                                                                  | `update-file` matches line text; `todo$sync` shows completed+1 + correct next-item; st-memory refreshed.                          |
| **Insert then flip**                   | Add an item mid-loop, flip an *earlier* item by text                                           | The right box flips — the test that today's `:item-idx` drift would fail.                                                          |
| Resume from prior dossier              | Prior dossier exists; some items :hold                                                       | C8 informational via `exec$find`; held items prioritized; previously-done items skipped.                                          |
| Post-flight HOLD                       | One or more failed items                                                                       | No auto-retry (v1); dossier records holds; answer surfaces.                                                                       |
| Acceptance progress completeness       | All items advanced                                                                            | acceptance_progress map has every criterion either evidence-recorded or pending; eval-agent can score.                            |
| Single dossier per turn                | Single turn                                                                                   | Exactly one dossier under dossiers/; `exec$read-dossier` returns all contract keys; no `runs/` artifact.                          |
| Downstream unchanged                   | Feed the dossier to an eval-agent fixture                                                      | Reads `acceptance_progress` / `items_advanced` exactly as before.                                                                |
| **Substrate**                          | main-agent (with the substrate) drives a working checklist                                     | Routes a write through edit-agent, verifies, flips index-free, reconciles — zero exec-agent dispatch.                            |
| Auto-persist backstop                  | Skipped dossier                                                                                | Hook materializes a parseable one from answer text; `Saved dossier:` line injected.                                              |
| Index integrity                        | Append 100 dossiers                                                                           | INDEX.md has 100 lines, append-only.                                                                                             |

mulog signals (as-built): `::exec.find`, `::exec.dossier-index`, `::exec.auto-persist`,
`::exec.auto-persist-failed`, plus the redesign's `::exec.item`
(`{:slug :idx :via :ok :flipped}`) and `::exec.sync`.

---

## 16. Files Summary

| File                                                                                       | What changes                                                                                                                                                                  |
| ------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `components/agent/src/ai/brainyard/agent/common/exec_agent.clj`                            | The contract executor — three-phase pipeline (PRE-FLIGHT / EXECUTE / POST-FLIGHT), instruction + tool-context per §8 / §9, single-dossier emission per §7. `write-file` unbound; `update-file`/`read-file` bound for the index-free flip. |
| `components/agent/src/ai/brainyard/agent/common/exec.clj`                                  | Read seams (`exec$read-dossier`, `exec$find`) + the `:agent.ask/finalize` auto-persist hook. Write-side dossier helpers retired; emitter/parser internals kept private to back the hook. |
| `components/agent/src/ai/brainyard/agent/common/agent_roster.clj`                          | Defines `exec-substrate-protocol` — the `## Executing a checklist` base system-context section. |
| `components/agent/src/ai/brainyard/agent/common/coact_agent.clj`                           | Installs `:exec-substrate` into `coact-system-context` (next to `:todo-substrate`). No BT/sandbox/DSPy change. |
| `components/agent/src/ai/brainyard/agent/common/react_agent.clj`                           | Installs `:exec-substrate` into `react-system-context`. |
| `components/agent/test/…/exec_agent_test.clj` (planned)                                    | Registration smoke + index-free-flip / resume / substrate / auto-persist tests per §15 (not yet present in the test tree). |
| `.brainyard/agents/exec-agent/README.md`                                                   | Directory layout cheat-sheet. |
| `docs/design/exec-agent-design.md`                                                         | THIS FILE. |
| `components/agent/src/ai/brainyard/agent/common/eval_agent.clj`                            | Unchanged by this redesign — the dossier schema (§7.2) is a superset, so eval-agent consumes it unmodified. |

---

## 17. Open Questions

1. **`:max-items-per-turn` default.** Shipped default is 5. Too low → many turns; too high → token bloat per turn + harder to debug. Instrument and revisit.
2. **Auto-retry (REVISE) for v1.5.** v1 is PASS / HOLD — any rubric failure HOLDs. v1.5 adds one corrective pass on a single failing item. A natural extension: allow N retries when items are :via :bash (cheap) but only 1 for :edit-agent (slow + risky). Defer to instrumentation.
3. **Should exec-agent be allowed to add items mid-loop?** Today todo-agent owns SPAWN; exec-agent has a limited add-item surface for on-the-fly splits. A stricter alternative: STOP and dispatch todo-agent for mid-loop additions. Trade-off: deliberation vs. throughput. Stick with current model; if mid-loop additions correlate with HOLD turns in benchmarks, tighten.
4. **Per-turn vs. per-batch dossier.** A long-running execution could span 5+ turns. Current design: one dossier per turn. Alternative: a single rolling dossier that exec-agent appends to. Per-turn is simpler and gives natural rollback granularity; rolling is leaner on disk. Stick with per-turn.
5. **Should exec-agent gate on a CI run, not just bash test?** Some plans should not be considered "advanced" until a CI build passes. Could add a `:via :ci <pipeline-id>` route. Out of scope for v1; revisit when the first plan needs it.
6. **Manual-item resumption ergonomics.** Today the user re-invokes exec-agent with the dossier path AND a fresh question carrying the manual reading. Could be smoother — e.g. an `exec$resume-with-manual :item-idx N :evidence "..."` helper. Defer.
7. **One combined "Working checklists" substrate section, or two?** The exec substrate and the todo substrate are installed as separate `:exec-substrate` / `:todo-substrate` base sections. One combined "track + execute" section reads better; two keeps tracking vs. doing separable. Align with the same decision in the todo doc.
8. **Should the substrate's "prefer edit-agent" be advisory or enforced?** A `:agent.tool-use/pre` hook could refuse a base-agent `write-file` on tracked source and nudge to edit-agent. Stronger guarantee; bigger blast radius. For exec-agent specifically the question is moot — `write-file` is simply unbound. Prototype for the broader fleet.
9. **Auto-sync on flip?** A `:agent.tool-use/post` hook on `update-file` targeting `todos/*.md` could auto-`todo$sync`. Cleaner, but couples the hook registry to the path convention. Same question as the todo doc — answer them together.
