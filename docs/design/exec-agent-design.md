# Exec-Agent — Pre-flight & Post-flight Gated Execution with Per-Item Routing & Dossier Evidence (CoAct-derived)

> **Status:** Shipped — `exec-agent` is registered in `components/agent` (`common/exec_agent.clj`). This document is the original design proposal (revision 2); the shipped implementation may diverge in details. See [core/agent.md](../core/agent.md) for the current roster.
>
> **As-built (verify against `common/exec_agent.clj`, `common/exec.clj`):**
> - **No `runs/` directory and no separate run-record artifact.** The design's split of `runs/<…>.md` (verbose per-iteration log, §7.2) from `dossiers/<…>.md` was **not built**. Exec-agent emits a **single dossier** under `.brainyard/agents/exec-agent/dossiers/<ts>-<slug>.md`; per-item evidence rides in the dossier's `execute.evidence` map. There is **no `exec$run-write` helper** and no `run_record` frontmatter key. The eval-agent doc's `exec_run_record` reference is therefore usually nil in practice.
> - **POST-FLIGHT verdict is PASS / HOLD only.** The design's REVISE auto-retry (§6.2, one `todo$reset-item` + re-execute round) is **deferred to v1.5**.
> - **Cross-agent dispatch is direct kebab-case** — `(edit-agent {…})`, `(explore-agent {…})`, `(todo-agent {…})` — though the registry also makes them reachable via `call-tool`. Hard Rule 4 reads "NO clone-self dispatch." Checkbox flips use `(doc$update {:kind :todo … :item-idx N :item-done true})`, not `todo$update-item`.
> - **`write-file` / `update-file` are NOT bound** — every write delegates to edit-agent (Hard Rule 1, as designed).
> - **Shipped helper roster:** `exec$dossier-slug`, `exec$dossier-frontmatter`, `exec$dossier-write`, `exec$dossier-index-append`, `exec$read-dossier`, `exec$find`, `exec$next-handoff`. No `exec$run-write` / `exec$preflight` / `exec$postflight` / `exec$item-route` helpers shipped (§12's helper list is aspirational).
> - **An `:agent.ask/post` auto-persist hook** (not in this design) writes a minimal dossier from the answer text if the helpers were skipped, and catches hallucinated `Saved dossier:` paths.
> **Scope:** redesign of `components/agent/src/ai/brainyard/agent/common/exec_agent.clj`
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Sibling of:** `plan-agent`, `todo-agent`, `eval-agent`, `edit-agent`, `explore-agent`
> **Related reading:** `docs/plan-agent-design.md` (dossier schema is the template), `docs/todo-agent-design.md` (per-item routing tags), `docs/edit-agent-design.md` (the safe-edit pipeline exec delegates to), `docs/explore-agent-design.md`, `docs/eval-agent-design.md`

> **API rename (2026-05):** the per-verb `todo$list/read/create/update-item/update-goal/add-item/status/complete/abandon/reopen/reset-item/delete/exists` shims have been removed. Use the polymorphic `doc$*` family with `:kind :todo`. See `docs/design/todo-agent-design.md` (frontmatter note) for the verb-by-verb mapping. The dossier helpers (`todo$read-dossier`, `todo$dossier-*`, `todo$next-handoff`, etc.) are NOT deprecated. The body below still uses the old names for historical clarity.

---

## 1. Motivation

The current `exec-agent` (`components/agent/.../common/exec_agent.clj`) drives a todo list to completion: pick the next pending item, do the work, flip the checkbox, capture per-item detail in `:answer`, advance. The instruction is good — it tells the LLM to write rich per-item records ("done what, surprises, follow-ups") because eval-agent reads `:answer` as evidence. Three problems surface in practice:

1. **No pre-flight guard on what the executor was handed.** The agent jumps straight to `todo$read` and starts running. There is no pass that asks: *Does the source plan exist, did its post-flight pass, did todo-agent's post-flight pass, do the items have routing tags I can act on, are the required tools/permissions present, is the working tree clean for items that will write to disk?* When any of these is false, the executor blunders ahead and the failure mode is opaque ("eval-agent says it failed; not sure why").
2. **Writes are inlined, not delegated.** Today exec-agent calls `update-file` / `write-file` directly inside its loop. With `edit-agent` now landed as the specialist for safe single-file edits (`docs/edit-agent-design.md`), exec-agent should `(call-tool "edit-agent" …)` for every write — getting the safety pipeline (probe → apply → verify → rollback → record) for free. The current instruction has no such delegation rule.
3. **Per-item evidence is captured in `:answer` only.** Eval-agent reads it as a string, parses it heuristically, and hopes the executor wrote enough. There is no schema'd per-item record on disk that can be re-read independently of the chat trajectory. This makes exec runs that span multiple turns hard to evaluate (the evidence sits in turn-N's answer; eval-agent in turn-N+1 has to be hand-fed it).

The same redesign also folds in the layout migration begun by `plan-agent` and `todo-agent`. Today exec-agent has no on-disk artifact at all — its evidence lives only in `:answer`. The redesign adds `.brainyard/agents/exec-agent/runs/<slug>-<ts>.md` (one record per execution turn) and `.brainyard/agents/exec-agent/dossiers/<slug>-<ts>.md` (the cumulative handoff to eval-agent).

**Thesis.** Redesign `exec-agent` so every execution turn runs through a fixed three-phase pipeline:

1. **PRE-FLIGHT (sufficiency check)** — does the agent have everything it needs? Plan dossier + todo-agent dossier on hand, items routed, tools available, working tree clean (for items that will write). Output: GO / GATHER / REFUSE.
2. **EXECUTE** — pick next pending item; route per `:tags.via`; perform the work, delegating writes to `edit-agent`, reads to `bash` / `read-file` / `query$llm`, MCP calls to `mcp$tools`, manual items to surfacing-and-stopping. Flip the checkbox via `todo$update-item`. Repeat per turn budget.
3. **POST-FLIGHT (confirmation check)** — for every item advanced this turn, did we have supporting evidence? Did delegated agents (e.g., edit-agent) succeed? Did any verification fail? Output: PASS / REVISE (re-do a failing item) / HOLD.

