# Task Management System — Design Document

Background task execution layer for the brainyard agent. Enables async work units (bash commands, tool invocations, CliClient processes) that run independently of the TUI input loop, with real-time notifications and LLM-driven task creation.

## Table of Contents

- [Architecture](#architecture)
- [Task Record & Protocols](#task-record--protocols)
- [TaskManager](#taskmanager)
- [Job Executors](#job-executors)
- [TUI Integration](#tui-integration)
- [LLM Commands](#llm-commands)
- [File Structure](#file-structure)
- [Implementation Phases](#implementation-phases)
- [Verification](#verification)

---

## Architecture

```
User Input (TUI)
       │
       ├── :tasks / :task / :task-cancel / :task-log / :task-run ──► [TaskManager]
       │                                                                  │
       └── normal question ──► Agent ask (existing, unchanged)    [!tasks atom]
                                                                         │
                                                           ┌─────────────┼─────────────┐
                                                           │             │             │
                                                           ▼             ▼             ▼
                                                     BashExecutor  ToolExecutor  CliClientExecutor
                                                           │             │             │
                                                           └──────┬──────┘──────┬──────┘
                                                                  │             │
                                                           Output capture   Completion
                                                          (per-task atom)   notification
                                                                  │             │
                                                                  ▼             ▼
                                                         [TUI watch on !tasks]
                                                         emit! + update-status-bar!
```

### Design Principles

1. **Atom-based registry** (`!tasks`) — follows `!tool-defs`, `!agent-registry` patterns
2. **Watch-based notifications** — follows `handle-st-memory-change` in `tui/session.clj`
3. **ExecutorService for concurrency** — fixed thread pool (not Clojure agents, which are agent-specific)
4. **IJobExecutor protocol** — polymorphic dispatch per job type
5. **defcommand registration** — task commands appear in `!tool-defs` for LLM use
6. **Configurable output buffer** — ring buffer with per-task max-lines setting

---

## Task Record & Protocols

### File: `task/protocol.clj`

```clojure
(ns ai.brainyard.agent.task.protocol
  "Task management protocols, records, and status definitions.")

;; Status lifecycle: :pending → :running → :completed | :failed | :cancelled

(defrecord Task
  [id              ;; keyword, e.g. :task-1 — monotonically generated
   name            ;; string, human-readable description
   job-type        ;; :bash | :tool | :cli-client
   job-config      ;; map, job-type-specific (see below)
   status          ;; :pending | :running | :completed | :failed | :cancelled
   created-at      ;; epoch ms (System/currentTimeMillis)
   started-at      ;; epoch ms | nil
   completed-at    ;; epoch ms | nil
   result          ;; any — final result value or {:error "message"}
   output-lines    ;; atom<vector<string>> — captured stdout/stderr lines
   max-output-lines ;; int, default 10000 — ring buffer cap
   future-ref      ;; java.util.concurrent.Future | nil — for cancellation
   schedule        ;; {:cron "..."} | nil — reserved for future cron support
   metadata])      ;; map — arbitrary user metadata (tags, retried-from, etc.)

(defprotocol IJobExecutor
  "Protocol for executing different job types.
   Each implementation handles one job-type."
  (execute-job [this task on-output]
    "Execute the task's job synchronously (called from executor thread).
     on-output: (fn [line-string]) — called for each output line, thread-safe.
     Returns result map: {:exit-code N :output \"...\"} or {:error \"...\"}
     or {:result <value>} for tool jobs.")
  (cancel-job [this task]
    "Cancel a running job. Returns true if cancellation was initiated.")
  (job-type [this]
    "Return the keyword job type this executor handles: :bash | :tool | :cli-client"))

(defprotocol ITaskManager
  "Central task coordinator. Manages registry, execution, and lifecycle."
  (create-task [this name job-type job-config]
               [this name job-type job-config opts]
    "Create and register a new task (status :pending). Returns Task.
     opts: {:schedule {:cron \"...\"} :metadata {...} :max-output-lines N}")
  (start-task [this task-id]
    "Start a :pending task. Submits to executor, sets :running. Returns Task.")
  (cancel-task [this task-id]
    "Cancel a :running task. Returns updated Task or nil.")
  (retry-task [this task-id]
    "Retry a :failed/:cancelled task. Creates new task with same config. Returns new Task.")
  (remove-task [this task-id]
    "Remove task from registry. Cancels if running. Returns nil.")
  (get-task [this task-id]
    "Get task by ID. Returns Task or nil.")
  (list-tasks [this]
              [this filters]
    "List all tasks, optionally filtered by {:status :kw :job-type :kw}.")
  (shutdown [this]
    "Shutdown: cancel all running tasks, shutdown executor service, clear registry."))
```

### job-config Shapes

```clojure
;; ── :bash ──────────────────────────────────────────────────
{:command      "ls -la /tmp"         ;; string — shell command (passed to /bin/sh -c)
 :working-dir  "/path/to/dir"        ;; optional — ProcessBuilder directory
 :env          {"KEY" "VAL"}         ;; optional — environment variables
 :timeout-ms   120000}               ;; optional — process wait timeout (default 120s)

;; ── :tool ──────────────────────────────────────────────────
{:tool-id    :list-tools             ;; keyword — from !tool-defs registry
 :tool-args  {:type "command"}}      ;; map — arguments for invoke-tool

;; ── :cli-client ────────────────────────────────────────────
{:command        ["bash" "-c" "cd ROOT && set -a && source .env && bb tui ..."]
 :working-dir    "/path"             ;; optional
 :env            {"KEY" "VAL"}       ;; optional
 :interaction-fn (fn [cli-client]    ;; function that drives the CliClient
                   ;; Use cli/wait-for, cli/send-line!, cli/wait-for-idle
                   ;; Return result map when done
                   {:output (cli/get-all-output cli-client)})}
```

---

## TaskManager

### File: `task/manager.clj`

```clojure
(ns ai.brainyard.agent.task.manager
  (:require [ai.brainyard.agent.task.protocol :as tp]
            [ai.brainyard.agent.task.executor :as executor])
  (:import [java.util.concurrent Executors ExecutorService]))

;; ── Global State ─────────────────────────────────────────
(defonce !tasks (atom {}))           ;; {task-id → Task}
(defonce !task-counter (atom 0))     ;; monotonic ID: :task-1, :task-2, ...
(defonce ^:private !default-manager (atom nil))
(defonce ^:private !executor-service (atom nil))

(defn get-default-manager [] @!default-manager)
(defn set-default-manager! [tm] (reset! !default-manager tm))

;; ── TaskManager Record ───────────────────────────────────
(defrecord TaskManager [executors]   ;; {:bash BashJobExecutor, :tool ToolJobExecutor, ...}
  tp/ITaskManager
  ;; ... protocol implementation ...
  )

;; ── Factory ──────────────────────────────────────────────
(defn create-task-manager
  "Create a TaskManager with all standard executors.
   Options: :pool-size (default 4)"
  [& {:keys [pool-size] :or {pool-size 4}}]
  (when-not @!executor-service
    (reset! !executor-service (Executors/newFixedThreadPool pool-size)))
  (->TaskManager {:bash       (executor/->BashJobExecutor)
                  :tool       (executor/->ToolJobExecutor)
                  :cli-client (executor/->CliClientJobExecutor)}))
```

### Execution Flow

```
create-task(name, :bash, {:command "echo hi"})
  │
  ├── Generate ID (:task-1)
  ├── Build Task record (status :pending, output-lines (atom []))
  ├── swap! !tasks assoc :task-1 task
  └── Return task

start-task(:task-1)
  │
  ├── Lookup executor for :bash → BashJobExecutor
  ├── Define on-output callback (ring-buffer append to output-lines atom)
  ├── Submit to ExecutorService:
  │     (fn []
  │       (try
  │         (execute-job executor task on-output) → result
  │         (swap! !tasks update :task-1 → :completed + result)
  │       (catch InterruptedException → :cancelled)
  │       (catch Exception → :failed + error)))
  ├── swap! !tasks update :task-1 → :running + future-ref
  └── Return updated task

  ──── Watch on !tasks fires ─────
  │
  ├── TUI detects :pending → :running transition → emit notification
  ├── ... time passes, output lines accumulate ...
  ├── TUI detects :running → :completed transition → emit notification
  └── Update status bar (running task count)
```

### Output Ring Buffer

Each task has a configurable `max-output-lines` (default 10,000). The `on-output` callback enforces this:

```clojure
(defn- make-on-output
  "Create a thread-safe on-output callback with ring-buffer behavior."
  [output-lines-atom max-lines]
  (fn [line]
    (swap! output-lines-atom
           (fn [lines]
             (let [lines (conj lines line)]
               (if (> (count lines) max-lines)
                 (subvec lines (- (count lines) max-lines))
                 lines))))))
```

Configurable per-task via job-config or opts:

```clojure
;; Long-running log tailer — larger buffer
(tp/create-task tm "log-watch" :bash
  {:command "tail -f /var/log/app.log"}
  {:max-output-lines 50000})

;; Quick command — default 10K is fine
(tp/create-task tm "disk-check" :bash
  {:command "df -h"})
```

### Thread Safety

| State | Type | Concurrency |
|-------|------|-------------|
| `!tasks` | atom | CAS via `swap!` — safe for concurrent updates |
| `output-lines` | atom (per-task) | `swap!` from executor threads — safe |
| `!executor-service` | atom | Set once at init — effectively immutable |
| `!default-manager` | atom | Set once at TUI start — effectively immutable |
| TUI notification | watch callback | Runs on swapping thread; `emit!` uses `locking output-lock` |

---

## Job Executors

### File: `task/executor.clj`

### BashJobExecutor

```clojure
(defrecord BashJobExecutor []
  tp/IJobExecutor
  (execute-job [_ task on-output]
    (let [{:keys [command working-dir env timeout-ms]
           :or {timeout-ms 120000}} (:job-config task)
          pb (ProcessBuilder. ^java.util.List ["/bin/sh" "-c" command])]
      (.redirectErrorStream pb true)  ;; merge stderr into stdout
      (when working-dir (.directory pb (java.io.File. ^String working-dir)))
      (when env
        (let [env-map (.environment pb)]
          (doseq [[k v] env] (.put env-map (str k) (str v)))))
      (let [proc (.start pb)
            reader (BufferedReader. (InputStreamReader. (.getInputStream proc)))]
        (try
          (loop []
            (when-let [line (.readLine reader)]
              (on-output line)
              (recur)))
          (.waitFor proc (long timeout-ms) java.util.concurrent.TimeUnit/MILLISECONDS)
          (let [exit-code (.exitValue proc)]
            (if (zero? exit-code)
              {:exit-code 0}
              {:error (str "Exit code: " exit-code) :exit-code exit-code}))
          (catch InterruptedException _
            {:error "cancelled"})
          (finally
            (.destroyForcibly proc))))))

  (cancel-job [_ _task] true)   ;; future.cancel(true) interrupts the thread
  (job-type [_] :bash))
```

### ToolJobExecutor

```clojure
(defrecord ToolJobExecutor []
  tp/IJobExecutor
  (execute-job [_ task on-output]
    (let [{:keys [tool-id tool-args]} (:job-config task)]
      (on-output (str "Invoking: " (name tool-id) " " (pr-str tool-args)))
      (let [result (apply tool/invoke-tool tool-id (mapcat identity tool-args))]
        (on-output (str "Result: " (pr-str result)))
        {:result result})))

  (cancel-job [_ _task] false)  ;; tool invocations are synchronous, not interruptible
  (job-type [_] :tool))
```

### CliClientJobExecutor

```clojure
(defrecord CliClientJobExecutor []
  tp/IJobExecutor
  (execute-job [_ task on-output]
    (let [{:keys [command working-dir env interaction-fn]} (:job-config task)
          client (cli/start! :command command
                             :working-dir working-dir
                             :env env)]
      (try
        ;; Background thread forwards captured output to task's on-output
        (let [forwarder (future
                          (loop [cursor 0]
                            (Thread/sleep 100)
                            (let [lines @(:!lines client)
                                  new-lines (when (> (count lines) cursor)
                                              (subvec lines cursor))]
                              (doseq [line new-lines] (on-output line))
                              (when @(:!running client)
                                (recur (count lines))))))]
          (if interaction-fn
            (let [result (interaction-fn client)]
              (future-cancel forwarder)
              {:result result})
            (let [exit-code (cli/shutdown! client)]
              (future-cancel forwarder)
              {:exit-code exit-code})))
        (catch Exception e
          {:error (ex-message e)})
        (finally
          (.close client)))))

  (cancel-job [_ _task] true)
  (job-type [_] :cli-client))
```

---

## TUI Integration

### Status Bar

**Current** (`tui/layout.clj` `format-status`):
```
running │ 3 calls │ 1,234 tokens │ $0.0012
```

**New** — add running task count when > 0:
```
running │ 2 tasks │ 3 calls │ 1,234 tokens │ $0.0012
```

Modify `format-status` in `tui/layout.clj:463`:

```clojure
(defn format-status
  [{:keys [status calls tokens cost tasks-running]}]
  (let [status-str  (case status ...)
        tasks-str   (when (and tasks-running (pos? tasks-running))
                      (ansi/style (str tasks-running " task" (when (> tasks-running 1) "s"))
                                  ansi/bold ansi/bright-yellow))
        calls-str   (ansi/muted (str (or calls 0) " calls"))
        ...]
    ;; Insert tasks-str between status-str and calls-str when present
    ))
```

Modify `update-status-bar!` in `tui/session.clj:290` to include task count:

```clojure
(let [tasks-running (count (filter #(= :running (:status %)) (vals @manager/!tasks)))]
  (layout/draw-status-bar!
    (layout/format-status
      {:status ... :calls ... :tokens ... :cost ...
       :tasks-running tasks-running})))
```

### Meta-Commands

Add to `handle-input-line` in `tui/core.clj:1076`:

| Command | Args | Description |
|---------|------|-------------|
| `:tasks` | — | List all tasks (table: ID, Status, Type, Name, Elapsed) |
| `:task` | `ID` | Show detailed task info |
| `:task-cancel` | `ID` | Cancel a running task |
| `:task-log` | `ID [N]` | Show task output (last N lines, default all) |
| `:task-run` | `CMD` | Quick-run bash command as background task |

```clojure
;; In handle-input-line case dispatch:
":tasks"       (do (emit-command-header! ":tasks") (tasks-cmd) :continue)
":task"        (do (emit-command-header! ":task") (task-detail-cmd args) :continue)
":task-cancel" (do (emit-command-header! ":task-cancel") (task-cancel-cmd args) :continue)
":task-log"    (do (emit-command-header! ":task-log") (task-log-cmd args) :continue)
":task-run"    (do (emit-command-header! ":task-run") (task-run-cmd args) :continue)
```

Implementation functions (in `tui/core.clj`):

```clojure
(defn- tasks-cmd []
  (if-let [mgr (manager/get-default-manager)]
    (tui-session/emit! (task-fmt/format-task-list (tp/list-tasks mgr)))
    (tui-session/emit! (ansi/warning "Task manager not initialized."))))

(defn- task-detail-cmd [args]
  (if-let [mgr (manager/get-default-manager)]
    (let [task-id (keyword (str/trim args))]
      (if-let [task (tp/get-task mgr task-id)]
        (tui-session/emit! (task-fmt/format-task-detail task))
        (tui-session/emit! (ansi/warning (str "Task not found: " (name task-id))))))
    (tui-session/emit! (ansi/warning "Task manager not initialized."))))

(defn- task-cancel-cmd [args]
  (if-let [mgr (manager/get-default-manager)]
    (let [task-id (keyword (str/trim args))]
      (if (tp/cancel-task mgr task-id)
        (tui-session/emit! (ansi/success (str "Cancelled: " (name task-id))))
        (tui-session/emit! (ansi/warning (str "Could not cancel: " (name task-id))))))
    (tui-session/emit! (ansi/warning "Task manager not initialized."))))

(defn- task-log-cmd [args]
  (if-let [mgr (manager/get-default-manager)]
    (let [parts (str/split (str/trim args) #"\s+" 2)
          task-id (keyword (first parts))
          last-n  (some-> (second parts) parse-long)]
      (if-let [task (tp/get-task mgr task-id)]
        (tui-session/emit! (task-fmt/format-task-output task last-n))
        (tui-session/emit! (ansi/warning (str "Task not found: " (name task-id))))))
    (tui-session/emit! (ansi/warning "Task manager not initialized."))))

(defn- task-run-cmd [args]
  (if-let [mgr (manager/get-default-manager)]
    (let [cmd  (str/trim args)
          name (str "bash: " (subs cmd 0 (min 40 (count cmd))))
          task (tp/create-task mgr name :bash {:command cmd :timeout-ms 120000})]
      (tp/start-task mgr (:id task))
      (tui-session/emit! (ansi/success (str "Started " (name (:id task)) ": " name))))
    (tui-session/emit! (ansi/warning "Task manager not initialized."))))
```

### Watch-Based Notifications

Add to `tui/session.clj`:

```clojure
(defn- handle-tasks-change
  "Watch callback for !tasks atom. Detects status transitions,
   emits inline notifications, and refreshes status bar."
  [_ _ old-tasks new-tasks]
  (doseq [[task-id new-task] new-tasks]
    (let [old-task    (get old-tasks task-id)
          old-status  (:status old-task)
          new-status  (:status new-task)]
      (when (and old-status (not= old-status new-status))
        ;; Emit inline notification for completed/failed/cancelled
        (when-let [notif (task-fmt/format-task-notification new-task old-status)]
          (emit! notif))
        ;; Refresh status bar to update running count
        (update-status-bar!)))))
```

Attach in `attach-watches!`:

```clojure
(defn attach-watches! [agent]
  (let [... existing watches ...]
    ;; Add task watch (global, not per-agent)
    (add-watch manager/!tasks ::tui-tasks handle-tasks-change)
    ;; ... store in watches for cleanup
    ))
```

Detach in `detach-watches!`:

```clojure
(remove-watch manager/!tasks ::tui-tasks)
```

### Lifecycle

In `tui/core.clj` `start!` — create TaskManager:

```clojure
;; After agent creation, before emitting welcome banner:
(let [tm (manager/create-task-manager)]
  (manager/set-default-manager! tm))
```

In `tui/core.clj` `stop!` — shutdown TaskManager:

```clojure
;; Before closing agent:
(when-let [tm (manager/get-default-manager)]
  (tp/shutdown tm)
  (manager/set-default-manager! nil))
```

### Help Text

Add to `format-help` in `tui/format.clj:392`:

```clojure
;; Add to cmds vector:
[":tasks"           ""          "List background tasks"]
[":task"            " ID"       "Show task detail"]
[":task-cancel"     " ID"       "Cancel a running task"]
[":task-log"        " ID [N]"   "Show task output (last N lines)"]
[":task-run"        " CMD"      "Run bash command as background task"]
```

---

## LLM Commands

### File: `task/commands.clj`

Registered via `defcommand` into `!tool-defs`, enabling LLM agents to create and manage tasks.

```clojure
(defcommand task$list
  "List all background tasks with their status."
  (fn [& {:as args}] ...)
  :inputs {:status [:string {:desc "Optional filter: pending, running, completed, failed, cancelled"}]})

(defcommand task$cancel
  "Cancel a running background task."
  (fn [& {:as args}] ...)
  :inputs {:task-id [:string {:desc "Task ID to cancel (e.g. task-1)"}]})

(defcommand task$run
  "Start a background task. Pick the job type via :job-type."
  (fn [& {:keys [job-type] :as args}] ...)
  :inputs {:job-type  [:enum "bash" "tool"]
           ;; :bash  → :command (required), :name (opt), :timeout (opt, ms; default 120000)
           ;; :tool  → :tool-id (required), :tool-args (opt JSON object), :name (opt)
           :command   [:string {:desc "For :bash — the shell command" :optional true}]
           :timeout   [:string {:desc "For :bash — timeout in ms (default 120000)" :optional true}]
           :tool-id   [:string {:desc "For :tool — registered tool ID" :optional true}]
           :tool-args [:string {:desc "For :tool — JSON object of tool arguments" :optional true}]
           :name      [:string {:desc "Optional task name" :optional true}]})

(def task-commands
  [#'task$list #'task$detail #'task$cancel #'task$run])
```

### Agent Compatibility

**coact-agent** — **No code change needed.** RLM discovers all tools from `!tool-defs` via `get-visible-tool-defs` at runtime. Task commands registered with `defcommand` automatically appear in `(list-tools)` and are callable via `(call-tool "task$run" {:job-type "bash" :command "echo hello"})`.

**react-agent** — Must add task commands to `:agent-tools {:tools ...}` in the `defagent` declaration. `bind-tools` now accepts any mix of tool/command/skill/agent vars under a single `:tools` key; the type is read from each var's metadata.

```clojure
;; Option A: Add to bootstrap-tools
(def bootstrap-tools
  (vec (concat [#'list-tools #'get-tool-details #'call-tools]
               task-commands)))

;; Option B: Add separately in defagent
(defagent react-agent
  ...
  :agent-tools {:tools (vec (concat bootstrap-tools task-commands))}
  ...)
```

LLM calls via `call-tools` JSON:
```json
[{"tool-name": "task__run-bash", "tool-args": [{"name": "command", "value": "echo hello"}]}]
```

---

## Formatting

### File: `task/format.clj`

Pure functions (no I/O), following the pattern in `tui/format.clj`:

```clojure
(defn format-task-list
  "Format tasks as a table.
   Columns: ID │ Status │ Type │ Name │ Elapsed
   Status is color-coded: running=green, completed=cyan, failed=red, cancelled=yellow"
  [tasks] ...)

(defn format-task-detail
  "Format detailed view for a single task.
   Shows: name, type, status, timestamps, elapsed, result, schedule."
  [task] ...)

(defn format-task-output
  "Format captured output lines for :task-log command.
   Supports last-N lines. Shows line count header."
  [task last-n] ...)

(defn format-task-notification
  "Format inline notification for task state transitions.
   :running   → dim '[task] task-1 started: name'
   :completed → green '✓ [task] task-1 completed: name (3.2s)'
   :failed    → red '✗ [task] task-1 failed: name — error message'
   :cancelled → yellow '[task] task-1 cancelled'"
  [task prev-status] ...)
```

### Example Output

**`:tasks`**
```
Tasks
  ID         Status      Type         Name                           Elapsed
  task-1     completed   bash         bash: echo hello               0.1s
  task-2     running     bash         bash: sleep 30                 12.4s
  task-3     failed      tool         invoke: list-tools             0.3s
```

**`:task task-1`**
```
Task: task-1
  Name:     bash: echo hello
  Type:     bash
  Status:   completed
  Created:  Wed Mar 04 10:30:15 KST 2026
  Started:  Wed Mar 04 10:30:15 KST 2026
  Finished: Wed Mar 04 10:30:15 KST 2026
  Elapsed:  0.1s
  Result:   {:exit-code 0}
```

**`:task-log task-1`**
```
Output: task-1 (1 lines)
  hello
```

**Inline notification (auto-emitted):**
```
  ✓ [task] task-1 completed: bash: echo hello (0.1s)
```

---

## File Structure

### New Files

```
components/agent/src/ai/brainyard/agent/task/
  protocol.clj     — Task record, IJobExecutor, ITaskManager protocols
  manager.clj      — TaskManager record, !tasks atom, execution lifecycle
  executor.clj     — BashJobExecutor, ToolJobExecutor, CliClientJobExecutor
  commands.clj     — defcommand registrations for LLM tool use
  format.clj       — Pure formatting functions for TUI output
  scheduler.clj    — (Future work) Cron parser, ScheduledExecutorService

components/agent/test/ai/brainyard/agent/
  task_test.clj    — Unit tests (no LLM needed)
```

### Modified Files

| File | Changes |
|------|---------|
| `tui/core.clj` | Add 5 meta-commands to `handle-input-line`; create/shutdown TaskManager in `start!`/`stop!` |
| `tui/session.clj` | Add `handle-tasks-change` watch; attach/detach in lifecycle; include `tasks-running` in `update-status-bar!` |
| `tui/layout.clj` | Add `:tasks-running` to `format-status` |
| `tui/format.clj` | Add task commands to `format-help` `cmds` vector |
| `interface.clj` | Export `task-create`, `task-list`, `task-cancel`, `task-get`, `task-shutdown` (lazy-loaded) |
| `common/react_agent.clj` | Add `task-commands` into `:agent-tools :tools` |

---

## Implementation Phases

### Phase 1: Core Infrastructure
**Files:** `task/protocol.clj`, `task/executor.clj` (BashJobExecutor only), `task/manager.clj`

**Deliverable:** Create, start, cancel bash tasks from REPL. No TUI integration yet.

### Phase 2: TUI Integration
**Files:** `task/format.clj` + modify `tui/core.clj`, `tui/session.clj`, `tui/layout.clj`, `tui/format.clj`

**Deliverable:** `:tasks`, `:task`, `:task-cancel`, `:task-log`, `:task-run` commands, inline notifications, status bar running-task count.

### Phase 3: Remaining Executors
**Files:** Add ToolJobExecutor + CliClientJobExecutor to `task/executor.clj`

**Deliverable:** All three job types working.

### Phase 4: LLM Integration
**Files:** `task/commands.clj` + add `task-commands` to `react_agent.clj` `:agent-tools :tools`

**Note:** rlm-agent needs no code change — auto-discovers task commands from `!tool-defs`.

### Phase 5: Interface & Tests
**Files:** Modify `interface.clj` + create `test/ai/brainyard/agent/task_test.clj`

### Future: Cron Scheduling
**Files:** `task/scheduler.clj` + wire into TaskManager lifecycle

The `Task` record already has a `:schedule` field for forward compatibility. When needed, add a 5-field cron parser and `ScheduledExecutorService` that checks every 60s.

---

## Verification

### REPL Smoke Test (Phase 1)
```clojure
(require '[ai.brainyard.agent.task.manager :as manager]
         '[ai.brainyard.agent.task.protocol :as tp])

(def tm (manager/create-task-manager))
(manager/set-default-manager! tm)

(def t (tp/create-task tm "test-ls" :bash {:command "ls -la /tmp"}))
(tp/start-task tm (:id t))
;; wait ~1s
(tp/get-task tm (:id t))    ;; => :completed
@(:output-lines t)           ;; => ["total ..." "drwx..." ...]
(tp/shutdown tm)
```

### TUI Test — react-agent (Phase 2)
```
bb tui react-agent -- openai

:task-run echo hello world
:tasks
:task task-1
:task-log task-1
```

### TUI Test — rlm-agent (Phase 2)
```
bb tui coact-agent -- openai

:task-run sleep 3 && echo done
:tasks
```
Then ask: "List available tasks using list-tools, then check task status"
— verifies RLM auto-discovers task commands.

### LLM-Driven Tasks — react-agent (Phase 4)
```
bb tui react-agent -- openai
> Run "date && whoami" as a background task, then check its status
```
Agent calls `task__run-bash` via `call-tools`, then `task__list`.

### LLM-Driven Tasks — rlm-agent (Phase 4)
```
bb tui coact-agent -- openai
> Run "echo hello" as a background task, then show me the task list
```
Agent calls `(call-tool "task__run-bash" {:command "echo hello"})` then `(call-tool "task__list" {})`.

### CliClient Programmatic Test (Phase 3)
```clojure
(def t (tp/create-task tm "tui-test" :cli-client
         {:command ["bash" "-c" "cd ROOT && set -a && source .env && bb tui coact-agent -- openai"]
          :interaction-fn (fn [c]
                            (cli/wait-for c #"(?i)agent tui" :timeout-ms 60000)
                            (cli/send-line! c "What is 2+2?")
                            (cli/wait-for-idle c :idle-ms 5000 :timeout-ms 120000)
                            {:output (cli/get-all-output c)})}))
(tp/start-task tm (:id t))
```

### Unit Tests (Phase 5)
Test without LLM (like `stdio_test.clj` pattern):
- BashJobExecutor: `echo`, `false` (nonzero exit), timeout
- ToolJobExecutor: invoke a simple registered command
- TaskManager: create → start → complete lifecycle, cancel, retry, remove
- Output ring buffer: verify truncation at max-output-lines
- Format functions: format-task-list, format-task-detail, format-task-notification
