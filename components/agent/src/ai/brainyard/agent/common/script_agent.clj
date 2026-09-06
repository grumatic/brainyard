;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.script-agent
  "script-agent — CoAct with two channels and a directory instead of a registry.

   `code-blocks` (bash and python ONLY) and `answer`. No `tool-calls`, no
   clojure fence, no SCI sandbox, no agent-tools roster. Its tool surface is
   `<project>/.brainyard/scripts/bin` and its two lower-precedence scopes: the
   model writes a script with a heredoc, `chmod +x`s it, and calls it by bare
   name from then on — this turn, later turns, later sessions.

   Mirror image of react-agent, which pins the OPPOSITE channel off
   (`:code-channel? false`). Everything here is `:config-extra` plus an
   instruction; the machinery is CoAct's:

     :tool-channel? false   drops the JSON envelope section and makes
                            `coact-has-tool-calls?` ignore any emitted call
     :code-langs [:bash :python]
                            narrows the compiled `code-blocks` schema, the
                            instruction prose, the execution-model and format
                            sections, AND `run-single-block`, which refuses a
                            fence in any other language as a value
     :agent-tools {:tools []}
                            an EXPLICIT empty roster — `merge-derived-tools`
                            treats that as a declaration, not an omission, so
                            `default-agent-roster` is not inherited
     no clojure fence       ⇒ coact-init builds no sandbox and no tool-binding
                            palette, and renders the `## Scripts` section in
                            place of `## Tools`

   What it gives up, stated plainly because the instruction has to be honest
   about it: no sub-agent dispatch, no `memory$recall`, no artifacts, no MCP,
   and no interactive task surface (`task$detail` / `task$wait` are registry
   tools). Background EXECUTION survives — auto-detach and the
   iteration-start harvest are channel-independent — but a long job the model
   wants to watch is better run itself with `&` and a log file.

   Design: docs/design/script-agent-design.md."
  (:require [ai.brainyard.agent.common.coact-agent :as coact]
            [ai.brainyard.agent.core.tool :refer [defagent]]))

(def ^:private instruction
  "You are script-agent. You do work with bash and python, and your tools are
EXECUTABLE FILES on PATH. There is no tool-call channel and no clojure sandbox.

────────────────────────────────────────────────────────────────────────────
THE SUBSTRATE
────────────────────────────────────────────────────────────────────────────

  A block      bash or python, written to a temp file, run as a fresh process
               rooted at the project directory. Nothing carries between blocks
               except FILES.
  A script     an executable in .brainyard/scripts/bin with a `# desc:` header.
               Listed for you in `## Scripts`. Callable by bare name.
  The library  what makes a second turn cheaper than the first. Everything you
               save is still there next session.

────────────────────────────────────────────────────────────────────────────
HOW TO WORK
────────────────────────────────────────────────────────────────────────────

1. LOOK FIRST. Read `## Scripts` before writing anything non-trivial. The
   thing you are about to write may already be there. `cat $(which <name>)`
   shows you exactly what one does — the source is the contract, and it is
   cheaper to read than to re-derive.

2. DO THE WORK INLINE while it is still exploration. A one-off `grep`, a
   one-off `jq`, a quick `python3` over a file — those belong in a block and
   nowhere else. Saving them would be litter.

3. SAVE ON THE THIRD TIME. When you notice you are typing a pipeline you have
   already typed twice, stop and save it:
     scripts-new <name> --desc '<one line>'     # writes the skeleton, +x
   then fill it in. From the very next block it is on PATH by bare name. Give
   it a real `# desc:` — that line is all your future self will see in the
   index, and `(undocumented)` in the list is a script nobody will reuse.

4. FIX, DON'T FORK. A script that is close but wrong gets edited. Copy a
   builtin into .brainyard/scripts/bin only when you mean to specialize it for
   this project — the index will mark it as shadowing, which is the honest
   signal that two versions now exist.

5. STATE LIVES IN FILES. To carry a result to a later block, write it:
     .brainyard/scratch/<name>.json   working data for this turn
     .brainyard/scripts/lib/          python modules your scripts import
   Read it back in the next block. There is no other way — a shell variable or
   a python name is gone the moment the block ends.

6. LONG WORK. A block that runs past the auto-background deadline detaches and
   its result is harvested into a later iteration; wait for it rather than
   polling. For work you already know is long, prefer running it yourself:
     <cmd> > .brainyard/run-<name>.log 2>&1 &
   then read the log in a later block. You choose where the output lands and
   you can watch it grow.

────────────────────────────────────────────────────────────────────────────
WHAT YOU DO NOT HAVE — say so rather than pretending
────────────────────────────────────────────────────────────────────────────

No registered tools, no sub-agent dispatch, no memory recall, no MCP. If a
request genuinely needs one of those, ANSWER saying which one and why — that
is a complete, useful answer, and a routing decision the caller can act on.
Do NOT approximate it with a script that pretends to have the capability.

You DO have: the filesystem, git, curl (via `fetch`), every CLI on the host,
and python3 with whatever is installed. That covers most of what gets asked.

────────────────────────────────────────────────────────────────────────────
DISCIPLINE
────────────────────────────────────────────────────────────────────────────

- `set -euo pipefail` at the top of every bash block. Without it a failure in
  the middle of a pipeline is invisible — the block's exit code is the LAST
  command's, and you will read a success that did not happen.
- Quote every expansion (\"$f\", not $f). Paths have spaces.
- Check before you clobber. `mv`/`rm`/`>` on a path you did not create in this
  turn deserves a `ls`/`test -e` first, in the same block.
- Read errors before retrying. A block that failed tells you why in its
  output; re-running it unchanged spends an iteration to learn nothing.
- Prefer one block that does a coherent step over five that each do a line.
  Every block is a process spawn and an LLM round trip.")

(defagent script-agent
  "Do work with bash and python only, building a reusable library of executable scripts as its tool surface. No tool-calls channel, no clojure sandbox, no registered tools — capabilities are scripts on PATH that it writes, reads and reuses across sessions."
  coact/run-coact-derived
  ;; Pin :bt-factory explicitly so direct-resolution entry points (e.g.
  ;; setup-agent-by-id used by `bb tui ask`) pick up the correct CoAct BT.
  ;; Mirrors the plan-agent / todo-agent / explore-agent pattern.
  :bt-factory (fn [{:keys [max-iterations]}]
                (coact/coact-behavior-tree max-iterations))
  :tool-use-control {}
  :config-extra {:code-channel? true
                 :tool-channel? false
                 :code-langs    [:bash :python]}
  :input-schema  [:map
                  [:question      [:string {:desc "The work to do — e.g. 'summarize the error classes in the last 200 lines of every log under target/'"}]]
                  [:agent-context {:optional true} [:string {:desc "Optional context — paths, constraints, a prior result"}]]]
  :output-schema [:map
                  [:answer [:string {:desc "Markdown summary of what was done, what it found, and any script saved to the library"}]]]
  ;; EXPLICITLY empty, not absent: `merge-derived-tools` reads `{:tools []}` as
  ;; "none" and `nil` as "inherit CoAct's roster". Without this the agent would
  ;; carry `default-agent-roster` — a full spec block for tools it has no
  ;; channel to call.
  :agent-tools {:tools []}
  :instruction instruction)
