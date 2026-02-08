# Spec: Complete Deep History Validation

**Status**: backlog
**Priority**: P2
**Created**: 2026-02-08
**Owner**: conductor

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

## Verification

1. [ ] Deep history in compound state passes validation
2. [ ] Deep history outside compound state fails validation
3. [ ] Deep history transition target rules validated
4. [ ] All existing deep history tests still pass
