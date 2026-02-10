# Spec: Add Convenience API Tests

**Status**: done
**Priority**: P2
**Created**: 2026-02-08
**Completed**: 2026-02-09
**Owner**: convenience-api-tester

## Context

The convenience namespace (`convenience.cljc`, `convenience_macros.cljc`) has zero test coverage despite being used in documentation examples. The `send-after` helper implements a non-trivial entry/exit pair with cancel logic that could harbor subtle bugs.

The namespace is marked ALPHA but appears widely used.

## Requirements

1. Test `handle` function and macro
2. Test `assign-on` function and macro
3. Test `on` shorthand
4. Test `choice` function and macro — verify correct predicate dispatch
5. Test `send-after` — verify entry sends delayed event, exit cancels it
6. Verify macro expansion produces correct element structures

## Affected Modules

- `src/test/com/fulcrologic/statecharts/` - New test file
- `src/main/com/fulcrologic/statecharts/convenience.cljc` - Under test
- `src/main/com/fulcrologic/statecharts/convenience_macros.cljc` - Under test

## Verification

1. [x] `on` produces correct transition element
2. [x] `choice` dispatches to correct target based on predicates
3. [x] `choice` `:else` clause works as default
4. [x] `send-after` on-entry sends delayed event
5. [x] `send-after` on-exit cancels the delayed event
6. [x] `handle` creates transition with script handler
7. [x] `assign-on` creates transition with assignment

## Implementation Notes

Test file created at `src/test/com/fulcrologic/statecharts/convenience_spec.cljc` with:
- **15 specifications** with **83 assertions**
- Both structural tests (verify element structure) and behavioral tests (verify runtime execution)
- 100% pass rate
