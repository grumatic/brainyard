# Tool Permission Gate — Generalizing the MCP Gate, Retiring `check-permission`

> **Status: SHIPPED, and inert by default.** All of §3 is built; the note is
> now the as-built reference. `check-permission` is gone.
>
> **Problem in one line:** `core/tool`'s `check-permission` looked like a
> permission gate, was called on every dispatch, and could not deny anything.
>
> **Scope, as built:** `core/tool.clj` (`check-permission` + `permission-config`
> + `match-items` deleted, the `declare` and the dead `:denied` branch with
> them, docstring corrected), new `common/tool_permission.clj` (shared matcher
> + `gate-verdict` + the general gate), `mcp/permission.clj` (now one policy
> over the shared verdict, wording byte-identical), `core/config.clj`
> (`:tool-approval-patterns`, `:tool-allow-tools`), `core/feature.clj`
> (`:tools/permission`), `common/script_bridge.clj` (its private glob matcher
> replaced by the shared one), `bases/agent-tui/…/permissions.clj` (the
> `:type :tool-use` arm), `interface.clj` (load path for the install).
> **Tests:** `components/agent/test/…/common/tool_permission_test.clj` —
> 10 tests, 57 assertions.
>
> **The four §5 questions, as resolved:** (1) nothing ships non-empty — §3.2
> already argued inert-by-default and the candidates are gated elsewhere for
> file writes anyway; (2) the approval prompt caches on the **matched
> pattern**, the family analogue of MCP's per-server cache, since the unit a
> human agreed to is the family they were shown; (3) two keys, as the note
> leaned; (4) no special case for sub-agent dispatch — a pattern matches only
> what an operator wrote, so `*` reaching every specialist is a choice, not a
> default.
>
> **Found while:** widening `:script-bridge-tools` to accept globs. The bridge
> asked "what stops a script calling `config$apply`?", and the honest answer
> turned out to be "the bridge allowlist, and nothing else."

---

## 1. The three findings this rests on

### 1.1 `check-permission` cannot deny anything

```clojure
;; core/tool.clj:1147
(def permission-config {:approval [] :deny [] :allow []})
```

Hardcoded, and never rebound anywhere in the repo (grepped: the only
occurrences are the `def` and the three `match-items` calls inside
`check-permission` itself). Every branch therefore falls through to
`:else :allowed`.

It has one caller — `tool.clj:777`, inside `call-tool` — and that caller acts
on exactly one of its three verdicts:

```clojure
permission (check-permission tool-name)
…
(= permission :denied) {:error-message "Tool execution denied by permission configuration."}
```

`:approval-required` is computed and discarded. There is no branch for it, and
`grep -rn approval-required` finds no other consumer.

**It is also wrong twice**, which is the strongest evidence it has never been
exercised:

- `(re-find (re-pattern item) target)` is an **unanchored substring** match, so
  `:deny ["read"]` would deny `read-file`, `spread-metrics`, and anything else
  containing `read`.
- `re-pattern` on a raw tool name makes `$` an **end-of-input anchor**. Every
  registered tool name contains `$`, so `:deny ["memory$recall"]` compiles to a
  pattern that matches nothing. (Identical trap to the one
  `:script-bridge-tools`' globs had to avoid; see the `$`-anchor note in
  `script-agent-design.md` §13.)

Meanwhile `call-tool`'s docstring says:

> Permission is checked before dispatch, and `*current-agent*` is bound…

That sentence is why this is worth fixing rather than ignoring: a reader
auditing the dispatch path is told there is a gate, and stops looking.

### 1.2 `permission-fn` is a prompt channel, not a policy engine

The natural question — *"can't `permission-fn` decide allowed / denied /
approval-required for a tool?"* — is no, for three structural reasons.

**It is typed, and closed.** `make-permission-fn`
(`bases/agent-tui/…/permissions.clj:579`) branches on exactly one value:

```clojure
(if (= :mcp-tool type)
  (mcp-permission-confirm …)
  ;; everything else → the file-access branch, keyed on :path / :paths
  …)
```

A new `{:type :tool-use …}` would not be rejected; it would fall into the
file-access branch with no path and prompt `File access requested: `. Adding a
tool verdict means adding a **branch**, not passing a new key.

**Its callers only ever send two shapes.** `{:type :file-access …}` from
`reference.clj` (×3) and `task/commands.clj`; `{:type :mcp-tool …}` from
`mcp/permission.clj`. Nothing else.

