# Keyword Type Coercion Fix

**Date**: 2026-05-29
**Components**: `agent/core/tool.clj`

## Problem

When a `deftool` declares a `:keyword` type parameter in its Malli `:input-schema`, the LLM receives it as `{:type "string"}` in JSON schema (correct, since JSON has no keyword type). However, when the LLM calls the tool via the **bound-tools path** (tools bound through `bind-tools`), the string value was not converted back to a keyword.

### Example

```clojure
(deftool config$apply
  "..."
  (fn [{:keys [scope ...]}] ...)
  :input-schema [:map
                 [:scope [:keyword {:desc ":project | :user | :auto"}]]
                 ...])
```

**Expected flow**:
1. Malli `:keyword` → JSON `{:type "string"}` (for LLM)
2. LLM sends `{"scope": "project"}` (string)
3. Tool receives `{:scope :project}` (keyword) ✓

**Actual behavior**:
- **Registry path** (direct `invoke-tool`): ✓ Worked via `llm-args-transformer`
- **Bound-tools path** (via `bind-tools` + `call-tool`): ✗ Stayed as string

The bound-tools path used `coerce-tool-args` → `coerce-value`, which only converts to keyword when `type = "keyword"`, but the JSON schema has `type = "string"`.

## Root Cause

In `def->tool` (tool.clj:651-678), the tool descriptor only preserved:
```clojure
{:name "config$apply"
 :parameters {:type "object"
              :properties {:scope {:type "string" ...}}
              ...}}
```

The original Malli `:input-schema` was discarded, so the bound-tools path had no way to know that `:scope` should be a keyword.

## Solution

**Single-location fix** in `components/agent/src/ai/brainyard/agent/core/tool.clj` (line ~513):

Look up `:input-schema` from the registry during bound-tools path dispatch:

```clojure
(= schema-format :json)
(let [tool-id (keyword tool-name)
      registry-def (get-tool-defs :id tool-id)
      input-schema (get-in registry-def [:meta :input-schema])
      coerced (if (and input-schema (seq (malli-map-entries input-schema)))
                ;; Malli decode path: keywordize, decode, then back to string keys
                (let [kw-args (update-keys normalized-args keyword)
                      inputs-schema (inputs->malli-map-schema input-schema)
                      decoded (m/decode inputs-schema kw-args llm-args-transformer)]
                  (update-keys decoded name))
                ;; JSON coerce path: for plain fn->tool without registry entry
                (let [props (get-in bound-entry [:parameters :properties])]
                  (coerce-tool-args normalized-args props)))]
  ...)
```

### Why Registry Lookup?

- **No st-memory pollution**: Tools stored in `:st-memory :tools` don't carry `:input-schema` (cleaner for serialization/logging)
- **Single source of truth**: Schema lives only in `!tool-defs` registry, not duplicated in tool descriptors
- **Minimal change**: Only one location modified, no changes to `def->tool` or `bind-tools`
- **Works for all deftool**: Any registered tool (command/skill/agent) gets automatic keyword conversion

## Impact

- ✓ All tools with `:keyword` parameters now work correctly on both paths
- ✓ Backward compatible: plain `fn->tool` (no Malli schema) falls back to old coercion
- ✓ No API changes: transparent fix at the conversion layer
- ✓ All existing tests pass (28 assertions in tool-test, 160 in config-helpers-test)

## Testing

Verified with:
1. **Registry path**: Direct `invoke-tool` with keyword args → works
2. **Bound-tools path**: Simulated LLM call with string args → now converts to keywords
3. **Real tool**: `config$apply` with `scope` parameter → correctly receives `:user` / `:project`

## Related

- `llm-args-transformer` (tool.clj:594-616) already handled `:keyword` for the registry path
- This fix extends the same conversion to the bound-tools path
- Applies to all Malli scalar types (`:int`, `:boolean`, etc.), not just `:keyword`
