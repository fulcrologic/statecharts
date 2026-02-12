# Spec: Async URL State Restoration

**Status**: backlog
**Priority**: P1
**Created**: 2026-02-11
**Owner**: conductor

## Context

The primary motivation for the async statechart processor was URL-driven deep state restoration. When a user loads a page with a URL like `/users/42/settings/notifications`, the routing system must navigate the statechart to the corresponding deeply nested state. Each intermediate state's `on-entry` may perform async I/O (e.g., loading user data, initializing a settings panel). The async processor (`v20150901_async`) now supports expressions that return promesa promises, parking the algorithm until they resolve. This spec designs how the routing layer leverages that capability.

### Current State

- `apply-external-route` (ui_routes.cljc:361-383) finds a state matching the URL path and fires a `route-to-*` event
- `routes` (ui_routes.cljc:385-435) creates direct `transition` elements for every route target, so SCXML's `enter-states!` handles entering all intermediate states
- `start-routing!` (ui_routes.cljc:539-559) calls `scf/start!` which may return a promise with the async processor
- `istate` (ui_routes.cljc:240-317) creates route states that invoke co-located statecharts, whose setup may also be async

### The Problem

1. `start-routing!` calls `scf/start!` then immediately expects synchronous completion. With the async processor, `start!` may return a promise.
2. `apply-external-route` fires a routing event, but does not account for the possibility that the event processing (entering intermediate states) is async.
3. During restoration, each intermediate `rstate`/`istate` on-entry independently pushes/replaces browser history entries. A deep restoration creates spurious intermediate history entries.
4. `istate` invocations start child statecharts in on-entry. The child chart's `start!` may also be async — the invocation system must propagate this correctly.
5. There is no mechanism to distinguish "restoring from URL" vs "user navigated here" — the on-entry scripts behave identically in both cases, but URL restoration should suppress intermediate URL pushes.

## Requirements

1. `start-routing!` must handle promise-returning `start!` — either by returning a promise itself or by using the async event loop which handles this naturally
2. On page load, `apply-external-route` must work correctly when the async processor is in use — the fired routing event will cause async state entries, and the system must not assume synchronous completion
3. A "restoration mode" flag must be available in the processing environment or event data so that on-entry scripts can distinguish URL restoration from normal navigation. During restoration, URL push/replace should be suppressed (or batched to a single final update)
4. `istate` invocations during restoration must work with async — the invocation processor must handle promise-returning `start!` on child charts
5. After full restoration completes (all intermediate on-entry handlers have resolved), the URL should reflect the final state without spurious history entries
6. If any on-entry handler fails during restoration (promise rejection), the system should either (a) stop at the last successfully entered state and update the URL to match, or (b) fall back to a configurable default route
7. The `::sc/statechart-src` in working memory must be set before any async processing begins so that event routing works during restoration
8. This must work with both `rstate` (simple routing) and `istate` (invoked chart routing)

## Affected Modules

- `integration/fulcro/ui_routes.cljc` — `start-routing!`, `apply-external-route`, `establish-route-params-node`, `rstate`, `istate`
- `integration/fulcro.cljc` — `start!` wrapper must handle async processor return values
- `integration/fulcro/route_history.cljc` — History push/replace must respect restoration mode
- `invocation/statechart.cljc` — Child chart `start!` invocation must propagate async
- `algorithms/v20150901_async_impl.cljc` — No changes expected (already handles async on-entry)

## Approach

### Phase 1: Async-aware `start-routing!`

Make `start-routing!` return a promise when the async processor is used. The initial `apply-external-route` (fired in the root state's on-entry) triggers deep state entry. The promise resolves when the full configuration is stable.

Key change: `scf/start!` already delegates to the processor's `start!`. The Fulcro integration layer needs to handle the promise return and update the Fulcro state atom when it resolves.

### Phase 2: Restoration mode

Add a `::restoration?` key to the event data when `apply-external-route` fires the routing event. `establish-route-params-node` checks this flag:
- If restoring: use `replace-route!` only on the final state (skip intermediates)
- If navigating: use `push-route!` as today

The flag propagates through the event data so all on-entry scripts in the path can see it.

### Phase 3: Error handling during restoration

Wrap the restoration chain with error handling. If a promise rejects mid-chain:
1. The async algorithm's existing error handling catches it
2. An `:error.routing/restoration-failed` event is raised with the error and the last good state
3. The routing system can transition to a default/error route

### Phase 4: istate async invocation

Ensure the statechart invocation processor's `start-invocation!` returns/propagates the promise from the child chart's `start!`. The parent algorithm already awaits promises in on-entry via the async processor.

## Design Decisions to Resolve During Implementation

- Should `start-routing!` always use the async processor, or should it auto-detect?
- Should restoration mode be per-event-data or a flag on the processing environment?
- What is the default behavior when restoration fails? (Error route vs. stay at root)
- Should `istate` child charts use the same processor type as the parent?

## Verification

1. [ ] `start-routing!` with async processor returns promise that resolves to working memory
2. [ ] URL `/a/b/c` correctly restores to deeply nested state `:c` passing through `:a` and `:b`
3. [ ] Each intermediate state's async on-entry completes before the next begins
4. [ ] Browser history has exactly ONE entry after restoration (not N intermediate entries)
5. [ ] `istate` targets with async on-entry in child charts restore correctly
6. [ ] Failed restoration (rejected promise in on-entry) falls back gracefully
7. [ ] Normal navigation (non-restoration) continues to push history entries as before
8. [ ] Works with parallel route regions (multiple leaf routes active simultaneously)
