# Spec: Add Invocation Processor Tests

**Status**: backlog
**Priority**: P1
**Created**: 2026-02-08
**Owner**: conductor

## Context

The invocation system has **zero test coverage** despite being a core SCXML feature. Both `invocation/statechart.cljc` and `invocation/future.clj` are completely untested. This is the most critical testing gap in the library.

Statechart invocations, in particular, are HEAVILY used by users. The changes here MUST NOT break backward compatibility. Get input about how to address any issue you can't prove is going to be backward compatible.

## Requirements

1. Test statechart invocation lifecycle (start, events, finalize, done.invoke, stop)
2. Test parent-child event communication
3. Test error event generation when chart not found
4. Test autoforward behavior
5. Test invocation data passing (namelist, params)
6. Test future invocation lifecycle (CLJ only)
7. Test future completion and error handling
8. Test future cancellation

## Affected Modules

- `src/test/com/fulcrologic/statecharts/invocation/` - New test files
- `src/main/com/fulcrologic/statecharts/invocation/statechart.cljc` - Under test
- `src/main/com/fulcrologic/statecharts/invocation/future.clj` - Under test

## Approach

### Slice 1: Statechart Invocation
Test the full lifecycle using `new-testing-env` with a parent chart that invokes a child chart.

### Slice 2: Error Scenarios
Test missing charts, invalid types, invocation processor not found.

### Slice 3: Future Invocation (CLJ only)
Test future completion, error handling, cancellation. May need CLJ-only test file.

## Verification

1. [ ] Invocation start creates child session
2. [ ] Child done.invoke event reaches parent
3. [ ] Finalize executes on child events
4. [ ] Autoforward delivers parent events to child
5. [ ] Missing chart generates error.platform
6. [ ] Future completion sends done event
7. [ ] Future error sends error event
8. [ ] State exit cancels active invocations
