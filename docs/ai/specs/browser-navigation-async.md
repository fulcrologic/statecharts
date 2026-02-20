# Spec: Browser Navigation with Async State Entry

**Status**: done
**Priority**: P2
**Created**: 2026-02-11
**Completed**: 2026-02-18
**Owner**: conductor

## Context

Browser back/forward navigation creates unique challenges when combined with async state entry. When a user presses "back", the browser immediately updates the URL and fires a `popstate` event. The routing system must then transition the statechart to match the new URL — but if that transition requires async I/O (loading data), there is a window where the URL and application state are out of sync.

The existing `busy?` guard and `undo-url-change` mechanism (ui_routes.cljc:326-358) partially address this, but they were designed for synchronous state transitions and don't account for:
- Async state entry (the target state's on-entry returns a promise)
- The gap between URL change and statechart catching up
- Multiple rapid back/forward presses while async entry is in progress
- The interaction between `istate` child chart teardown/startup during navigation

## Requirements

### Core Navigation Flow

1. When `popstate` fires, the system must transition to the URL's target state using the async processor. The URL has already changed (browser did it), so no history manipulation is needed — just state restoration.
2. If the transition is denied by `busy?`, the URL must be reverted to match the current state. This already partially works via `undo-url-change` but must be verified with the async processor.
3. While async state entry is in progress (promise pending), subsequent `popstate` events must be queued, not processed concurrently. The system must not start entering state B while still entering state A.

### Loading Indicators

4. The system should expose whether a route transition is in progress (async pending) so the UI can show loading indicators. A data model location like `[:routing/loading?]` or a dedicated state in the routing statechart.
5. The routing statechart should have a `:routing/transitioning` state (or similar) that is active while an async transition is in flight.

### Error Recovery

6. If async entry fails during back/forward navigation, the URL must be reverted to match the last successfully entered state.
7. The error should be surfaceable to the UI (e.g., via a data model assignment or raised event).

### istate Lifecycle

8. Navigating away from an `istate` route tears down the invoked child chart (`exit-states!` handles this). If the child chart's teardown is async, back/forward must wait for it to complete.
9. Navigating to an `istate` route via back/forward starts the child chart's invocation. This follows the same async flow as forward navigation.

### Rapid Navigation

10. If the user presses back/forward multiple times quickly, only the final target state should be reached. Intermediate transitions should be cancelled or short-circuited if possible.
11. At minimum, transitions must be serialized — no concurrent `process-event!` calls on the same session.

## Affected Modules

- `integration/fulcro/ui_routes.cljc` — `undo-url-change`, `busy?`, `apply-external-route`, routing statechart structure
- `integration/fulcro/route_history.cljc` — `popstate` listener, history state tracking
- `integration/fulcro.cljc` — Event processing serialization for async
- `algorithms/v20150901_async_impl.cljc` — No changes expected (handles async naturally)
- `invocation/statechart.cljc` — Async teardown/startup of child charts

## Approach

### Event Serialization

The Fulcro integration's `send!` must serialize event processing for a given session. When using the async processor, `process-event!` may return a promise. A subsequent `send!` for the same session must wait for the previous promise to resolve before processing.

Implementation options:
- A per-session promise chain: each `send!` appends to the chain via `p/then`
- A per-session processing queue backed by core.async
- A lock/semaphore pattern (less idiomatic in CLJS)

The core.async event loop (`async_event_loop.cljc`) may already provide this serialization — verify.

### Navigation State Machine

Extend the routing statechart to include transition states:

```
:routing/idle ──[route-to.*]──> :routing/transitioning ──[transition-complete]──> :routing/idle
                                      │
                                      ├──[transition-failed]──> :routing/error ──> :routing/idle
                                      └──[route-to.*]──> (queue/replace pending transition)
```

This gives the UI a clear signal for loading indicators and prevents concurrent transitions.

### URL Revert on Failure

```
1. popstate fires → URL is already at target
2. System attempts transition (async)
3a. Success → URL stays, state matches ✓
3b. Failure → replace-url! back to previous state's URL
3c. Busy → replace-url! back to current state's URL (existing behavior)
```

### Rapid Navigation Debounce

When a new `popstate` arrives while a transition is in progress:
- Option A: Cancel the in-progress transition and start the new one (requires cancellable promises)
- Option B: Queue the new target and process after current completes, but skip intermediate queued targets (only process the latest)
- Option B is simpler and more robust. Track `pending-navigation-target` — each new popstate overwrites it.

## Design Decisions to Resolve During Implementation

- Should event serialization be per-session or global? (Per-session allows independent charts to process concurrently)
- Is the `:routing/transitioning` state necessary, or can loading status be derived from the event processing promise?
- Should rapid navigation use cancellation or debounce? Cancellation is harder but more responsive.
- How does this interact with the `busy?` guard? If transitioning to state A is denied, but then back to state B is requested, should B be attempted?

## Verification

1. [ ] Browser back triggers correct async state restoration
2. [ ] Browser forward triggers correct async state restoration
3. [ ] `busy?` denial reverts URL correctly with async processor
4. [ ] Rapid back/forward (multiple presses) settles on the correct final state
5. [ ] No concurrent `process-event!` calls on the same session
6. [ ] Loading indicator is available during async transitions
7. [ ] Failed async entry during back/forward reverts URL
8. [ ] `istate` child chart teardown completes before new state entry begins
9. [ ] Works correctly when some intermediate states have sync entry and others have async
10. [ ] popstate events during restoration mode are handled correctly (queued or ignored)
11. [ ] routing-demo2 demonstrates browser back/forward with async state entry and busy guard denial
