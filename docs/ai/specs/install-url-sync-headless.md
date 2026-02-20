# Spec: Headless install-url-sync! with Provider and Index Tracking

**Status**: done
**Completed**: 2026-02-18
**Priority**: P1
**Created**: 2026-02-18
**Depends-on**: url-history-protocol, url-parsing-cross-platform, url-sync-runtime-state
**Owner**: conductor

## Context

`install-url-sync!` in `ui_routing2.cljc` is wrapped in `#?(:cljs ...)` and directly calls `js/window`, `js/setTimeout`, and `route-url/current-url`. After the three prerequisite specs are done (protocol, CLJ parsing, runtime state), this function can be refactored to accept a `URLHistoryProvider` and work cross-platform.

The critical correctness issue this spec addresses: **history stack corruption during back/forward navigation**. The current on-save handler fires on every statechart save and may push URLs even when the state change was triggered by browser back/forward. This corrupts the stack so that back-2x/forward-2x doesn't return to the starting point.

## Requirements

### Cross-Platform Provider Integration

1. Remove the `#?(:cljs ...)` wrapper from `install-url-sync!`
2. Add `:provider` option: defaults to `(browser-url-history)` on CLJS, required on CLJ (throws clear error if missing)
3. Replace all direct browser calls with provider methods:
   - `(some-> js/window .-location .-pathname)` → `(current-href provider)`
   - `route-url/current-url` → `(current-href provider)`
   - `route-url/push-url!` → `(-push-url! provider ...)`
   - `route-url/replace-url!` → `(-replace-url! provider ...)`
   - `(route-url/url->route-target)` 0-arity → `(route-url/url->route-target (current-href provider))`
4. Replace `js/setTimeout`/`js/clearTimeout` with platform-appropriate debounce:
   - CLJS: keep existing setTimeout (default 50ms)
   - CLJ: invoke directly with no debounce (synchronous headless)
5. Replace `.addEventListener/.removeEventListener` with `(set-popstate-listener! provider ...)`
6. Cleanup function calls `(set-popstate-listener! provider nil)`

### Index-Based Navigation State (History Corruption Fix)

7. The popstate listener receives the target entry's index from the provider
8. Popstate listener stores `@prev-url` (the tracked URL BEFORE the browser moved) and the popped index, then sets `nav-state`:
   ```
   {:browser-initiated? true
    :pre-nav-index     (current-index provider)   ;; where we were
    :popped-index      popped-index                ;; where browser moved to
    :pre-nav-url       @prev-url}                  ;; URL before browser moved
   ```
   **Note:** `current-href` at popstate time returns the NEW url (browser already moved). `@prev-url` is the correct pre-nav reference.
9. On-save handler has **four branches** (read then clear `nav-state`):
   - **Browser-initiated + route accepted**: `browser-initiated?` true AND `new-url` matches expected destination → skip push/replace, update `prev-url`
   - **Browser-initiated + route denied**: `browser-initiated?` true AND `new-url` does NOT match where browser went (busy guard rejected) → call `go-forward!` or `go-back!` on provider to undo the browser's navigation (compare `popped-index` vs `pre-nav-index` to determine direction)
   - **Initial load** (`old-url` is nil) → `(-replace-url! provider new-url)`
   - **Programmatic navigation** (no flag, URL changed) → `(-push-url! provider new-url)`
10. On-save handler clears `nav-state` after reading it
11. JS is single-threaded so the popstate→route-to→on-save chain completes atomically. No async race condition. Last-one-wins is the natural behavior.

### Backward Compatibility

12. Existing CLJS callers with no `:provider` option get `BrowserURLHistory` automatically
13. routing-demo2 requires zero code changes
14. `url-sync-on-save` 2-arity is removed (breaking change). All callers must use the 3-arity form that receives `app`. The `install-url-sync-headless` and `url-sync-runtime-state` specs together handle this.

## Affected Modules

- `integration/fulcro/ui_routing2.cljc` — `install-url-sync!` (major refactor), requires for `route-url` protocol

## Approach

### Popstate Listener Flow

```
popstate fires with popped-index
  → save pre-nav-url from @prev-url (NOT current-href, which is already the new URL)
  → save pre-nav-index from (current-index provider)
  → set nav-state {:browser-initiated? true
                   :pre-nav-index pre-nav-index
                   :popped-index popped-index
                   :pre-nav-url pre-nav-url}
  → parse URL from (current-href provider)  ;; this IS the destination URL now
  → find matching route target
  → send route-to! event to statechart
  → statechart transitions → on-save fires
  → on-save handles it (see below)
```

### On-Save Handler Flow (4 branches)

```
on-save fires
  → read nav-state, then clear it
  → compute new-url from deep-configuration->url
  → compute browser-url from (current-href provider)

  BRANCH 1: browser-initiated + route ACCEPTED
    (browser-initiated? AND new-url == browser-url)
    → just update prev-url to new-url
    → do NOT touch history (browser already moved)

  BRANCH 2: browser-initiated + route DENIED
    (browser-initiated? AND new-url != browser-url)
    → route was rejected by busy guard; browser moved but chart didn't
    → undo the browser navigation:
        if popped-index < pre-nav-index → (go-forward! provider)  ;; user went back, undo by going forward
        if popped-index > pre-nav-index → (go-back! provider)     ;; user went forward, undo by going back
    → restore prev-url to pre-nav-url
    → fire on-route-denied callback if provided

  BRANCH 3: initial load (old-url is nil)
    → (-replace-url! provider new-url)
    → set prev-url to new-url

  BRANCH 4: programmatic navigation (no nav-state, URL changed)
    → (-push-url! provider new-url)
    → set prev-url to new-url
```

**Critical note on Branch 2:** When `go-forward!`/`go-back!` is called to undo, the provider fires the popstate listener again. This must NOT cause a re-entrant loop. Solution: set a `restoring?` flag before calling `go-forward!/go-back!`, and have the popstate listener skip when `restoring?` is true. Clear after the undo completes.

### Platform Debounce

```clojure
(let [debounce-ms #?(:cljs 50 :clj 0)]
  ;; CLJS: js/setTimeout wrapping as before
  ;; CLJ: direct invocation (no timer needed for synchronous headless)
  ...)
```

## Verification

### History Integrity (test BOTH browser and headless)

1. [ ] Navigate A→B→C programmatically, verify stack = [A, B, C]
2. [ ] Back from C: statechart goes to B, stack unchanged [A, B, C], cursor at B
3. [ ] Back again: statechart goes to A, stack unchanged, cursor at A
4. [ ] Forward: statechart goes to B, stack unchanged, cursor at B
5. [ ] Forward: statechart goes to C, stack unchanged, cursor at C — **back where started**
6. [ ] Back to B, then programmatic navigate to D: stack = [A, B, D] (forward history truncated)
7. [ ] Route denied by busy guard during back: URL restored to pre-nav position

### Cross-Platform (headless CLJ)

8. [ ] `install-url-sync!` with `SimulatedURLHistory` provider works on CLJ
9. [ ] Popstate callback fires synchronously on `go-back!`/`go-forward!`
10. [ ] `url->route-target` parses simulated URLs correctly on CLJ
11. [ ] Multiple headless apps with separate providers don't interfere

### Backward Compatibility (browser CLJS)

12. [ ] routing-demo2 works with zero code changes
13. [ ] `install-url-sync!` with no `:provider` auto-creates `BrowserURLHistory` on CLJS
14. [ ] Browser back/forward works correctly with index in history.state
