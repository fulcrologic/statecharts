# Deep Route Guard — Single Authority for Nested Invocation Charts

| Field    | Value                                                  |
|----------|--------------------------------------------------------|
| Status   | Done                                                   |
| Priority | P1                                                     |
| Created  | 2026-02-18                                             |
| Completed| 2026-02-18                                             |
| Owner    | AI                                                     |

## Context

When child charts (via `istate`) use `routing-regions`, each level gets an independent
`route-guard` and `routing-info-state` parallel region. This creates multiple independent
guard systems that:

- Cannot see leaf components in invoked child charts
- Block routing independently instead of through a single authority
- Create duplicate routing-info modals at each nesting level

The routing-demo2 admin chart (`admin_chart.cljc`) demonstrates this: it wraps its routes in
`routing-regions` just like the parent chart, producing two parallel routing-info regions.

## Requirements

1. Only the top-level chart uses `routing-regions`. Child charts use bare `routes` with no guard.
2. The top-level `route-guard` walks the invocation tree through Fulcro state to check
   busy/guard conditions on route components at any depth.
3. `routes` accepts a `:routing/guarded?` option (default `true`). When `false`, the guard
   transition and routing-info raise are omitted.
4. No protocol changes. No new event patterns. Existing `rstate`/`istate`/`routes` APIs unchanged.

## Approach

### Actual Implementation

The implementation uses `busy?` (not `route-guard`) as the sole guard predicate — `route-guard`
was removed entirely per the `remove-route-guard` spec.

Key functions:
- `check-component-busy?` — checks a single component, returns boolean (not keyword denial reason)
- `deep-busy?` — recursive walk through invocation tree with cycle protection (`seen` set).
  Uses each session's own `local-data` as the data context when checking components at that level.
- `busy?` — top-level guard on `routes`, delegates to `deep-busy?`
- `routes` accepts `:routing/guarded?` option (default `true`). When `false`, the guard
  transition is omitted — used by child charts invoked via `istate`.

## Key Files

| File | Role |
|------|------|
| `src/main/com/fulcrologic/statecharts/integration/fulcro/ui_routing2.cljc` | Main changes: extract guard, deep recursion, guarded? option |
| `src/routing-demo2/com/fulcrologic/statecharts/routing_demo2/admin_chart.cljc` | Demo update: drop routing-regions |
| `src/main/com/fulcrologic/statecharts/integration/fulcro.cljc` | Reuse: `local-data-path`, `current-configuration` |
| `src/main/com/fulcrologic/statecharts/integration/fulcro_impl.cljc` | Reuse: `local-data-path` impl |
| `src/main/com/fulcrologic/statecharts/protocols.cljc` | Reuse: `get-statechart` on registry |

## Verification

1. Existing `ui-routes-test` and all statechart tests pass
2. routing-demo2 navigates between all routes including admin sub-routes
3. Adding `ro/route-guard` to an admin child component triggers the top-level routing-info modal
4. Force-continue from top modal navigates through to the child route
5. Only one routing-info region active (in top chart)
