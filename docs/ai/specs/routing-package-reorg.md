# Routing Package Reorganization

**Status**: Done
**Completed**: 2026-02-19
**Priority**: P2
**Created**: 2026-02-19
**Owner**: —

## Context

The `routing-rewrite` branch added URL codec protocols, browser history providers, URL sync, cross-chart routing, deep busy checking, and validation. This code currently lives in `ui_routes.cljc` (grown from 650→1194 lines) plus 3 already-extracted sibling files (`route_url.cljc` 224 lines, `url_codec.cljc` 217 lines, `url_codec_transit.cljc` 120 lines).

`ui_routes` on `main` is published API. We need backward compatibility for existing users while making the new routing system available under a clean package.

## Requirements

1. **Move** all new routing code into `com.fulcrologic.statecharts.integration.fulcro.routing.*`
2. **Revert** `ui_routes.cljc` to main's 650-line version + deprecation docstring pointing to `routing.*`
3. **Revert** `ui_routes_options.cljc` to main's version (remove `checks` def — it's a fn arg to `start!`/`install-url-sync!`, not a component option)
4. **No delegation wrappers** — old `ui_routes` keeps main's behavior, new features are `routing.*`-only
5. **Revert** `ui_routes_test.cljc` to main's 109-line version; move the 743 lines of new test code to `routing/`

## New Package Layout

```
c.f.s.i.f.routing/
  core.cljc              ;; Full routing API: DSL, URL sync, validation, busy checking, rendering
  url_codec.cljc         ;; URLCodec protocol (moved from existing url_codec.cljc)
  url_codec_transit.cljc ;; TransitBase64Codec (moved from existing url_codec_transit.cljc)
  url_history.cljc       ;; URLHistoryProvider protocol + shared helpers (no implementations)
  browser_history.cljc   ;; BrowserURLHistory deftype + factory (CLJS-only content)
  simulated_history.cljc ;; SimulatedURLHistory defrecord + factory + inspection helpers
```

## Approach

### Step 1: Create directory structure
```
src/main/com/fulcrologic/statecharts/integration/fulcro/routing/
src/test/com/fulcrologic/statecharts/integration/fulcro/routing/
```

### Step 2: Move already-extracted files (git mv + ns rename)

These files already exist as standalone, zero-coupling-to-ui_routes files:

**`url_codec.cljc` → `routing/url_codec.cljc`**
- Rename ns: `c.f.s.i.f.url-codec` → `c.f.s.i.f.routing.url-codec`
- No require changes needed — only requires `statecharts`, `protocols`, `clojure.set`, `clojure.string`
- Contains: `URLCodec` protocol, `element-segment`, `path-from-configuration`, `params-from-configuration`, `configuration->url`, `deep-configuration->url`

**`url_codec_transit.cljc` → `routing/url_codec_transit.cljc`**
- Rename ns: `c.f.s.i.f.url-codec-transit` → `c.f.s.i.f.routing.url-codec-transit`
- Update require: `c.f.s.i.f.url-codec` → `c.f.s.i.f.routing.url-codec`
- Contains: `TransitBase64Codec`, `transit-base64-codec` factory, base64 helpers, param encoding/decoding

**`route_url.cljc` → split into 3 files for CLJS dead-code elimination**

CLJS cannot DCE protocols, so implementations must be in separate namespaces.
Apps that only use `BrowserURLHistory` won't bundle `SimulatedURLHistory` and vice versa.

**`routing/url_history.cljc`** — Protocol + shared helpers (DCE-friendly)
- New ns: `c.f.s.i.f.routing.url-history`
- Requires: `c.f.s.i.f.routing.url-codec` (for `element-segment`)
- Contains from `route_url.cljc`:
  - `URLHistoryProvider` protocol (lines 41-49)
  - `current-url`, `current-url-path` — browser URL utilities (lines 19-31)
  - `push-url!`, `replace-url!` — bare pushState/replaceState wrappers (lines 33-35)
  - `find-target-by-leaf-name`, `find-target-by-leaf-name-deep` — URL restoration (lines 176-207)
  - `new-url-path` — legacy compat (lines 213-224)

