# viz-docstring-updates

**Status**: backlog
**Priority**: P2
**Created**: 2026-02-25

## Context

The `:diagram/label` and `:diagram/condition` keys work on transitions but aren't documented. The `:diagram/expression` key on scripts should be soft-deprecated in favor of `:diagram/label`. The convenience macros should emit `:diagram/label` alongside (not replacing) `:diagram/expression`.

## Requirements

1. Add `:diagram/label` and `:diagram/condition` to `transition` docstring in `elements.cljc`
2. Add soft deprecation note to `:diagram/expression` in `script` element docstring — recommend `:diagram/label`
3. Update `handle` macro in `convenience_macros.cljc` to emit `:diagram/label` IN ADDITION to `:diagram/expression`
4. Update `assign-on` macro similarly
5. No keys removed, no signature changes — strictly additive

## Affected Modules

- `src/main/com/fulcrologic/statecharts/elements.cljc` — docstring additions (lines 108-121, ~315-323)
- `src/main/com/fulcrologic/statecharts/convenience_macros.cljc` — emit additional `:diagram/label` key in `handle` (line 32) and `assign-on` (line 53)

## Verification

### Tier 1: Unit tests (fulcro-spec via REPL)
1. [ ] Existing convenience_spec tests still pass
2. [ ] `handle` macro output contains both `:diagram/label` and `:diagram/expression` (same value)
3. [ ] `assign-on` macro output contains both keys
4. [ ] `:diagram/expression` still present on all existing macro outputs (backwards compat)

### Tier 2: N/A (docstrings and macro output, no rendering)
