# Routing Options Namespace Rename

**Status**: Backlog
**Priority**: P2
**Created**: 2026-02-19
**Owner**: —

## Context

The routing system follows a RAD-like pattern where the options namespace is the base library namespace plus `-options`. Currently:

- `c.f.s.i.f.ui-routes-options` (deprecated) defines component options as qualified keywords under `::ui-routes/`
- `c.f.s.i.f.routing.core` is the main routing API namespace
- The `core` suffix is non-standard — the API ns should just be `c.f.s.i.f.routing`

To follow the RAD pattern:
- Main API: `c.f.s.i.f.routing` (rename from `routing.core`)
- Options: `c.f.s.i.f.routing-options` (new, duplicates deprecated `ui-routes-options`)

The deprecated `ui-routes-options` stays as-is for backward compat. The new `routing-options` ns defines the same vars but with qualified keywords under `::routing/` namespace.

## Requirements

1. **Create** `src/main/com/fulcrologic/statecharts/integration/fulcro/routing_options.cljc`
   - Namespace: `c.f.s.i.f.routing-options`
   - Same vars as `ui-routes-options`: `initialize`, `initial-props`, `busy?`, `statechart`, `statechart-id`, `actors`
   - Keywords qualified under `::routing/` (i.e. `:c.f.s.i.f.routing/initialize`, etc.)
   - **IMPORTANT**: These are NEW keywords, not aliases of the old ones. Users of the new routing system will use these; the old keywords from `ui-routes-options` are for legacy `ui-routes` users only.

2. **Rename** `routing/core.cljc` → `routing.cljc`
   - New namespace: `c.f.s.i.f.routing`
   - The old `routing/core.cljc` should become a thin delegation ns with `^:deprecated` metadata that requires the new ns and re-defs all public vars (use `potemkin/import-vars` pattern or manual def delegation)

3. **Rename** `routing/core_test.cljc` → `routing_test.cljc`
   - Update ns to `c.f.s.i.f.routing-test`
   - Update require from `routing.core` to `routing`

4. **Update alias** in the main routing ns from `uro` to `sfro` (statechart fulcro routing options)
   - Change the require from `ui-routes-options :as uro` to `routing-options :as sfro`
   - Update all `uro/` references in the routing ns body to `sfro/`

5. **Update `routing.core` require in other files**:
   - `routing-demo2/ui.cljs` — requires `routing.core :as sroute` → `routing :as sroute` (alias stays same)
   - `routing-demo2/ui.cljs` — uses `uro/busy?`, `uro/statechart-id` → `sfro/busy?`, `sfro/statechart-id` (update require + references)

6. **Update Guide.adoc** — all `uro/` prefixes in routing section become `sfro/`

7. **Update `integration/CLAUDE.md`** — file map entry for `routing/core.cljc` → `routing.cljc`

8. **Do NOT change** `ui-routes-options` or `ui-routes` — they remain deprecated as-is

## Files to Create

```
src/main/.../fulcro/routing_options.cljc     — NEW options ns
src/main/.../fulcro/routing.cljc             — RENAMED from routing/core.cljc
src/test/.../fulcro/routing_test.cljc        — RENAMED from routing/core_test.cljc
```

## Files to Modify

```
src/main/.../fulcro/routing/core.cljc                  — Convert to deprecated delegation ns
src/main/.../fulcro/routing/url_codec.cljc             — Docstring ref to routing.core → routing
src/routing-demo2/.../routing_demo2/ui.cljs            — Update routing.core → routing require; uro/ → sfro/
src/routing-demo2/.../routing_demo2/app.cljs           — Update routing.core → routing require
src/routing-demo2/.../routing_demo2/chart.cljc         — Update routing.core → routing require + docstrings
src/routing-demo2/.../routing_demo2/admin_chart.cljc   — Update routing.core → routing require + docstring
src/test/.../routing_demo2/chart_test.cljc             — Update routing.core → routing require
src/test/.../fulcro/routing/url_sync_headless_spec.cljc — Update routing.core → routing require
Guide.adoc                                             — routing.core → routing (6 refs); uro/ → sfro/ (8 refs)
src/main/.../integration/CLAUDE.md                     — Update file map
```

