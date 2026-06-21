# Bootstrapping — The `config` Subcommand with Guaranteed-Workable LLM

> **Status:** Shipped (originally design proposal rev 1; may diverge from the proposal below). The guaranteed-workable-LLM wizard landed in `config_wizard.clj` (the BOOTSTRAP fallback ladder + Ollama-install fallback) and `cmd-config` in `main.clj`. The collaborator marked below as "out of scope, contract only" — `config_agent.clj` — has since shipped as `config-agent` (see `config-agent-design.md`).
> **Scope:** redesign of `bases/agent-tui/src/ai/brainyard/agent_tui/config_wizard.clj` and the `bb tui config` subcommand (`projects/agent-tui-app/.../main.clj` → `cmd-config`)
> **New collaborators:** `components/env-detect` (extended), a new `components/agent/.../common/config_agent.clj` (out of scope here, contract only)
> **Related reading:** `CLAUDE.md` §"Agent Architecture Highlights", `docs/design/explore-agent-design.md`, `docs/design/update-agent-design.md`, `components/agent/src/ai/brainyard/agent/core/config.clj`, `components/env-detect/src/ai/brainyard/env_detect/core/providers.clj`

---

## 1. Motivation

`bb tui config` is the only place where a new user is supposed to land before they ever try to talk to an agent. Today the wizard (`config_wizard.clj`) runs six steps — LLM providers, executables, sandbox, permissions, agent defaults, MCP — and writes a `~/.brainyard/config.edn` (or per-project `.brainyard/config.edn`) at the end. It works **only when the user already has at least one provider credential or `claude` CLI on PATH**. Step 1 detects providers, and if none come back as `:available? true` the wizard prints

> `No LLM providers detected. Set API keys in environment or .env file.`

and writes `:default-provider nil` / `:default-model nil` to disk. The subsequent steps still run, but the resulting config cannot launch an agent — the very next `bb tui run` immediately fails on "no LM configured." For a new user this is a dead end. They have to leave the tool, go acquire an API key (or install Claude Code), and come back. The wizard did *almost* nothing for them.

The same gap blocks the planned `config-agent` — an LLM-driven configuration manager that we want to take over post-bootstrap (re-tune model choices, install MCP servers on request, edit allowlists from natural language, etc.). `config-agent` is itself a brainyard agent; it needs a working `default-provider`/`default-model` to call. If bootstrap can leave the system without an LLM, config-agent is unreachable on exactly the cohort that most needs it.

Two further problems sit on the same floor:

1. **The wizard mixes concerns.** Detection, decision, persistence, and UX prompts are interleaved across six step functions. There is no clean place to insert a non-interactive `--auto` mode (CI, devcontainer first-run, scripted onboarding), and no way for `config-agent` to drive the same flow programmatically later — every choice is gated on `prompt-line` against `System/in`.
2. **There is no fallback ladder.** Step 1 either finds an available provider or surrenders. It does not try to install one. Ollama is hardcoded as `localhost:11434` reachability — if Ollama is installed but not running, the wizard treats it the same as "not installed." If no providers are present at all, the wizard does not offer to install Ollama and pull a free open-weights model (e.g. Z.ai's GLM-4.5-Air, or `glm-5:cloud` via Ollama Cloud's free tier) even though that is the one move that would get the user from "nothing" to "agent runs."

**Thesis.** Redesign the `config` subcommand around a fixed three-phase pipeline:

1. **DETECT (read-only)** — full environment scan (`env-detect`) with a single normalized report; pure function of the world, no writes, no prompts.
2. **BOOTSTRAP (deterministic, with fallback ladder)** — pick a workable `default-provider`/`default-model` using the ladder in §5. If nothing is reachable, OFFER to install Ollama and pull a free open-weights model (GLM-4.5-Air locally, or `glm-5:cloud` via Ollama Cloud if local pull is too heavy). The phase **must** end with either a working LLM written to disk or an explicit user-acknowledged cancellation; it must **not** leave a dangling nil provider.
3. **HANDOFF (optional, opt-in)** — once a workable LLM is guaranteed, invite the user into `config-agent` for the remaining choices (permissions, MCP servers, agent defaults, sandbox mode). The user can also accept the deterministic defaults and skip the LLM-driven phase entirely.

