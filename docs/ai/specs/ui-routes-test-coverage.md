# Spec: UI Routes Behavioral Test Coverage

**Status**: backlog
**Priority**: P1
**Created**: 2026-02-18
**Owner**: conductor

## Context

`ui_routes.cljc` is the statechart-driven routing system for Fulcro — the highest-value integration point. It has ~30 public/internal functions but only ~6 are directly tested. Existing tests cover happy-path routing and URL sync well, but miss many behavioral branches, edge cases, and entire function groups.

Current test files:
- `ui_routes_test.cljc` — Pure statechart tests: `has-routes?`, `leaf-route?`, route entry/exit, busy guard, force-continue
- `url_sync_headless_spec.cljc` — Full Fulcro app: programmatic nav, back/forward, restoration, cleanup
- `route_url_test.cljc` — URL parsing: `url->route-target`, `current-url-path`
- `route_url_history_spec.cljc` — `SimulatedURLHistory` protocol implementation

## Requirements

### Slice 1: Pure utility functions (quick wins)
1. `route-to-event-name` — qualified-keyword, component class, qualified-symbol inputs
2. `?!` — fn argument invoked, non-fn returned, component class NOT invoked
3. `reachable-targets` — chart with targets + reachable sets, empty chart, deduplication
4. `form?` / `rad-report?` — component detection

### Slice 2: Route initialization
5. `initialize-route!` — `:once` vs `:always` strategies, form defaults to `:always`, report defaults to `:once`, `initial-props` callback, event-data passthrough for forms, ident extraction
6. `establish-route-params-node` — params filtering by `:route/params` set, empty params

### Slice 3: Busy checking depth
7. `busy-form-handler` — dirty form returns true, clean form false, missing form false
8. `check-component-busy?` — custom `ro/busy?` fn, form fallback, no busy fn
9. `deep-busy?` — recursive walk through child sessions, `seen` set prevents infinite loops, multi-level nesting

### Slice 4: Route denial completeness
10. `route-denied?` — returns true when `:routing-info/open` active, false when idle
11. `abandon-route-change!` — closes modal, stays on current route (complement to existing force tests)

### Slice 5: Cross-chart routing (most complex)
12. `istate` — child chart invocation, `srcexpr` resolution (statechart-id vs co-located chart), actor setup
13. `routes` cross-transitions — Transition 1: enter owner + store pending route; Transition 2: forward to child session
14. `::pending-child-route` passthrough from parent to child on entry

### Slice 6: Child communication
15. `send-to-self!` — walks parent chain to find nearest co-located child chart, sends event; no-op when no ancestor has a child session
16. `current-invocation-configuration` — walks parent chain to find nearest co-located child chart, returns child config; nil when none found

### Slice 7: Query management
17. `replace-join!` — parallel join-key vs `:ui/current-route`, parent without ident error
18. `update-parent-query!` — parallel vs sequential delegation

### Slice 8: URL sync child delegation
19. `url-sync-on-save` — child session walks parent chain to find root handler, caches in child-to-root

## Affected Modules

- `src/main/com/fulcrologic/statecharts/integration/fulcro/ui_routes.cljc` — Primary source (898 lines)
- `src/main/com/fulcrologic/statecharts/integration/fulcro/route_url.cljc` — URL utilities
- `src/test/com/fulcrologic/statecharts/integration/fulcro/ui_routes_test.cljc` — Extend with slices 1-4
- `src/test/com/fulcrologic/statecharts/integration/fulcro/url_sync_headless_spec.cljc` — Extend with slices 5-8

## Approach

Slices 1-4 use `new-testing-env` (no Fulcro app needed). Slices 5-8 require a headless Fulcro app fixture like `url_sync_headless_spec.cljc` already provides. Each slice is independently implementable and verifiable.

Priority order: Slices 1, 2, 3, 4 first (pure/easy). Then 5 (highest value). Then 6, 7, 8.

## Verification

1. [ ] All 8 slices have passing tests
2. [ ] `route-to-event-name` tested with 3 input types
3. [ ] `initialize-route!` tested with `:once`/`:always`/form/report branches
4. [ ] `deep-busy?` tested with 2+ levels of nesting
5. [ ] `abandon-route-change!` tested as complement to force-continue
6. [ ] Cross-chart transition branches both tested (enter owner vs forward to child)
7. [ ] `send-to-self!` tested with and without active child session
8. [ ] `url-sync-on-save` child→root delegation tested