**`routing/browser_history.cljc`** — Browser implementation (CLJS-only)
- New ns: `c.f.s.i.f.routing.browser-history`
- Requires: `c.f.s.i.f.routing.url-history` (for protocol)
- Contains from `route_url.cljc`:
  - `BrowserURLHistory` deftype (lines 56-82) — wraps `js/window.history` with monotonic index
  - `browser-url-history` factory fn (lines 84-91) — seeds initial entry with `replaceState`

**`routing/simulated_history.cljc`** — Test implementation (cross-platform)
- New ns: `c.f.s.i.f.routing.simulated-history`
- Requires: `c.f.s.i.f.routing.url-history` (for protocol)
- Contains from `route_url.cljc`:
  - `SimulatedURLHistory` defrecord (lines 97-140) — atom-backed history stack
  - `simulated-url-history` factory fn (lines 142-151)
  - `history-stack`, `history-cursor`, `history-entries` inspection helpers (lines 157-170) — these reach into `SimulatedURLHistory`'s `:state-atom` internals

### Step 3: Create `routing/core.cljc` — the entire branch `ui_routes.cljc` (lines 1-1194)

New ns: `c.f.s.i.f.routing.core`

This is the complete new routing system — DSL, URL sync, and all. It's the branch's `ui_routes.cljc` with updated namespace and requires. One file, one require for users.

