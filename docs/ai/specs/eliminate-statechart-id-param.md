# Spec: Eliminate redundant `statechart-id` parameter from `ui-routes` API

**Status**: backlog
**Priority**: P1
**Created**: 2026-02-18
**Owner**: AI

## Context

After the session-id simplification (spec `ui-routing2-simplify-session-id`), `install-url-sync!` and `active-leaf-routes` derive the statechart registration key from working memory (`::sc/statechart-src`). But `start!` still forces the caller to invent a `statechart-id` that is never referenced again — the only consumer was `install-url-sync!`, which no longer needs it.

The root issue: `start!` takes `statechart-id` as a parameter, registers the chart under that key, then hardcodes the well-known `session-id` constant for the session. The caller must track a key they never use again.

## Requirements

### 1. Simplify `start!` signature

**Before:** `(start! app statechart-id statechart)`
**After:** `(start! app statechart)`

Register the chart under `session-id` (the existing constant `::session`). This makes the registration key and session key the same well-known value — which is fine since there's exactly one routing session per app.

```clojure
(defn start!
  "Registers the routing `statechart` and starts a routing session.
   No URL history integration is performed."
  [app statechart]
  (scf/register-statechart! app session-id statechart)
  (scf/start! app {:machine    session-id
                   :session-id session-id
                   :data       {}}))
```

### 2. Update demo app

- `routing_demo2/app.cljs`: Remove `(def chart-key ::routing-chart)`, change `(uir/start! app-instance chart-key demo-chart/routing-chart)` → `(uir/start! app-instance demo-chart/routing-chart)`

### 3. Update tests

- `url_sync_headless_spec.cljc`: Change all `(uir/start! app ::test-chart test-routing-chart)` → `(uir/start! app test-routing-chart)`

### 4. Update Guide.adoc

Change `(uir/start! app :my-app/routing routing-chart)` → `(uir/start! app routing-chart)` (line ~1079).

### 5. Update spec doc

Update `ui-routing2-simplify-session-id.md` API table row for `start!`.

## Affected Files

1. `src/main/com/fulcrologic/statecharts/integration/fulcro/ui_routes.cljc` — `start!` function (~line 627)
2. `src/routing-demo2/com/fulcrologic/statecharts/routing_demo2/app.cljs` — remove `chart-key`, update `start!` call
3. `src/test/com/fulcrologic/statecharts/integration/fulcro/url_sync_headless_spec.cljc` — update `start!` calls
4. `Guide.adoc` — update example
5. `docs/ai/specs/ui-routing2-simplify-session-id.md` — update API table

## Not Changing

- **`istate` child chart registration** — `istate` uses `target-key` as both the registry key and invocation tracking key. This is intentional: each child chart needs a unique registry key, and the component key serves that purpose. Different concept from the root routing chart.
- **Functions taking `app-ish`** — `route-to!`, `force-continue-routing!`, etc. need `app-ish` to access the Fulcro app's event queue. The `app-ish` parameter is not redundant.

## Verification

1. [ ] Reload `ui-routes` namespace in REPL
2. [ ] Run `url-sync-headless-spec` tests — expect 15 assertions, 0 failures
3. [ ] Run `ui-routes-test` — expect same results as before (the "Route entry" error is pre-existing)
4. [ ] Verify shadow-cljs compiles routing-demo2 without warnings
