# Project Tracker

<!-- Last updated: 2026-11-13 | Active: 0 | Blocked: 0 | Backlog: 0 | Done: 19 -->

Routing specs (the `routing.*` package added in 1.4.0-RC1) moved to the `com.fulcrologic/fulcro-statechart-routing` project (https://github.com/fulcrologic/fulcro-statechart-routing), under `docs/ai/specs`.

## Active

(none)

## Blocked

(none)

## Backlog

(none)

## Done (recent)

| Spec                                 | Priority | Completed  | Summary                                                                                                                                     |
|--------------------------------------|----------|------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| viz-app-shell                        | P1       | 2026-02-25 | App shell: mode selector, event panel, guard panel, simulator integration in viz demo app                                                   |
| viz-simulator                        | P1       | 2026-02-25 | SimulatorExecutionModel with toggleable guards, noop scripts, extract-guards, available-events, full simulator lifecycle                    |
| viz-visualizer-enhancements          | P1       | 2026-02-25 | Visualizer uses chart/transition-label for UML labels, entry/exit activity compartments in state boxes                                      |
| viz-labels-system                    | P1       | 2026-02-25 | diagram-label, transition-label, state-entry-labels, state-exit-labels in chart.cljc                                                        |
| viz-docstring-updates                | P2       | 2026-02-25 | Docstrings for :diagram/label + :diagram/condition; handle/assign-on macros emit :diagram/label                                             |
| send-to-self-parent-chain            | P1       | 2026-02-19 | `send-to-self!` and `current-invocation-configuration` walk Fulcro parent chain; works from child components of route targets               |
| async-plet-refactor                  | P2       | 2026-02-12 | Replace nested maybe-then chains with p/let in async impl                                                                                   |
| routing-demo-update                  | P1       | 2026-02-10 | Fixed raise! API, CLJS promise handling, added build config                                                                                 |
| convenience-api-tests                | P2       | 2026-02-09 | 15 specs / 83 assertions for convenience helpers                                                                                            |
| scxml-flow-control-elements          | P2       | 2026-02-09 | if/elseif/else/foreach already implemented, tests added                                                                                     |
| invocation-error-propagation         | P2       | 2026-02-09 | Fixed return values + race condition, 59 assertions                                                                                         |
| documentation-improvements           | P2       | 2026-02-09 | Fixed 2 typos in AI docs and Guide.adoc                                                                                                     |
| event-loop-tests                     | P2       | 2026-02-09 | 18 assertions covering core.async event loop                                                                                                |
| deep-history-validation              | P2       | 2026-02-09 | Deep history validation in chart.cljc                                                                                                       |
| async-execution-engine               | P1       | 2026-02-08 | Promesa-based async algorithm, 33 tests / 99 assertions                                                                                     |
| history-validation-bug               | P0       | 2026-02-08 | Fixed 3 inverted conditions + missing element lookup                                                                                        |
| scxml-system-variables               | P1       | 2026-02-08 | _sessionid, _name in data model + In() predicate                                                                                            |
| invocation-tests                     | P1       | 2026-02-08 | 104 characterization tests (49 passing, documents known bugs)                                                                               |
| fulcro-integration-tests             | P1       | 2026-02-08 | 93 assertions (60 API tests passing, operations tests structural)                                                                           |
