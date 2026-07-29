# Skill agent + component — improvement audit

> **Status:** Audit. Findings against `common/skills.clj`, `common/skill_agent.clj`,
> `common/skill_distill*.clj`, `common/skill_refine.clj`, plus the substrate wiring
> in `common/agent_roster.clj` / `coact_agent.clj` / `react_agent.clj`.
>
> **Baseline:** `skills-test`, `skill-distill-test`, `skill-refine-test`,
> `skill-interop-test` — 46 tests, 218 assertions, 0 failures, 0 errors.
>
> **Related:** `docs/design/skill-agent-design.md` (as-built reference),
> `docs/design/skills.md` (static vs dynamic dispatch).
>
> **Line citations describe the PRE-change state** — they point at the code as it
> was when the finding was made, not after the fixes in the Resolution section.

The skill subsystem is in good shape structurally — the lifecycle/use split
shipped cleanly and the self-improvement loop (distill → propose → accept) is
well-factored. Every finding below is in the **discovery path**, and that is not
a coincidence: the skill-substrate work made `skills$find` **ambient across the
whole fleet**, which promoted several latent discovery weaknesses from "rare
edge case in one specialist" to "hot path on every agent."

---

## Severity summary

| # | Finding | Severity | Site |
| --- | --- | --- | --- |
| F1 | `skills$find` shells out to `npx` on any local miss — 5s latency **and** irrelevant marketplace results | **High** | `skills.clj:473-484` |
| F2 | Dynamic skill ids collide across backends; later registration silently wins | **High** | `skills.clj:456`, `skills.clj:883`, `skills.clj:936-957` |
| F3 | `skills$list` / `skills$find` output schemas describe a `{:result …}` wrapper that the fns never return | **Medium** | `skills.clj:617`, `skills.clj:630` |
| F4 | Dead cond branch in CLI backend type detection | **Low** | `skills.clj:365` |
| F5 | `docs/design/skills.md` skill-agent section is stale vs. as-built | **Low** | `skills.md:188-195` |
| F6 | ~~explore-agent's explicit skill bindings are redundant~~ — **disproved**; they are load-bearing | **Won't fix** | `explore_agent.clj:431-434` |
| F7 | No auto-reload after a `.brainyard/skills/` write — registry coherence is model-remembered | **Medium** | design doc open-Q #2 |

---

## F1 — `skills$find` shells out to `npx` on every local miss (High)

### What the code does

`find-skills` with no `:type` returns a by-type map, and for the two CLI
backends it calls `find-cli`, which falls back to the marketplace CLI whenever
the local scan finds nothing:

```clojure
;; skills.clj:473-477
find-cli (fn [cli-type]
           (let [local (filterv match-local (cli-list-from-fs cli-type))]
             (if (seq local)
               local
               (cli-find cli-type query))))     ; → npx skills find, 15s timeout
```

So a no-`:type` miss runs **two** `npx` subprocesses (`:claude` and `:agents`),
each with a 15 s timeout.

### Measured

Query `"zzz-no-such-skill-anywhere"` against a temp project:

| Call | Elapsed |
| --- | --- |
| `(find-skills dirs q)` — no `:type` | **4,986 ms** |
| `(find-skills dirs q :type :brainyard)` | **0 ms** |

### Why it is worse than latency

The `npx` fallback does not return *skills you have*. It returns **marketplace
install candidates**, and for a nonsense query it still returns a full page of
them:

```
:claude => {:result "Install with npx skills add <owner/repo@skill>

cloudflare/cloudflare-docs@dependabot-review 32 installs
yugabyte/yugabytedb-skills@yba-api 15 installs
dragoon0x/optik@touch-target 5 installs
…"}
```

An agent following the substrate's step 1 (`skills$find`) on a query with no
local match therefore receives, as its "discovery result", a list of unrelated
third-party packages. The shape also differs from a local hit (a `{:result
"<text blob>"}` map vs. a vector of skill maps), so a model that pattern-matched
on the local shape gets neither a clean miss nor a usable hit.

### Why the substrate made this urgent

Before the substrate, `skills$find` was reachable from explore-agent and
skill-agent only. `skill-substrate-protocol` now instructs **every**
coact/react-derived agent to "check for one" before any multi-step procedure
(`agent_roster.clj:123-142`). Misses are the common case — most tasks have no
matching skill — so the expensive path is now the default path.

### Recommendation

