# Routing Documentation Enhancement

| Field    | Value                                                  |
|----------|--------------------------------------------------------|
| Status   | Backlog                                                |
| Priority | P1                                                     |
| Created  | 2026-02-19                                             |
| Owner    | (unassigned)                                           |

## Context

The new `routing` namespace and `routing.*` sub-package provide a comprehensive statechart-driven routing system for Fulcro apps. The Guide.adoc covers the basics (lines 1035-1303) but reads more like a feature checklist than a tutorial. A developer new to this system would struggle to understand:

- How all the pieces fit together end-to-end
- When and why to use `istate` vs `rstate`
- How to implement authentication guards with bookmark restoration
- How the URL codec is pluggable and how to write a custom one
- How to structure components for routing (ident requirements, dynamic queries, rendering)
- How code-splitting works with `istate` and child charts
- How to test routing headlessly in CI

The routing-demo2 app shows all of these patterns but isn't referenced from the Guide.

## Requirements

Expand the "Hierarchical Routing" section of Guide.adoc to be a comprehensive guide. The section should be reorganized and expanded to cover the following areas, each with working code examples:

### 1. End-to-End Setup Walkthrough (NEW)

A complete, sequential walkthrough showing every step from zero to working routes:

```
1. install-fulcro-statecharts! with :on-save and :async? true
2. Define the routing chart with routing-regions / routes / rstate
3. register-statechart! (if using istate with pre-registered child charts)
4. start! the router
5. install-url-sync! (optional, with cleanup for hot reload)
6. mount! the Fulcro app
```

Include the `defonce cleanup` pattern for shadow-cljs hot reload. Reference the demo app setup (`routing-demo2/app.cljs`).

### 2. Component Setup Requirements (NEW)

Document what route target components need:

- **Constant ident** on routing root (already in Invariants, but needs a code example)
- **`:preserve-dynamic-query? true`** on any component that renders routes (already in Invariants, needs prominence)
- **How to render routes**: `ui-current-subroute` for serial regions, `ui-parallel-route` for parallel
- Show a minimal route target component with correct ident, query, and initial-state
- Show the routing root component that renders the current subroute

### 3. `istate` Deep-Dive: Composition and Code Splitting (NEW)

This is the most underdocumented and most powerful feature. Cover:

- **What `istate` does**: invokes a child statechart when the route is entered, tears it down on exit
- **Co-located charts** via `sfro/statechart` component option (chart definition lives on the component)
- **Pre-registered charts** via `sfro/statechart-id` (for code-splitting: child chart in separate namespace, registered separately)
- **Cross-chart routing** via `:route/reachable` — explain that the parent chart can route to targets that only exist in the child
- **How `send-to-self!` works** — sending events from a component (or any of its UI children) to the nearest ancestor's co-located child chart
- **`current-invocation-configuration`** — querying the child chart's state (also walks parent chain)
- **Child chart lifecycle**: auto-start, auto-stop, `exit-target`, `on-done`
- **`sfro/actors`** — passing additional actors to the child chart

Use the admin panel from routing-demo2 as the running example:
- Parent chart declares `istate` with `:route/reachable`
- Child chart (`admin_chart.cljc`) is a separate statechart with its own routes
- Show how the parent can `route-to!` targets that live inside the child

### 4. Authentication Guard Pattern (NEW)

Document the bookmark/restore pattern from routing-demo2:

- Unauthenticated state wrapping routes, intercepting `route-to.*` events
- `save-bookmark` function capturing the denied route event
- `replay-bookmark!` on dashboard entry after successful login
- How this interacts with URL sync (bookmarked URL restored after login redirect)

This is a very common need and the demo shows an excellent pattern.

### 5. Pluggable URL Codec (NEW)

Document the `URLCodec` protocol and how to write a custom implementation:

- The protocol: `encode-url` and `decode-url`
- Default `TransitBase64Codec` behavior (transit+base64 in `_p` query param)
- When you'd want a custom codec (e.g., human-readable query params, SEO-friendly URLs)
- How to pass a custom codec to `install-url-sync!` via `:url-codec`
- Example skeleton of a custom codec

### 6. Route Parameters Lifecycle (EXPAND existing)

The current section is too brief. Expand to cover:

