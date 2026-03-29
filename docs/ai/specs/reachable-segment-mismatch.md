# Spec: Reachable Target Lookup Ignores `:route/segment`

**Status**: backlog
**Priority**: P1
**Created**: 2026-03-29
**Owner**: conductor
**GitHub**: #27

## Context

When a child chart route target uses `:route/segment` to customize its URL path segment,
forward navigation (programmatic `route-to`) works correctly — the URL shows the custom
segment (e.g. `/admin/main`). However, browser back/forward navigation to that URL fails
silently because the URL restoration code cannot resolve the custom segment back to the
correct target.

This was reported in GitHub issue #27.

## Root Cause

`find-target-by-leaf-name-deep` in `url_history.cljc:66-86` has two lookup strategies:

1. **Direct match** (line 79): calls `find-target-by-leaf-name`, which correctly uses
   `element-segment` (respects `:route/segment` → falls back to `(name target)`).

2. **Reachable match** (lines 82-85): searches `:route/reachable` sets on `istate` elements,
   but matches using `(name kw)` — the keyword name of the reachable target, **not** its
   `:route/segment` value.

The reachable keywords (e.g. `::dashboard/Dashboard`) are references to elements in a
*child* chart. The parent chart's `elements-by-id` doesn't contain those child elements,
so `:route/segment` metadata isn't available at lookup time.

Example: Child target `::dashboard/Dashboard` has `:route/segment "main"`. URL encodes as
`/admin/main`. On restore, leaf-name is `"main"`, but `(name ::dashboard/Dashboard)` is
`"Dashboard"` → no match → navigation fails.

## Requirements

1. URL restoration via browser back/forward must resolve custom `:route/segment` values
   on reachable (child chart) targets, not just keyword names
2. Forward navigation (route-to) behavior must remain unchanged
3. Targets without `:route/segment` must continue to work via keyword name fallback
4. No changes to URL encoding — only the decode/restore path is affected

## Affected Files

- `src/main/com/fulcrologic/statecharts/integration/fulcro/routing/url_history.cljc` — `find-target-by-leaf-name-deep`
- `src/test/com/fulcrologic/statecharts/integration/fulcro/url_sync_headless_spec.cljc` — new test cases

## Approach

The reachable match branch needs access to the child chart's element metadata to check
`:route/segment`. Two options:

### Option A: Reverse segment index (preferred)

Build a lookup map during `install-url-sync!` or chart registration that maps
`segment-string → {:target-key kw :owner-id istate-id}` for all reachable targets across
all registered charts. Pass this index to `find-target-by-leaf-name-deep` (or a new variant).

**Pros**: Single O(1) lookup, clean separation
**Cons**: Must be rebuilt when charts are registered/unregistered

### Option B: Resolve child chart at lookup time

When the direct match fails, iterate reachable sets and for each reachable keyword, look up
the child chart's elements-by-id from the registry to check `element-segment`.

**Pros**: Always current, no extra state
**Cons**: Requires registry access in url_history.cljc, slower per-lookup

### Option C: Store segment on reachable metadata

Enhance `istate`/`rstate` to store a map in `:route/reachable` instead of a set, e.g.
`{::dashboard/Dashboard {:route/segment "main"}}`. The reachable match branch can then
check segment metadata directly.

**Pros**: Self-contained, no external lookups
**Cons**: Breaking change to `:route/reachable` format (set → map)

## Verification

1. [ ] Child chart target with `:route/segment "main"` generates URL `/parent/main`
2. [ ] Browser back to `/parent/main` correctly resolves and navigates to that target
3. [ ] Child chart target without `:route/segment` still works via keyword name
4. [ ] Direct (same-chart) targets with `:route/segment` continue working
5. [ ] Existing URL sync headless tests still pass
6. [ ] New test: round-trip encode→decode for reachable targets with custom segments
