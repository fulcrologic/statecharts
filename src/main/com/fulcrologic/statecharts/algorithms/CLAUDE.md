# Algorithms — CLAUDE.md

W3C SCXML 2015-09-01 algorithm implementation. **Correctness is paramount — deviations from the spec are bugs.**

## Key Files
- `v20150901.cljc` — Public API: `initialize!`, `process-event!`, `exit-interpreter!`
- `v20150901_impl.cljc` — Internal algorithm (imperative style with volatiles, matching W3C pseudocode)
- `v20150901_validation.cljc` — Configuration validation

## Architecture Pattern
Uses **volatiles internally** for direct comparison with W3C pseudocode appendix D. External interface is functional (returns new working memory).

## Critical Functions
- `microstep!` — exit states → execute transitions → enter states
- `select-transitions!` / `select-eventless-transitions!` — Transition selection with conflict resolution
- `enter-states!` / `exit-states!` — Entry/exit with history recording, invocation lifecycle
- `compute-entry-set!` — History resolution, parallel child activation, initial transitions

## Known Issues
- `::sc/raw-result?` flag needed when expression results should not be treated as operations (see commit ffe30fb)
- Catches `Throwable` (CLJ) / `:default` (CLJS) broadly — may hide serious JVM errors
- TODOs at lines 349 (proper error events) and 368 (states-for-default-entry lifetime)

## Testing
Algorithm tests are the strongest in the codebase (70-80% coverage). Test via `new-testing-env` from `testing.cljc`.

## Gotcha
`execute-element-content!` is a multimethod — extensible for new element types. When adding types, register via `defmethod` on `::sc/element-type`.
