# Data Model — CLAUDE.md

Stores and retrieves state chart data. Protocol-based with multiple implementations.

## Implementations
- **`WorkingMemoryDataModel`** (scoped) — Data scoped to state context. Default for most use cases.
- **`FlatWorkingMemoryDataModel`** (flat) — Single global scope, simpler.
- **Fulcro DataModel** — In `integration/fulcro_impl.cljc`, uses Fulcro app state.

## Key Protocol Methods (DataModel)
- `load-data` — Initialize from src/expr
- `current-data` — Get full data map
- `get-at` — Path-based access (keyword vectors)
- `update!` — Operation-based modification (assign, delete, set-map-ops)

## Operations (operations.cljc)
- `assign` — Set value at path
- `delete` — Remove value at path
- `set-map-ops` — Batch operations as map

## Gotchas
- **Binding modes matter**: Early binding initializes all data at start; late binding initializes when state first entered
- **Context scoping**: In scoped model, data is isolated per state context with shadowing
- **`_event` variable**: Stored at `[:ROOT :_event]` in data model, updated before transition selection
- **Duplicate `dissoc-in`**: Same helper exists here and in `fulcro_impl.cljc` — technical debt
