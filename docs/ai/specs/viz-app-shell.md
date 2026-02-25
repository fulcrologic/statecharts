# viz-app-shell

**Status**: backlog
**Priority**: P1
**Created**: 2026-02-25
**Blocked by**: viz-visualizer-enhancements, viz-simulator

## Context

The existing viz demo app (`src/examples/com/fulcrologic/statecharts/visualization/app/`) already has chart selection, session viewing, and the Visualizer component wired up via shadow-cljs `:viz` build. We expand it with a mode selector, event panel, guard panel, and simulator integration rather than creating a separate app.

## Requirements

### Mode selector
1. Add mode toggle to `app/ui.cljs`: connected vs simulator
2. Connected mode: existing behavior (chart picker, session ID input, live configuration)
3. Simulator mode: chart picker + simulator controls (no session ID needed)

### Event panel (both modes)
4. Add event panel component (in `app/ui.cljs` or new `app/event_panel.cljs` if large)
5. Lists available events from current configuration (from `simulator/available-events`)
6. Click event name to select it
7. EDN textarea for event data (`:data` parameter)
8. "Send" button:
   - Connected: `df/load!` + remote send-event mutation (existing resolver)
   - Simulator: calls `simulator/send-event!`, updates configuration in Fulcro state

### Guard panel (simulator mode only)
9. Add guard panel component
10. Lists all guards from `simulator/extract-guards`
11. Shows `:diagram/condition` label for each guard
12. Toggle switch for true/false
13. Visual indication of which transitions each guard enables

### Simulator integration
14. When simulator mode + chart picked: call `simulator/start-simulation!` with the chart definition
15. Pass resulting configuration to `ui-visualizer` via `:current-configuration` computed prop
16. "Reset" button calls `simulator/reset-simulation!`
17. Guard toggles call `simulator/toggle-guard!`

### Layout
18. Main area: `Visualizer` (existing, via `ui-visualizer`)
19. Right sidebar: event panel (always) + guard panel (simulator mode)

## Affected Modules

- `src/examples/com/fulcrologic/statecharts/visualization/app/ui.cljs` — expand with mode selector, event panel, guard panel, simulator wiring
- Possibly split into `app/event_panel.cljs` and `app/guard_panel.cljs` if `ui.cljs` gets too large

## Approach

Expand the existing `ChartViewer` component to be mode-aware. In simulator mode, it creates a simulator instance (from `visualization/simulator.cljc`) and passes its configuration to `ui-visualizer`. Event and guard panels are new components rendered alongside the chart. Connected mode retains existing behavior.

## Verification

### Tier 1: Compile check
1. [ ] Shadow-cljs `:viz` build compiles without errors

### Tier 2: Visual verification in demo app
Run `shadow-cljs watch viz` + start server. Open browser at http://localhost:8090.

**Connected mode (existing behavior preserved):**
2. [ ] Chart picker still works, shows all demo charts
3. [ ] Session ID input still works, shows active configuration
4. [ ] Event panel shows events available from current configuration
5. [ ] Click event + send → session state updates

**Simulator mode (new):**
6. [ ] Switch to simulator mode
7. [ ] Select a chart with guards (e.g. `:conditional-demo` or `:label-demo`)
8. [ ] Visualizer shows initial configuration highlighted
9. [ ] Guard panel lists all guards with labels
10. [ ] Toggle a guard to false → visual feedback
11. [ ] Event panel shows available events
12. [ ] Click event + send → configuration updates, visualizer highlights new active states
13. [ ] Toggle guard back to true, send same event → different transition taken
14. [ ] Reset button → returns to initial configuration
