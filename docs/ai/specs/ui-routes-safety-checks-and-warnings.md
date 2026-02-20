# Spec: UI Routes Safety Checks and Warnings

**Status**: backlog
**Priority**: P1
**Created**: 2026-02-18
**Owner**: conductor

## Context

`ui_routes.cljc` currently logs some misconfigurations, but several high-risk cases still fail late or silently:

- Parent query rewrites can proceed even when parent ident is missing (`replace-join!`), potentially writing malformed state updates.
- Routing root ident requirements are checked ad hoc and mostly via logs.
- URL matching can be ambiguous when two route targets share the same leaf name.
- `:route/reachable` declarations can collide with direct route targets without explicit warnings.

The goal is to add deterministic checks/warnings and a clear strict-vs-lenient behavior policy.

## Requirements

1. Add explicit runtime validation for route configuration before/at startup:
   - missing/invalid routing root ident assumptions
   - duplicate route target leaf names (URL ambiguity)
   - direct-target vs `:route/reachable` collisions
2. Harden `replace-join!` and caller flow so missing parent ident does not proceed as if successful.
3. Add clear warning payloads with enough context (`route id`, `target`, `owner`, `session-id` when available).
4. Introduce a strict mode option (throw on violations) and default warning mode (log + safe fallback).
5. Ensure checks are covered in tests (behavioral and message-level where practical).

## Affected Modules

- `src/main/com/fulcrologic/statecharts/integration/fulcro/ui_routes.cljc` - validation and guarded behavior
- `src/main/com/fulcrologic/statecharts/integration/fulcro/route_url.cljc` - duplicate-leaf detection hooks
- `src/main/com/fulcrologic/statecharts/integration/fulcro/ui_routes_options.cljc` - optional strict/checks option key(s)
- `src/test/com/fulcrologic/statecharts/integration/fulcro/ui_routes_test.cljc` - new coverage
- `src/test/com/fulcrologic/statecharts/integration/fulcro/url_sync_headless_spec.cljc` - ambiguity/collision scenarios

## Approach

### Slice A: Validation Surface (parallelizable)

- Add pure validation helpers for:
  - duplicate leaf route names
  - reachable/direct collisions
  - invalid/missing root ident invariants
- Wire them into `start!` and `install-url-sync!` paths where route tables are already consulted.

### Slice B: Runtime Safety in Query Rewrites (parallelizable)

- Change parent-query update path so a missing parent ident becomes a no-op with explicit warning in lenient mode.
- In strict mode, throw structured ex-info instead of logging.

### Slice C: Strict Mode and Warnings Policy (parallelizable)

- Add `:routing/checks` policy option (`:warn` default, `:strict` throws).
- Define consistent warning keys for downstream log analysis.

### Slice D: Tests (depends on A/B/C)

- Add focused tests for each validation failure category.
- Add one integration path proving warnings do not crash in default mode.
- Add one strict-mode path proving deterministic throw behavior.

## Parallelization Plan

1. Agent 1: Slice A (pure validators + startup hooks)
2. Agent 2: Slice B (`replace-join!` safety and parent-ident behavior)
3. Agent 3: Slice C (option/policy plumbing)
4. Agent 4: Slice D (tests) after 1-3 merge

## Verification

1. [ ] Missing parent ident no longer mutates state as if join rewrite succeeded
2. [ ] Duplicate route leaf names are reported before ambiguous URL routing executes
3. [ ] Direct vs reachable target collisions are surfaced with explicit warnings
4. [ ] Default mode remains non-throwing and backward compatible
5. [ ] Strict mode throws deterministic ex-info for invalid route configs
6. [ ] New tests cover all three warning categories and strict-mode throws
