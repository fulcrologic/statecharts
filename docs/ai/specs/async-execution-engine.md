# Spec: Async Execution Engine (Promesa)

**Status**: done
**Completed**: 2026-02-08
**Priority**: P1
**Created**: 2026-02-08
**Owner**: conductor

## Context

Application routing with statecharts requires async operations during state transitions. When a URL change triggers a route, nested routes may need to initialize parent states (load data, authenticate). These operations are async and may fail/reject. JavaScript is single-threaded — you cannot block.

The current W3C algorithm (`v20150901_impl.cljc`) is fully synchronous: `process-event!` runs `microstep!` in a tight loop with volatiles. Expressions execute synchronously via `run-expression!`. This makes async route initialization impossible within the statechart algorithm itself.

The solution is a parallel async implementation of the W3C algorithm using promesa. Expressions can return promises, and the algorithm parks when it encounters one. Existing sync expressions work unchanged with near-zero overhead — the algorithm checks return values and only parks when a promise is actually returned.

## Requirements

1. Full parallel implementation of `v20150901_impl.cljc` that supports async expressions via promesa
2. Existing synchronous implementation must NOT be broken — the async variant lives alongside it
3. Cross-platform (CLJ/CLJS) via .cljc files
4. Promesa is an optional dependency — only needed when async namespaces are required
5. All existing `(fn [env data] ...)` expressions must work unchanged (backwards compatible)
6. The async algorithm must be behaviorally identical to the sync algorithm for sync expressions
7. Async becomes the default for Fulcro integration (but sync expressions still work)
8. Event loop must serialize events per session — no concurrent processing of a single session

## Affected Modules

### New Files (created in worktree `priceless-babbage`)
- `src/main/com/fulcrologic/statecharts/algorithms/v20150901_async_impl.cljc` — Core async algorithm (~900 lines)
- `src/main/com/fulcrologic/statecharts/algorithms/v20150901_async.cljc` — Public API (AsyncProcessor)
- `src/main/com/fulcrologic/statecharts/execution_model/lambda_async.cljc` — Promise-aware execution model
- `src/main/com/fulcrologic/statecharts/event_queue/async_event_loop.cljc` — core.async + promise bridge
- `src/main/com/fulcrologic/statecharts/event_queue/async_event_processing.cljc` — Async event handler
- `src/main/com/fulcrologic/statecharts/simple_async.cljc` — Convenience env setup
- `src/main/com/fulcrologic/statecharts/testing_async.cljc` — Async test utilities

### Modified Files
- `deps.edn` — Added `:async` alias with promesa dependency
- `src/main/com/fulcrologic/statecharts/integration/fulcro.cljc` — (pending) Default to async processor
- `src/main/com/fulcrologic/statecharts/integration/fulcro_impl.cljc` — (pending) Handle promise results

### Test Files
- `src/test/com/fulcrologic/statecharts/algorithms/v20150901_async/regression_spec.cljc` — Sync expressions on async processor
- `src/test/com/fulcrologic/statecharts/algorithms/v20150901_async/async_spec.cljc` — Promise-returning expressions

## Approach

### Core Pattern: `maybe-then`

The key abstraction that enables "sometimes-async" behavior:

```clojure
(defn- maybe-then [v f]
  (if (p/promise? v)
    (p/then v f)
    (f v)))
```

Every place the sync algorithm calls `run-expression!`, the async algorithm wraps the result with `maybe-then`. If the result is a plain value (the common case), `f` is called directly — no promise overhead. If it's a promise, the chain becomes async from that point.

### Slice 1: Core algorithm + execution model (DONE)

Copied `v20150901_impl.cljc` and converted all control flow:
- `while` loops → recursive functions with `maybe-then`
- `doseq` → `do-sequence` helper (sequential async iteration)
- Triply-nested `select-transitions*` → decomposed into 3 helper functions
- `condition-match` → returns boolean or promise-of-boolean
- `process-event!` / `initialize!` → return working memory or promise-of-working-memory

### Slice 2: Infrastructure (DONE)

- `lambda_async.cljc` — Execution model that chains data model ops via `p/then` when result is a promise
- `async_event_loop.cljc` — go-loop that bridges promises into channels
- `async_event_processing.cljc` — Event handler that awaits async `process-event!`
- `simple_async.cljc` — Wires async processor + execution model
- `testing_async.cljc` — Test utilities that deref promises on CLJ

### Slice 3: Tests (DONE)

- Regression tests: 24 tests, 71 assertions — async processor with sync expressions produces identical results
- Async-specific tests: 9 tests, 28 assertions — promise expressions, rejection handling, async conditions, mixed sync/async
- Fixed: `AsyncMockExecutionModel` handles promise results from expressions via `p/then`
- Fixed: Final state regression test — top-level final states clear configuration per W3C spec

### Slice 4: Fulcro integration (DEFERRED)

- `install-fulcro-statecharts!` defaults to async processor
- Event handler bridges promises
- Existing user code works unchanged
- Deferred to separate spec — requires changing Fulcro integration code

## Verification

1. [x] All existing sync tests pass (no regression to sync processor) — 89 tests, 394 assertions, 0 new failures
2. [x] Regression tests pass: async processor with sync expressions produces identical results — 24 tests, 71 assertions
3. [x] Async-specific tests pass: promise expressions resolve correctly — 9 tests, 28 assertions
4. [x] Promise rejection sends `error.execution` event — tested in async_spec
5. [x] Async conditions select correct transitions — tested in async_spec
6. [x] Sequential async entry/exit handlers maintain order — tested in async_spec
7. [ ] CLJS compilation succeeds — deferred (shadow-cljs config needs `:async` alias)
8. [ ] Fulcro integration defaults to async — deferred to separate spec
9. [x] Event loop serializes events per session — architecture enforces via core.async go-loop

## Progress Log

- 2026-02-08: Created all core files (Slices 1-2). All namespaces compile on CLJ. Smoke test of basic0/basic1/basic2 passes with async processor + sync expressions.
- 2026-02-08: Created regression and async-specific test files. Regression suite: 24 tests, 71 assertions. One failure in final state handling (investigating).
- 2026-02-08: Fixed `AsyncMockExecutionModel` — the sync mock didn't handle promise results from expressions. Created async-aware mock that chains data model updates via `p/then`. Fixed async test specs that incorrectly passed `{:run-unmocked? true}` as `mocks` instead of `mocking-options`. Fixed final state regression test — W3C spec clears configuration on interpreter exit. All tests green (33 async tests, 99 assertions).