**It returns two states, not three:** `{:allowed true} | {:denied true :reason
…}`. There is no `approval-required` coming *out* of it — approval-required is
what the caller concluded *before* calling. `permission-fn` is what you invoke
once you have already decided a prompt is owed.

So the three-way policy lives one level above `permission-fn`. It already does.

### 1.3 The real gate exists, is proven, and is MCP-shaped by accident

`mcp/permission.clj` is a `:agent.tool-use/pre` decision hook, and it is
already the whole design:

```
resolve-permission-mode ─ :auto-approve    → allow, no prompt
                        ├ :deny-by-default → :replace refusal, no prompt
                        └ :ask-each-time   → permission-fn prompt
                                             no channel (headless) ⇒ REFUSE
downgrade to auto-allow: annotations.readOnlyHint | :mcp-allow-tools glob
```

`:auto` resolves per environment via `resolve-permission-mode` — auto-approve
in a detected container, prompt on a bare host.

Two facts that make this the right foundation:

- **It sits where every dispatch passes.** `dispatch-with-hooks` wraps the
  registry path, so the hook sees native MCP bindings *and* the `mcp$tools :op
  :call` proxy. Verified live: a `by-tool` bridge call fires
  `:agent.tool-use/pre` with the real tool name, and nine handlers are
  registered there today —

  | priority | handler | source |
  |---:|---|---|
  | 200 | `memory-agent/write-guard` | memory-agent |
  | 100 | `loop-guard/redundant-tool-call-guard` | default |
  | 90 | `context-actions/tool-cache-lookup` | context-actions |
  | 80 | `mcp/mcp-permission-gate` | mcp |
  | 50 | `eval/eval-bash-guard` | eval-agent |
  | 50 | `auto-notify/deflect-poll` | auto-notify |

  (plus three TUI/persist observers). Higher runs first —
  `matching-entries` sorts `(comp - :priority)`.

- **Its refusal is better than an error string.** A `:replace` verdict hands
  the model an `{:error …}` explaining how to get permission, and the turn
  continues. `check-permission` can only return `{:error-message …}` from
  inside `call-tool`, with no prompt channel and no recovery hint.

`mcp/permission.clj`'s `glob-match?` is also, independently, the exact
`Pattern/quote`-segments-joined-on-`.*` construction that
`:script-bridge-tools` arrived at. Two call sites converging on the same
matcher is the argument for extracting it.

#### 1.3.1 `fire-decision!` in one table — a gate is VETO-ONLY

Everything §3.2 and §6 claim rests on this, so it is written down rather than
assumed. `fire-decision!` (`hooks.clj:437`) filters by `:match`, sorts by
priority **descending**, and walks until someone vetoes:

| a handler returns | effect |
|---|---|
| `nil`, a scalar, a map with no recognized `:result` | continue walking |
| `{:result :allow}` | continue walking |
| a decision missing its verdict's required key | log `::malformed-decision`, continue |
| throws (default `:on-error :log`) | log, continue |
| any other valid verdict | **wins immediately**, later handlers skipped |

Nobody vetoes ⇒ `{:result :allow}`; the winner is stamped `:by <handler-id>`.

**An allow is an abstention, not a vote.** A gate can add a refusal and can
never license a call another gate refuses — which is why priority buys exactly
one thing (who gets to refuse first) and why `:tool-allow-tools` cannot bypass
the MCP gate.

**And the walk fails OPEN in three ways** — malformed decision, thrown
exception, `:match` that never matches. For a permission gate all three are
the wrong-direction failure, which is what §6 turns into assertions.

Verdicts consumed by `dispatch-with-hooks`: `:modify-args` (non-empty `:args`
replaces the call's), `:replace` (body skipped, `:replacement` becomes the
result — what MCP refuses with, so the model sees a recovery hint and the turn
continues), `:block` (body skipped, `blocked-tool-result` synthesizes
`{:hook-blocked true :reason :by}` plus an optional `:answer` that lets
upstream BT actions terminate the loop). `:reason` is required on all three.
`tool-post-hook` fires either way, tagging `:hook-replaced` / `:hook-blocked` /
`:hook-modified-args` so observers see a refusal rather than a silent gap.

---

## 2. What is NOT proposed, and why

**Do not resurrect `check-permission`.** Wiring it to config would create two
places that answer "may this tool run": one inside `call-tool`, one in the hook
layer. They would drift, and the one inside `call-tool` is the weaker of the
two — no prompt channel, no `:replace`, no `:match` predicate, so it pays a
policy lookup on every dispatch including the ~90% that no policy touches.
This is the "no config key that describes what another implies" rule applied to
gates.

