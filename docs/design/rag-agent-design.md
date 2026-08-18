# rag-agent — retrieval specialist for a project's Graph RAG corpus

- **Namespaces**: `ai.brainyard.agent.common.rag-agent`,
  `ai.brainyard.agent.common.rag-commands`
- **Status**: implemented
- **Console**: the **RAG** section of `apps/workspace` in
  `brainyard-playground-apps`. Section plan:
  `../../brainyard-playground-apps/docs/design/rag-section-plan.md` §5.

---

## 1. What it is for

`rag-agent` owns one project's retrieval corpus: the documents indexed into
it, the knowledge graph over them, and whether searching it actually works.

**It is deliberately not the path for routine work.** The console does its own
CRUD deterministically over HTTP — upload, search, delete and the graph reads
cost no LLM turn. This agent exists for the four things a form cannot decide:

| Kind | The judgement |
|---|---|
| **Chunking** | `section` mode splits on numbered headings and suits specs and manuals; `char` mode needs a window and overlap justified against the prose. Getting it wrong is invisible until retrieval is quietly bad. |
| **Diagnosis** | Three signals fail differently. The per-signal ranks say which one found a hit and which should have; turning that into *"your chunks are too large"* or *"that entity was never extracted"* is the work. |
| **KG spend** | A re-extraction costs an LLM call per document when one is configured. Whether it is worth it is a judgement about the corpus. |
| **Fusion weights** | The shipped WRRF weights were tuned against a different embedder. Re-deriving them needs evidence, not taste. |

---

## 2. Why HTTP, not Bolt

`rag-commands` calls the `rag-backend` FastAPI service over HTTP rather than
opening its own Neo4j driver. That is a design decision with a cost, and the
cost is worth paying:

- The backend owns embedding, Weighted Reciprocal Rank Fusion and graph
  expansion. A second Clojure implementation of any of those **would drift**
  from the one the operator console shows — and the failure mode is not a
  crash, it is the agent and the UI quietly disagreeing about what the corpus
  says.
- The embedder is the sharpest case. Both sides embed with Model2Vec
  `potion-base-8M`, but "both sides use the same model" is a claim that has to
  stay true through every future change. One implementation cannot drift from
  itself.

The cost: the agent depends on a sidecar process. That is why **every command
returns `{:error …}` rather than throwing** when the service is unreachable —
the agent must be able to say *"the backend is not running, start it with …"*
instead of failing the turn with a connection error.

`RAG_API_URL` selects the backend. The workspace section puts it on this
agent's owner process (`SECTION_CONTEXTS.rag` gates), so one project's agent
talks to that project's backend. `RAG_PROJECT` — the scope the backend stamps
on writes — is set on the **backend**, not read here; this namespace never has
to know it.

---

## 3. The command family

| Command | Backend | Notes |
|---|---|---|
| `rag$health` | `GET /health` | backend + database + embedder, incl. fingerprint state |
| `rag$stats` | `GET /stats` | this project's counts **and** the whole store's — the ratio predicts recall |
| `rag$documents` | `GET /list` | flattens the backend's Chroma-shaped envelope so the model never sees three parallel arrays |
| `rag$search` | `GET /search` | four modes; `per_signal` per hit is the evidence for diagnosis |
| `rag$graph` | `GET /graph/{document,entity}/…` | neighbourhood nodes + edges |
| `rag$ingest` | `POST /ingest` | see below |
| `rag$delete` | `DELETE /delete/…` | chunk id or document id |
| `rag$extract-kg` | `POST /admin/extract-kg` | 15-minute ceiling; an LLM extractor is genuinely slow |

### 3.1 `POST /ingest` exists because of this agent

The backend's browser ingest path is `POST /upload`, which is **multipart** —
and `clj-http-native` does not support multipart bodies (its own docstring
says so; it is a deliberately minimal `java.net.http` wrapper that exists to
keep the native image small).

That left `rag$ingest` able to reach only `POST /add`, which writes **one
chunk with no chunking at all**. The command's `:mode`, `:chunk-size` and
`:overlap` arguments would have been cosmetic — the tool schema would have
advertised control the tool did not have, which is worse than not offering it.
`clj-kondo` caught it as three unused bindings.

So the backend grew a JSON twin: `POST /ingest` takes
`{filename, text, chunk_mode, chunk_size, overlap, category}` and runs **the
same `ingest_file` pipeline** `/upload` does. Verified equivalent — the same
document through both paths produces 4 chunks, 3 `NEXT` edges, identical
scores, and a saved original either way. An agent-ingested document is
indistinguishable from a browser-uploaded one.

---

## 4. What replaced what

`rag_commands.clj` previously held an **in-memory `!vector-store`** ported
from cloudcast: `rag-command$add-document`, `rag-command$search`,
`rag-command$clear`, `rag-command$stats` over an atom.

It was dead code. No namespace required it, it appeared in no agent's roster,
and its `embed-fn` was never configured — so every command in it returned
`{:error "RAG not configured …"}` if it had ever been called. It was rewritten
in place rather than left beside the working surface, because two RAG command
families of which one silently fails is worse than one.

---

## 5. Instruction design

Two rules in the instruction carry most of the weight, and both are about
honesty rather than capability:

> **NEVER present a retrieved chunk as your own knowledge, or your own
> knowledge as retrieved.** Cite source + chunk index for anything from the
> corpus.

That distinction *is* the value of a retrieval system. An agent that answers
plausibly from its own weights while implying the corpus said so has not
failed loudly — it has failed silently, which is worse.

> **If the index fingerprint state is `mismatch`, say so BEFORE interpreting
> any ranking.**

A same-width embedder swap passes every dimension check and produces
incomparable vectors. The backend disables the vector signal in that state; an
agent that explained a fulltext-only ranking as if all three signals had run
would be confidently wrong.

The diagnosis section lists causes **in the order they actually occur**, ending
with the one that is invisible from inside a single project: this project may
be a small share of a large shared store, and vector recall is filtered *after*
the index call, so its fan-out can be consumed by other projects' chunks.
`rag$stats` reports both counts precisely so the agent can notice.

---

## 6. Roster

`coact/run-coact-derived`, like every other specialist. `:agent-tools` is the
`rag$*` family plus:

- **file tools** — to *look at* material before choosing how to chunk it, which
  is the whole reason to ask an agent rather than fill in the form;
- **shell tools** (allowlisted reads);
- `query$llm` for synthesis over retrieved passages;
- task commands, for slow extraction runs;
- invocation tools for bookkeeping.

No `BY_ENABLE_*` gate: RAG has no unattended loop to arm. The section's gates
carry `RAG_API_URL`, `RAG_PROJECT` and a longer `BY_ASK_TIMEOUT_MS`, because
ingest turns are slow.

Registered in `ai.brainyard.agent.interface` beside the other common agents.
