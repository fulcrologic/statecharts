# Spec: Fix History Element Validation Bug

**Status**: done
**Priority**: P0
**Created**: 2026-02-08
**Completed**: 2026-02-08
**Owner**: AI

## Context

The `invalid-history-elements` function in `chart.cljc:398-415` has multiple inverted conditions in a `cond->` chain. It reports errors for VALID history configurations and silently accepts INVALID ones.

NOTE: I can swear there are charts using history in circulation. Perhaps they are not running validation, but be very very sure we're right here.

## Requirements

1. Fix the inverted condition on line 409: `(= 1 (count transitions))` should be `(not= 1 (count transitions))`
2. Fix the inverted condition on line 410: `(and (nil? event) (nil? cond))` should be `(or (some? event) (some? cond))`
3. Review line 413 condition: `(or deep? (= 1 (count immediate-children)))` — appears to flag deep history as always problematic
4. Complete the TODO on line 412: "Validate deep history"
5. Add regression tests for all validation conditions
6. Verify no existing charts in the codebase rely on the broken behavior

## Affected Modules

- `src/main/com/fulcrologic/statecharts/chart.cljc:398-415` - Fix validation logic
- `src/test/` - Add validation tests

## Approach

1. Write failing tests first that demonstrate the incorrect validation
2. Fix the conditions
3. Run full test suite to check for regressions
4. Review any callers of `invalid-history-elements`

## Verification

1. [ ] Test: valid history node (1 transition, no event/cond) passes validation
2. [ ] Test: history node with 0 transitions fails validation
3. [ ] Test: history node with 2+ transitions fails validation
4. [ ] Test: history node with event attribute fails validation
5. [ ] Test: history node with cond attribute fails validation
6. [ ] Test: shallow history with multiple targets fails validation
7. [ ] Test: deep history validation implemented
8. [ ] Full test suite passes
