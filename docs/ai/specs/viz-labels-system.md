# viz-labels-system

**Status**: backlog
**Priority**: P1
**Created**: 2026-02-25
**Blocks**: viz-visualizer-enhancements, viz-simulator

## Context

The current visualizer shows `[cond]` as a literal string on transitions, ignoring the `:diagram/condition` key that convenience macros already attach. UML statechart notation has standard conventions: transitions show `event [guard] / actions`, states show `entry / actionName` and `exit / actionName`. We need label formatting functions to support this notation.

## Requirements

1. Add label formatting functions to `chart.cljc` (alongside existing chart analysis functions)
2. `diagram-label [{:keys [id diagram/label]}]` — prefers `:diagram/label`, falls back to `(name id)`
3. `transition-label [elements-by-id transition]` — UML format: `event [condition] / action1, action2`
   - Uses `:diagram/label` on the transition if set (overrides all)
   - Uses `:diagram/condition` for guard display text
   - Falls back to `[cond]` only if `:cond` present but no `:diagram/condition`
   - Collects `:diagram/label` from executable content children for the action part after `/`
4. `state-entry-labels [elements-by-id state-element]` — walks on-entry children, returns vec of `:diagram/label` strings from their executable content
5. `state-exit-labels [elements-by-id state-element]` — same for on-exit
6. All functions are CLJC, pure data, no host dependencies

## Affected Modules

- `src/main/com/fulcrologic/statecharts/chart.cljc` — add functions (additive, no signature changes)

## Approach

These functions navigate `elements-by-id` using existing chart navigation functions (`get-children`, `entry-handlers`, `exit-handlers`). The `transition-label` function assembles parts conditionally and joins with spaces. Standard UML format: `event [guard] / action1, action2`.

## Verification

### Tier 1: Unit tests (fulcro-spec via REPL)
1. [ ] `transition-label`: event only → `":go"`
2. [ ] `transition-label`: event + cond (no diagram/condition) → `":go [cond]"`
3. [ ] `transition-label`: event + diagram/condition → `":go [valid?]"`
4. [ ] `transition-label`: event + condition + action labels → `":go [valid?] / load-data, notify"`
5. [ ] `transition-label`: diagram/label overrides everything → `"custom label"`
6. [ ] `state-entry-labels`: on-entry with labeled script → `["load-data"]`
7. [ ] `state-exit-labels`: on-exit with labeled script → `["cleanup"]`
8. [ ] `state-entry-labels`: on-entry with no diagram/label → `[]`
9. [ ] Existing chart_spec tests still pass

### Tier 2: N/A (pure data functions, no rendering)
