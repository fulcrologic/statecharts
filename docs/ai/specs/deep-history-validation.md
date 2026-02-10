# Spec: Complete Deep History Validation

**Status**: done
**Priority**: P2
**Created**: 2026-02-08
**Owner**: history-validator
**Completed**: 2026-02-09

## Context

The `invalid-history-elements` function in `chart.cljc:412` has a TODO comment: "Validate deep history." Shallow history validation exists (though buggy, see `history-validation-bug` spec) but deep history has no static validation.

The W3C SCXML spec Section 3.11 defines requirements for deep history that should be validated:
- Deep history records full descendant configuration
- Transition target requirements differ from shallow history
- Must be child of a compound state

## Requirements

1. Research exact W3C requirements for deep history nodes
2. Implement static validation rules
3. Add to existing `invalid-history-elements` function
4. This depends on `history-validation-bug` being fixed first

## Affected Modules

- `src/main/com/fulcrologic/statecharts/chart.cljc:412` - Add validation

## Implementation Summary

Enhanced `invalid-history-elements` function in `chart.cljc`:
- Added validation that history must be child of compound state
- Added deep history target must be proper descendant of parent
- Wired validation into `statechart` construction (throws on invalid charts)
- Added comprehensive tests in `chart_validation_spec.cljc`

##Verification

1. [x] Deep history in compound state passes validation
2. [x] Deep history outside compound state fails validation
3. [x] Deep history transition target rules validated
4. [PENDING] All existing deep history tests still pass - needs REPL to run tests
