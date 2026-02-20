# Spec: Move URL Sync State to Fulcro Runtime Atom

**Status**: done
**Completed**: 2026-02-18
**Priority**: P1
**Created**: 2026-02-18
**Owner**: conductor

## Context

`ui_routing2.cljc` stores URL sync state in module-level `defonce` atoms (lines 631-634):

```clojure
(defonce ^:private url-sync-handlers (atom {}))      ;; session-id -> handler fn
(defonce ^:private url-sync-child-to-root (atom {}))  ;; child-sid -> root-sid
```

This means ALL Fulcro app instances share URL sync state. For headless testing with multiple simulated browser sessions (or multiple apps), each app must own its URL sync state independently. The state should live in the Fulcro runtime atom, following the existing pattern used by `::sc/env` (see `fulcro.cljc:305`).

## Requirements

1. Store URL sync state in the Fulcro app's runtime atom under `::url-sync`:
   ```clojure
   {::url-sync {:handlers      {session-id handler-fn}
                :child-to-root {child-sid root-sid}}}
   ```

2. Add helper functions for accessing/updating:
   - `(url-sync-state app)` — reads `::url-sync` from runtime atom
   - `(swap-url-sync! app f & args)` — updates `::url-sync` in runtime atom

3. **Remove the 2-arity `url-sync-on-save`** (breaking change). All callers must use the 3-arity form `(url-sync-on-save session-id wmem app)`. The 2-arity cannot determine which app to look in. This is a clean break — the 3-arity already exists and is the recommended form.

4. Update 3-arity `url-sync-on-save` to read handlers from the app's runtime atom.

5. Update `install-url-sync!` to register/deregister handlers in the runtime atom.

6. Update `find-root-session` to read child-to-root cache from the app's runtime atom. It already receives `state-map` so needs the app passed through or the cache looked up from state-map.

7. Update cleanup function to clear from runtime atom.

8. Remove the module-level `defonce` atoms.

## Affected Modules

- `integration/fulcro/ui_routing2.cljc` — `url-sync-handlers`, `url-sync-child-to-root`, `url-sync-on-save`, `install-url-sync!`, `find-root-session`

## Approach

Replace `@url-sync-handlers` reads with `(-> (url-sync-state app) :handlers)` and `swap!` calls with `(swap-url-sync! app ...)`. The `find-root-session` function already receives `state-map` so it can look up the handlers. Thread `app` through where needed.

## Verification

1. [ ] Two headless Fulcro apps with separate URL sync sessions don't interfere with each other
2. [ ] `install-url-sync!` registers handler in runtime atom (not global)
3. [ ] Cleanup function removes handler from runtime atom
4. [ ] `url-sync-on-save` 3-arity reads handlers from correct app's runtime atom
5. [ ] `url-sync-on-save` 2-arity is removed; callers updated to 3-arity
6. [ ] Child-to-root cache is per-app
7. [ ] Module-level `defonce` atoms are removed
8. [ ] routing-demo2 updated to use 3-arity `url-sync-on-save` (already does — line 39 of app.cljs)
9. [ ] routing-demo2 still works (CLJS, browser)
