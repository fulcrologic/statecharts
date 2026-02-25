# Project Tracker

<!-- Last updated: 2026-02-25 | Active: 0 | Blocked: 0 | Backlog: 0 | Done: 46 -->

## Active

(none)

## Blocked

(none)

## Backlog

(none)

## Done (recent)

| Spec                                 | Priority | Completed  | Summary                                                                                                                                   |
|--------------------------------------|----------|------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| viz-app-shell                        | P1       | 2026-02-25 | App shell: mode selector, event panel, guard panel, simulator integration in viz demo app                                                 |
| viz-simulator                        | P1       | 2026-02-25 | SimulatorExecutionModel with toggleable guards, noop scripts, extract-guards, available-events, full simulator lifecycle                   |
| viz-visualizer-enhancements          | P1       | 2026-02-25 | Visualizer uses chart/transition-label for UML labels, entry/exit activity compartments in state boxes                                     |
| viz-labels-system                    | P1       | 2026-02-25 | diagram-label, transition-label, state-entry-labels, state-exit-labels in chart.cljc                                                      |
| viz-docstring-updates                | P2       | 2026-02-25 | Docstrings for :diagram/label + :diagram/condition; handle/assign-on macros emit :diagram/label                                           |
| routing-documentation                | P1       | 2026-02-25 | Guide.adoc routing section already comprehensive (~1000 lines covering all 5 areas)                                                       |
| nav-state-safety-timeout             | P3       | 2026-02-25 | 5s safety timeout clears stuck nav-state if process-event! fails during browser nav                                                       |
| send-to-self-parent-chain            | P1       | 2026-02-19 | `send-to-self!` and `current-invocation-configuration` walk Fulcro parent chain; works from child components of route targets              |
| routing-options-rename               | P2       | 2026-02-19 | Rename `routing.core` → `routing`; create `routing-options` ns (RAD pattern); `uro/` → `sfro/`                                             |
| routing-package-reorg                | P2       | 2026-02-19 | Moved new routing code to `routing.*` package; reverted `ui_routes` to main + deprecation; 6 new files, all tests pass                     |
| extract-url-codec-namespaces         | P2       | 2026-02-19 | Split route_url into url_codec + url_codec_transit + route_url (history only); 190 assertions pass                                        |
| pluggable-url-codec                  | P2       | 2026-02-19 | URLCodec protocol + TransitBase64Codec default; pluggable via install-url-sync!; R5 collision detection; 167 assertions                    |
| forward-button-after-back-istate     | P0       | 2026-02-19 | Fixed: child chart init after browser-back acceptance called pushState destroying forward history; settling? atom uses replaceState instead |
| denied-route-timing-redesign         | P0       | 2026-02-19 | Implemented: Root-save gate + outstanding nav counter replaces 300ms timer; saving-session-id 3rd arg on url-sync-on-save                 |
| cljs-test-fixes                      | P0       | 2026-02-19 | Fixed transit encode/decode (btoa→encodeURIComponent), mock remote in ops spec, CLJ-only reader conditionals for async back/forward tests |
| eliminate-statechart-id-param        | P1       | 2026-02-18 | Remove redundant `statechart-id` from `start!`; 2-arity form; 5 files updated                                                             |
| deep-active-leaf-routes              | P2       | 2026-02-18 | `active-leaf-routes` walks invocation tree for true leaves across istate boundaries + parallel regions                                    |
| ui-routes-safety-checks-and-warnings | P1       | 2026-02-18 | `:routing/checks` `:warn`/`:strict` mode; duplicate leaf, reachable collision, root ident validators; `replace-join!` safety              |
| route-segment-override               | P1       | 2026-02-18 | `:route/segment` (simple string) override; URL gen/restore; duplicate segment validation                                                  |
| ui-routes-test-coverage              | P1       | 2026-02-18 | Slices 1-5 + 8: ~80 new assertions covering utilities, initialization, busy checking, denial, cross-chart routing, URL sync delegation    |
| ui-routes-doc-contract-alignment     | P1       | 2026-02-18 | Guide routing section updated; audit matrix; 5 new subsections; routing invariants documented                                             |
| ui-routing2-simplify-session-id      | P1       | 2026-02-18 | Remove session-id from public API; well-known constant; routing-demo2; Guide.adoc update                                                  |
| install-url-sync-headless            | P1       | 2026-02-18 | Headless install-url-sync! with provider + index-based nav-state                                                                          |
| url-history-protocol                 | P1       | 2026-02-18 | URLHistoryProvider protocol + BrowserURLHistory + SimulatedURLHistory                                                                     |
| url-parsing-cross-platform           | P1       | 2026-02-18 | CLJ url->route-target via java.net.URI                                                                                                    |
| url-sync-runtime-state               | P1       | 2026-02-18 | Move module-level atoms to Fulcro runtime atom                                                                                            |
| async-url-restoration                | P1       | 2026-02-18 | URL restoration on page load via install-url-sync!                                                                                        |
| url-state-sync                       | P2       | 2026-02-18 | Bidirectional URL sync via on-save hook + popstate                                                                                        |
| browser-navigation-async             | P2       | 2026-02-18 | Popstate with debounce, denial detection, guard handling                                                                                  |
| remove-route-guard                   | P1       | 2026-02-18 | Removed route-guard/denial-reason, busy? is sole guard                                                                                    |
| deep-route-guard                     | P1       | 2026-02-18 | Deep recursive busy? through invocation tree, :routing/guarded? option                                                                    |
| url-path-design                      | P1       | 2026-02-18 | Auto-derived URL paths, single _p param, on-save hook design                                                                              |
| async-plet-refactor                  | P2       | 2026-02-12 | Replace nested maybe-then chains with p/let in async impl                                                                                 |
| routing-demo-update                  | P1       | 2026-02-10 | Fixed raise! API, CLJS promise handling, added build config                                                                               |
| convenience-api-tests                | P2       | 2026-02-09 | 15 specs / 83 assertions for convenience helpers                                                                                          |
| scxml-flow-control-elements          | P2       | 2026-02-09 | if/elseif/else/foreach already implemented, tests added                                                                                   |
| invocation-error-propagation         | P2       | 2026-02-09 | Fixed return values + race condition, 59 assertions                                                                                       |
| documentation-improvements           | P2       | 2026-02-09 | Fixed 2 typos in AI docs and Guide.adoc                                                                                                   |
| event-loop-tests                     | P2       | 2026-02-09 | 18 assertions covering core.async event loop                                                                                              |
| deep-history-validation              | P2       | 2026-02-09 | Deep history validation in chart.cljc                                                                                                     |
| async-execution-engine               | P1       | 2026-02-08 | Promesa-based async algorithm, 33 tests / 99 assertions                                                                                   |
| history-validation-bug               | P0       | 2026-02-08 | Fixed 3 inverted conditions + missing element lookup                                                                                      |
| scxml-system-variables               | P1       | 2026-02-08 | _sessionid, _name in data model + In() predicate                                                                                          |
| invocation-tests                     | P1       | 2026-02-08 | 104 characterization tests (49 passing, documents known bugs)                                                                             |
| fulcro-integration-tests             | P1       | 2026-02-08 | 93 assertions (60 API tests passing, operations tests structural)                                                                         |
