# Spec: ui-routing2 Core Namespace

**Status**: backlog
**Priority**: P1
**Created**: 2026-02-17
**Owner**: conductor

## Context

The current `ui_routes.cljc` has three architectural problems identified in the
[routing design discussion](routing-design-discussion.md):

1. **Global state**: `history` volatile, `route-table-atom`, and fixed `session-id` prevent
   multiple routing charts and composable routing.
2. **Interleaved concerns**: `establish-route-params-node` both stores params AND syncs URLs.
   `undo-url-change` manipulates browser history directly.
3. **No composition across invoked charts**: `routes` only discovers targets in its own
   children, so `(route-to! app :deeply/nested)` doesn't work across `istate` boundaries.

This spec creates a new `com.fulcrologic.statecharts.integration.fulcro.ui-routing2`
namespace that copies needed functions from `ui_routes`, removes all history/URL concerns,
adds composable routing via `:route/reachable` declarations, and parameterizes session IDs.

The old namespace is left untouched.

## Requirements

### 1. No Global State

1. No module-level atoms or volatiles for history or route tables
2. All API functions (`route-to!`, `active-leaf-routes`, `route-denied?`, etc.) accept
   `session-id` as a parameter (with a sensible default or required arg)
3. Multiple independent routing charts can coexist in one app

### 2. Externalized History/URL

4. `establish-route-params-node` stores params from event data only — no URL reading
5. No `undo-url-change`, `apply-external-route`, or `state-for-path` in this ns
6. No dependency on `route_history.cljc` or `route_url.cljc`
7. The `routes` helper does NOT generate transitions for `:event/external-route-change`
   (that's the external layer's job to send `route-to.*` events)

### 3. Composable Routing via `:route/reachable`

8. `istate` accepts a `:route/reachable` set of keywords — the transitive set of route
   targets reachable through the invoked child chart
9. `routes` reads `:route/reachable` from `istate` children and generates cross-chart
   transitions that target the `istate` and store a `::pending-child-route` in the data model
10. `istate` passes `::pending-child-route` through invoke params to the child chart
11. The child chart's `routes` on-entry checks for an initial route in its data/params and
    raises the corresponding `route-to.*` event to self-route
12. Recursive composition works: an `istate` within an `istate` cascades correctly
13. Provide `reachable-targets` helper that analyzes a chart and returns the set of all
    route target keywords (for generating the `:route/reachable` declaration)

### 4. Preserved Functionality (copy from ui_routes)

14. `rstate` — route state with on-entry that initializes component and patches parent query
15. `istate` — invoked route state (enhanced with reachable support)
16. `routes` — generates direct transitions and wraps with denied-routing guard
17. `routing-regions` — parallel wrapper with routing-info state
18. `initialize-route!` — ident resolution cascade (unchanged)
19. `update-parent-query!` — dynamic query patching (unchanged)
20. `busy?` / `record-failed-route!` / `override-route!` — event-based denied routing
21. `ui-current-subroute` / `ui-parallel-route` — render helpers (parameterized session-id)
22. `route-to!` — sends `route-to.*` event (parameterized session-id)
23. `route-to-event-name` — keyword derivation (unchanged)

### 5. Bookmark Primitive

24. A general-purpose "save event and replay later" mechanism, extracted from the
    denied-routing pattern. The existing `record-failed-route!`/`override-route!` pair
    already does this — generalize it so auth guards can use the same mechanism.
25. The `routes` guard system should support multiple denial reasons (busy, not-authenticated)
    via a `:route/guard` option that returns nil (allow) or a keyword reason

## Affected Modules

- **NEW**: `integration/fulcro/ui_routing2.cljc` — main namespace
- `integration/fulcro/ui_routes.cljc` — NOT modified, source for copying
- `integration/fulcro/ui_routes_options.cljc` — may reuse or copy options

## Approach

### Phase 1: Core Routing (no composition)

Copy and clean up the core routing functions:
- `rstate`, `routes`, `routing-regions`, `istate` (without reachable support yet)
- `initialize-route!`, `update-parent-query!`, `replace-join!`
- `busy?`, `record-failed-route!`, `override-route!`, `clear-override!`
- `routing-info-state`
- `route-to!`, `route-to-event-name`
- `ui-current-subroute`, `ui-parallel-route`
- Render helpers

Strip out: `history`, `route-table-atom`, `undo-url-change`, `apply-external-route`,
`state-for-path`, URL reading from `establish-route-params-node`.

Parameterize session-id on all API functions.

Simplify `establish-route-params-node` to:
```clojure
(ops/assign [:routing/parameters id] (select-keys event-data params))
```

### Phase 2: Composable Routing

Add `:route/reachable` support:
- Enhance `routes`' `find-targets` to walk into `:route/reachable` sets
- Generate cross-chart transitions with `::pending-child-route` storage
- Enhance `istate` to read `::pending-child-route` and pass via invoke params
- Add initial-route detection in child chart's `routes` on-entry
- Write `reachable-targets` helper function

### Phase 3: Generalized Route Guards

Extract the bookmark/replay mechanism:
- Generalize `busy?` to a `:route/guard` fn returning nil or denial-reason keyword
- Default guard checks `busy?` for backward compatibility
- Auth guard example: `(fn [env data] (when-not (authenticated? env data) :not-authenticated))`
- Same `record-failed-route!` / `override-route!` mechanism works for any denial
- Different denial reasons can trigger different responses (modal vs. login redirect)

## Verification

### Phase 1
1. [ ] No global atoms/volatiles in the namespace
2. [ ] `rstate` on-entry initializes component and patches query (test with mock Fulcro app)
3. [ ] `routes` generates direct transitions for all child targets
4. [ ] `busy?` guard prevents routing when component is busy
5. [ ] `record-failed-route!` saves event, `override-route!` replays it
6. [ ] `route-to!` accepts session-id parameter
7. [ ] Two independent routing charts can coexist (different session-ids)

### Phase 2
8. [ ] `routes` generates cross-chart transitions from `:route/reachable`
9. [ ] `istate` forwards pending child route via invoke params
10. [ ] Child chart auto-routes on startup when given initial route
11. [ ] `reachable-targets` correctly computes transitive target set
12. [ ] Recursive istate-within-istate cascades correctly
13. [ ] Async parking: full cascade completes within one `process-event!`

### Phase 3
14. [ ] `:route/guard` returning `:not-authenticated` triggers denial flow
15. [ ] Saved route replays after authentication completes
16. [ ] Different denial reasons can be handled differently in routing-info state