**Do not add a `:type :tool-use` to `permission-fn` and call it done.** That
supplies the *prompt* and none of the *policy*: nothing would decide which
tools need one, nothing would honor `:permission-mode`, and headless would have
no defined behavior.

**Do not gate on the LLM tool channel only.** A gate that lives in the
tool-calls path is invisible to CoAct's sandbox callables, to the script
bridge, and to sub-agent dispatch. `dispatch-with-hooks` is the one chokepoint
all of them share.

---

## 3. Proposal

### 3.1 Extract the gate, keep MCP as its first policy

New `common/tool_permission.clj` holding what is currently MCP-specific but is
not actually about MCP:

```clojure
(defn gate-verdict
  "allow (nil) or refuse (:replace), honoring resolve-permission-mode.
   `request` is the permission-fn payload; `display` names the target."
  [agent request display] …)

(defn glob-match? [glob target] …)   ; moved from mcp/permission.clj
```

`mcp/permission.clj` keeps its *classification* — `native-target`,
`proxy-call?`, `read-only?`, `allowlisted?`, `needs-approval?` — and calls the
shared `gate-verdict`. **No behavior change to MCP**, which is the point: the
extraction has to be provably inert before a second policy rides on it.

### 3.2 A general policy over registered tools

A second hook, `::tool-permission-gate`, registered by the same module:

| config key | type | default | meaning |
|---|---|---|---|
| `:tool-approval-patterns` | vector | `[]` | globs whose match needs approval |
| `:tool-allow-tools` | vector | `[]` | globs that bypass approval (checked first) |

Both use the shared glob matcher, so `mcp$*`, `user$tool$*` and `*` mean the
same thing they mean in `:script-bridge-tools`. **Ships empty**, so the gate is
inert on day one and the change is provably non-regressing: no pattern ⇒ the
`:match` predicate never fires ⇒ zero added work per dispatch.

`:match` is the load-bearing part. It must be a cheap name test against the
compiled patterns, not a config read — this runs on every tool call in the
process, and `mcp-call-event?` is the precedent.

**Priority: 85** — between `context-actions/tool-cache-lookup` (90) and
`mcp-permission-gate` (80). The reasoning needs care, because
`fire-decision!` treats an allow as *no verdict*: a handler returning `nil`
does not stop the walk, so a gate can only ever **add** a refusal, never
license a call another gate refuses.

- **Below the cache (90)** so a cache hit still short-circuits before any
  prompt — approving a call that was about to be served from cache is a
  prompt paid for nothing.
- **Above MCP (80)** so a general deny takes effect on an MCP tool that MCP
  would have auto-allowed via `readOnlyHint`. Ordering matters in exactly
  this direction and no other.
- **Consequence worth stating plainly:** `:tool-allow-tools` cannot widen
  anything for MCP tools. Matching it yields `nil`, the walk continues, and
  `mcp-permission-gate` still gets its say. `:mcp-allow-tools` remains the
  only key that bypasses the MCP gate — which is also the answer to open
  question 3.

All three orderings belong in a test; the third is the one a future
refactor will break silently.

### 3.3 One prompt branch

`make-permission-fn` gains a `:type :tool-use` arm beside `:mcp-tool`,
rendering `{:type :tool-use :tool "config$apply" :display …}` and caching by
tool name the way the MCP arm caches by server. Fail-closed when there is no
input channel, mirroring both existing arms.

### 3.4 Delete `check-permission`

Remove the `def`, the `defn`, the `match-items` helper, the `declare`, and the
`permission` binding in `call-tool`; correct the docstring to say what is
actually true — that permission is enforced by `:agent.tool-use/pre`.

**As built,** a comment stands where the function did, recording both bugs and
pointing at the hook. A deleted gate leaves no trace in a diff a year later,
and the next person to want tool permissions should find the answer at the
place they will look for it.

**One thing the build added that the note did not call for:** the gate
registers with **`:on-error :throw`**, against the house `:log` default.
Under `:log` a handler that throws returns nil, `fire-decision!` reads that as
an abstention, and the call proceeds ungated — a crashing permission gate that
permits. For a cache or a nudge `:log` is right; for this it is the
wrong-direction failure, so a bug surfaces as a failed tool call instead of a
silently ungated one.

---

## 4. What this deliberately does not fix