Every run produces:
- A **run record** under `.brainyard/agents/exec-agent/runs/<slug>-<ts>.md` — the per-turn execution log (which items advanced, what edit-agent records were produced, surprises).
- A **dossier** under `.brainyard/agents/exec-agent/dossiers/<slug>-<ts>.md` — the cumulative handoff to eval-agent, including links to all per-item edit-agent records and a structured per-item evidence map.

Same minimal-diff principle. Sandbox, BT, DSPy untouched.

---

## 2. Design Principles

1. **Delegate writes to edit-agent, always.** Exec-agent NEVER calls `update-file` or `write-file` directly. Items tagged `:via :edit-agent` route through `(call-tool "edit-agent" …)` and inherit its probe/verify/rollback pipeline. Items that need writes but lack the tag → STOP and ask todo-agent to retag.
2. **Per-item routing is data, not LLM judgement.** Route via `author.items[].tags.via` from the todo-agent dossier. The allowed set: `:edit-agent` (writes), `:bash` (read-only shell), `:mcp <server>:<tool>` (MCP), `:explore-agent` (mid-execution discovery), `:read-only` (just `read-file` / `grep` / `query$llm`), `:manual` (user must do this — STOP, surface).
3. **Evidence on disk, not in chat.** Every per-item action is recorded in the run-record file (with the same shape eval-agent expected from `:answer`, but now schema'd). The dossier aggregates run records and is what eval-agent reads.
4. **One auto-revise per turn.** If POST-FLIGHT fails on a single item (e.g., edit-agent rolled back), the agent attempts ONE corrective pass: `todo$reset-item`, re-execute. After that → HOLD.
5. **Stop conditions are explicit.** Hard blockers (missing creds, ambiguous spec, item lacks routing tag, would touch shared/production state without confirmation) STOP the loop with a structured handoff back to plan-agent / todo-agent / user. Soft stops (turn budget exhausted, user pause request) are recorded so the next turn can resume.
6. **Layout matches the rest of the ecosystem.** Runs and dossiers under `.brainyard/agents/exec-agent/`. Todo file path moves are todo-agent's responsibility (`docs/todo-agent-design.md` §14); exec-agent reads from wherever todo-agent points.
7. **Plans and todos are read-only.** Exec-agent reads via `plan$read-dossier`, `plan$read`, `todo$read-dossier`, `todo$read`, `todo$status`. It writes only via `todo$update-item` / `todo$add-item` / `todo$reset-item` / `todo$abandon` / `todo$complete`. NEVER mutates plans.
8. **No clone-self recursion.** No `query$clone`. Cross-agent dispatch via `(call-tool …)` is fine — to edit-agent, explore-agent, mcp-agent.
9. **Resume cleanly across turns.** Every dossier carries enough state (last item attempted, blockers, partial results) that a fresh exec-agent invocation in turn N+1 can pre-flight, see the prior dossier, and pick up where it left off.

---

## 3. Position in the Agent Stack

See `docs/plan-agent-design.md` §3 for the full pipeline diagram. Exec-agent sits in the third slot:

```
plan-agent → ... → todo-agent → Saved dossier: <todo-agent dossier path>
                                              │
                                              ▼
exec-agent → Saved run:     <exec-agent run record path>
            → Saved dossier: <exec-agent dossier path>
                                              │
                                              ▼
eval-agent → ...
```

Within an exec turn, each item that needs a write fans out to edit-agent:

```
exec-agent (loop iter N)
  pick item K with :tags.via :edit-agent
  → (call-tool "edit-agent" {:question "..."
                                :agent-context "<exec-agent dossier-so-far>"})
       → returns Saved edit: <edit-agent record>
                 Rollback:   <git checkout cmd>
  → record link in run record
  → todo$update-item :slug <todo> :item-idx K (only if :ok? true)
```

The link from exec evidence to the underlying edit-agent record means eval-agent can drill from "did acceptance criterion C pass?" → "yes, item 1 covers it" → "exec record 20260510-...md" → "edit-agent record 20260510-...md" → the actual diff. Full audit trail.

---

## 4. PRE-FLIGHT — Sufficiency Check (NEW)

Runs before any `todo$update-item` or `(call-tool …)` call. Walks a fixed checklist; produces a `pre` map.

### 4.1 The Checklist

| Check | What it verifies                                                                                                            | How                                                                                                                                                                                                  | Fail → action                                                                                                                                |
| ----- | --------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| C1    | A todo-agent dossier was supplied.                                                                                         | Look for `Saved dossier: <path>` in `:agent-context`. Fallback: a todo slug + `:todo-ok? true` override (e.g., legacy todos pre-redesign).                                                          | GATHER — recommend `(call-tool "todo-agent" {…})` first.                                                                                     |
| C2    | The todo-agent dossier's post-flight passed.                                                                                | `todo$read-dossier :path <path>` → check `post.verdict = :pass`.                                                                                                                                     | If `:hold` → REFUSE with the holds; if `:revise` and not user-opted-in → GATHER.                                                             |
| C3    | The plan-agent dossier referenced by todo-agent's dossier exists and post.verdict = :pass.                                  | Read `source.plan_dossier` from todo dossier; `plan$read-dossier`; assert `post.verdict = :pass`.                                                                                                    | If missing or :hold → REFUSE (plan-agent re-do first).                                                                                       |
| C4    | The todo file is actually present and matches the dossier's slug.                                                           | `todo$exists` + `todo$status`; cross-check item count against dossier.                                                                                                                               | REFUSE — todo and dossier diverged; recommend a fresh todo-agent run.                                                                        |
| C5    | Every pending item has a `:tags.via` routing tag in the allowed set.                                                        | Iterate the items. Allowed: `:edit-agent` `:bash` `:mcp <server>:<tool>` `:explore-agent` `:read-only` `:manual`.                                                                                  | GATHER — recommend `(call-tool "todo-agent" {:question "Retag items K…M."})`.                                                                |
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

`todo$status` → next-item. If `pre.resume-from` shows held items, prioritize them (re-attempt) before pending items.

### 5.2 Route

Read `tags.via` for the picked item. Each route has a fixed call shape:

#### 5.2.1 `:via :edit-agent`

```clojure
(def edit-result
  (call-tool "edit-agent"
             {:question      (:description item)
              :agent-context (str "Saved dossier: " (:plan-dossier pre)
                                  "\n\nFor item idx " idx ", target file inferred from "
                                  "the plan ## References. Acceptance covered: "
                                  (clojure.string/join ", " (-> item :tags :covers)))
              :run-tests?    false      ; or true when the item description says "and verify"
              :dirty-ok?     false}))
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
  (call-tool "bash" {:command (:command item)        ; or inferred from :description
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
  (call-tool "mcp$tools"
             {:op :call
              :tool-calls [{:server-name (:server (:via (:tags item)))
                            :tool-name   (:tool (:via (:tags item)))
                            :tool-args   (:tool-args item)}]}))
```

Read-only MCP calls (search/list/get/show/read) proceed; write-side MCP calls (create/update/delete/send/post/execute) STOP and surface the proposed call to the user — same rule explore-agent uses.

#### 5.2.4 `:via :explore-agent`

When an item description like "discover where the X middleware lives" is best handled by mid-execution discovery:

```clojure
(def explore-result
  (call-tool "explore-agent"
             {:question      (:description item)
              :agent-context (:plan-dossier pre)}))
```

The `Saved exploration: <path>` line lands in the run record.

#### 5.2.5 `:via :read-only`

In-loop reads via `read-file`, `grep`, `query$llm`. No external dispatch.

#### 5.2.6 `:via :manual`

STOP the inner loop. Record the item as `:status :manual-pending` in the run record. The dossier surfaces it as a `Manual:` line in the answer with the user instruction.

### 5.3 Verify

For routes that produced a write or external side-effect, sanity check before flipping the checkbox:

- `:edit-agent` — already runs its own verify; trust the `:ok?` flag.
- `:bash` — non-zero exit code = NOT ok.
- `:mcp` — error in response = NOT ok.
- `:explore-agent` — always succeeds (read-only); only fails on agent error.
- `:read-only` / `:manual` — N/A.

If `:ok?` is true → `todo$update-item :slug … :item-idx <idx>`. If false → leave checkbox un-flipped, record the failure, and let POST-FLIGHT decide whether to auto-retry once.

### 5.4 Record

Every per-item iteration appends a structured block to the run record (in memory; persisted at the end of the turn):

```clojure
(swap! !run conj
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

- `:max-items-per-turn` reached (soft stop — record `:status :budget-exhausted`).
- `:pending = 0` (terminal — recommend `todo$complete` in answer).
- A hard blocker: missing creds, manual item surfaced, repeated failures on the same item, user halt request.

---

## 6. POST-FLIGHT — Confirmation Check (NEW)

Runs after EXECUTE. Re-reads the run record and grades it against a fixed rubric.

### 6.1 The Rubric

| Item | Rubric question                                                                                                                                                                              | Pass criterion                                                                                                              |
| ---- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| R1   | Every item that flipped done in `todo$update-item` has a non-trivial evidence record (not just "ok").                                                                                       | Each `evidence.path` resolves OR `evidence.type ∈ {:bash :mcp :read-only}` with non-empty `:stdout-tail` / response excerpt. |
| R2   | No `todo$update-item` ran for items where the route returned `:ok? false`.                                                                                                                  | Cross-check `evidence.ok?` against the todo's checkbox state for items advanced this turn.                                   |
| R3   | For every `:via :edit-agent` item, the underlying edit-agent record's `verify.diff_match = true`.                                                                                       | `edit$read-record :path <evidence.path>` → check `verify.diff_match`.                                                     |
| R4   | Every advanced item's `:tags.covers` lists at least one criterion that this turn now has positive evidence for.                                                                              | Cross-reference with `pre.acceptance-cov`.                                                                                  |
| R5   | No item produced more than one rollback this turn (loop guard against thrashing).                                                                                                            | Count rollbacks per `idx`.                                                                                                  |
| R6   | If the loop hit a hard blocker, the dossier names the blocker AND a concrete handoff (plan-agent / todo-agent / user).                                                                        | Inspect the run record's tail.                                                                                              |
| R7   | No advanced item was an `:via :manual` item that secretly slipped past the manual gate (would mean the agent did the work without surfacing).                                                | Sanity check.                                                                                                                |

### 6.2 Verdict

- **PASS** — every advanced item has clean evidence, every rubric item passes.
- **REVISE** — at most ONE item failed verification; auto-retry once: `todo$reset-item`, re-execute the item, re-verify. Re-run R1–R7 once.
- **HOLD** — multiple failures, persistent failure after one retry, or hard-blocker route. Surface in the answer; STOP the agent.

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
│   ├── runs/                      ; one file per execution turn — verbose
│   │   ├── 20260510-110131-ship-v2-checkout.md
│   │   └── 20260510-114502-ship-v2-checkout.md
│   ├── dossiers/                  ; cumulative handoff records — what eval-agent reads
│   │   ├── 20260510-110131-ship-v2-checkout.md
│   │   └── 20260510-114502-ship-v2-checkout.md
│   ├── drafts/
│   ├── INDEX.md
│   └── README.md
```

The split between `runs/` and `dossiers/` is a tradeoff: runs are verbose per-iteration logs (every read, every command output excerpt); dossiers are the polished, schema'd handoff that eval-agent consumes. They share a slug + timestamp so they pair 1:1.

### 7.2 Run Record Schema

```markdown
---
slug: ship-v2-checkout
agent: exec-agent
created: 2026-05-10T11:01:31Z
turn_id: <id>
session_id: <id>
todo_path: .brainyard/agents/todo-agent/todos/ship-v2-checkout.md
plan_path: .brainyard/agents/plan-agent/plans/ship-v2-checkout.md
items_advanced: [0, 1, 2]
items_pending_after: [3]
items_failed_this_turn: []
budget:
  max_items_per_turn: 5
  used: 3
  reason_for_stop: pending_complete
---

# Exec run — Ship v2 checkout (turn 20260510-110131)

## Item 0 — "Wire LD flag `checkout-v2` in src/checkout/flags.clj"
- via: :edit-agent
- covers: ["feature-flag checkout-v2 toggleable from staging admin"]
- edit-agent record: .brainyard/agents/edit-agent/edits/20260510-110205-wire-ld-flag-checkout-v2.md
- verify.diff_match: true
- rollback: `git checkout -- src/checkout/flags.clj`
- ok: ✅
- surprises: none
- follow-ups: none

## Item 1 — "Update payment validator for legacy carts"
- via: :edit-agent
- covers: ["all checkout/* unit tests green"]
- edit-agent record: .brainyard/agents/edit-agent/edits/20260510-110318-update-payment-validator.md
- verify.diff_match: true
- rollback: `git checkout -- src/checkout/payment_validator.clj`
- ok: ✅
- surprises: legacy carts had a hidden `:type :guest` branch; covered by the
  same condition. Recorded as a follow-up to add a regression test.
- follow-ups: add `payment_validator_test.clj` case for `:type :guest`.

## Item 2 — "Run bb test:component checkout"
- via: :bash
- command: `bb test:component checkout`
- exit: 0
- stdout-tail: "Ran 18 tests, 0 failures."
- ok: ✅
- surprises: none
- follow-ups: none
```

### 7.3 Dossier Schema (eval-agent's input)

```markdown
---
slug: ship-v2-checkout
agent: exec-agent
created: 2026-05-10T11:01:31Z
run_record: .brainyard/agents/exec-agent/runs/20260510-110131-ship-v2-checkout.md
todo_dossier: .brainyard/agents/todo-agent/dossiers/20260510-105612-ship-v2-checkout.md
plan_dossier: .brainyard/agents/plan-agent/dossiers/20260510-104503-ship-v2-checkout.md
todo_path:    .brainyard/agents/todo-agent/todos/ship-v2-checkout.md
plan_path:    .brainyard/agents/plan-agent/plans/ship-v2-checkout.md
turn_id: <id>
session_id: <id>

pre:
  verdict: go
  checks:
    c1_todo_dossier:    pass
    c2_todo_postflight: pass
    c3_plan_postflight: pass
    c4_todo_present:    pass
    c5_routing_tags:    pass
    c6_tools_available: pass
    c7_tree_clean:      pass
    c8_resume:          informational
  resume_from: null

execute:
  budget: {max_items_per_turn: 5, used: 3}
  items_advanced: [0, 1, 2]
  items_pending_after: [3]
  evidence:
    0: {ok: true, via: edit-agent,
        update_record: .brainyard/agents/edit-agent/edits/20260510-110205-...md,
        rollback: "git checkout -- src/checkout/flags.clj",
        covers: ["feature-flag checkout-v2 toggleable from staging admin"]}
    1: {ok: true, via: edit-agent,
        update_record: .brainyard/agents/edit-agent/edits/20260510-110318-...md,
        rollback: "git checkout -- src/checkout/payment_validator.clj",
        covers: ["all checkout/* unit tests green"],
        follow_ups: ["add payment_validator_test.clj case for :type :guest"]}
    2: {ok: true, via: bash,
        command: "bb test:component checkout",
        exit: 0,
        stdout_tail: "Ran 18 tests, 0 failures.",
        covers: ["all checkout/* unit tests green"]}

post:
  verdict: pass
  rubric:
    r1_evidence_present:    pass
    r2_no_false_flips:      pass
    r3_diff_match:          pass
    r4_progress_on_acceptance: pass
    r5_no_thrashing:        pass
    r6_blocker_named:       n/a
    r7_no_silent_manual:    pass
  revision_applied: false
  revision_summary: null
  holds: []
  acceptance_progress:
    "feature-flag checkout-v2 toggleable from staging admin": evidence-recorded
    "all checkout/* unit tests green":                        evidence-recorded
    "p99 checkout latency unchanged within ±5%":              pending

handoff:
  next_agent: exec-agent          # not yet done — items 3 still pending
  next_call: '(call-tool "exec-agent" {:question "Continue from item 3." :agent-context "<this dossier path>"})'
---

# Exec dossier — Ship v2 checkout (turn 20260510-110131)

## Pre-flight summary
All 7 hard checks passed; no resume-from prior dossier.

## Execution summary
Advanced 3/4 items this turn. Item 3 (manual Grafana sample) is :via :manual and was surfaced for user action.

## Per-item evidence
- 0 ✅ → edit-agent edit applied + verified
- 1 ✅ → edit-agent edit applied + verified; one follow-up surfaced
- 2 ✅ → 18 tests, 0 failures
- 3 ⏸ → manual: sample p99 from Grafana dashboard <url>

## Post-flight notes
All rubric items passed. Acceptance progress: 2/3 criteria have evidence; the third (p99) waits on the manual item.

## Handoff
Item 3 is manual — surface to user. Once user supplies the p99 reading, re-invoke exec-agent with this dossier as `:agent-context` and item 3 will close. After that, eval-agent for the verdict.
```

Frontmatter contract (eval-agent depends on these):

| Key                          | Type                       | Description                                                                                                |
| ---------------------------- | -------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `slug`                       | string                     | Shared with plan / todo when canonical pair.                                                               |
| `agent`                      | string                     | Always `exec-agent`.                                                                                       |
| `run_record`                 | string                     | Path to the verbose run-record companion.                                                                  |
| `todo_dossier`               | string                     | Path to the consumed todo-agent dossier.                                                                   |
| `plan_dossier`               | string                     | Path to the source plan-agent dossier.                                                                     |
| `pre.*`                      | map                        | Verbatim copy of `pre` (§4.2).                                                                             |
| `execute.items_advanced`     | vector of int              | Item idxs flipped this turn.                                                                               |
| `execute.items_pending_after`| vector of int              | Item idxs still pending after this turn.                                                                   |
| `execute.evidence`           | map idx → evidence record  | The structured per-item evidence — eval-agent's primary input.                                             |
| `post.*`                     | map                        | Verbatim copy of `post` (§6.3).                                                                            |
| `post.acceptance_progress`   | map criterion → status     | `evidence-recorded` \| `partial` \| `pending` \| `contradicted`.                                          |
| `handoff.next_agent`         | string                     | `exec-agent` (continue) when items remain; `eval-agent` when `:pending = 0`; `user` on HOLD/manual.        |
| `handoff.next_call`          | string                     | Exact `(call-tool …)` form.                                                                                |

### 7.4 ANSWER Format

> **As-built:** the shipped answer emits **only `Saved dossier:`** — there is no `Saved run:` line (no run-record artifact ships). `Next:` uses direct kebab-case dispatch, e.g. `(eval-agent {…})`, not `(call-tool "eval-agent" {…})`.

Stable lines at the end (as shipped):

```
Saved dossier: <dossier path>
Manual: <one line per :manual item surfaced this turn>     (when applicable)
Next: <handoff.next_call>
```

GATHER / REFUSE / HOLD variants follow the same family pattern.

When all items are done AND POST-FLIGHT PASS:

```
Saved dossier: <dossier path>
Done: <slug> — all <N> items advanced; recommend doc$update :status :completed + eval-agent.
Next: (eval-agent {:question "Score this todo against its plan." :agent-context "<dossier path>"})
```

---

## 8. Instruction (System Prompt Body)

Layered on top of `coact-agent`'s instruction.

```text
You are an EXEC-agent. You drive a todo list to completion item-by-item.
You ALWAYS pre-flight before executing, and post-flight after. You
DELEGATE every write to edit-agent. You ALWAYS produce a run record AND
a dossier.

Runs at .brainyard/agents/exec-agent/runs/, dossiers at
.brainyard/agents/exec-agent/dossiers/. Plans / todos at .brainyard/agents/plan-agent/
.brainyard/agents/todo-agent/ respectively.

────────────────────────────────────────────────────────────────────────────
THE THREE PHASES (every turn)
────────────────────────────────────────────────────────────────────────────
PRE-FLIGHT  — sufficiency check. Output: GO | GATHER | REFUSE.
EXECUTE     — only on GO. Inner loop bounded by :max-items-per-turn
              (default 5). Per item: pick → route → verify → record →
              flip checkbox.
POST-FLIGHT — only after EXECUTE. Self-critique against a 7-item rubric.
              Output: PASS | REVISE | HOLD.
PERSIST     — always. Run record AND dossier under .brainyard/agents/exec-agent/.
ANSWER      — `Saved run:`, `Saved dossier:`, optional `Manual:`/`Done:`,
              `Next:`.

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

  1. PICK — todo$status to confirm next-item; cross-check against the
     dossier's items list.

  2. ROUTE on item :tags.via:

     :edit-agent →
        (call-tool "edit-agent"
                   {:question      <item description>
                    :agent-context (str "Saved dossier: " (:plan-dossier pre)
                                        " — item idx " idx ", covers "
                                        <covers list>)
                    :run-tests?    false
                    :dirty-ok?     <pre :dirty-ok?>})
        Extract `Saved edit: <path>` and `Rollback: <cmd>` from
        edit-agent's answer. :ok? = no `Rolled back:` line.

     :bash →
        (call-tool "bash" {:command <command from item :command or
                                       inferred from :description>
                           :timeout 30000})
        :ok? = exit 0.

     :mcp →
        (call-tool "mcp$tools" {:op :call :tool-calls [<one call>]})
        Read-only tools: proceed. Write-side tools (create/update/
        delete/send/post/execute): STOP, surface to user, do NOT flip
        the checkbox.

     :explore-agent →
        (call-tool "explore-agent" {:question <description>
                                    :agent-context (:plan-dossier pre)})
        Always :ok? true (exploration is read-only).

     :read-only →
        Inline reads via read-file / grep / query$llm. Stash a short
        excerpt as evidence.

     :manual →
        STOP. Record :status :manual-pending. Surface in the
        `Manual:` answer line. Do NOT flip the checkbox.

  3. VERIFY (per route, see above). If :ok? false → leave checkbox
     un-flipped, record failure, let POST-FLIGHT decide.

  4. RECORD — append a per-item block to the in-memory run.

  5. FLIP — only on :ok? true: todo$update-item :slug <todo>
     :item-idx <idx>.

  6. ADVANCE — loop until :max-items-per-turn, :pending = 0, or a hard
     blocker (manual item, repeat failure, missing creds discovered
     mid-loop).

NEVER call update-file / write-file directly. NEVER call
plan$update-* / plan$create / plan$delete. NEVER call
todo$create / todo$delete / todo$reopen.

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
R6. BLOCKER NAMED — if loop hit a blocker, the run record names it AND
    a concrete handoff.
R7. NO SILENT MANUAL — no :via :manual item was secretly executed.

VERDICT:
- All pass → PASS.
- 1 item failed verification → REVISE. ONE retry: todo$reset-item, re-
  execute, re-verify. If still failing → HOLD.
- Multiple failures or hard-blocker route → HOLD.

Stash `post` (§6.3 schema). Build acceptance_progress map.

────────────────────────────────────────────────────────────────────────────
PERSIST + ANSWER
────────────────────────────────────────────────────────────────────────────
Write run record:
   .brainyard/agents/exec-agent/runs/<yyyyMMdd-HHmmss>-<slug>.md
Write dossier:
   .brainyard/agents/exec-agent/dossiers/<yyyyMMdd-HHmmss>-<slug>.md
PREPEND a line to .brainyard/agents/exec-agent/INDEX.md.

When exec$* helpers (§12) are bound:
   (exec$run-write :slug slug :records run-records)
   (exec$dossier-write :slug slug :pre pre :execute execute :post post
                       :handoff handoff)
   (exec$index-append …)

ANSWER (PASS, items remain):
    Saved run: <run-record-path>
    Saved dossier: <dossier-path>
    Next: (call-tool "exec-agent" {:question "Continue."
                                    :agent-context "<dossier-path>"})

ANSWER (PASS, all done):
    Saved run: <run-record-path>
    Saved dossier: <dossier-path>
    Done: <slug> — all N items advanced; recommend todo$complete + eval-agent.
    Next: (call-tool "eval-agent" …)

ANSWER (manual surfaced):
    Saved run: ...
    Saved dossier: ...
    Manual: item <idx> — <description>; supply result then re-invoke.
    Next: (call-tool "exec-agent" …)  ; with this dossier as :agent-context

GATHER / REFUSE / HOLD follow plan-agent's pattern.

────────────────────────────────────────────────────────────────────────────
HARD RULES
────────────────────────────────────────────────────────────────────────────
1. NO direct writes. Every write delegated to edit-agent.
2. NO mutating plans. Plans read-only.
3. NO mutating todos beyond {todo$update-item, todo$add-item,
   todo$reset-item, todo$abandon, todo$complete}. Authoring is
   todo-agent's domain.
4. NO query$clone. Cross-agent dispatch via call-tool is fine.
5. NO writing outside .brainyard/agents/exec-agent/.
6. NO flipping a checkbox without supporting evidence. R2 enforces.
7. NO write-side MCP without explicit user confirmation.
8. NEVER skip the run record or the dossier.
```

---

## 9. Tool-Context

```text
## Exec Tools — drive a todo and write evidence

TODO TRACKING (limited write surface)
- todo$list, todo$read, todo$status, todo$exists      -- read.
- todo$update-item   -- flip checkbox to done. Args: slug, item-idx.
                        Use ONLY after VERIFY says :ok? true.
- todo$add-item      -- insert mid-loop. Args: slug, description, tags
                        (optional new), after-idx. Use when an item is
                        revealed to be over-coarse.
- todo$reset-item    -- reset single item. Args: slug, item-idx. Used by
                        POST-FLIGHT REVISE when retrying an :edit-agent
                        item that was rolled back.
- todo$complete      -- only after :pending = 0 AND user confirms.
- todo$abandon       -- confirm.

PLAN ACCESS (READ-ONLY)
- plan$read-dossier, plan$read, plan$exists, plan$status

CROSS-AGENT DISPATCH (the core of execution)
- (call-tool "edit-agent" {…})    — every write item.
- (call-tool "explore-agent" {…})   — mid-loop discovery items.
- (call-tool "mcp$tools" {…})       — MCP-routed items (write-side
                                       requires user confirm).
- bash                              — :via :bash items only.

NEVER bound here:
- update-file / write-file          — deliberate; use edit-agent.
- todo$create / todo$delete / todo$reopen — todo-agent's domain.
- plan$create / plan$update-body / plan$delete — plan-agent's domain.

DISCOVERY (inherited; in-loop reads only)
- read-file, grep, search, query$llm
- list-tools, get-tool-info

PERSISTENCE HELPERS (exec$* — auto-bound when present)
- exec$run-write          — write the verbose run record
- exec$dossier-frontmatter — YAML per §7.3
- exec$dossier-write       — write the dossier (paired with run record)
- exec$index-append        — prepend INDEX.md
- exec$read-dossier        — frontmatter-only parse for downstream
- exec$find                — search prior dossiers by slug (resume support)
- exec$next-handoff        — single source of truth for `Next:`
- exec$preflight           — full pre-flight as one call
- exec$postflight          — full post-flight as one call (with one
                              auto-revise round)
- exec$item-route          — dispatch one item per its :tags.via

## Typical end-to-end flow per turn
1. Parse :question and :agent-context (a `Saved dossier:` for todo).
2. PRE-FLIGHT C1–C8. Stash `pre`.
3. If GATHER/REFUSE → skip EXECUTE/POST-FLIGHT, jump to PERSIST + ANSWER.
4. EXECUTE inner loop bounded by :max-items-per-turn. Per item:
   pick → route → verify → record → flip.
5. POST-FLIGHT rubric R1–R7. One auto-retry round if needed. Stash `post`.
6. PERSIST run record + dossier; INDEX prepend.
7. ANSWER — `Saved run:` + `Saved dossier:` + (`Manual:` | `Done:` |
   `Hold:`) + `Next:`.
```

---

## 10. Behavior Tree — Inherited As-Is

Same as plan-agent / todo-agent. No new BT. Iteration shape:

| Iter         | Channel       | Body                                                                         |
| ------------ | ------------- | ---------------------------------------------------------------------------- |
| 1            | code          | PRE-FLIGHT C1–C8. `def pre`.                                                  |
| 2..2+K-1     | tool / code   | (only on GO) EXECUTE inner loop, K = items-this-turn (≤ max-items-per-turn). |
| 2+K          | code          | POST-FLIGHT rubric. One auto-retry if needed. `def post`.                     |
| 2+K+1        | code          | PERSIST run record + dossier; INDEX prepend.                                  |
| 2+K+2        | answer        | `Saved run:` + `Saved dossier:` + (`Manual:`|`Done:`) + `Next:`.             |

---

## 11. Demonstration: "Drive ship-v2-checkout to completion"

`:agent-context = "Saved dossier: .brainyard/agents/todo-agent/dossiers/20260510-105612-ship-v2-checkout.md"`. Suppose this is the first exec turn — pre.resume-from is nil; all 4 items pending.

### Iteration 1 — PRE-FLIGHT

```clojure
(def todo-dossier
  (call-tool "todo$read-dossier"
             {:path ".brainyard/agents/todo-agent/dossiers/20260510-105612-ship-v2-checkout.md"}))

(def plan-dossier
  (call-tool "plan$read-dossier"
             {:path (-> todo-dossier :source :plan_dossier)}))

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
  (call-tool "edit-agent"
             {:question "Wire LD flag `checkout-v2` in src/checkout/flags.clj"
              :agent-context (str "Saved dossier: " (:plan-dossier pre))
              :run-tests? false
              :dirty-ok? false}))

(def evid-0
  {:idx 0
   :via :edit-agent
   :covers ["feature-flag checkout-v2 toggleable from staging admin"]
   :ok? (not (re-find #"Rolled back:" (:answer edit-0)))
   :evidence {:type :edit-agent
              :path     (last (re-find #"Saved edit: (\S+)" (:answer edit-0)))
              :rollback (last (re-find #"Rollback: (.+)" (:answer edit-0)))}})

(when (:ok? evid-0)
  (call-tool "todo$update-item" {:slug (:todo-slug pre) :item-idx 0}))
```

**idx 1 — `:via :edit-agent`** (similar)

**idx 2 — `:via :bash`**

```clojure
(def b-2 (call-tool "bash" {:command "bb test:component checkout"}))
(def evid-2
  {:idx 2 :via :bash :ok? (zero? (:exit-code b-2))
   :evidence {:type :bash
              :command "bb test:component checkout"
              :exit (:exit-code b-2)
              :stdout-tail (subs (:output b-2) 0 (min 500 (count (:output b-2))))}})
(when (:ok? evid-2)
  (call-tool "todo$update-item" {:slug (:todo-slug pre) :item-idx 2}))
```

**idx 3 — `:via :manual`** — STOP, record:

```clojure
(def evid-3
  {:idx 3 :via :manual :ok? :manual-pending
   :evidence {:type :manual
              :description "Sample p99 from Grafana dashboard <url>"
              :user-action "Read p99 (over 24h window) and reply with the value."}})
;; do NOT call todo$update-item.
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
   :revision-applied? false
   :revision-summary nil
   :holds []
   :advanced [evid-0 evid-1 evid-2]
   :pending-after [3]
   :acceptance-progress
   {"feature-flag checkout-v2 toggleable from staging admin" :evidence-recorded
    "all checkout/* unit tests green"                         :evidence-recorded
    "p99 checkout latency unchanged within ±5%"               :pending}})
```

### Iteration 6 — PERSIST

```clojure
(exec$run-write :slug (:todo-slug pre) :records (:evidence execute))
(def res (exec$dossier-write :slug (:todo-slug pre)
                             :pre pre :execute execute :post post
                             :handoff {:next-agent "exec-agent"
                                       :next-call (str "(call-tool \"exec-agent\" "
                                                       "{:question \"Continue from item 3.\" "
                                                       ":agent-context \"<this dossier path>\"})")}))
(exec$index-append :path (:path res) :slug (:todo-slug pre)
                   :pre-verdict :go :post-verdict :pass
                   :next-agent "exec-agent")
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

Saved run: .brainyard/agents/exec-agent/runs/20260510-110131-ship-v2-checkout.md
Saved dossier: .brainyard/agents/exec-agent/dossiers/20260510-110131-ship-v2-checkout.md
Manual: item 3 — Sample p99 from Grafana dashboard <url>; supply result then re-invoke.
Next: (call-tool "exec-agent" {:question "Continue from item 3 with this p99 reading: <value>." :agent-context ".brainyard/agents/exec-agent/dossiers/20260510-110131-ship-v2-checkout.md"})
```

When the user replies with the p99 reading, exec-agent's next turn pre-flights, sees `pre.resume-from.prior-status = {0 :done 1 :done 2 :done 3 :pending}`, jumps directly to item 3, completes it (with evidence in the manual user reply), and the dossier ends with `Next: (call-tool "eval-agent" …)`.

---

## 12. Optional `(exec$*)` Helpers

Live in a new namespace `ai.brainyard.agent.common.exec` (sibling of `plan.clj` / `todo.clj`). Same shape as the dossier helpers in plan-agent / todo-agent, plus:

- `exec$run-write` — verbose per-iteration run record.
- `exec$find` — search prior dossiers by slug (resume support).
- `exec$item-route` — one-call dispatch of an item per its `:tags.via`. Returns `{:ok? :evidence}`.
- `exec$preflight` / `exec$postflight` — full pipeline as single calls.

When `exec$item-route` is bound, the inner-loop boilerplate from §11 collapses to:

```clojure
(def execute
  (loop [pending (rest pending-items)
         adv []
         evid {}
         budget (or :max-items-per-turn 5)]
    (if (or (zero? budget) (empty? pending))
      {:items-advanced adv :evidence evid
       :items-pending-after (mapv :idx pending)}
      (let [item (first pending)
            r    (exec$item-route :item item :pre pre)]
        (when (:ok? r)
          (call-tool "todo$update-item" {:slug (:todo-slug pre) :item-idx (:idx item)}))
        (if (= :manual-pending (:ok? r))
          ;; manual stop
          {:items-advanced adv :evidence (assoc evid (:idx item) (:evidence r))
           :items-pending-after (mapv :idx pending)
           :reason-for-stop :hard-blocker}
          (recur (rest pending)
                 (if (:ok? r) (conj adv (:idx item)) adv)
                 (assoc evid (:idx item) (:evidence r))
                 (dec budget)))))))
```

---

## 13. Handoff Mechanics

Eval-agent reads the exec-agent dossier in `:agent-context`. Two read levels (cheap frontmatter via `exec$read-dossier`; full body via `read-file`). The frontmatter gives:

- `plan_dossier` and `todo_dossier` for cross-references.
- `execute.evidence` — the structured per-item map.
- `post.acceptance_progress` — the criterion-status map. Eval-agent extends this with its verdict.
- `post.advanced[].evidence.path` — links to edit-agent records for diff-level audit.

For multi-turn exec runs (item-3-manual case in §11), each turn produces its own dossier; eval-agent's pre-flight reads the latest. The `pre.resume-from` field threads turns together.

---

## 14. Migration Plan

Same phases as plan-agent (`docs/plan-agent-design.md` §14): Phase 0 land, Phase 1 dual-read for legacy callers (no file move needed since exec-agent had no on-disk artifacts before), Phase 2 update eval-agent to consume exec dossiers, Phase 3 emit deprecation warnings on direct `update-file`/`write-file` calls in exec-agent's loop, Phase 4 remove the fallback paths.

The legacy "evidence in `:answer` only" channel is preserved through Phase 2 — eval-agent reads dossier first, falls back to `:answer` when no dossier path is present.

---

## 15. Verification

| Benchmark                              | Shape                                                                                         | What it verifies                                                                                                                  |
| -------------------------------------- | --------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| Pre-flight GO happy path               | Valid todo dossier in :agent-context; all routing tags present; tree clean                    | All C1–C8 pass; EXECUTE runs; POST-FLIGHT PASS; one dossier with full evidence map.                                              |
| Pre-flight GATHER (no todo dossier)    | :agent-context = ""                                                                          | C1 fails; agent recommends todo-agent; dossier records GATHER.                                                                    |
| Pre-flight REFUSE (todo HOLD)          | Todo dossier post.verdict :hold                                                              | C2 fails; agent refuses with the holds.                                                                                           |
| Pre-flight GATHER (missing routing)    | One pending item lacks :tags.via                                                              | C5 fails; agent suggests todo-agent retag.                                                                                        |
| Pre-flight GATHER (dirty tree)         | git status non-empty; pending :edit-agent items                                             | C7 fails; agent suggests stash or :dirty-ok?.                                                                                     |
| Execute :edit-agent route happy path | One :via :edit-agent item                                                                  | (call-tool "edit-agent" …) invoked; Saved edit / Rollback parsed; checkbox flipped.                                            |
| Execute :edit-agent rollback         | Update-agent rolls back due to verify failure                                                 | :ok? false; checkbox NOT flipped; POST-FLIGHT triggers REVISE; one auto-retry.                                                    |
| Execute :bash route                    | One :via :bash item                                                                           | bash invoked; exit-code 0 → flip; non-zero → no flip.                                                                             |
| Execute :mcp read-only                 | One :via :mcp <s>:<read-tool>                                                                | Proceeds without confirmation.                                                                                                    |
| Execute :mcp write-side                | One :via :mcp <s>:<write-tool>                                                              | STOPS; surfaces the proposed call; checkbox NOT flipped.                                                                          |
| Execute :explore-agent route           | Mid-loop discovery item                                                                       | explore-agent invoked; Saved exploration: parsed; recorded as evidence; checkbox flipped.                                          |
| Execute :manual route                  | One :via :manual item                                                                        | Inner loop stops; `Manual:` line in answer; checkbox NOT flipped.                                                                  |
| Resume from prior dossier              | Prior dossier exists; some items :hold                                                       | C8 informational; held items prioritized; previously-done items skipped.                                                          |
| Post-flight REVISE                     | One :edit-agent item rolled back                                                            | One auto-retry; if successful → PASS; if not → HOLD.                                                                              |
| Post-flight HOLD                       | Multiple failed items                                                                         | No retry; dossier records holds; answer surfaces.                                                                                 |
| Acceptance progress completeness       | All items advanced                                                                            | acceptance_progress map has every criterion either evidence-recorded or pending; eval-agent can score.                            |
| Run + dossier pairing                  | Single turn                                                                                   | One run record + one dossier with shared timestamp; `dossier.run_record` resolves.                                                |
| Index integrity                        | Append 100 dossiers                                                                           | INDEX.md has 100 lines, newest first.                                                                                             |

mulog signals: `::exec.preflight`, `::exec.item-route`, `::exec.edit-agent-call`, `::exec.flip`, `::exec.postflight`, `::exec.dossier-write`.

---

## 16. Files Summary

| File                                                                                       | What changes                                                                                                                                                                  |
| ------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `components/agent/src/ai/brainyard/agent/common/exec_agent.clj`                            | REDESIGNED — three-phase pipeline (PRE-FLIGHT / EXECUTE / POST-FLIGHT), new instruction + tool-context per §8 / §9, run + dossier emission per §7.                            |
| `components/agent/src/ai/brainyard/agent/common/exec.clj` (NEW)                            | NEW — `exec$run-write`, `exec$dossier-frontmatter`, `exec$dossier-write`, `exec$index-append`, `exec$read-dossier`, `exec$find`, `exec$item-route`, `exec$preflight`, `exec$postflight`. |
| `components/agent/test/ai/brainyard/agent/exec_agent_test.clj`                             | EXTENDED — new tests per §15.                                                                                                                                                |
| `.brainyard/agents/exec-agent/README.md`                                                          | NEW (templated by helpers).                                                                                                                                                  |
| `bb.edn`                                                                                   | NO new task (no file migration — exec had no on-disk artifacts before).                                                                                                       |
| `docs/exec-agent-design.md`                                                                | THIS FILE.                                                                                                                                                                   |
| `components/agent/src/ai/brainyard/agent/common/eval_agent.clj`                            | TOUCHED at Phase 2 to consume exec-agent dossiers.                                                                                                                            |
| `docs/agent-design.md` / `docs/AUTORESEARCH.md`                                            | TOUCHED — pipeline diagram updated to thread exec dossiers.                                                                                                                   |

---

## 17. Open Questions

1. **`:max-items-per-turn` default.** Today's design says 5. Too low → many turns; too high → token bloat per turn + harder to debug. The right default depends on average item complexity. Suggest 5 for v1; instrument and revisit.
2. **Auto-retry budget.** POST-FLIGHT REVISE allows ONE retry of one failed item per turn. A natural extension: allow N retries when items are :via :bash (cheap) but only 1 for :edit-agent (slow + risky). Defer to instrumentation.
3. **Should exec-agent be allowed to add items mid-loop?** Today todo-agent owns SPAWN; exec-agent has `todo$add-item` for on-the-fly splits. The redesign keeps this. A stricter alternative: STOP and dispatch todo-agent for mid-loop additions. Trade-off: deliberation vs. throughput. Stick with current model; if mid-loop additions correlate with HOLD turns in benchmarks, tighten.
4. **Per-turn vs. per-batch dossier.** A long-running execution could span 5+ turns. Current design: one dossier per turn. Alternative: a single rolling dossier that exec-agent appends to. Per-turn is simpler and gives natural rollback granularity; rolling is leaner on disk. Stick with per-turn.
5. **Should exec-agent gate on a CI run, not just bash test?** Some plans should not be considered "advanced" until a CI build passes. Could add a `:via :ci <pipeline-id>` route. Out of scope for v1; revisit when the first plan needs it.
6. **Manual-item resumption ergonomics.** Today the user re-invokes exec-agent with the dossier path AND a fresh question carrying the manual reading. Could be smoother — e.g. an `exec$resume-with-manual :item-idx N :evidence "..."` helper. Defer.