Make discovery **local-first and offline by default**: search installed skills
across all backends with no subprocess, and move marketplace search behind an
explicit opt-in argument (e.g. `:marketplace true`) that only skill-agent's
install flow would pass. A miss should return an empty result, not a catalog.

---

## F2 — Dynamic skill ids collide across backends (High)

### What the code does

The dynamic tool id is the bare skill name:

```clojure
;; skills.clj:883
(defn- dynamic-skill-id [skill-name]
  (keyword (str "skill$" skill-name)))
```

`reload-skills!` registers sequentially over `list-skills`' concatenation order
— `brainyard :project` → `brainyard :user` → `:claude` → `:agents`
(`skills.clj:456`) — and each `register-dynamic-skill!` does
`(swap! tool/!tool-defs assoc id …)`. Later entries **overwrite** earlier ones
with no warning and no log line.

Precedence is therefore exactly backwards from what a user would expect:
`:agents` beats `:claude` beats your **user** skills beats your **project**
skills.

### Measured on this machine

```
total skills discovered: 46
distinct names:          35
COLLISION agent-browser         => [:claude :agents]
COLLISION pdf                   => [:claude :agents]
COLLISION pptx                  => [:claude :agents]
COLLISION aws-cost-operations   => [:claude :agents]
COLLISION autoresearch          => [:claude :agents]
COLLISION find-skills           => [:claude :agents]
COLLISION xlsx                  => [:claude :agents]
COLLISION aws-cli               => [:claude :agents]
COLLISION sqlite-database-expert=> [:claude :agents]
COLLISION agent-tools           => [:claude :agents]
COLLISION docx                  => [:claude :agents]
```

11 of 46 discovered skills are silently shadowed **today**, before anyone
authors a project skill. A project skill named `pdf` would lose to
`~/.agents/skills/pdf` with no indication.

Note the reporting hides it: `reload-skills!` returns `:total (count registered)`
over a **set of ids**, so it reports 35 and looks self-consistent.

### Recommendation

Two parts, both cheap:

1. **Detect and log.** Emit a `mulog/warn` naming the shadowed skill, the
   winning backend and the losing one, so the condition is visible.
2. **Fix precedence, and give the loser a reachable id.** Local (`:brainyard`,
   project before user) should win the bare `:skill$<name>` id — it is the most
   specific and the only one the user authored. Shadowed entries should still be
   registrable under a qualified id (e.g. `:skill$<backend>$<name>`) so they
   remain invocable rather than vanishing.

---

## F3 — Output schemas describe a wrapper that is never returned (Medium)

`skills$list` and `skills$find` both declare:

```clojure
:output-schema [:map
                [:result [:any {:desc "…"}]]
                [:error  [:string …]]]
```

but `list-skills` returns a **raw vector** and `find-skills` returns either a
vector or a by-type map. Verified:

```
list-skills returns type: clojure.lang.PersistentVector
  map? false  vector? true
  (:result r) => nil
```

The output schema is what the LLM is told to expect, so it is documentation that
actively misleads. The tell that this already bites: `skill-distill`'s
`existing-skills-text` defensively probes three different shapes —

```clojure
;; skill_distill.clj:170-171
(let [res    (tool/invoke-tool :skills$list)
      skills (or (:skills res) (:brainyard res) (when (sequential? res) res) [])]
```

— including a `:skills` key nothing in the codebase produces.

### Recommendation

Pick one and make it true. Wrapping in `{:result …}` is the smaller change and
matches the declared schema, but it is a breaking change for existing callers
(`existing-skills-text`, sandbox bindings, tests). Correcting the *schema* to
describe the real shape is non-breaking and equally honest. Prefer the latter,
plus tightening `existing-skills-text` to the one real shape.

---

## F4 — Dead branch in CLI type detection (Low)

```clojure
;; skills.clj:365-367
t (cond (str/ends-with? (.getPath skill-dir) "/.claude/skills/") :claude
        (str/includes?  (.getPath skill-dir) "/.claude/skills/") :claude
        :else :agents)
```

`skill-dir` is an individual skill directory (`~/.claude/skills/pdf`), so it can
never *end with* `/.claude/skills/`. The first branch is unreachable; the second
subsumes it.

---

## F5 — `docs/design/skills.md` is stale (Low)

`skills.md:188` still documents skill-agent's roster as:

```clojure
:agent-tools  {:tools skills/skills-commands}   ; skills$list/find/read/write/install/sync/reload
```

