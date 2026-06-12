# nREPL Live Verification

Hot-reload changed TUI / agent code into a running `bb tui` and verify
it without restarting. The running TUI's in-process nREPL server is
both the reload channel (eval `require :reload`) and the inspection
channel (read scrollback, agent state, iteration history). tmux
provides the input path (`send-keys`).

This complements the other test layers in [testing.md](testing.md):
pure-function tests and CliClient integration run offline, while this
technique verifies changes against a live, fullscreen TUI session with
real LLM backends. It is the fastest edit-verify loop for changes
that don't touch the startup path (`run!` / `start!`).

---

## When to use

- You changed a slash command handler, a BT action, a prompt section,
  or a tool implementation and want to verify it without restarting.
- You need to inspect live agent state mid-session (iteration history,
  sandbox bindings, tool registry).
- You're testing agent-level behavior (multi-iteration loops, loop
  guard, answer formatting) with a real LLM backend.
- You want to run a quick smoke test across multiple agents.

When the change is in the startup path (`run!`, `start!`,
`init-fullscreen!`), a restart is required — hot-reload updates the
function definition but the current `run!` is already in the call
stack. See [Startup-path changes](#startup-path-changes) below.

---

## Prerequisites

| What | How |
|------|-----|
| Running `bb tui` | In a tmux pane (any session) |
| nREPL server | Auto-started by `bb tui` when `BY_NREPL_ENABLED=true` in `.env`, or via `bb repl:ata` in a separate terminal |
| `clj-nrepl-eval` | On `$PATH` — the CLI tool for evaluating Clojure via nREPL |
| tmux | For sending keystrokes to the TUI pane |

---

## Setup

### 1. Discover the nREPL port

```bash
clj-nrepl-eval --discover-ports
```

Output names the directory — look for `projects/agent-tui-app`:

```
  localhost:53890 (clj) - /Users/.../brainyard/projects/agent-tui-app
```

### 2. Identify the tmux pane

```bash
tmux list-panes -s -t <session> \
  -F '#{window_index}.#{pane_index} #{pane_current_command}'
```

The pane running `bb` (or `java`) is the TUI. Reference it as
`<session>:<window>.<pane>` (e.g. `1:1.0`).

### 3. Verify connectivity

```bash
PORT=53890
PANE=1:1.0

clj-nrepl-eval -p $PORT '"connected"'
# => "connected"
```

---

## Hot-reload

Reload every namespace you edited. Always use `:reload`, never
`:reload-all` — the latter transitively rebuilds protocols and breaks
live record instances (see `docs/tui/testing.md` and the
`feedback_no_reload_all_interface` project memory).

```bash
# Single namespace
clj-nrepl-eval -p $PORT \
  "(require '[ai.brainyard.agent-tui.commands :as cmds] :reload)"

# Multiple namespaces
clj-nrepl-eval -p $PORT \
  "(do (require '[ai.brainyard.agent-tui.core :as core] :reload)
       (require '[ai.brainyard.agent-tui.commands :as cmds] :reload)
       :reloaded)"
```

### Symbols containing `!`

The Claude Code sandbox escapes `!` in bash commands. When evaluating
code that references `stop!`, `swap!`, etc., write the code to a temp
file and `load-file` it:

```bash
cat > /tmp/reload.clj << 'CLJ'
(require '[ai.brainyard.agent-tui.core :as core] :reload)
(println "reloaded core, start! and stop! are live")
CLJ
clj-nrepl-eval -p $PORT '(load-file "/tmp/reload.clj")'
```

---

## Inspecting state

All reads go through the nREPL — no `tmux capture-pane` needed.

### Active agent

```bash
clj-nrepl-eval -p $PORT \
  '(let [ag (ai.brainyard.agent-tui.session/get-active-agent)]
     {:agent-id (:agent-id ag)
      :status   (:status @(:!state ag))})'
```

### Scrollback buffer (ANSI-stripped)

```bash
clj-nrepl-eval -p $PORT \
  '(->> @ai.brainyard.agent-tui.layout/!scrollback
        (take-last 15)
        (mapv #(clojure.string/replace % #"\033\[[0-9;]*m" "")))'
```

### Iteration history

```bash
clj-nrepl-eval -p $PORT \
  '(let [ag  (ai.brainyard.agent-tui.session/get-active-agent)
         mem (get-in @(:!state ag) [:behavior-tree :context :st-memory])
         its (:iterations @mem)]
     (mapv (fn [i]
             {:iter    (:iteration i)
              :channel (:channel i)
              :tools   (mapv :tool-name (:tool-results i))
              :sizes   (mapv #(count (str (:tool-result %)))
                             (:tool-results i))})
           its))'
```

### Agent answer

```bash
clj-nrepl-eval -p $PORT \
  '(let [ag  (ai.brainyard.agent-tui.session/get-active-agent)
         mem (get-in @(:!state ag) [:behavior-tree :context :st-memory])
         a   (:answer @mem)]
     (when (seq a) (subs a 0 (min 300 (count a)))))'
```

### Session list

```bash
clj-nrepl-eval -p $PORT \
  '(mapv (fn [s] {:idx (:id s) :agent-id (:agent-id s)})
         (ai.brainyard.agent-tui.sessions/session-list))'
```

### Tool / agent registry

```bash
clj-nrepl-eval -p $PORT \
  '(->> (ai.brainyard.agent.interface/get-tool-defs :type :agent)
        vals (map :id) sort vec)'
```

---

## Driving the TUI

### Send a prompt

```bash
tmux send-keys -t $PANE 'your question here' C-m
```

### Slash commands

Use `C-m` (raw CR) instead of `Enter` — `Enter` is absorbed by the
autocomplete popup for `/`-prefixed input:

```bash
tmux send-keys -t $PANE '/agent new skill-agent' C-m
```

### Clear the input box

```bash
tmux send-keys -t $PANE C-a C-k
```

---

## Waiting for completion

Poll agent status via nREPL. Prefer this over `sleep` + tmux capture.

### Blocking poll (bash)

```bash
until clj-nrepl-eval -p $PORT \
  '(:status @(:!state (ai.brainyard.agent-tui.session/get-active-agent)))' \
  2>&1 | grep -q ':idle'; do
  sleep 3
done
```

### One-shot check

```bash
clj-nrepl-eval -p $PORT \
  '(:status @(:!state (ai.brainyard.agent-tui.session/get-active-agent)))'
```

---

## Verification patterns

### Check scrollback for expected text

```bash
clj-nrepl-eval -p $PORT \
  '(->> @ai.brainyard.agent-tui.layout/!scrollback
        (take-last 30)
        (filter #(clojure.string/includes? % "EXPECTED"))
        count)'
```

### Check for errors

```bash
clj-nrepl-eval -p $PORT \
  '(->> @ai.brainyard.agent-tui.layout/!scrollback
        (filter #(or (clojure.string/includes? % "Error")
                     (clojure.string/includes? % "Exception")))
        (take-last 5)
        (mapv #(clojure.string/replace % #"\033\[[0-9;]*m" "")))'
```

### Verify a code change (full cycle)

```
1. Edit source file(s)
2. Hot-reload the changed namespace(s)
3. Send a prompt that exercises the change via tmux
4. Poll for :idle via nREPL
5. Read scrollback / agent answer to verify
```

---

## Verifying session persistence (project-scoped)

Persisted sessions are **project-scoped**: they live under
`<project>/.brainyard/sessions/<id>/`, where `<project>` is the git root
(`config/sessions-root`, honoring `-C` / `BY_PROJECT_DIR`), **not**
`~/.brainyard/sessions/`. Because `bb tui` `cd`s into
`projects/agent-tui-app/`, this also exercises git-root resolution — the
session must land at the repo root, not the cwd subdir. Verify against a
live session:

```bash
PORT=<discovered>
SID=<from the banner: "session agt-…">

# 1. Live root resolves to the project, via the base resolver → agent/sessions-root
clj-nrepl-eval -p $PORT '(str (ai.brainyard.agent-tui-persist.interface/root-dir))'
clj-nrepl-eval -p $PORT '(ai.brainyard.agent.interface/sessions-root)'
#   => "<repo-root>/.brainyard/sessions"

# 2. After a turn, every file is written under the project root
ls "$(git rev-parse --show-toplevel)/.brainyard/sessions/$SID/"
#   => messages.log session.edn scrollback.stream.txt trajectory.edn turn.complete …

# 3. No leak: the session is NOT under the user-global dir
ls ~/.brainyard/sessions/$SID 2>&1   # => No such file or directory
```

### Resume + resume picker

`--resume <id>` and bare `--resume` (interactive picker) both read from the
project-scoped root, so the picker lists only the **current repo's**
sessions — never the user-global set. After a restart the nREPL port
changes (re-run `--discover-ports`).

```bash
# Specific id (restart, then resume)
BY_NREPL_ENABLED=true bb tui -p claude-code -m opus --resume "$SID"

# Bare picker — capture the menu, then pick by number (cooked read-line → plain CR)
BY_NREPL_ENABLED=true bb tui -p claude-code -m opus --resume
tmux capture-pane -t $PANE -p          # menu lists only this project's sessions
tmux send-keys   -t $PANE '2' C-m      # pick #2

# Confirm the resumed session + restored conversation (new PORT after restart)
clj-nrepl-eval -p $NEWPORT \
  '(let [ag (ai.brainyard.agent-tui.session/get-active-agent)]
     {:sid (ai.brainyard.agent.interface/session-id ag)
      :msgs (mapv :role (:messages @(:!session ag)))})'
```

The active agent is briefly nil right after the pick while the session
restores — poll until `get-active-agent` is non-nil before inspecting.

---

## Multi-agent smoke test

Switch through agents and send a simple prompt to each:

```bash
for AGENT in skill-agent plan-agent eval-agent explore-agent react-agent; do
  tmux send-keys -t $PANE "/agent new $AGENT" C-m
  sleep 3
  tmux send-keys -t $PANE "hello, what can you do?" C-m

  until clj-nrepl-eval -p $PORT \
    '(:status @(:!state (ai.brainyard.agent-tui.session/get-active-agent)))' \
    2>&1 | grep -q ':idle'; do sleep 3; done

  echo "=== $AGENT ==="
  clj-nrepl-eval -p $PORT \
    '(->> @ai.brainyard.agent-tui.layout/!scrollback
          (take-last 5)
          (mapv #(clojure.string/replace % #"\033\[[0-9;]*m" "")))'
done
```

---

## Startup-path changes

Changes to `run!`, `start!`, `init-fullscreen!`, or the session-picker
flow require a TUI restart — the current `run!` is in the call stack
and won't pick up the new definition.

```bash
# 1. Hot-reload so the JVM has the new code
clj-nrepl-eval -p $PORT \
  "(require '[ai.brainyard.agent-tui.core :as core] :reload)"

# 2. Quit the TUI
tmux send-keys -t $PANE '/quit' C-m
sleep 3

# 3. Restart
tmux send-keys -t $PANE 'bb tui' C-m
sleep 8

# 4. Start a new session (or pick one to resume)
tmux send-keys -t $PANE 'N' C-m

# 5. Re-discover nREPL port (new JVM = new port)
clj-nrepl-eval --discover-ports
```

---

## Key namespaces

| Namespace | What it exposes |
|-----------|----------------|
| `a.b.agent-tui.session` | `get-active-agent`, `!tui-state` |
| `a.b.agent-tui.sessions` | `session-list`, `get-session`, `switch-to!` |
| `a.b.agent-tui.layout` | `!scrollback`, `!layout`, `fullscreen?` |
| `a.b.agent-tui.core` | `start!`, `stop!`, `run!`, `ask` |
| `a.b.agent.interface` | `get-tool-defs`, `session-id` |
| `a.b.clj-llm.interface` | `get-default-lm` |

(`a.b` = `ai.brainyard`)

---

## Gotchas

1. **`:reload` not `:reload-all`.** `:reload-all` transitively
   rebuilds protocols; live record instances (agents, sessions) stop
   satisfying the new protocol version. Use `:reload` on the specific
   namespace you changed.

2. **`!` in symbol names.** The Claude Code sandbox escapes `!` to
   `\!`. Write code to a temp `.clj` file and `load-file` it.

3. **Startup-path code.** `run!` and `start!` are already in the call
   stack — hot-reload updates the var but the running invocation uses
   the old bytecode. Requires a restart (see above).

4. **Protocol / defrecord changes.** If you change a `defprotocol` or
   `defrecord`, existing instances are stale. Restart the TUI.

5. **nREPL port changes on restart.** After `/quit` + `bb tui`, the
   new JVM gets a new nREPL port. Re-run `--discover-ports`.

6. **tmux `/quit` + relaunch race.** `/quit` + immediate `bb tui`
   can collide in the pane input buffer. Wait for the shell prompt
   (or use `kill-session` + `new-session`).
