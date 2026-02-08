# Spec: Add SCXML Flow Control Elements

**Status**: backlog
**Priority**: P2
**Created**: 2026-02-08
**Owner**: conductor

## Context

The SCXML spec (Section 4.2) defines `<if>`, `<elseif>`, `<else>`, and `<foreach>` as standard executable content. The library explicitly omits these (documented in `elements.cljc:10-13`) because Clojure's lambda execution model makes them unnecessary for native usage.

However, they are needed for:
- XML document import/compatibility
- Conformance with the SCXML Full Profile
- Users migrating from XML-based SCXML tools

The library already has the extension point: `execute-element-content!` multimethod in `v20150901_impl.cljc:258-266`.

## Requirements

1. Implement `if` element constructor in `elements.cljc`
2. Implement `elseif` element constructor
3. Implement `else` element constructor
4. Implement `foreach` element constructor
5. Add multimethod handlers for execution
6. Backward compatible — no changes to existing behavior
7. Should work with existing ExecutionModel (lambda expressions for conditions)

## Affected Modules

- `src/main/com/fulcrologic/statecharts/elements.cljc` - New element constructors
- `src/main/com/fulcrologic/statecharts/algorithms/v20150901_impl.cljc` - Multimethod handlers

## Approach

Leverage existing `execute-element-content!` multimethod dispatch. Each flow control element becomes a new node type that the algorithm can process.

## Verification

. [ ] `if` with single branch works
. [ ] `if`/`else` works
. [ ] `if`/`elseif`/`else` chain works
. [ ] Nested conditional work
. [ ] `foreach` iterates correctly
. [ ] Nested flow control works
. [ ] No regressions in existing tests
. [ ] The `raise` and other executable content can be used within conditionals.
