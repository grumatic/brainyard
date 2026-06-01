# Input Queue for TUI & Web Interfaces

## Problem

Both TUI and Web interfaces block user input while the agent is processing:

- **TUI**: The synchronous `loop/recur` in `run!` calls `ask` which blocks the main thread. No new input can be read until `ask` returns.
- **Web**: The textarea is disabled when agent status is `:running`. The `handle-ask` in `bridge.clj` runs `agent/ask` in a bare `future`, sending `:agent/status {:state :running}` immediately, which disables the input on the client.

## Solution

A shared input queue module allows users to submit inputs while the agent is processing. Inputs are queued (FIFO, max 10) and processed sequentially. Queue state is visible to the user in both interfaces.

### Design Decisions

- **Sequential processing**: Each queued input becomes its own `agent/ask` call, processed FIFO after the current run completes (not batched/concatenated).
- **Cancel behavior**: Ctrl-C (TUI) or Cancel button (Web) cancels only the current run. Queued items remain and continue processing.
- **Max queue size**: 10 items. Submissions beyond the limit are rejected with a notification.
- **Shared core**: A single queue module (`queue.clj`) is used by both TUI and Web, with injected `process-fn` and `notify-fn` for interface-specific I/O.

## Architecture

```
                    ┌─────────────────────────────┐
                    │   queue.clj (shared core)    │
                    │                              │
                    │  create-queue                │
                    │  enqueue! → FIFO processing  │
                    │  cancel-item! / cancel-all!  │
                    │  get-queue-info              │
                    │  stop-queue!                 │
                    └──────────┬──────────────────┘
                               │
                ┌──────────────┴──────────────┐
                │                             │
        ┌───────┴───────┐            ┌────────┴────────┐
        │   TUI (core)  │            │  Web (bridge)   │
        │               │            │                 │
        │ process-fn:   │            │ process-fn:     │
        │  agent/ask +  │            │  agent/ask +    │
        │  usage diff + │            │  usage diff +   │
        │  status bar   │            │  WS status push │
        │               │            │                 │
        │ notify-fn:    │            │ notify-fn:      │
        │  emit! +      │            │  WS push        │
        │  status bar   │            │  queue-update   │
        └───────────────┘            └─────────────────┘
```

### Queue Processing Loop

The queue runs a worker `future` that processes items sequentially:

```
loop:
  pop next :queued item → set status :processing → notify-fn :processing
  try:   process-fn(input) → notify-fn :completed
  catch: notify-fn :error
  remove item from :items
  recur if more items
  notify-fn :queue-empty when done
```

### Queue Item Lifecycle

```
:queued → :processing → (completed / error / cancelled)
```

## Files Changed

### New

| File | Description |
|------|-------------|
| `components/agent/src/ai/brainyard/agent/core/queue.clj` | Shared FIFO queue with processing loop, cancellation, max size (10) |

### Modified — Web

| File | Changes |
|------|---------|
| `projects/agent-web-app/src/ai/brainyard/agent_web/bridge.clj` | Replaced bare `future` in `handle-ask` with queue-based processing. Added `handle-queue-cancel` and `handle-queue-cancel-all` WS handlers. Queue state sent on reconnect. Queue cleaned up on session teardown. |
| `projects/agent-web-app/src/ai/brainyard/agent_web/state.cljs` | Added `:input-queue` to store atom. Added `:agent/queue-update` action handler. |
| `projects/agent-web-app/src/ai/brainyard/agent_web/views/input.cljs` | Textarea always enabled (removed `disabled` guard). Shows "Queue" button alongside Cancel when running. Queue indicator above input shows queued items with per-item cancel buttons. Dynamic placeholder text. |
| `projects/agent-web-app/src/ai/brainyard/agent_web/app.cljs` | Registered `:agent/queue-update` WebSocket target. |
| `projects/agent-web-app/src/ai/brainyard/agent_web/views/header.cljs` | Shows queue count badge ("N queued") next to status badge when items are queued. |

### Modified — TUI

| File | Changes |
|------|---------|
| `projects/agent-tui-app/src/ai/brainyard/agent_tui/core.clj` | Input goes through `enqueue-input!` instead of blocking `ask`. Added `tui-queue-process-fn` and `tui-queue-notify-fn`. Added `/queue` and `/queue-cancel` commands. Queue stopped on `stop!`. |
| `projects/agent-tui-app/src/ai/brainyard/agent_tui/session.clj` | Added `:queue-count` to `!tui-state`. Passed `queue-count` to `format-status` in `update-status-bar!`. |
| `projects/agent-tui-app/src/ai/brainyard/agent_tui/layout.clj` | `format-status` displays "N queued" in yellow in the status bar when queue is non-empty. |

## API Reference

### queue.clj

```clojure
;; Create a queue with custom processing and notification functions
(queue/create-queue process-fn notify-fn)  ;; → atom

;; Enqueue input for processing
(queue/enqueue! !queue "user input")       ;; → {:id uuid :position N} or {:error :queue-full}

;; Cancel a specific queued item (not the currently processing one)
(queue/cancel-item! !queue item-id)        ;; → boolean

;; Cancel all queued items (not the currently processing one)
(queue/cancel-all-queued! !queue)          ;; → count removed

;; Get queue state for UI display
(queue/get-queue-info !queue)              ;; → {:items [...] :queue-length N :processing-id uuid|nil}

;; Stop queue and cancel worker
(queue/stop-queue! !queue)
```

### WebSocket Messages

| Target | Direction | Payload | Purpose |
|--------|-----------|---------|---------|
| `:agent/queue-update` | Server → Client | `{:items [{:id :input :status :queued-at}] :queue-length N}` | Full queue state sync |
| `:agent/queue-cancel` | Client → Server | `{:item-id uuid-string}` | Cancel specific queued item |
| `:agent/queue-cancel-all` | Client → Server | `{}` | Cancel all queued items |

### TUI Commands

| Command | Description |
|---------|-------------|
| `/queue` | Show queued items (index, status, input preview, UUID) |
| `/queue-cancel all` | Cancel all queued items |
| `/queue-cancel <uuid>` | Cancel a specific queued item by UUID |

## User Experience

### Web

- Textarea is **always enabled** — users can type and submit at any time.
- When agent is running, the Send button label changes to **"Queue"**.
- A **queue indicator** appears above the input showing queued items with truncated previews and per-item cancel (x) buttons.
- The header shows a **"N queued" badge** next to the status indicator.
- Cancel button cancels the current run; queued items continue processing.

### TUI

- Input prompt is **always available** — users can type while the agent processes.
- Submitting input while running shows: `Queued (#N in queue)` in yellow.
- The **status bar** shows `running │ 2 queued │ 3 calls │ ...` when items are queued.
- Ctrl-C cancels the current run; the next queued item starts processing automatically.
- `/queue` lists all queued items; `/queue-cancel all` clears the queue.

## Testing

1. **Queue unit tests**: Enqueue, sequential processing, cancellation, max size rejection, stop
2. **Web manual**: Submit a question, while running submit 2 more → verify queue indicator, sequential processing, per-item cancel
3. **TUI manual**: Ask a question, type more while running → verify "Queued" message, `/queue` output, sequential processing
4. **Reconnection**: Disconnect/reconnect WebSocket mid-queue → verify queue state replayed
5. **Queue limit**: Submit 11 items rapidly → verify 11th rejected with notification
6. **Ctrl-C (TUI)**: Press Ctrl-C with queued items → verify current cancels, next queued item starts
