# Spec: Simplify ui-routing2 Public API — Remove Unnecessary Session ID Parameters

**Status**: done
**Completed**: 2026-02-18
**Priority**: P1
**Created**: 2026-02-18
**Owner**: AI

## Context

`ui_routing2.cljc` was designed to support multiple independent routing statecharts by
parameterizing the session ID throughout its public API. However, this generality is
unnecessary:

1. **Parallel regions already solve multiple routing areas** — you don't need two router
   instances; a single chart with parallel states handles independent routing regions.
2. **Nested `istate` child charts don't surface session IDs to users** — `send-to-self!`
   looks up the child session internally from the parent session's data; user code never
   sees child session IDs via the public API.

The result is gratuitous boilerplate at every call site. `ui-routes.cljc` correctly used a
well-known `::session` constant, and `ui-routing2` should do the same. The older `ui-routes`
API shape is the right model; `ui-routing2` adds real value through deep `busy?` checking,
cross-chart `:route/reachable` transitions, and decoupled URL sync — not through session-id
parameterization.

## Requirements

### 1. Add a well-known session-id constant
```clojure
(def session-id
  "The well-known statechart session ID used for the routing statechart."
  ::session)
```

### 2. Remove session-id from all public API functions

| Current signature | New signature |
|---|---|
| `(route-to! app session-id target)` | `(route-to! app target)` |
| `(route-to! app session-id target data)` | `(route-to! app target data)` |
| `(ui-current-subroute this session-id factory-fn)` | `(ui-current-subroute this factory-fn)` |
| `(active-leaf-routes app-ish session-id statechart-id)` | `(active-leaf-routes app-ish)` |
| `(route-denied? app-ish session-id)` | `(route-denied? app-ish)` |
| `(force-continue-routing! app-ish session-id)` | `(force-continue-routing! app-ish)` |
| `(abandon-route-change! app-ish session-id)` | `(abandon-route-change! app-ish)` |
| `(send-to-self! this routing-session-id event)` | `(send-to-self! this event)` |
| `(send-to-self! this routing-session-id event data)` | `(send-to-self! this event data)` |
| `(current-invocation-configuration this routing-session-id)` | `(current-invocation-configuration this)` |
| `(start! app session-id statechart-id statechart)` | `(start! app statechart)` |
| `(install-url-sync! app session-id statechart-id & opts)` | `(install-url-sync! app & opts)` |

### 3. Keep `url-sync-on-save` signature unchanged
It receives `[session-id wmem app]` as a framework callback — the session-id comes from
the `:on-save` hook, not from user code. No change needed.

### 4. Update all docstrings
Remove all mention of `session-id` parameters from public function docstrings.
Update `routing_regions` and related docstrings to reflect the simpler API.

### 5. Update or create routing-demo2
Create `src/routing-demo2/` (or update existing routing-demo) to demonstrate `ui-routing2`
with the simplified API. Add a `:routing-demo2` build to `shadow-cljs.edn`.
The demo should show: basic routing, guarded transitions (busy form), cross-chart routing
via `:route/reachable`, and URL sync via `install-url-sync!`.

### 6. Update Guide.adoc
The routing section (line ~1040+) references `ui-routes`. Update it to reference `ui-routing2`
and show the current API. Remove any mention of session-id as a user-visible concern.

## Affected Modules

- `src/main/com/fulcrologic/statecharts/integration/fulcro/ui_routing2.cljc` — main refactoring
- `src/routing-demo2/` — new or updated demo (to be created)
- `shadow-cljs.edn` — add `:routing-demo2` build
- `deps.edn` — add `src/routing-demo2` to source paths
- `Guide.adoc` — update routing section

## Approach

1. Add `session-id` constant and update all public functions to use it internally
2. Remove the `session-id` / `routing-session-id` parameter from each public function,
   replacing references to it with the constant
3. Update docstrings throughout
4. Create routing-demo2 modeled after routing-demo but using `ui-routing2` API
5. Add build configuration for routing-demo2
6. Update Guide.adoc routing section

## Verification

1. [x] `(def session-id ::session)` is defined in `ui_routing2.cljc`
2. [x] All public API functions listed in Requirement 2 have the simplified signatures
3. [x] `url-sync-on-save` signature is unchanged
4. [x] All docstrings are updated — no stale references to removed session-id params
5. [x] `routing-demo2` compiles without warnings under shadow-cljs
6. [x] Demo demonstrates: basic routing, busy guard, cross-chart routing, URL sync
7. [x] Guide.adoc routing section references `ui-routing2` and shows correct API
8. [x] Existing tests still pass (url-sync-headless-spec: 15 assertions, 0 failures)
