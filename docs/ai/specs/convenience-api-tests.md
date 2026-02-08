# Spec: Add Convenience API Tests

**Status**: backlog
**Priority**: P2
**Created**: 2026-02-08
**Owner**: conductor

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

1. [ ] `on` produces correct transition element
2. [ ] `choice` dispatches to correct target based on predicates
3. [ ] `choice` `:else` clause works as default
4. [ ] `send-after` on-entry sends delayed event
5. [ ] `send-after` on-exit cancels the delayed event
6. [ ] `handle` creates transition with script handler
7. [ ] `assign-on` creates transition with assignment