Every phase is implemented as a pure function returning a config delta, with a thin interactive shell on top. The non-interactive `--auto` / `--profile` variant skips prompts and applies documented defaults — the same code path, just a different shell.

Same minimal-diff principle as the other design docs in this directory: one redesigned wizard file, one helper namespace in `env-detect` for "install/pull Ollama", and one new agent (`config-agent`, contract only — full design lives in a sibling doc). `agent/core/config.clj` (read/write of `config.edn`) is untouched.

---

## 2. Design Principles

1. **Bootstrap must leave a runnable LLM, or stop honestly.** The contract for `config` exiting successfully is `(some? (:default-provider config))` AND `(some? (:default-model config))` AND that provider is reachable at exit. Falling through to "nil provider written" is a bug, not a fallback.
2. **Free local LLM is a first-class fallback, not an escape hatch.** Ollama + GLM (`glm-4.5-air` local, or `glm-5:cloud` for users who don't want a multi-GB pull) is the documented end of the ladder. The wizard knows how to install Ollama (where possible) and how to `ollama pull` a model, with progress shown to the user.
3. **Detection is a pure read.** Phase 1 never writes, never starts daemons, never pulls models. All side effects are gated behind explicit user (or `--auto`) consent in phase 2.
4. **Three modes share one engine.** Interactive (default), `--auto` (non-interactive paved path, e.g. CI / devcontainer), and `config-agent`-driven (LLM-mediated, post-bootstrap) all consume the same `detect/bootstrap/handoff` functions; only the shell differs. No flow gets a copy-pasted second implementation.
5. **`config-agent` is downstream, not coupled.** Bootstrap does not depend on `config-agent`. It produces the minimum viable `config.edn` and exits. `config-agent` is then a regular agent that re-enters with a working LLM, reads the same `config.edn`, and offers richer configuration via chat. This keeps the boot path simple and config-agent stop-the-world failures harmless.
6. **No surprise installs.** The wizard never installs anything (Ollama, brew, curl-piped scripts) without an explicit confirmation that names the binary, the source URL, and the on-disk size. In `--auto` mode it follows a written-down policy (§9) per OS, never an opaque "do what you think is best."
7. **One config.edn, idempotent writes.** Re-running `bb tui config` after bootstrap reads the existing config and only overwrites keys the user explicitly changes (same behaviour as the current wizard's existing-config merge). The bootstrap phase becomes a no-op when an already-reachable LLM is configured.
8. **Trace every decision.** Each phase records what it observed and what it did into `~/.brainyard/bootstrap-log.edn` (last 5 runs, rotated). When something goes wrong — and Ollama pulls / network installs WILL go wrong — the user (and config-agent) can read the log instead of guessing.
9. **Don't fight the user's existing config.** If `:default-provider` is already set and reachable, bootstrap skips the ladder entirely. If it is set but **un**reachable (revoked key, daemon down), bootstrap surfaces that and offers re-bootstrap rather than silently overwriting.

---

## 3. Position in the Stack

```
                                ┌────────────────────────────────────────┐
   $ bb tui config              │ bases/agent-tui/.../config_wizard.clj  │
   ─────────────►               │   (this redesign)                      │
                                └──────────┬─────────────────────────────┘
                                           │ uses
                  ┌────────────────────────┼─────────────────────────────┐
                  ▼                        ▼                             ▼
       env-detect/interface       agent.core.config              new helpers:
       (detect-all-providers,     (init-dirs!, read-edn-config,  env-detect/
        detect-executables,       write-edn-config!, schema)     ollama_install.clj
        detect-sandbox-           — UNCHANGED                    (install-ollama!,
        environment, detect-os)                                   ensure-daemon!,
                                                                  pull-model!)
                                           │
                              writes ~/.brainyard/config.edn
                                           │
                                           ▼
                              (later, on a separate invocation)
                                $ bb tui run -a config-agent
                                ─────────────────────────────►
                                LLM-mediated configuration (out of scope here,
                                contract in §8)
```

Bootstrap is a one-shot CLI that ends by `System/exit`-ing. `config-agent` is a long-lived agent the user enters when they want richer (LLM-driven) configuration. They share the same `config.edn` schema and the same `env-detect` interface; nothing else.

---

## 4. Phase 1 — DETECT (pure)

`env-detect/interface` already exposes `detect-all`, returning `{:providers :executables :sandbox :os}`. Two additions:

- **`detect-ollama-installation`** (new): distinct from `detect-ollama` (daemon reachability). Returns `{:installed? :binary-path :version :daemon-running? :pulled-models [str] :detail str}`. `pulled-models` is the parsed output of `ollama list` (best-effort; empty vec on failure). This is the lookup the bootstrap ladder needs in step 4.
- **`detect-network-egress`** (new): a 2-second HEAD to `https://huggingface.co` and `https://ollama.com`, returns `{:huggingface? :ollama? :detail str}`. Used to decide whether the offline-only path (§5) applies. Failing closed (no egress) routes the ladder to "stop with instructions" rather than "try to pull a multi-GB model on a flaky link."

Phase 1's output is a single map; no other phase calls `env-detect` directly. This keeps detection cacheable for re-prompt loops (`recur` after a bad selection should not re-scan the world).

---

## 5. Phase 2 — BOOTSTRAP (the fallback ladder)

The ladder runs top-down. It stops at the first **reachable** rung; an unreachable rung is logged and skipped. Every rung except (e) is non-installing — it only *selects* an already-present option. Rung (e) is the one that installs.

| # | Rung | Condition | Action |
|---|---|---|---|
| a | Existing config | `config.edn` exists AND its `:default-provider` is reachable in phase-1 results | No-op; print "Using existing LLM `<provider>/<model>`. Run `bb tui run -a config-agent` to reconfigure." |
| b | API-key provider | Any of `OPENAI/ANTHROPIC/GOOGLE/...` env-var providers is `:available? true` | Offer the user the menu of available providers; preselect the highest-priority one (order: anthropic → openai → google → groq → deepseek → ...; see §10). |
| c | Claude CLI | `claude` is on PATH (`detect-claude-code`) | Offer `:claude-code` with model picker (sonnet/opus/haiku). |
| d | Ollama already running with model | `:ollama` daemon reachable AND at least one chat-capable model in `pulled-models` | Offer `:ollama` with that model preselected. |
| e | Ollama install + free model pull | none of (a–d) | **The new behaviour.** Offer to (1) install Ollama if missing, (2) start the daemon, (3) pull a free open-weights model. Default model: `glm-4.5-air` (local; ~7 GB). Alternative: `glm-5:cloud` (Ollama Cloud free tier; near-zero local disk, requires `ollama signin`). The choice is shown with size/latency trade-offs (§5.2). |
| f | Apple FM | macOS 26+ with `apfel --serve` reachable (detected by `detect-apple-fm`) | Offer `:apple-fm` / `apple-foundationmodel` (no-network on-device option for Apple Silicon). Ranked below (e) only because availability is narrow. |
| g | Stop with instructions | All of the above failed (no egress, no install possible, no creds) | Write a stub `config.edn` with `:bootstrap/incomplete true` and a `:bootstrap/next-steps [str]` list. Exit non-zero. The user sees concrete copy-paste instructions for the cheapest unblock path their OS / network supports. |

### 5.1 The Ollama + GLM rung in detail (rung e)

This is the rung most users will fall into on a fresh laptop. Concretely:

1. **Confirm install.** If `ollama` is not on PATH:
   - macOS: prompt to run `brew install ollama` (or the official `.pkg` from `https://ollama.com/download/Ollama-darwin.zip` if Homebrew is absent). We do NOT shell out to a `curl | sh` pipeline — the user gets a copy-pasteable command, not a hidden install.
   - Linux: prompt to run the official installer URL (printed in full, never piped without consent). Detect distro via `/etc/os-release` to print the right packaging hint.
   - Windows / unsupported: print the download URL and stop the ladder at rung (g).
2. **Start the daemon.** If `ollama` is installed but the daemon isn't reachable:
   - macOS: `ollama serve` in the background via `launchd` is the right answer in steady state; for the immediate session we shell out to `nohup ollama serve >/dev/null 2>&1 &` after user confirmation.
   - Linux: `systemctl --user start ollama` if a unit is present, otherwise the same `nohup` fallback.
   - Wait up to 10 seconds for `http://localhost:11434` to answer; bail out otherwise.
3. **Pull the model.** Two choices the user picks between:

    | Option | Disk | First-token latency | Egress | Notes |
    |---|---|---|---|---|
    | `glm-4.5-air` (local) | ~7 GB | ~0.5–2 s on Apple Silicon | One-time download from `huggingface.co` / `ollama.com` | Recommended default. Works fully offline after pull. |
    | `glm-5:cloud` | 0 | network round-trip | Every request hits `ollama.com` cloud | Recommended when disk-constrained, on a fast network. Requires `ollama signin`; uses Ollama Cloud's free tier. |
    | `glm-5.1` (HF direct) | varies (FP8 ≈ 80 GB) | depends on GPU | one-time | Only offered if `pulled-models` already contains it; we do not propose a GLM-5.1 pull from the wizard. |

    The pull progress (`ollama pull <model>`) is streamed to the wizard with a simple percentage / ETA renderer; the user can `Ctrl-C` to abandon, which writes a partial-state row to the bootstrap log and falls through to rung (g).

4. **Smoke test.** After the pull succeeds, the wizard sends a one-token chat to the model (`clj-llm/predict` with a trivial prompt and a 30-second timeout). On success, write `:default-provider :ollama` / `:default-model <chosen>` and emit `Ready: <model> via Ollama`. On failure, log the response, do NOT write the model as default, and offer the user the choice between retry, try `glm-5:cloud`, or stop.

### 5.2 Why GLM, and why we accept the cloud variant

GLM-4.5-Air and GLM-5/5.1 are open-weight, MIT-licensed releases from Z.ai (formerly Zhipu) packaged in Ollama's registry. They are agent-tuned (multi-turn, tool-call-friendly) and small enough that the Air variant runs interactively on an 8 GB-class GPU or recent Apple Silicon. Accepting `glm-5:cloud` as a fallback within the same provider (`:ollama`) lets us avoid a "disk too small" failure mode on consumer laptops without introducing a separate provider key. Ollama Cloud's free tier requires `ollama signin` but not an API key configured in env vars, so it slots cleanly under the existing `:ollama` provider entry in `clj-llm`. (If, by the time we ship, Ollama removes free-tier access, the ladder falls through to rung (f) or (g); see §11.)

---

## 6. Phase 3 — HANDOFF (optional, opt-in)

Once phase 2 has written a `config.edn` with a reachable LLM, the wizard prints:

```
Bootstrapping complete.
  LLM:      ollama / glm-4.5-air
  Sandbox:  standard
  Project:  /Users/.../my-repo

Continue with LLM-assisted configuration?
  [1] Yes — enter config-agent now (recommended)
  [2] No  — keep deterministic defaults, exit
Select [1-2] (default: 1):
```

Selecting `[1]` is equivalent to spawning `bb tui run -a config-agent` in-process. `config-agent` (designed in a sibling doc; this design only fixes its contract):

- **Inputs:** the same `dirs` / `existing-config` the wizard used.
- **Tools available:** `read-edn-config`, `write-edn-config!`, `env-detect/detect-all`, `mcp$server` / `mcp$tools`, `permissions$*` (allowlist edits), `query$llm`.
- **Output:** writes through `write-edn-config!`, same schema. Never writes outside `:permissions`, `:mcp`, `:agent`, `:environment.sandbox-mode` keys — `:llm.default-provider` / `:llm.default-model` are owned by bootstrap (config-agent can *suggest* changes via a confirmation flow, but the flow re-uses §5's ladder, not free-form writes).

If the user picks `[2]` — or if `--auto` is in effect — phase 3 is skipped, and `cmd-config` exits. The deterministic defaults written by phase 2 (and the existing-config merge for steps 2–6) are the contract the user is left with.

---

## 7. CLI Surface

```
bb tui config [opts]

Options:
  --auto                    Non-interactive mode. Apply paved-path defaults per §9.
                            No prompts; exits non-zero if bootstrap cannot
                            produce a reachable LLM without user input.
  --profile NAME            Apply a named profile (e.g. "dev", "ci", "offline")
                            before/instead of detection. Profiles are EDN files
                            under bases/agent-tui/resources/profiles/.
  --skip-handoff            Run phases 1–2, skip phase 3 regardless of TTY.
  --re-bootstrap            Force phase 2 to re-run even if (a) would apply
                            (i.e., overwrite an existing reachable LLM choice).
  --dry-run                 Run phases 1–2 to completion but do not write
                            config.edn; print the diff instead.
  --log PATH                Override bootstrap-log location (default:
                            ~/.brainyard/bootstrap-log.edn).
```

`bb tui config` with no flags is the interactive path (current default, this is preserved). `bb tui config --auto --profile ci` is the entry point for the (planned) devcontainer first-run hook.

---

## 8. `config.edn` Schema (delta)

The existing schema (per `agent/core/config.clj`) is preserved. Bootstrap touches three keys plus two new ones:

```clojure
{:version    1
 :created-at "2026-05-14T17:42:00Z"
 :updated-at "2026-05-14T17:42:00Z"

 :llm
 {:default-provider :ollama
  :default-model    "glm-4.5-air"
  :available-providers [:ollama :openai]}        ;; populated by phase 1

 :environment
 {:sandbox-mode :standard
  :sandbox-type :none
  :os           {:name "Mac OS X" :version "26.1" :arch "aarch64"}
  :executables  {:git "/opt/homebrew/bin/git", ...}}

 :permissions
 {:mode :ask-each-time
  :allowed-dirs ["/Users/jane/code/brainyard"]}

 :agent
 {:default-agent :coact-agent
  :max-iterations 100
  :enable-context-budget true}

 :mcp {:servers {}}

 ;; --- NEW keys ---
 :bootstrap
 {:rung      :ollama-pull         ;; which ladder rung produced the LLM
  :installed [:ollama]            ;; binaries the wizard installed this run
  :pulled    ["glm-4.5-air"]      ;; models pulled this run
  :smoke-test {:ok? true
               :latency-ms 412
               :ts "2026-05-14T17:42:14Z"}
  :next-steps []                  ;; non-empty only on rung (g) failure
  :incomplete false}}             ;; true only when ladder stopped at (g)
```

`:bootstrap` is read by `config-agent` (and by `bb tui run` for the "LLM unreachable" diagnostic) but never re-written outside the wizard. The `bootstrap-log.edn` is a sibling file that retains the last 5 runs' `:bootstrap` blocks plus a phase-by-phase trace.

---

## 9. Non-Interactive Profiles (`--auto`)

| Profile | Scenario | Rung priority |
|---|---|---|
| `dev` (default for `--auto`) | Developer laptop, fast network, expects local model | (a) → (b) → (c) → (d) → (e: `glm-4.5-air` local) → fail |
| `ci`  | Continuous integration, no GPU, network egress to `huggingface.co` blocked | (a) → (b) → (c) → fail (no install, no pull, no Ollama Cloud) |
| `offline` | Air-gapped laptop / customer demo | (a) → (b) → (c) → (d) → fail (no pull, no install) |
| `cloud` | Disk-constrained, ok with cloud calls | (a) → (b) → (c) → (e: `glm-5:cloud` only) → fail |

Profiles live at `bases/agent-tui/resources/profiles/<name>.edn` and are merged on top of detection results before §5 runs. Profiles cannot turn on installation; the install/pull rung is the same opt-in everywhere.

---

## 10. Provider Priority (rung b)

When multiple API-key providers are reachable, the wizard preselects in this order:

```
anthropic → openai → google → azure → deepseek → groq → mistral
          → together → fireworks → openrouter
```

The user can override the preselection in interactive mode. The order is centralised in `env-detect/core/providers.clj` as `provider-priority` (new vec), not duplicated in the wizard — so adding a provider is one place to edit. The order reflects (a) maturity of tool-use support, (b) typical default-quality-for-agent-work, and (c) the fact that the brainyard team's own `coact-agent` is tuned against Anthropic models. None of this is locked in: `--profile` and the interactive picker both override it.

---

## 11. Edge Cases and Failure Modes

1. **Ollama daemon already running with no GLM model.** Skip install; offer pull. Same UX as rung (e) starting from step 3.
2. **Pull aborted partway.** `ollama` leaves a partial blob; `ollama list` will not show the model. Wizard treats it the same as "model not pulled" and re-offers from step 3. The bootstrap log notes the abort.
3. **API key set but invalid.** `detect-all-providers` reports `:available? true` purely on env-var presence. Smoke test (§5.1 step 4 — performed for the *selected* provider, not only Ollama) catches this. On smoke-test failure, the wizard drops the provider from the selection menu and continues down the ladder.
4. **Ollama Cloud free tier removed or rate-limited.** The `glm-5:cloud` option is detected at pull time (`ollama pull glm-5:cloud` succeeds or fails fast). If it fails, the wizard tells the user, removes the option, and falls back to `glm-4.5-air` local (with size warning) or rung (g).
5. **`~/.brainyard/config.edn` corrupted (unreadable EDN).** `read-edn-config` returns `{}` on parse error today. The wizard prints a warning, renames the bad file to `config.edn.bad-<ts>`, and starts fresh.
6. **Disk full during pull.** `ollama pull` errors; the wizard surfaces stderr verbatim and offers `glm-5:cloud` as a degrade path before falling to rung (g).
7. **Non-TTY stdin (piped, CI without `--auto`).** The wizard refuses to start. Exit code 2 with "Use `--auto` for non-interactive runs."
8. **Re-running `bb tui config` after success.** Rung (a) applies. Wizard prints the no-op message and exits 0. `--re-bootstrap` forces re-run.
9. **`config-agent`.** **As-built:** `config-agent` shipped (`components/agent/src/ai/brainyard/agent/common/config_agent.clj`; full design in `config-agent-design.md`), so Phase 3's `[1] enter config-agent` now launches the real agent in-process (`spawn-config-agent!` in `config_wizard.clj`) rather than the `config-agent-stub` placeholder this design originally proposed. There is no `config-agent-stub` in the tree.

---

## 12. Migration

The current wizard (`config_wizard.clj`) is replaced wholesale in one PR. Compatibility points:

- `agent/core/config.clj` (`read-edn-config`, `write-edn-config!`, `default-runtime-config`, `init-dirs!`) is unchanged. Existing on-disk configs are forward-compatible: the new `:bootstrap` key is optional and defaults to `{:incomplete false :rung :existing}` when missing.
- `bb tui config` invocation is unchanged.
- `projects/agent-tui-app/.../main.clj`'s `cmd-config` becomes a 3-line dispatch: parse opts, call new `config-wizard/run!`, exit with its return code.
- Old behaviour ("write nil provider if nothing detected") is removed. Anyone scripting against the old behaviour gets a clear failure (`:bootstrap/incomplete true`) instead of a silent broken config.
- Existing `config.edn` files keep working. The wizard's existing-config merge logic (steps 2–6) is preserved verbatim; only step 1 is replaced by the ladder.

No data migration is required.

---

## 13. Testing Plan

Three test surfaces.

1. **Pure detection + ladder selection** (unit). `env-detect/interface` is stubbed; for each of 12 scenario fixtures (no creds + ollama installed; api key + ollama running; everything missing; corrupted config; …) assert that `bootstrap/choose-rung` returns the expected rung and provider/model pair. Fast, hermetic.
2. **Wizard shell, interactive** (integration). A `StubIO` reader/writer pair feeds canned input; assert the printed transcript matches a golden file. Covers re-prompt loops, defaults, summary rendering.
3. **End-to-end Ollama bootstrap** (manual / CI-opt-in). A devcontainer image with no models pulled runs `bb tui config --auto --profile dev`; assert that after exit, `~/.brainyard/config.edn` has `:default-provider :ollama` and a working chat completes via `clj-llm/predict`. Tagged `:slow` because it pulls ~7 GB.

The bootstrap log file is its own contract — a fourth test reads it after each scenario and asserts the rung/install/pull/smoke-test fields are populated as documented in §8.

---

## 14. Open Questions

1. **Should bootstrap also write a `BY_DEFAULT_PROVIDER` line to `~/.brainyard/env`?** Would let `bb` tasks pick up the provider without re-reading `config.edn`. Probably yes, but punt to follow-up.
2. **Does `config-agent` get write access to `:llm.default-provider`?** Current design says no (bootstrap owns it). If users start asking config-agent to "switch me to Claude" we may relax this, gated on the same smoke test.
3. **Apple FM ranking.** Today rung (f) sits below the Ollama install rung. On Apple Silicon with macOS 26+, the user almost certainly prefers `apple-fm` over a 7 GB pull. Consider promoting (f) above (e) when the OS / arch match. Not done in this revision because the Apple FM `apfel` server is still gated behind a separate install we haven't automated.
4. **GLM model choice.** GLM-4.5-Air is recommended on size + agent-tuning grounds. If a smaller, well-supported alternative ships (Qwen3-Coder small? Gemma 3 4B?), revisit; the ladder rung is the same shape, only the default `model` string changes.
