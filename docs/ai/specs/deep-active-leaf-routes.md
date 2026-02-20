# Spec: Deep `active-leaf-routes` through invocation tree

**Status**: backlog
**Priority**: P2
**Created**: 2026-02-18
**Owner**: AI

## Context

`active-leaf-routes` and its helper `leaf-route?` only inspect the root routing chart's `elements-by-id` and `configuration`. An `istate` like `::ui/AdminPanel` has `:route/target` but no child route states *within the root chart* (the children are in the invoked child chart). So `leaf-route?` incorrectly returns `true` for it.

When a user is at `AdminPanel > AdminUserDetail`, `active-leaf-routes` returns `#{::ui/AdminPanel}` instead of `#{::ui/AdminUserDetail}`. This defeats the primary use case: highlighting the active nav link or conditionally rendering based on which leaf screen is showing.

The library's own internal code (`deep-busy?`, `deep-configuration->url`) already handles this correctly by walking `[:invocation/id target-key]` into child sessions recursively. `active-leaf-routes` needs the same treatment.

## Requirements

1. `active-leaf-routes` must walk the invocation tree to find the true leaf route states across chart boundaries
2. Must handle parallel regions: if two parallel routing regions each have an active leaf (possibly in different child charts), both must be returned
3. Must handle arbitrary nesting depth (istate invoking a chart that itself has istates)
4. Must not infinite-loop on circular invocation references (use a `seen` set like `deep-busy?`)
5. `leaf-route?` and `has-routes?` may remain as-is (they're correct for single-chart inspection) but their docstrings should note they don't cross invocation boundaries
6. The return type remains `[:set :qualified-keyword]` — the state IDs of the leaf route states (which may be in child charts)

## Approach

### New: `deep-active-leaf-routes`

Model after `deep-busy?` (ui_routes.cljc:302-325) and `deep-configuration->url` (route_url.cljc:289-329):

```
(deep-active-leaf-routes app-ish) => #{leaf-state-ids...}
```

Walk from the root routing session:
1. Get the root session's working memory, configuration, and chart
2. For each active state in the configuration:
   - If it's a leaf route (has `:route/target`, no child routes in this chart), check for a child invocation via `[:invocation/id target-key]` in the session's local data
   - If a child session exists: recurse into it (the istate is NOT a leaf — its child chart has the real leaves)
   - If no child session exists: it IS a leaf — include it in the result
3. Collect across all parallel regions

### Rename or deprecate `active-leaf-routes`

Either:
- **Rename** current `active-leaf-routes` to `active-leaf-routes-shallow` (or similar) for callers that intentionally want single-chart inspection
- **Replace** `active-leaf-routes` in-place with the deep version (breaking change, but the current behavior is arguably a bug)

Recommended: replace in-place, since the shallow version returns wrong answers for istates and no one should rely on that.

### Docstring updates

- `leaf-route?`: add note that it inspects a single chart only and does not cross invocation boundaries
- `has-routes?`: same note

## Files to modify

1. `src/main/com/fulcrologic/statecharts/integration/fulcro/ui_routes.cljc` — rewrite `active-leaf-routes`, update docstrings on `leaf-route?` and `has-routes?`
2. `src/test/com/fulcrologic/statecharts/integration/fulcro/ui_routes_test.cljc` — add tests for deep leaf detection through istates and parallel regions

## Verification

1. Test: flat chart (rstate only) — same behavior as today
2. Test: single istate — returns child chart's leaf, not the istate itself
3. Test: istate with parallel regions in child chart — returns all active leaves
4. Test: nested istates (istate > child chart with istate) — returns deepest leaves
5. Test: parallel regions at root level, one with istate — returns leaves from both branches
6. Test: no child session yet (istate entered but invoke hasn't completed) — falls back to istate as leaf
7. Existing core library tests still pass