**It is not a sandbox.** An agent with a bash fence — script-agent, coact-agent
— already runs arbitrary code as the user, and `~/.brainyard` is writable under
the default seatbelt policy. A tool gate raises the cost of a *mistake* and
makes the audit trail legible; it does not contain a determined process. The
honest framing, carried over from `script-agent-design.md` §13: **blast radius
and legibility, not a security boundary.** Anything stronger belongs in
`--sandbox`.

**It does not close the `config$apply` self-confirm.** `config.clj:1077` gates
a sensitive write behind `:confirm? true`, which the *same caller* can supply
on retry; config-agent's R8 ("SECURITY-SENSITIVE KEYS NEED A HUMAN") is prompt
text bound to that agent, so a direct call never loads it. Putting
`config$apply` in `:tool-approval-patterns` would route it through this gate
and give it a real human — but that is a **policy default to argue separately**,
not something to smuggle in with the mechanism.

**It does not change `:allowed-dirs` or `:permission-mode`.** Both are
`ambient-keys` in `core/feature.clj` and must stay ungateable — they are the
floor this gate reads.

---

## 5. Open questions for review

1. **Should any pattern ship non-empty?** Inert-by-default is the safe landing,
   but a gate nobody configures protects nobody. Candidate starter set:
   `config$apply`, `edit-agent`, `write-file` — each already gated elsewhere
   for *file* writes, which argues they are redundant here.
2. **Does an `:approval-required` verdict deserve a per-tool session cache**
   ("always allow `config$apply` this session"), or is per-call the point?
   MCP caches per *server*; the tool analogue is per *family* (`mcp$*`), not
   per tool.
3. **`:tool-allow-tools` vs `:mcp-allow-tools`** — one key or two? Two keeps
   MCP's `server/tool` target shape distinct from the registry's `family$tool`
   ids, which do not interchange. Leaning two.
4. **Does the gate apply to sub-agent dispatch?** `do-call-tool--agent` runs
   inside `dispatch-with-hooks`, so `:agent`-type tools are matchable today. A
   pattern admitting one would prompt per dispatched specialist, which may be
   right or may be unusable.

---

## 6. Test obligations

- **Inertness:** empty patterns ⇒ byte-identical dispatch results and no
  `permission-fn` call, across registry / bound-fn / agent-type paths.
- **MCP is unchanged:** the existing `mcp/permission` suite passes untouched
  after the extraction.
- **Ordering:** a general deny refuses an MCP tool that `readOnlyHint` would
  have auto-allowed (85 > 80); a cache hit at 90 short-circuits before any
  prompt; and `:tool-allow-tools` does **not** bypass `mcp-permission-gate` —
  an allow is `nil`, so the walk continues.
- **Headless is fail-closed:** no `permission-fn` and `:ask-each-time` ⇒
  refusal, not silent allow.
- **The refusal is WELL-FORMED, asserted structurally.** `fire-decision!`
  fails **open** on a malformed decision: `valid-decision?` requires
  `:replacement` on a `:replace` and a non-empty map `:args` on
  `:modify-args`, and a decision missing them is logged
  (`::malformed-decision`) and **skipped** — the walk continues as if the
  handler had abstained. A gate whose refusal is malformed is therefore
  indistinguishable from no gate at all, and nothing in the running system
  says so above debug level. So assert the shape of what the gate RETURNS,
  not only that the call was refused: a test that drives the gate and checks
  "the tool did not run" passes just as happily when the tool did not run for
  an unrelated reason. Measured, on the live registry: `{:result :replace}`
  with no `:replacement` was skipped in favour of a later handler, and a
  handler that threw was likewise stepped over (`:on-error :log` ⇒ nil ⇒
  abstain) — so **a gate that crashes permits the call**. Both belong in the
  suite, and `:on-error` deserves an explicit choice rather than the default.
- **An allow is an ABSTENTION, not a vote** — the property every ordering
  claim above depends on. Pin it directly: a high-priority handler returning
  `{:result :allow}` must not stop a lower-priority handler from refusing.
  Measured: priority 90 allowing did not prevent priority 10 blocking. A
  refactor that "optimizes" the walk to stop on the first explicit `:allow`
  would silently disable every gate below the first permissive one, and no
  ordering test written in terms of two refusals would catch it.
- **The glob matcher:** shared with `:script-bridge-tools`, so the `$`-anchor
  and unanchored-substring regressions are pinned once and cover both.
- **`check-permission` is gone:** no caller, and the `call-tool` docstring no
  longer claims a check it does not perform.