**Requires** (update from branch's ui_routes ns form):
- All of branch's ui_routes requires EXCEPT the old sibling paths
- Replace `c.f.s.i.f.route-url` → `c.f.s.i.f.routing.url-history` (protocol + helpers only; core doesn't need browser/simulated impls directly)
- Replace `c.f.s.i.f.url-codec` → `c.f.s.i.f.routing.url-codec`
- Replace `c.f.s.i.f.url-codec-transit` → `c.f.s.i.f.routing.url-codec-transit`
- Keep: `c.f.s.i.f.ui-routes-options :as uro` (component option keywords — `uro` avoids RAD `ro` collision)

**Contents — the full file, organized by section:**

Shared utilities (lines 36-57):
- `form?`, `rad-report?`, `?!`, `coerce-to-keyword`

Public constants (lines 32-34):
- `session-id` (well-known `::session`)

Route name generation (lines 59-68):
- `route-to-event-name`

Validation (lines 72-221 — BRANCH ONLY, not on main):
- `report-issue!` (private), `find-all-route-targets` (private), `find-all-reachable-targets` (private)
- `validate-duplicate-leaf-names`, `validate-reachable-collisions`, `validate-routing-root`
- `compute-segment-chain` (private), `validate-duplicate-segments`
- `validate-route-configuration`

Route initialization (lines 223-327):
- `initialize-route!`, `replace-join!`, `find-parent-route` (private), `update-parent-query!`
- `establish-route-params-node` — NOTE: branch version is simpler than main's (no URL history logic)

DSL builders (lines 339-446):
- `rstate` — NOTE: branch version throws if `:id` passed; uses `:route/segment` instead of `:route/path`
- `istate` — branch version adds `:route/reachable`, `::pending-child-route`

Busy checking (lines 448-507):
- `busy-form-handler`, `check-component-busy?` (private)
- `deep-busy?` (private — BRANCH ONLY, walks invocation tree)
- `busy?`

Route state (lines 509-648):
- `record-failed-route!`, `clear-override!`, `override-route!`
- `routing-info-state` (def)
- `find-reachable-owner` (private — BRANCH ONLY)
- `routes` — NOTE: branch version has cross-chart routing via `:route/reachable`

Regions + rendering (lines 649-706):
- `routing-regions`, `ui-current-subroute`, `ui-parallel-route`, `route-to!`

Inspection (lines 709-839):
- `has-routes?`, `leaf-route?`
- `session-statechart-id` (private — BRANCH ONLY)
- `deep-leaf-routes` (private — BRANCH ONLY, walks invocation tree)
- `active-leaf-routes` — branch version uses `deep-leaf-routes`
- `route-denied?`, `force-continue-routing!`, `abandon-route-change!`, `send-to-self!`
- `current-invocation-configuration`
- `reachable-targets` (BRANCH ONLY)

Startup (lines 841-855):
- `start!` — branch version validates configuration, replaces main's `start-routing!`

Bidirectional URL synchronization (lines 857-1194):
- `runtime-atom`, `url-sync-state`, `swap-url-sync!` (private helpers)
- `url-sync-provider`, `url-sync-installed?`
- `find-root-session` (private — walks parent chain)
- `url-sync-on-save` — the on-save hook that pushes URL state
- `resolve-route-and-navigate!` (private — URL→route conversion)
- `install-url-sync!` — comprehensive installer with popstate handling, acceptance/denial state machine, settling behavior
- `route-current-url`, `route-history-index`
- `route-sync-from-url!`
- `route-back!`, `route-forward!`

### Step 4: Revert `ui_routes.cljc` to main's version
- Source: `git show main:src/main/.../ui_routes.cljc` (650 lines)
- Change only the docstring:
```clojure
(ns com.fulcrologic.statecharts.integration.fulcro.ui-routes
  "DEPRECATED. Use com.fulcrologic.statecharts.integration.fulcro.routing.core instead.

   This namespace is maintained for backward compatibility. New projects should use
   the routing.* package which adds URL synchronization, cross-chart routing via
   :route/reachable, deep busy-checking, and route configuration validation.

   ALPHA. This namespace's API is subject to change."
  (:require ...))  ;; keep main's requires exactly
```

### Step 5: Revert `ui_routes_options.cljc` to main's version
- Source: `git show main:src/main/.../ui_routes_options.cljc` (35 lines)
- Remove the `checks` def (lines 36-42 on branch) — it's a fn keyword arg, not a component option
- Result is byte-identical to main

### Step 6: Handle test files

**Revert `ui_routes_test.cljc` to main's version** (109 lines)
- Source: `git show main:src/test/.../ui_routes_test.cljc`
- Tests: `has-routes?`, `leaf-route?`, route entry, busy checking, force route — all exist on main

**Move new test code to `routing/core_test.cljc`**
- The branch added ~743 lines of tests for branch-only functions
- Functions tested: `deep-leaf-routes` (private), `validate-*`, `establish-route-params-node`, `reachable-targets`, `deep-busy?`, `check-component-busy?`, `start!`, `install-url-sync!`, `url-sync-*`, `route-back/forward!`, `route-sync-from-url!`, `route-current-url`, `route-history-index`
- Rename all aliases to new standard: `uir/` → `sroute/`, `ru/` → `ruh/` or `rsh/`, `uc/` → `ruc/`, `uct/` → `ruct/`, `ro/` → `uro/`

**Move existing test files to routing/ (ns rename)**:
- `route_url_test.cljc` → `routing/url_codec_test.cljc`
  - Update requires: `url-codec` → `routing.url-codec`
  - Note: this test uses `uc/element-segment` — lives in url-codec, no history ns needed
- `route_url_history_spec.cljc` → `routing/simulated_history_spec.cljc`
  - Update requires: `route-url` → `routing.simulated-history` (for `simulated-url-history`, `history-stack`, etc.) + `routing.url-history` (for protocol fns if any), `url-codec` → `routing.url-codec`, `url-codec-transit` → `routing.url-codec-transit`
- `url_sync_headless_spec.cljc` → `routing/url_sync_headless_spec.cljc`
  - Update requires: `route-url` → `routing.simulated-history` (uses `simulated-url-history`), `ui-routes` → `routing.core`, `url-codec` → `routing.url-codec`, `url-codec-transit` → `routing.url-codec-transit`

### Step 7: Delete old source files
- `url_codec.cljc` (moved to `routing/url_codec.cljc`)
- `url_codec_transit.cljc` (moved to `routing/url_codec_transit.cljc`)
- `route_url.cljc` (moved to `routing/url_history.cljc`)

### Step 8: Grep for dangling references
- Search all source for old namespace strings: `c.f.s.i.f.url-codec`, `c.f.s.i.f.url-codec-transit`, `c.f.s.i.f.route-url`
- Verify no file requires the old paths
- Check that `routing/core.cljc` does NOT still require the old paths

### Step 9: Update routing-demo2 (4 files)

routing-demo (v1) does NOT reference any of our namespaces — leave it alone.
routing-demo2 uses `ui-routes` throughout and must switch to `routing.core`.

**`src/routing-demo2/.../app.cljs`** (49 lines)
- Replace require: `c.f.s.i.f.ui-routes` → `c.f.s.i.f.routing.core :as sroute`
- Add require: `c.f.s.i.f.routing.browser-history` (for CLJS build to include the impl)
- Rename all `uir/` → `sroute/` in file body

**`src/routing-demo2/.../chart.cljc`** (123 lines)
- Replace require: `c.f.s.i.f.ui-routes` → `c.f.s.i.f.routing.core :as sroute`
- Rename all `uir/` → `sroute/` in file body
- Update docstring references from "ui_routing2" to "routing.core"

**`src/routing-demo2/.../admin_chart.cljc`** (56 lines)
- Replace require: `c.f.s.i.f.ui-routes` → `c.f.s.i.f.routing.core :as sroute`
- Rename all `uir/` → `sroute/` in file body
- Update docstring references from "ui_routing2" to "routing.core"

**`src/routing-demo2/.../ui.cljs`** (314 lines)
- Replace require: `c.f.s.i.f.ui-routes` → `c.f.s.i.f.routing.core :as sroute`
- Replace alias: `ui-routes-options :as ro` → `ui-routes-options :as uro` (avoids RAD `report-options` collision)
- Update all `uir/` → `sroute/` and `ro/` → `uro/` in the file body
- Update file-level docstring from "ui_routes" to "routing.core"

**`src/test/.../routing_demo2/chart_test.cljc`** (230 lines)
- Replace require: `c.f.s.i.f.ui-routes` → `c.f.s.i.f.routing.core :as sroute`
- Rename all `uir/` → `sroute/` (used for `route-to-event-name` only)

### Step 10: Update Guide.adoc routing section (lines 1037-1260)

The routing section is 224 lines and references the old namespace paths throughout.

**Namespace references to update:**
- `com.fulcrologic.statecharts.integration.fulcro.ui-routes` → `c.f.s.i.f.routing.core` (lines 1037, 1049-1052)
- `com.fulcrologic.statecharts.integration.fulcro.route-url` → split reference (line 1177, 1231, 1238)
- All `uir/` prefix references → `sroute/`, all `ro/` → `uro/`

**Specific changes:**

Line 1037-1039: Update opening paragraph ns reference to `routing.core`

Lines 1049-1052: Update example require:
```clojure
;; OLD
[com.fulcrologic.statecharts.integration.fulcro.ui-routes :as uir]
;; NEW
[com.fulcrologic.statecharts.integration.fulcro.routing.core :as sroute]
```
All `uir/` prefixes in Guide examples become `sroute/`, all `ro/` become `uro/`.

Lines 1177-1184: "Key Functions" section — update ns reference from `route-url` to the split:
- `element-segment` → `routing.url-codec`
- `path-from-configuration`, `configuration->url` → `routing.url-codec`
- `find-target-by-leaf-name` → `routing.url-history`
- Note that `install-url-sync!` uses these automatically

Lines 1229-1246: "URL Sync Provider Protocol" section — **major rewrite needed**:
- Update ns reference from `route-url` to the split namespaces
- Explain the 3-way split: protocol in `url-history`, browser impl in `browser-history`, simulated impl in `simulated-history`
- Add headless operation notes (see below)
- Update example code:
```clojure
;; OLD
(require '[com.fulcrologic.statecharts.integration.fulcro.route-url :as route-url])
(let [provider (route-url/simulated-url-history "/initial")]
  (uir/install-url-sync! app {:provider provider}))

;; NEW
(require '[com.fulcrologic.statecharts.integration.fulcro.routing.simulated-history :as rsh])
(let [provider (rsh/simulated-url-history "/initial")]
  (sroute/install-url-sync! app {:provider provider}))
```
- Update inspection helper references: `route-url/history-stack` → `rsh/history-stack`, etc.

**Add new subsection: "Headless Operation"** (after "URL Sync Provider Protocol"):

Document that `SimulatedURLHistory` enables full routing testing without a browser:
- Cross-platform (CLJ + CLJS) — works in kaocha, REPL, CI
- Requires explicit `:provider` — `install-url-sync!` on CLJ throws without one
- Inspection helpers (`history-stack`, `history-cursor`, `history-entries`) enable assertions on navigation state
- The `url_sync_headless_spec.cljc` test file serves as a comprehensive example
- Pattern for headless routing tests:
  1. Create Fulcro app with `fulcro-app`
  2. Install statecharts with `install-fulcro-statecharts!`
  3. Create `simulated-url-history` with initial URL
  4. Call `start!` then `install-url-sync!` with `:provider`
  5. Wire `url-sync-on-save` in the `:on-save` handler
  6. Navigate with `route-to!`, assert with `history-stack`/`history-cursor`
  7. Simulate browser nav with `go-back!`/`go-forward!` on the provider

**Add deprecation note** at the top of the routing section:
```asciidoc
NOTE: The legacy `com.fulcrologic.statecharts.integration.fulcro.ui-routes` namespace is
deprecated. All examples below use the new `routing.core` namespace. The old namespace
remains available for backward compatibility but does not include URL sync, cross-chart
routing, or configuration validation.
```

### Step 11: Update integration/CLAUDE.md
- Replace old file map entries with new routing package:
```
- `fulcro/routing/core.cljc` — Full routing API (DSL, URL sync, validation)
- `fulcro/routing/url_codec.cljc` — URLCodec protocol
- `fulcro/routing/url_codec_transit.cljc` — Default transit+base64 codec
- `fulcro/routing/url_history.cljc` — URLHistoryProvider protocol + helpers
- `fulcro/routing/browser_history.cljc` — Browser history (CLJS)
- `fulcro/routing/simulated_history.cljc` — Simulated history (testing)
```
- Keep `fulcro/ui_routes.cljc` entry with "(deprecated)" note
- Keep `fulcro/ui_routes_options.cljc` entry (shared)
- Keep `fulcro/route_history.cljc` entry (legacy RAD)

### Step 12: Update root CLAUDE.md
- Under "Fulcro Integration" in Architecture Overview, add note about `routing/` package
- Any references to `route-url` or `ui-routes` in the routing context should mention the new package

### Step 13: Grep for dangling references
- Search all source (including demos, docs, CLAUDE.md files) for old namespace strings:
  `c.f.s.i.f.url-codec`, `c.f.s.i.f.url-codec-transit`, `c.f.s.i.f.route-url`
- Verify no file requires the old paths
- Check that `routing/core.cljc` does NOT still require the old paths
- Check Guide.adoc has no remaining old ns references

## Namespace Aliases

Standardized aliases for the new package. All routing namespaces use `r`-prefixed aliases
for visual grouping. The core ns uses `sroute` (statechart-route) to avoid `src`/source confusion.

| New namespace | Alias | Mnemonic | Old alias it replaces |
|---------------|-------|----------|-----------------------|
| `routing.core` | `sroute` | **s**tatechart-**route** | `uir` (ui-routes) |
| `routing.url-codec` | `ruc` | **r**outing **u**rl **c**odec | `url-codec`, `uc` |
| `routing.url-codec-transit` | `ruct` | **r**outing **u**rl **c**odec **t**ransit | `url-codec-transit`, `uct` |
| `routing.url-history` | `ruh` | **r**outing **u**rl **h**istory | `route-url`, `ru` |
| `routing.browser-history` | `rbh` | **r**outing **b**rowser **h**istory | (new) |
| `routing.simulated-history` | `rsh` | **r**outing **s**imulated **h**istory | (new) |
| `ui-routes-options` | `uro` | **u**i-routes **o**ptions | `ro` (conflicts with RAD `report-options`) |

Apply these aliases in:
- `routing/core.cljc` internal requires
- All test files under `routing/`
- routing-demo2 (all 4 files)
- Guide.adoc code examples

## Key Design Decisions

- **URL sync lives in core, not a separate ns**: The URL sync code is tightly coupled to routing internals (`session-id`, `route-to-event-name`, working memory, configuration). It's the "URL layer" of routing, not a standalone concern. One require gives users the full routing API.
- **History implementations in separate nses for CLJS DCE**: CLJS cannot dead-code-eliminate protocols, so `BrowserURLHistory` and `SimulatedURLHistory` must live in their own namespaces. The protocol ns (`url_history`) has only the protocol + pure helper fns (which CAN be DCE'd). Production apps require `browser-history`; tests require `simulated-history`. Neither pulls in the other.
- **`ui_routes_options` stays put and reverts to main**: It's the shared component options contract. Keywords are qualified to the `ui-routes` ns but exported as vars, so both old and new code can use them without conflict. `checks` was incorrectly placed here — it's a fn arg, not a component option.
- **No delegation wrappers**: Old `ui_routes` stays as main's version (working, tested). New features are routing-only. Clean separation.
- **`route_history.cljc` untouched**: The legacy RAD-era route history file stays where it is. Main's `ui_routes` still requires it.
- **Namespace keyword qualification**: `routing.core` will use `::core/...` keywords for its own state (e.g., `::core/failed-route-event`). Component options still use `ui-routes-options` vars which resolve to `::ui-routes/...` keywords — this is fine since they're read from component options maps, not from ns-qualified keywords directly.

## Dependency Graph (new package)

```
url_codec.cljc           ← no routing deps (only statecharts core)
url_codec_transit.cljc   ← url_codec
url_history.cljc         ← url_codec (protocol + helpers only, no impls)
browser_history.cljc     ← url_history (CLJS-only impl)
simulated_history.cljc   ← url_history (cross-platform test impl)
core.cljc                ← url_codec, url_codec_transit, url_history, ui_routes_options
```

Note: `core.cljc` depends on the protocol ns (`url_history`), not the implementations.
Users choose which impl to require based on their environment — `browser-history` in
production CLJS, `simulated-history` in tests. Neither is bundled unless required.

## Verification

1. `clojure -A:dev:test` REPL starts without errors
2. Run all CLJ tests via kaocha — main's `ui_routes_test` (109 lines) passes
3. Run routing package tests — all moved/split tests pass
4. Shadow-cljs compiles all builds without warnings (`shadow_cljs_checker.clj`):
   - `:ci-tests` build
   - `:routing-demo2` build (exercises the updated demo)
5. `grep -r` for dangling namespace references across `src/`, `Guide.adoc`, and all CLAUDE.md files:
   - `c.f.s.i.f.url-codec` (without `.routing.`) → 0 hits
   - `c.f.s.i.f.url-codec-transit` (without `.routing.`) → 0 hits
   - `c.f.s.i.f.route-url` → 0 hits
   - `ui-routes :as uir` in demo/test files → 0 hits (all switched to `routing.core :as sroute`)
6. routing-demo2 runs in browser — navigate, back/forward, busy guard, cross-chart routing all work
7. Guide.adoc renders correctly (`make docs/index.html`) — no broken references
