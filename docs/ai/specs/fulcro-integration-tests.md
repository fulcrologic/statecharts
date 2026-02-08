# Spec: Add Fulcro Integration Tests

**Status**: backlog
**Priority**: P1
**Created**: 2026-02-08
**Owner**: conductor

## Context

The Fulcro integration layer has approximately 10-20% test coverage despite being a major feature of the library. Eight integration files are completely untested:

- `fulcro.cljc` - Main integration API
- `fulcro_impl.cljc` - Implementation details
- `fulcro/hooks.cljc` - React hooks integration
- `fulcro/operations.cljc` - Fulcro-specific operations
- `fulcro/rad_integration.cljc` - RAD integration
- `fulcro/route_history.cljc` - History management
- `fulcro/route_url.cljc` - URL routing
- `fulcro/ui_routes_options.cljc` - Route options

Only `fulcro_spec.cljc` (basic data model) and `ui_routes_test.cljc` (basic routing) have tests.

## Requirements

. Test actor mapping and resolution
. Test alias resolution
. Test Fulcro mutations triggered from statecharts
. Test load operations (`fop/load`)
. Test remote invocations (`fop/invoke-remote`)
. Test component-local charts via hooks
. Test RAD form integration

NOTE: Routing nses are not really fully designed/accepted yet. Leave them be.

## Affected Modules

- `src/test/com/fulcrologic/statecharts/integration/` - New test files
- All 8 untested integration files

## Approach

### Slice 1: Core API (fulcro.cljc)
Test `register-statechart!`, `start!`, actor/alias resolution.

### Slice 2: Operations (operations.cljc)
Test `fop/load`, `fop/invoke-remote`, state mutation operations.

### Slice 3: Routing
Test route history, URL routing, route options integration.

### Slice 4: Hooks and RAD (if feasible without browser)
May require headless component testing setup.

## Verification

. [ ] Actor paths resolve correctly
. [ ] Aliases map to data paths
. [ ] Mutations modify Fulcro app state
. [ ] Load operations trigger correctly
