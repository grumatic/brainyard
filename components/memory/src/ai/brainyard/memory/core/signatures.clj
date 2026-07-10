;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.signatures
  "DSPy signatures for memory's own LLM decisions — currently graph
   extraction (CR-MEM-22). Kept in one file so the prompt + I/O schema are
   one place to read/evolve, matching memory-agent's signatures ns. The
   entity/relation vocabularies are derived from `protocol/node-types` /
   `protocol/relations` so the schema stays a single source of truth (adding
   a node-type needs no edit here — the enum recomputes at load).

   Outputs are Malli-validated by `clj-llm/predict`, so the extractor no
   longer hand-parses JSON. See `extract/make-extract-fn`."
  (:require [ai.brainyard.clj-llm.interface :refer [defsignature]]
            [ai.brainyard.memory.interface.protocol :as proto]))

;; Enums computed from the curated vocab — evaluated when the `def` loads, so
;; they track the protocol sets automatically (no manual sync).
(def ^:private node-type-enum
  (into [:enum {:desc "Curated entity kind"}] (mapv name (sort proto/node-types))))

(def ^:private relation-enum
  (into [:enum {:desc "Curated relation kind"}] (mapv name (sort proto/relations))))

(defsignature GraphExtraction
  "You extract a knowledge graph from an agent session. The input is one or
more consecutive TURNS of that session (a turn is a user/assistant exchange
or tool activity); when batched, several turns are concatenated. Read the
WHOLE input and extract durable knowledge from EVERY turn — do NOT summarize,
and do NOT fixate on the most prominent turn while skipping the rest. Each
turn may add its own entities and relations.

Identify durable ENTITIES and typed RELATIONSHIPS between them, using ONLY
the entity types and relations allowed by the output schema. Record only
durable, reusable knowledge (config keys, components, files, people,
concepts and how they relate) — skip ephemeral chatter, greetings, and
one-off values. Merge repeat mentions of the same thing across turns into a
single entity. Each relation's :src and :dst MUST name an entity you also
list in :entities. Return EMPTY arrays when nothing is worth recording (the
common case for operational chatter)."
  {:inputs  {:activity [:string {:desc "One or more consecutive turns of an agent session (episodes concatenated when batched; a single episode otherwise)"}]}
   :outputs {:entities
             [:vector {:desc "Durable entities; empty when nothing is worth recording"}
              [:map
               [:name    [:string {:desc "Canonical entity name"}]]
               [:type    node-type-enum]
               [:summary {:optional true} [:string {:desc "One-line description"}]]
               [:aliases {:optional true} [:vector {:desc "Alternate names"} :string]]]]
             :relations
             [:vector {:desc "Typed relations between listed entities; empty when none"}
              [:map
               [:src        [:string {:desc "Source entity name (must appear in :entities)"}]]
               [:relation   relation-enum]
               [:dst        [:string {:desc "Target entity name (must appear in :entities)"}]]
               [:fact       {:optional true} [:string {:desc "One-sentence statement of the relation"}]]
               [:confidence {:optional true} [:double {:min 0.0 :max 1.0 :desc "0.0..1.0"}]]]]}})
