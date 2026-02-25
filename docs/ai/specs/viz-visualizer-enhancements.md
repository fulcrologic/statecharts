# viz-visualizer-enhancements

**Status**: backlog
**Priority**: P1
**Created**: 2026-02-25
**Blocked by**: viz-labels-system

## Context

The existing `Visualizer` component in `visualizer.cljs` is used by Fulcro Inspect and other libraries. It needs enhanced rendering to show UML-standard labels and executable content annotations, but its public API (`ui-visualizer`, `Visualizer`) must remain compatible.

## Requirements

### Transition label improvements
1. Use `chart/transition-label` (from viz-labels-system) instead of inline label construction
2. Transitions show `event [guardName] / actions` instead of `event [cond]`
3. `:diagram/condition` is displayed when present

### State box improvements (NEW rendering)
4. States with on-entry/on-exit children that have `:diagram/label` show an activity compartment:
   - Thin divider line between name and activity area
   - `entry / actionName` lines for each labeled on-entry child
   - `exit / actionName` lines for each labeled on-exit child
   - Only shown when labels exist (states without labels render as before)
5. State box measurement must account for the larger size when activity compartment is present

### Compatibility
6. `ui-visualizer` factory and `Visualizer` defsc remain public with same props interface
7. Enhanced rendering is purely additive — charts without `:diagram/label`/`:diagram/condition` render as before

## Affected Modules

- `src/main/com/fulcrologic/statecharts/visualization/visualizer.cljs` — enhance `chart->elk-tree` label logic, add activity compartment rendering to state boxes

## Approach

Replace the inline label construction in `chart->elk-tree` (lines 40-46) and `use-chart-elements` (lines 113-119) with calls to `chart/transition-label`. Add entry/exit label extraction using `chart/state-entry-labels` and `chart/state-exit-labels`. In the state rendering section (lines 386-439), add the activity compartment div below the label div when entry/exit labels exist.

## Verification

### Tier 1: Compile check
1. [ ] Shadow-cljs `:viz` build compiles without errors or new warnings

### Tier 2: Visual verification in existing demo app
Use the existing `:viz` demo app (`shadow-cljs watch viz` + server on port 8081).

2. [ ] Add a demo chart to `demo_registry.clj` that uses `choice` macro with named predicates and `(on-entry {} (script {:expr f :diagram/label "load-data"}))` — call it `:label-demo`
3. [ ] Select `:label-demo` in chart picker → verify transitions show `event [predName]` not `event [cond]`
4. [ ] Verify state boxes show `entry / load-data` in the activity compartment
5. [ ] Select existing charts (e.g. `:basic-states-demo`) → verify they render identically to before (no regressions)
6. [ ] `ui-visualizer` still works with same computed props interface
