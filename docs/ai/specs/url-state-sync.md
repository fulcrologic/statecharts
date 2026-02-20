# Spec: URL-State Bidirectional Synchronization

**Status**: done
**Priority**: P2
**Created**: 2026-02-11
**Completed**: 2026-02-18
**Owner**: conductor

## Context

The routing system must maintain bidirectional synchronization between the statechart configuration and the browser URL. Currently, each route state independently pushes or replaces history in its `on-entry` handler (`establish-route-params-node`), which leads to several problems:

1. **Spurious history entries**: Entering a deep state creates one history entry per intermediate state
2. **Race conditions in parallel regions**: Multiple parallel regions entering states simultaneously each try to update the URL independently
3. **No coordinated URL composition**: Each state only knows its own params, not the full picture
4. **Query parameter leakage**: When leaving a state, its query parameters are not cleaned up from the URL

The URL must be the *output* of the statechart configuration, not an artifact of individual state entries.

## Requirements

### State → URL (Forward Sync)

1. URL updates must be **coordinated at the macrostep boundary**, not per-state-entry. After a macrostep completes (all microsteps done, configuration stable), a single URL update reflects the new configuration.
2. The URL path is determined by the active leaf route state that declares a `:route/path` (see `url-path-design.md` for path composition)
3. Query parameters are the union of all active states' declared params. When a state exits, its params are removed.
4. Only ONE history push per user-initiated navigation. Internal transitions within the same top-level route should use `replace-route!`, not `push-route!`.
5. Parallel regions contribute independently: path from the "primary" region, query params from all regions

### URL → State (Reverse Sync)

6. `apply-external-route` must handle the full URL: path → target state, query params → state params, opaque params → per-state data
7. Browser back/forward (`popstate`) triggers `apply-external-route` with the historical URL. The system must transition to the correct state, which may require async processing.
8. If back/forward targets a state whose entry is denied by `busy?`, the URL change must be undone (already partially implemented in `undo-url-change`)
9. Direct URL manipulation (user edits URL bar) is treated the same as `popstate` — an external route change

### Coordination Mechanism

10. URL updates must be deferred until the macrostep completes. This requires a hook point after `process-event!` returns (or its promise resolves)
11. The coordination layer must have access to the full active configuration to compute the composite URL
12. During restoration (see `async-url-restoration.md`), URL updates are suppressed entirely until restoration completes

## Affected Modules

- `integration/fulcro/ui_routes.cljc` — `establish-route-params-node` (refactor to not directly push history), `routes`, `apply-external-route`, `busy?`/`undo-url-change`
- `integration/fulcro/route_history.cljc` — May need a `batch-update!` or `compute-url` that takes full configuration
- `integration/fulcro.cljc` — `send!` / event processing wrapper may need post-macrostep hook
- `integration/fulcro/route_url.cljc` — URL composition from multiple state contributions

## Approach

### Post-Macrostep URL Update

Instead of each state's on-entry pushing history, introduce a **post-macrostep hook** that computes and applies the URL:

```
1. User clicks "Settings" → route-to event fired
2. Macrostep runs: exit old states, enter new states (potentially async)
3. Configuration stabilizes
4. Post-macrostep hook:
   a. Read active configuration
   b. Find the active leaf route state(s)
   c. Resolve URL path from leaf state's composed path
   d. Collect query params from all active route states
   e. Compute single URL
   f. push-route! (or replace-route! for same-top-level transitions)
```

This hook can be implemented as:
- A wrapper around `scf/send!` that handles the post-processing
- Or a statechart `done` callback on the event processing
- Or a watcher on the working memory atom that triggers URL sync when configuration changes

### State Param Lifecycle

Each route state declares its params (already supported via `:route/params`). The coordination layer:
- On state entry: adds that state's params to the URL
- On state exit: removes that state's params from the URL
- This is computed from the active configuration, not tracked incrementally

### Push vs Replace Heuristic

- **Push**: When the leaf route state changes (user navigated to a different page)
- **Replace**: When params change within the same leaf route (e.g., filter updated, tab switched)
- **Suppress**: During restoration or internal housekeeping transitions

Track the previous leaf route state(s) to make this determination.

## Design Decisions to Resolve During Implementation

- Should the post-macrostep hook be a protocol method, a callback, or built into the Fulcro integration's event processing?
- How to determine the "primary" region in a parallel state for path contribution?
- Should URL sync be opt-in per chart, or automatic for all charts using `start-routing!`?
- How to handle URL sync when multiple charts are running (nested `istate` charts)?

## Verification

1. [ ] Single history entry per user navigation (no intermediate entries for deep state changes)
2. [ ] Query params from exited states are removed from URL
3. [ ] Query params from entered states are added to URL
4. [ ] Parallel regions contribute params independently to a single URL
5. [ ] Browser back restores previous state (with async entry if needed)
6. [ ] Browser forward works symmetrically
7. [ ] `busy?` guard prevents back/forward and undoes URL change
8. [ ] URL bar editing triggers correct state restoration
9. [ ] Internal transitions (same leaf) use replace, not push
10. [ ] Restoration mode suppresses all URL updates until complete
11. [ ] routing-demo2 demonstrates bidirectional URL sync (URL updates on navigation, state updates on URL edit)
