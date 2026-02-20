# Routing Documentation / Contract Audit Matrix

**Date**: 2026-02-18
**Scope**: Guide.adoc "Hierarchical Routing" section (lines 1035–1186) vs shipped code

## Slice A: Claim-to-Implementation Matrix

| # | Guide Claim (line) | Implementation Status | Notes |
|---|---|---|---|
| 1 | `uir/session-id` is a well-known constant (1041) | **Matches** | `session-id` = `::session` in ui_routes.cljc:30 |
| 2 | `uir/routes` + `uir/rstate` define routing regions (1045) | **Matches** | `routes` and `rstate` are public fns |
| 3 | `uir/routing-regions` wraps routes in parallel state (1056) | **Matches** | Returns `state {:id :state/route-root}` with parallel node |
| 4 | `:route/segment` overrides URL path segment (1059, 1141) | **Matches** | `element-segment` in route_url.cljc:204-210 checks `:route/segment` first |
| 5 | Segments compose hierarchically (1065) | **Matches** | `leaf-route-path-segments` walks up collecting segments; `compute-segment-chain` does same |
| 6 | `uir/istate` invokes child statechart (1067-1073) | **Matches** | `istate` uses `ele/invoke` with `:type :statechart` |
| 7 | `:route/reachable` enables cross-chart transitions (1075) | **Matches** | `routes` generates `cross-transitions` from reachable sets |
| 8 | `uir/start!` takes 2 args: `(start! app statechart)` (1081) | **Matches** | 2-arity and 3-arity (with options map) |
| 9 | No session-id or registration key needed (1084) | **Matches** | Uses `session-id` constant for both machine and session-id |
| 10 | `uir/route-to!` navigates (1091) | **Matches** | Delegates to `scf/send!` with route-to event |
| 11 | `uir/install-url-sync!` after `start!` for URL sync (1096-1101) | **Matches** | Throws if no routing session found |
| 12 | `url-sync-on-save` required in `:on-save` (1104-1111) | **Matches** | Dispatches to registered handlers |
| 13 | Deep busy-checking across invocation tree (1116) | **Matches** | `deep-busy?` recursive fn in ui_routes.cljc:457-481 |
| 14 | `route-denied?` / `force-continue-routing!` / `abandon-route-change!` (1120-1126) | **Matches** | All three are public fns |
| 15 | `send-to-self!` sends events to child chart (1130-1135) | **Matches** | Walks parent chain to find nearest co-located chart, then uses `scf/send!` |
| 16 | Route params via `_p` query parameter (1155-1162) | **Matches** | `encode-params`/`decode-params` use transit+base64 |
| 17 | `:route/params` extracts keys into data model (1165-1173) | **Matches** | `establish-route-params-node` assigns to `[:routing/parameters id]` |
| 18 | `element-segment` returns segment or `(name target)` (1179) | **Matches** | route_url.cljc:204-210 |
| 19 | `path-from-configuration` builds URL path (1180) | **Matches** | route_url.cljc:254-263 |
| 20 | `configuration->url` combines path + params (1181) | **Matches** | route_url.cljc:287-295 |
| 21 | `url->route-target` parses URL (1182) | **Matches** | route_url.cljc:352-377 |
| 22 | `find-target-by-leaf-name` finds element by segment (1183) | **Matches** | route_url.cljc:379-388 |

## Slice A: Missing from Guide (shipped but undocumented)

| # | Feature | Location | Priority |
|---|---|---|---|
| M1 | `:routing/checks` validation option (`:warn`/`:strict`) | `start!` and `install-url-sync!` 3rd-arg options | **High** — users should know about validation |
| M2 | `active-leaf-routes` — walks invocation tree for true deep leaves | ui_routes.cljc:758-769 | **Medium** — useful for UI rendering decisions |
| M3 | `URLHistoryProvider` protocol — headless URL sync | route_url.cljc:41-49 | **Medium** — needed for SSR/testing |
| M4 | `SimulatedURLHistory` — cross-platform testing provider | route_url.cljc:97-151 | **Medium** — needed for test setup |
| M5 | `validate-route-configuration` — standalone validation | ui_routes.cljc:207-218 | **Low** — mainly internal |
| M6 | `reachable-targets` — utility for generating `:route/reachable` | ui_routes.cljc:808-822 | **Medium** — useful helper |
| M7 | `deep-configuration->url` — full invocation-tree URL building | route_url.cljc:297-337 | **Low** — internal to install-url-sync! |
| M8 | `current-invocation-configuration` — query child chart state | ui_routes.cljc:799-806 | **Medium** |
| M9 | `:route/segment` on `istate` | istate docstring, line 379 | **High** — already on rstate but istate equally supports it |
| M10 | `ro/initialize` — `:once`/`:always`/`:never` initialization modes | ui_routes_options.cljc:4-7 | **High** — critical for forms |

## Slice A: Deprecated/Removed (cleaned up)

| Item | Status |
|---|---|
| `:route/path` | Fully removed from source. Not in Guide. Clean. |
| 3-arg `start!` with statechart-id | Removed. Guide correctly shows 2-arg form. |

## Slice A: Verdict

**All 22 Guide claims match shipped behavior.** No false claims found.
The primary gap is undocumented features (M1-M10), not incorrect documentation.

---

## Slice C: Release-Gate Verification Checklist

Before releasing routing changes, verify:

- [ ] Every Guide routing claim has a matching public function with compatible signature
- [ ] `:route/segment` override works on both `rstate` and `istate`
- [ ] `:routing/checks` :warn logs and :strict throws for duplicate leaf names, segment chain collisions, reachable collisions, and invalid routing roots
- [ ] `start!` signature is `(start! app statechart)` or `(start! app statechart opts)`
- [ ] `install-url-sync!` throws if called before `start!`
- [ ] `url-sync-on-save` dispatches for both root and child sessions
- [ ] `active-leaf-routes` returns true deep leaves across invocation boundaries
- [ ] URL sync: programmatic navigation pushes URL, browser back/forward restores route or reverts on denial
- [ ] `:route/params` are encoded in `_p` query param and decoded on URL restoration
- [ ] `deep-configuration->url` produces correct hierarchical paths across chart boundaries
- [ ] `URLHistoryProvider` protocol is satisfied by both `BrowserURLHistory` and `SimulatedURLHistory`
