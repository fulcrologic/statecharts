# Spec: URLHistoryProvider Protocol

**Status**: done
**Completed**: 2026-02-18
**Priority**: P1
**Created**: 2026-02-18
**Owner**: conductor

## Context

`ui_routing2.cljc` needs a headless-capable URL history abstraction so that routing+URL behavior can be tested in CLJ without a browser. The browser-touching surface is just 3 functions (`current-url`, `push-url!`, `replace-url!`) plus popstate listener management. A protocol cleanly separates the history stack concern from pure routing logic.

Additionally, the current `pushState` passes `nil` as state data. The W3C History API allows passing state that comes back in `popstate` events. By pushing a monotonic index `{index: N}`, the popstate listener can determine navigation direction (back vs forward) and the on-save handler can distinguish browser-initiated from programmatic navigation — preventing history stack corruption.

## Requirements

1. Define `URLHistoryProvider` protocol in `route_url.cljc` with these methods:
   - `current-href` — returns current URL string or nil
   - `current-index` — returns the monotonic index of the current history entry
   - `-push-url!` — push new URL, increment index, store index in pushState
   - `-replace-url!` — replace current URL (same index)
   - `go-back!` — navigate back, fire popstate callback
   - `go-forward!` — navigate forward, fire popstate callback
   - `set-popstate-listener!` — register `(fn [index])` callback, or nil to remove

2. Implement `BrowserURLHistory` (CLJS only, `deftype`):
   - Wraps `js/window.history` and `js/window.location`
   - Maintains a `counter-atom` for monotonic index
   - **Constructor seeds initial entry**: calls `replaceState` with `{index: 0}` so that back-before-start produces index=0 not nil
   - `pushState` passes `#js {:index N}` as state
   - `replaceState` passes `#js {:index current}` as state
   - `set-popstate-listener!` wraps callback to extract `event.state.index`; passes nil index if `event.state` is null (back past initial entry)
   - `go-back!`/`go-forward!` delegate to `js/window.history`

3. Implement `SimulatedURLHistory` (cross-platform, `defrecord`):
   - State atom: `{:entries [{:url "/" :index 0}] :cursor 0 :counter 0 :listener nil}`
   - `-push-url!` truncates forward entries at cursor, appends new entry with incremented index
   - `-replace-url!` replaces URL at cursor position (same index)
   - `go-back!` decrements cursor (if > 0), invokes listener synchronously with target entry's index. **Cursor update happens FIRST, then listener fires** (listener sees consistent state).
   - `go-forward!` increments cursor (if < end), invokes listener synchronously with target entry's index. Same order guarantee.
   - `go-back!` at cursor=0 and `go-forward!` at end are no-ops (no listener call)
   - `set-popstate-listener!` stores callback in atom

4. Inspection helpers (functions, not protocol methods):
   - `(history-stack provider)` — vector of URL strings
   - `(history-cursor provider)` — current cursor position
   - `(history-entries provider)` — full entries with indices

5. Existing `current-url`, `push-url!`, `replace-url!` 0-arity functions remain unchanged for backward compatibility.

## Affected Modules

- `integration/fulcro/route_url.cljc` — Protocol definition, both implementations, constructors, inspection helpers

## Approach

Add protocol and implementations after the existing "Browser URL utilities" section in `route_url.cljc`. The `BrowserURLHistory` deftype goes inside `#?(:cljs ...)`. The `SimulatedURLHistory` defrecord is cross-platform. Existing bare functions (`current-url`, `push-url!`, `replace-url!`) are untouched — the protocol is a new abstraction consumed by `install-url-sync!` (separate spec).

## Verification

1. [ ] `SimulatedURLHistory` unit test: push 3 URLs, verify stack = [initial, url1, url2, url3], cursor = 3
2. [ ] `SimulatedURLHistory` unit test: push 3, go-back twice, cursor = 1, current-href = url1
3. [ ] `SimulatedURLHistory` unit test: push 3, go-back 2, go-forward 2, back at url3 (no corruption)
4. [ ] `SimulatedURLHistory` unit test: push 3, go-back 2, push new-url → truncates forward history, stack = [initial, url1, new-url]
5. [ ] `SimulatedURLHistory` unit test: replace-url changes URL at cursor but not index
6. [ ] `SimulatedURLHistory` unit test: go-back invokes listener with correct index
7. [ ] `SimulatedURLHistory` unit test: go-back at cursor=0 is no-op, no listener call
8. [ ] `BrowserURLHistory` smoke test (CLJS): push-url stores index in history.state, popstate returns it
9. [ ] Existing `push-url!`/`replace-url!`/`current-url` still work (backward compat)
