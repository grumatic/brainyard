# Spec: Task Manager

*Area code `TASK`. Covers the background task manager in
`agent/task/*`: the manager and executor protocols, the detached async
execution model, ring-buffered output, on-disk persistence, the task
commands, and scheduling. Tasks are how long-running work (background
shell, sub-agent, sandbox/nrepl eval) runs without blocking a turn.*

Status legend and contract-ID conventions: see [README](README.md).

---

## 1. Protocols & task shape

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-TASK-01 | The manager MUST implement `ITaskManager` (`create-task`, `start-task`, `cancel-task`, `retry-task`, `remove-task`, `get-task`, `list-tasks`, `shutdown`). | Implemented | `agent/task/protocol.clj` |
| CR-TASK-02 | Executors MUST implement `IJobExecutor` (`execute-job`, `cancel-job`, `job-type`). | Implemented | `agent/task/protocol.clj` |
| CR-TASK-03 | A `Task` MUST carry `output-lines` (a ring-buffer atom), `max-output-lines` (default 500), a `future-ref`, and a `schedule` field. | Implemented | `agent/task/protocol.clj` |

---

## 2. Lifecycle & execution model

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-TASK-04 | A task's status MUST progress `:pending → :running → :completed │ :failed │ :cancelled`, with `finalize-task!` as the single source of truth for terminal transitions. | Implemented | `agent/task/manager.clj` (`finalize-task!`) |
| CR-TASK-05 | Execution MUST be pure-async/detached: an executor returns `{:status :detached :on-poll :on-cancel}`, and a global daemon detach-watcher MUST poll (every ~300ms) to advance and finalize tasks. | Implemented | `manager.clj` (detach-watcher) |
| CR-TASK-06 | Output capture MUST cap the in-memory tail at `max-output-lines` and fan out simultaneously to a disk appender. | Implemented | `manager.clj` (`make-on-output`) |
| CR-TASK-07 | The registered executor types MUST include `:bash`, `:tool`, `:cli-client`, `:clj-sandbox-eval`, `:clj-nrepl-eval`; the pool MUST default to size 4 with daemon threads. | Implemented | `manager.clj` |

---

## 3. Persistence

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-TASK-08 | Each task MUST persist to `<project>/.brainyard/tasks/<task-id>/` as `meta.edn` (atomic-rename writes) and `output.log`. | Implemented | `agent/task/persist.clj` |
| CR-TASK-09 | Output reads MUST support a bounded tail (`read-tail`, ~16KiB backward scan). | Implemented | `persist.clj` (`read-tail`) |
| CR-TASK-10 | The task-id counter MUST ratchet across JVM restarts via `max-existing-task-id` so ids don't collide after a restart. | Implemented | `persist.clj` |

---

## 4. Commands

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-TASK-11 | The task commands MUST be `task$list`, `task$detail` (with `:last-n` for tail), `task$cancel`, `task$run`, `task$sweep`; there is intentionally no separate `task$output` (folded into `task$detail`). | Implemented | `agent/task/commands.clj` |

---

## 5. Scheduling

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-TASK-12 | The `Task` `:schedule` field MUST accept a `{:cron …}` spec and a scheduler MUST run tasks on that cadence. | **Missing** | `agent/task/protocol.clj` (field reserved, no scheduler) |

**CR-TASK-12 (Missing):** the `:schedule {:cron …}` field is reserved
"for future cron support" but no scheduler consumes it — scheduled/cron
tasks are not implemented. Candidate TODO: implement a scheduler that
honors `:schedule`, or drop the field until it's needed. (Note: this is
distinct from the host app's scheduled-tasks feature; this is the
in-runtime task manager.)

---

## Gaps & candidate TODOs (this spec)

- **CR-TASK-12 — cron/scheduled tasks not implemented.** The `:schedule`
  field is reserved but inert. Implement a scheduler or remove the field.
  *(Medium.)*

No `TODO`/`FIXME` markers exist in the task-manager code; everything
except scheduling is Implemented.