## Files NOT to Touch

```
src/main/.../fulcro/ui_routes.cljc           — Legacy, uses `ro` alias, unchanged
src/main/.../fulcro/ui_routes_options.cljc   — Deprecated, unchanged
```

## Approach

### Step 1: Create `routing_options.cljc`

```clojure
(ns com.fulcrologic.statecharts.integration.fulcro.routing-options
  "Component options for statechart-driven routing.
   Follows the RAD pattern: options ns = base API ns + '-options'.")

(def initialize
  "Component option. One of :once, :always, or :never. ..."
  :com.fulcrologic.statecharts.integration.fulcro.routing/initialize)

(def initial-props ...)
(def busy? ...)
(def statechart ...)
(def statechart-id ...)
(def actors ...)
```

### Step 2: Create `routing.cljc` (copy of `routing/core.cljc`)

- Copy full content of `routing/core.cljc`
- Change ns to `c.f.s.i.f.routing`
- Change require: `ui-routes-options :as uro` → `routing-options :as sfro`
- Find/replace all `uro/` → `sfro/` in file body
- Find/replace docstring references from `uro/` → `sfro/`

### Step 3: Convert `routing/core.cljc` to deprecated delegation

```clojure
(ns ^:deprecated com.fulcrologic.statecharts.integration.fulcro.routing.core
  "DEPRECATED. Use com.fulcrologic.statecharts.integration.fulcro.routing instead."
  (:require [com.fulcrologic.statecharts.integration.fulcro.routing :as routing]))

(def session-id routing/session-id)
(def route-to-event-name routing/route-to-event-name)
;; ... all public vars ...
```

### Step 4: Update test files

- Copy `routing/core_test.cljc` → `routing_test.cljc` — update ns and require
- `url_sync_headless_spec.cljc` — change require from `routing.core` → `routing`
- `routing_demo2/chart_test.cljc` — change require from `routing.core` → `routing`

### Step 5: Update routing-demo2 (4 files)

All demo files require `routing.core :as sroute` — update to `routing :as sroute` (alias stays `sroute`):
- `ui.cljs` — also change `uro/busy?` → `sfro/busy?`, `uro/statechart-id` → `sfro/statechart-id`, add `routing-options :as sfro` require
- `app.cljs` — require only
- `chart.cljc` — require + docstring references to `routing.core`
- `admin_chart.cljc` — require + docstring reference

### Step 6: Update Guide.adoc

- 6 references to `routing.core` (ns name, require forms, prose) → `routing`
- 8 references to `uro/` (component options documentation) → `sfro/`
- Update the require example: `routing.core :as sroute` → `routing :as sroute`

### Step 7: Update integration/CLAUDE.md

- File map: `fulcro/routing/core.cljc` → `fulcro/routing.cljc`
- Add entry for `fulcro/routing_options.cljc`

### Step 8: Update url_codec.cljc

- Docstring reference to `routing.core` → `routing`

## Verification

1. **CLJ tests**: Run all CLJ tests via REPL with Kaocha (see `docs/ai/running-tests.md`) — all must pass
2. **CLJS tests**: `npx shadow-cljs compile test && npx karma start --single-run` — all must pass
3. **CLJS demo build**: `npx shadow-cljs compile routing-demo2` — compiles without warnings
4. **Grep audit**: `uro/` references should be zero outside deprecated `ui_routes.cljc` and `ui_routes_options.cljc`
5. **Grep audit**: `routing.core` requires should only exist in the deprecated delegation ns `routing/core.cljc`
6. **Grep audit**: No remaining `routing\.core` in Guide.adoc or demo docstrings