As built (`skill_agent.clj:156-159`) it is `skills-commands` **+**
`skill-proposal-commands` **+** `file-tools` **+** `shell-tools`. The comment
also omits `skills$import`, and `skills.md:193-195` presents `skills$write` as
*the* mutation surface without mentioning that file-inherent CRUD is now the
preferred path.

---

## F6 — explore-agent's bindings are NOT redundant (disproved; won't fix)

Filed as a cleanup, then **disproved on inspection** — recorded here because the
existing docs assert the opposite and would lead someone to break it.

`explore_agent.clj:431-434` binds `skills$list/find/read/reload` explicitly, and
the read subset also rides `default-agent-roster` (`agent_roster.clj:43`). That
looks like duplication. It is not: the roster reaches a derived agent through
`run-coact-derived`'s `merge-derived-tools`, which runs at **dispatch**.
`setup-agent-by-id` — the direct-launch path for `bb tui -a explore-agent` and
`bb tui ask` — merges only `(:meta def-entry)` with caller options:

```clojure
;; core/agent.clj:1018
merged (merge (:meta def-entry) options {:id instance-id})
```

No roster merge. Deleting the explicit bindings would silently remove skill
discovery from that entry point. The duplication is intentional and is de-duped
at roster build.

**Action taken:** left the code as-is; added an explanatory comment at the
binding site, and corrected `skill-agent-design.md` §10, which described the
cleanup as merely "deferred, not done. Harmless."

---

## F7 — Registry coherence is model-remembered (Medium)

Every authoring flow ends with "…then `skills$reload`". Nothing enforces it: a
`write-file` under `.brainyard/skills/` that is not followed by a reload leaves
the new skill undiscoverable as a `:skill$<name>` tool until the next explicit
reload or process restart. `skill-agent-design.md` open question #2 records this
as a known gap with "a hook is the smoother follow-up."

### Recommendation

Register a post-write hook that reloads when a written path falls under a
`.brainyard/skills/` root. Must stay debounced and runtime-installed (the same
`compare-and-set!` discipline `skill_distill`/`skill_refine` use) so
native-image never bakes it.

---

## Out of scope / explicitly not findings

- **The lifecycle/use split itself** is sound; nothing here argues for changing
  who owns writes.
- **Dynamic skills routing through skill-agent** (`skills.md` §"Dispatch") is a
  deliberate, well-documented design decision, not a defect.
- **Registration being a runtime call rather than `defonce`** is load-bearing for
  native-image correctness and must stay that way (`skills.clj:973-984`).

---

## Resolution

All findings are addressed in this branch.

| # | Resolution |
| --- | --- |
| F1 | `find-skills` is installed-only, ranked and offline. Marketplace search split into `search-marketplace` / `skills$search`, kept **off** `skills-read-subset` so the substrate's hot path can never shell out. Measured: 4,986 ms → **8-25 ms**; a true miss now returns `[]`. |
| F2 | `resolve-registrations` gives the most local backend the bare `:skill$<name>` id and registers the rest as `:skill$<backend>$<name>`, logging `::dynamic-skill-name-shadowed` and reporting `:shadowed` from `skills$reload`. Measured: 47 discovered → **47 distinct ids, 0 lost** (was 35). |
| F3 | `skills$list` / `skills$find` now return `{:result [...] :count n}`, matching their declared schemas; `existing-skills-text` reads the one real shape. |
| F4 | Dead branch removed. |
| F5 | `docs/design/skills.md` synced to as-built (roster, `skills$import`/`skills$search`, file-inherent CRUD, collision handling). |
| F6 | Disproved — code unchanged, comment added, `skill-agent-design.md` §10 corrected. |
| F7 | `common/skill_watch.clj` closes it: flag on a skills-path write, one coalesced `reload-skills!` per turn. Explicit `skills$reload` still needed for same-turn callability. |

Two quality improvements beyond the filed findings, both from testing the fix:

- **Tokenizer noise.** The first ranking cut matched `no` inside "auto**no**mous",
  so a nonsense query returned 20 hits. Tokens under 3 chars are now dropped and
  matching is anchored to word starts (`lint` matches `lint-markdown` and
  `mark` still matches `markdown`, but `glint` no longer matches `lint`), with a
  `min-match-score` floor so a lone description mention does not qualify.
- **Duplicate rows.** `aws cost` returned `aws-cost-operations` twice — the
  `:claude` and `:agents` copies, indistinguishable to the model. Same-named
  matches now collapse to the most local one, carrying `:also-in`.
