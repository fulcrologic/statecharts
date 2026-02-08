# Spec: Fix Invocation Error Propagation

**Status**: backlog
**Priority**: P2
**Created**: 2026-02-08
**Owner**: conductor

## Context

In `invocation/statechart.cljc:29-37`, when a statechart is not found during invocation, the function sends an `:error.platform` event and logs an error, but always returns `true` (success). Callers cannot distinguish success from failure.

Additionally, `invocation/future.clj:44` has a potential race condition between `stop-invocation!` calling `future-cancel` and the future's `finally` block cleaning up `active-futures`.

## Requirements

1. Review return value contract for `start-invocation!` across all InvocationProcessor implementations
2. Document whether return value is meaningful
3. If return value should indicate success, fix statechart invocation to return false on failure
4. Review future invocation cleanup for race conditions
5. Add tests for invocation failure scenarios

## Affected Modules

- `src/main/com/fulcrologic/statecharts/invocation/statechart.cljc` - Error return value
- `src/main/com/fulcrologic/statecharts/invocation/future.clj` - Race condition
- `src/main/com/fulcrologic/statecharts/protocols.cljc` - Document contract

## Verification

1. [ ] Return value contract documented in InvocationProcessor protocol
2. [ ] Invocation failure returns correct value
3. [ ] Tests cover missing-chart scenario
4. [ ] Tests cover future cancellation race condition
