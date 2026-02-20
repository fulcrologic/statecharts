# Fulcro Integration — CLAUDE.md

Connects statecharts to Fulcro UI framework. This is the most complex subsystem with **10-20% test coverage — critical gap**.

## Key Concepts
- **Actors** — Map UI components to statechart data paths (`:actor/thing`)
- **Aliases** — Named paths to data (`:fulcro/aliases`)
- **Operations** — `fop/load`, `fop/invoke-remote` for I/O from within charts
- **Hooks** — `use-statechart` for component-local charts

## File Map
- `fulcro.cljc` — Main API: `register-statechart!`, `start!`, actor/alias resolution
- `fulcro_impl.cljc` — Implementation details, Fulcro-specific data model
- `fulcro/hooks.cljc` — React hooks integration
- `fulcro/operations.cljc` — Fulcro-specific operations (load, remote)
- `fulcro/rad_integration.cljc` — RAD form integration
- `fulcro/route_history.cljc` — Browser history management (legacy RAD)
- `fulcro/ui_routes.cljc` — Route state synchronization (deprecated — use `routing.core`)
- `fulcro/ui_routes_options.cljc` — Route configuration options (shared by old and new)
- `fulcro/routing.cljc` — Full routing API (DSL, URL sync, validation, busy checking)
- `fulcro/routing_options.cljc` — Component options for routing (RAD pattern)
- `fulcro/routing/url_codec.cljc` — URLCodec protocol
- `fulcro/routing/url_codec_transit.cljc` — Default transit+base64 codec
- `fulcro/routing/url_history.cljc` — URLHistoryProvider protocol + helpers
- `fulcro/routing/browser_history.cljc` — Browser history (CLJS)
- `fulcro/routing/simulated_history.cljc` — Simulated history (testing)

## Data Model Integration
Uses Fulcro app state as the data model. Special paths:
- `:fulcro/state-map` — Access full Fulcro state
- Actor paths resolve through component ident

## Known Issues
- `fulcro_impl.cljc` has duplicate `dissoc-in` (also in `data_model/`)
- `ui_routes.cljc` has FIXMEs: route idents storage (line 231), HTML coupling (line 376)
- Most files completely untested
