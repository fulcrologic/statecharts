# Spec: Routing Demo Update

**Status**: done
**Completed**: 2026-02-10
**Priority**: P1
**Created**: 2026-02-09
**Owner**: AI

## Context

The routing demo in `src/routing-demo/` was written before the recent async execution engine refactoring. It uses a custom `raise!` implementation that bypasses guardrails and has CLJS test compatibility issues. The demo should be updated to use proper APIs and showcase best practices.

## Requirements

1. **Remove custom raise! workaround** (model.cljc:15-22)
   - Replace direct `::sc/internal-queue` manipulation with proper `senv/raise!` call
   - If guardrails issue persists, document the proper fix

2. **Fix CLJS test promise handling** (chart_test.cljc:56-59)
   - Current `#?(:clj @v :cljs v)` doesn't actually await promises in CLJS
   - Use promesa's `deref` or proper async test patterns

3. **Add shadow-cljs build configuration**
   - Add `:routing-demo` build target to shadow-cljs.edn
   - Verify the demo compiles without errors

4. **Verify demo works end-to-end**
   - Run CLJ tests: `(run-tests 'com.fulcrologic.statecharts.routing-demo.chart-test)`
   - Verify CLJS compilation succeeds
   - Document any remaining issues

5. **Optional improvements** (if time permits)
   - Replace manual URL parsing with route_history.cljc/route_url.cljc
   - Use actors/aliases pattern for data access
   - Replace `on-save` trigger hack with proper working memory query

## Affected Modules

- `src/routing-demo/com/fulcrologic/statecharts/routing_demo/model.cljc` - Custom raise! removal
- `src/routing-demo/com/fulcrologic/statecharts/routing_demo/chart_test.cljc` - Promise handling fix
- `shadow-cljs.edn` - Add build configuration

## Approach

1. First verify the current state of the demo and identify all issues
2. Fix the custom raise! implementation to use proper APIs
3. Fix the CLJS test promise handling  
4. Add shadow-cljs build and verify compilation
5. Run tests to verify everything works

## Verification

1. [x] `model.cljc` uses `senv/raise` - proper API (no direct queue manipulation)
2. [x] `chart_test.cljc` handles promises correctly with `p/extract` for CLJS and `@` for CLJ
3. [x] shadow-cljs.edn has `:routing-demo` build target
4. [x] CLJ tests pass: 5 tests, 20 assertions, 0 failures
5. [x] CLJS compilation succeeds (330 files, 25 compiled, only third-party library warnings)
6. [x] No guardrails violations at runtime

## Changes Made

### 1. Fixed `environment.cljc` spec for `raise` 2-arity
The 2-arity version of `raise` was rejecting partial event maps (missing `:type`). Fixed spec to accept:
- A keyword event name, OR
- A partial event map with `:name` key (since `evts/new-event` fills in `:type`)

### 2. Removed custom `raise!` from `model.cljc`
- Removed direct `::sc/internal-queue` volatile manipulation
- Now uses proper `senv/raise` API

### 3. Fixed CLJS promise handling in `chart_test.cljc`
- Uses `p/extract` for CLJS (extracts value from already-resolved promises)
- Added `maybe-delay` helper that returns `(p/resolved nil)` when delay is 0

### 4. Added shadow-cljs build configuration
- Added `:routing-demo` target to `shadow-cljs.edn`
- Added `src/routing-demo` to `:dev` alias extra-paths in `deps.edn`
- Added `:async` alias to shadow-cljs deps for promesa

## Progress Log

- 2026-02-09: Spec created from codebase analysis
- 2026-02-10: All requirements completed and verified
