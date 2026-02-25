# viz-simulator

**Status**: backlog
**Priority**: P1
**Created**: 2026-02-25
**Blocked by**: viz-labels-system

## Context

A browser-only simulator that lets users step through a statechart interactively. It uses the real CLJS processor but with a custom `ExecutionModel` where guard predicates are user-toggleable and other executable content is a noop. Uses its own `simple-env` (ManuallyPolledQueue, WorkingMemoryDataModel, etc.) — no Fulcro integration dependency.

## Requirements

### SimulatorExecutionModel
1. Implements `sp/ExecutionModel` protocol
2. Guards: looks up fn ref in `guard-values-atom`, returns the toggled boolean
3. Unknown guards: default to `true` (permissive, user can flip to false)
4. Scripts/assigns/sends: noop (return `nil`)
5. Uses `::sc/raw-result?` in env to distinguish guard evaluation from script execution

### Simulator env + state
6. Creates local env via `simple/simple-env` pattern (processor, data model, queue, registry, working memory store) with `SimulatorExecutionModel` instead of lambda model
7. `start-simulation! [chart]` — registers chart, starts session, returns initial state (configuration + guard info)
8. `send-event! [sim event-name event-data]` — processes event, returns new configuration
9. `toggle-guard! [sim guard-fn new-value]` — updates guard atom
10. `reset-simulation! [sim]` — restarts from initial configuration

### Guard extraction
11. `extract-guards [chart]` — walks `::sc/elements-by-id`, finds all transitions with `:cond`, returns `{fn-ref {:label str :transition-id keyword :default true}}` using `:diagram/condition` for label

### Available events
12. `available-events [chart configuration]` — returns set of event keywords from transitions on active states

### CLJC where possible
13. Guard extraction and available-events are pure data functions — CLJC
14. SimulatorExecutionModel and env setup may need to be CLJC too (processor is CLJC, simple-env is CLJC)
15. Only the Fulcro app integration (connecting to UI) needs CLJS

## Affected Modules

- `src/main/com/fulcrologic/statecharts/visualization/simulator.cljc` (NEW)

## Approach

The simulator is a self-contained namespace. It creates a local statechart environment using the same `simple-env` pattern but swaps in `SimulatorExecutionModel`. The execution model wraps an atom of `{fn-ref -> boolean}`. When `run-expression!` is called, it checks if the expression fn is in the atom. The algorithm sets `::sc/raw-result?` in env when evaluating conditions, which lets us distinguish guards from scripts.

State is held in a map/record containing the env, guard-values atom, session-id, and chart reference. All processing is synchronous (uses v20150901 sync processor).

## Verification

### Tier 1: Unit tests (fulcro-spec via REPL)
1. [ ] `SimulatorExecutionModel`: toggled guard returns correct boolean
2. [ ] `SimulatorExecutionModel`: unknown guards return `true` (permissive default)
3. [ ] `SimulatorExecutionModel`: scripts return `nil` (noop)
4. [ ] `start-simulation!`: produces valid initial configuration for a simple chart
5. [ ] `send-event!`: transitions to correct state when guard is true
6. [ ] `send-event!`: does NOT transition when guard is toggled to false
7. [ ] `toggle-guard!`: flips value, subsequent `send-event!` respects new value
8. [ ] `extract-guards`: finds all unique guard fns with `:diagram/condition` labels
9. [ ] `available-events`: returns correct event set for a given configuration
10. [ ] `reset-simulation!`: returns to initial configuration

### Tier 2: Integration test with demo chart
11. [ ] Create a chart with 2+ guarded transitions, start simulation, toggle guards, send events, verify full walk-through produces expected configuration sequence
