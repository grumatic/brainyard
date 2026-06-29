;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.agent-roster
  "Shared default tool roster for the general-purpose agents (coact, react).

   Both agents advertise the SAME curated roster — all common deftool tools
   plus all common commands. It used to be duplicated byte-for-byte at each
   defagent's `:agent-tools` site; defined once here so it cannot drift.

   The two agents differ only in how they RENDER this roster, not in its
   membership:
     - coact reaches every tool through the SCI sandbox + hot-path primitives,
       so it renders the roster compactly (`:compact-agent-tools`) — the roster
       only scopes the category index and names the tools.
     - react has no code channel; this roster IS its advertised tool surface,
       so it renders verbose per-tool specs.

   `:agent-tools` is evaluated at defagent load time (NOT a macro-literal — only
   :type/:input-schema/:output-schema are parsed at macro-expand time), so the
   defagents reference `default-agent-roster` by symbol. This ns is required by
   both agents, which makes the native-image class-init order deterministic:
   it (and transitively common.tools / common.commands) loads before either
   agent's `def` runs."
  (:require [ai.brainyard.agent.common.tools :as common-tools]
            [ai.brainyard.agent.common.commands :as common-cmds]
            [clojure.string :as str]))

(def default-agent-roster
  "Shared coact/react `:agent-tools` value: all common deftool tools + all
   common commands, de-duped. Shape matches the defagent `:agent-tools` slot
   (`{:tools [...]}`)."
  {:tools (vec (distinct (concat common-tools/all-common-tools
                                 common-cmds/all-common-commands)))})

(def todo-substrate-protocol
  "A `## Todo substrate` system-context section, installed in BOTH base agents
   (coact + react) so every derived agent inherits the checklist convention —
   modeled on coact's Project Memory protocol. The tools it references
   (write-file/update-file/read-file + todo$sync) already ride
   `default-agent-roster`, so this is guidance only, no roster change.

   The point: a todo is a markdown checklist, manageable inline with file tools
   — no todo-agent dispatch needed for routine 'what I'm doing now' work."
  "## Todo substrate (a todo is a markdown checklist)
A todo is a GitHub-style checklist file — your own \"what I'm doing now\"
scratchpad to stay on track, OR a durable cross-agent backlog. Manage it with
the ordinary file tools; `.brainyard/` writes never prompt for permission.

- CREATE: write-file a todo file. Frontmatter needs `file-type: todo` + `id` +
  `title`; the body is a `## Todo` section of `- [ ] <action> {via:…, covers:[…]}`
  lines. (Working checklists: keep them under your own agent dir, e.g.
  `.brainyard/agents/<agent>/todos/<slug>.md`.)
- FLIP done: update-file \"- [ ] <unique text>\" → \"- [x] <unique text>\". Match on
  the line TEXT — never a drifting numeric index, which breaks after an insert.
- ADD: write-file :append a `- [ ] …` line under `## Todo`, or update-file after
  an anchor line.
- RECONCILE: after ANY checklist edit, call `todo$sync` once — it re-parses the
  checklist and refreshes the live TUI/web view. READ-ONLY. For a working
  checklist under your own dir, pass the file path:
  `(todo$sync {:path \".brainyard/agents/<agent>/todos/<slug>.md\"})`. (A contract
  todo under todo-agent/todos/ can use `(todo$sync {:slug \"<slug>\"})`.)
- LIST: `(doc$list {:kind :todo})` to enumerate.

Manage working checklists yourself, inline — no todo-agent dispatch. Reserve
todo-agent for a VETTED, plan-derived, AUDITED contract backlog: it adds
pre/post-flight gating + a dossier handoff that exec-agent/eval-agent consume.")

(def project-memory-protocol
  "Shared `## Project Memory` protocol prose, installed in BOTH base agents
   (coact + react) so every derived agent gets it — paired with
   `format-project-memory-section` which appends the live index. Single source
   of truth (coact + react alias it)."
  "Durable, project-scoped notes for THIS repo, kept as plain files under
`.brainyard/memory/` and persisting across sessions. The index below lists what
is stored; each entry points to a colocated `<slug>.md` topic file. You manage
these with the ordinary read-file / write-file / update-file tools — no special
tools, and `.brainyard/` writes never prompt for permission.

- RECALL: when a listed topic is relevant to the request, read its file
  (`read-file .brainyard/memory/<slug>.md`) BEFORE answering.
- REMEMBER: when you learn a durable project fact, decision, or convention worth
  keeping, write `.brainyard/memory/<slug>.md` (short YAML frontmatter —
  `title`, `tags`, `updated` — then the fact; link related notes with
  `[[other-slug]]`), and add or update its one-line pointer in
  `.brainyard/memory/index.md` (`- [Title](<slug>.md) — one-line hook`).
- One fact per file. Check the index first and UPDATE an existing file rather
  than creating a duplicate; delete a note that turns out wrong.
- Do NOT store transient task state, or anything already captured by the code,
  git history, or BRAINYARD.md.")

(defn format-project-memory-section
  "Render the `## Project Memory` system-context section: the static protocol
   followed by the live index.md contents (truncated to `max-chars`), or an
   empty-state stub when no index exists yet."
  [{:keys [content max-chars]}]
  (let [cap   (or max-chars 4000)
        idx   (when (and content (not (str/blank? content)))
                (if (> (count content) cap)
                  (str (subs content 0 cap)
                       "\n…(index truncated — read .brainyard/memory/index.md in full)")
                  content))
        body  (if idx
                (str "### Index\n" idx)
                "### Index\n(empty — no memories yet. Create `.brainyard/memory/index.md` with your first note.)")]
    (str "## Project Memory (.brainyard/memory/)\n"
         project-memory-protocol
         "\n\n" body)))