- Declaring `:route/params #{:id}` on `rstate`
- How params flow: `route-to!` event data -> on-entry handler -> `[:routing/parameters state-id]` in data model
- Accessing params in on-entry scripts (they're in `event-data`, not `data`)
- How params are encoded into the URL (via the codec)
- How params are restored from the URL on page load / browser back
- Nested params: each route level can declare its own params, all merge into the URL

### 7. Busy Guards Deep-Dive (EXPAND existing)

Expand the brief section to explain:

- How auto-detection works for forms (dirty form = busy)
- How to write a custom `sfro/busy?` predicate
- Deep recursive checking through invocation tree
- The `routing-info` modal state (built into `routing-regions`)
- `record-failed-route!` / `force-continue-routing!` / `abandon-route-change!` lifecycle
- How URL sync interacts with denial (browser URL is reverted)
- `:routing/guarded? false` on child charts to avoid double-guarding

### 8. Headless Testing Guide (EXPAND existing)

The current section shows the basic pattern but needs:

- Complete test setup from scratch (create app, install statecharts, start routing)
- How to wait for async operations in tests
- Asserting on route state: `current-configuration`, `active-leaf-routes`
- Asserting on URL state: `history-stack`, `history-cursor`
- Testing route denial and recovery
- Testing browser back/forward simulation
- Reference the actual test file `url_sync_headless_spec.cljc`

### 9. API Reference Table (NEW)

A concise table of all public functions in the `routing` namespace, grouped by category:

| Category | Functions |
|----------|-----------|
| Setup | `start!`, `install-url-sync!`, `url-sync-on-save` |
| Navigation | `route-to!`, `route-back!`, `route-forward!`, `send-to-self!` |
| State Query | `active-leaf-routes`, `route-denied?`, `has-routes?`, `current-invocation-configuration`, `reachable-targets` |
| Route Denial | `record-failed-route!`, `force-continue-routing!`, `abandon-route-change!` |
| Rendering | `ui-current-subroute`, `ui-parallel-route` |
| DSL | `routing-regions`, `routes`, `rstate`, `istate` |
| Validation | `validate-route-configuration` |

### 10. Common Gotchas (NEW)

A troubleshooting section:

- **Route stops rendering after hot reload**: Missing `:preserve-dynamic-query? true`
- **URL doesn't update on navigation**: Missing `:on-save` handler calling `url-sync-on-save`
- **Route denied unexpectedly**: Form is dirty (auto-detected), check with `busy?`
- **Child chart routes unreachable**: Missing `:route/reachable` declaration on `istate`
- **Duplicate URL segments**: Two targets with same simple name; use `:route/segment` to disambiguate
- **`route-to!` does nothing**: Target not found in chart; check target keyword matches `:route/target`
- **Parallel routes not rendering**: Using `ui-current-subroute` instead of `ui-parallel-route`

## Approach

1. **Read** the current Guide.adoc routing section (lines 1035-1303) and all routing source files
2. **Read** routing-demo2 files for real-world examples to include/reference
3. **Restructure** the routing section with the 10 areas above as subsections
4. **Write** new content with working code examples drawn from the actual codebase
5. **Preserve** existing content where it's already good (validation, invariants)
6. **Include** actual file references from routing-demo2 where appropriate

### Key Principles

- Every code example should be complete enough to copy-paste
- Use the routing-demo2 as the running example throughout
- Cross-reference between subsections (e.g., "see Busy Guards for how denial interacts with URL sync")
- Keep the Asciidoc formatting consistent with the rest of Guide.adoc
- Don't add content about features that don't exist — document what's actually in the code

## Verification

- [ ] All code examples compile (mentally verified against actual source)
- [ ] Every public function in `routing.cljc` is mentioned somewhere
- [ ] The `routing_options.cljc` constants are all documented
- [ ] The `URLCodec` and `URLHistoryProvider` protocols are both explained
- [ ] The istate/cross-chart pattern has a complete worked example
- [ ] Authentication guard pattern is documented with code
- [ ] Headless testing has a complete setup-to-assertion example
- [ ] All invariants from the current section are preserved
- [ ] The routing-demo2 is referenced where appropriate
- [ ] No content about features that don't exist in the code

## Files to Modify

- `Guide.adoc` — Replace lines 1035-1303 with expanded content (estimated ~600-800 lines)

## Files to Read (Context)

- `src/main/com/fulcrologic/statecharts/integration/fulcro/routing.cljc` — All public functions
- `src/main/com/fulcrologic/statecharts/integration/fulcro/routing_options.cljc` — Component options
- `src/main/com/fulcrologic/statecharts/integration/fulcro/routing/url_codec.cljc` — Codec protocol
- `src/main/com/fulcrologic/statecharts/integration/fulcro/routing/url_codec_transit.cljc` — Default codec
- `src/main/com/fulcrologic/statecharts/integration/fulcro/routing/url_history.cljc` — History protocol
- `src/main/com/fulcrologic/statecharts/integration/fulcro/routing/browser_history.cljc` — Browser impl
- `src/main/com/fulcrologic/statecharts/integration/fulcro/routing/simulated_history.cljc` — Test impl
- `src/routing-demo2/com/fulcrologic/statecharts/routing_demo2/chart.cljc` — Demo main chart
- `src/routing-demo2/com/fulcrologic/statecharts/routing_demo2/admin_chart.cljc` — Demo child chart
- `src/routing-demo2/com/fulcrologic/statecharts/routing_demo2/ui.cljs` — Demo components
- `src/routing-demo2/com/fulcrologic/statecharts/routing_demo2/app.cljs` — Demo setup
- `src/test/com/fulcrologic/statecharts/integration/fulcro/routing/url_sync_headless_spec.cljc` — Test example
