# Remove route-guard, Use busy? as the Guard in ui_routing2

| Field    | Value                                                  |
|----------|--------------------------------------------------------|
| Status   | Done                                                   |
| Priority | P1                                                     |
| Created  | 2026-02-18                                             |
| Completed| 2026-02-18                                             |
| Owner    | AI                                                     |

## Context

`ui_routing2.cljc` has two overlapping guard mechanisms: the original `busy?` (boolean,
from `ro/busy?` component option) and the newer `route-guard` (keyword denial reason, from
`ro/route-guard` component option). The `route-guard` function checks `ro/route-guard` first,
then falls back to `ro/busy?`, and stores a `::denial-reason` keyword in the data model.

This is redundant. The statechart already tracks what state the system is in — the active
configuration tells you which component blocked routing, and that component's own state is
already visible in app state. A separate denial reason code duplicates information the
statechart already has.

Simplify: use `busy?` as the sole guard predicate. Remove `route-guard`, `::denial-reason`,
and the `ro/route-guard` component option.

## Requirements

1. Remove `route-guard` function from `ui_routing2.cljc`
2. Remove `ro/route-guard` component option from `ui_routes_options.cljc`
3. Remove `::denial-reason` storage from `record-failed-route!`
4. Remove `denial-reason` public function from `ui_routing2.cljc`
5. The `routes` guard transition uses `busy?` as its `:cond`
6. Update demo (routing-demo2 ui.cljs) to remove `denial-reason` reference

## Approach

### ui_routing2.cljc

- Line 300-332: Delete `route-guard` function
- Line 334-339: Simplify `record-failed-route!` — only store `::failed-route-event`, remove
  `::denial-reason` assignment
- Line 341-345: Simplify `clear-override!` — remove `::denial-reason` clearing
- Line 462: Change `:cond route-guard` to `:cond busy?`
- Lines 572-577: Delete `denial-reason` public function

### ui_routes_options.cljc

- Lines 36-40: Delete `route-guard` def

### routing-demo2/ui.cljs

- Line 231: Remove or replace `(ur2/denial-reason this session-id)` reference

## Key Files

| File | Change |
|------|--------|
| `src/main/com/fulcrologic/statecharts/integration/fulcro/ui_routing2.cljc` | Remove route-guard, denial-reason; use busy? as guard |
| `src/main/com/fulcrologic/statecharts/integration/fulcro/ui_routes_options.cljc` | Remove ro/route-guard |
| `src/routing-demo2/com/fulcrologic/statecharts/routing_demo2/ui.cljs` | Remove denial-reason usage |

## Verification

1. Existing `ui-routes-test` tests pass (they don't reference route-guard or denial-reason)
2. routing-demo2 compiles without errors
3. `busy?` correctly blocks routing when a component's `ro/busy?` returns true
