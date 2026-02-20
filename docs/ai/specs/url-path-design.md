# Spec: URL Path Design

**Status**: done
**Priority**: P1
**Created**: 2026-02-11
**Completed**: 2026-02-18
**Owner**: conductor

## Context

The prior design had `:route/path` declarations on each route state, hierarchical path
composition, parameterized path segments, and plans for named query params + unified URL
encoding. This was overengineered.

The simplified design: URL paths are derived automatically from the chart's route target
hierarchy using simple component names. No `:route/path` option. Params are encoded as a
single opaque transit+base64 query param (as the existing system already does).

URL/history integration is an **external system** — decoupled from the core routing
statechart (which is what `ui_routing2` provides). The URL layer observes state changes
and translates between URLs and route-to events.

## Design

### URL Format

```
/AdminPanel/AdminUserDetail?_p=<base64-transit of {:user-id 7}>
```

- **Path segments**: Simple name of each route target's registry key in the active hierarchy,
  from root to leaf. `::ui/AdminUserDetail` → `AdminUserDetail`.
- **Params**: Single query param `_p` containing transit+base64 encoded map of **all** active
  route state params, keyed by state ID:
  `_p = base64(transit({:AdminPanel {:tab "users"}, :AdminUserDetail {:user-id 7}}))`
  Only present when at least one active route state has params. Not human-readable.
- **Leaf-only routing**: Only the deepest leaf is the actual routing target. One `route-to!`
  event is sent for the leaf; intermediate states enter automatically as ancestors in the
  statechart hierarchy. Each intermediate state's on-entry picks up its own params slice
  from the event data.
- **Param storage**: Each state stores its params at `[:routing/parameters <state-id>]` in
  the data model (via `establish-route-params-node`). States can use `assign` to mutate
  their params over time. The URL layer observes these changes and updates the URL
  (replace, not push) to stay in sync.

### Path Generation (state → URL)

When the statechart configuration changes, the URL layer:

1. Finds the active leaf route state(s) in the configuration
2. Walks the element tree from leaf up to the routing root, collecting each ancestor that
   has a `:route/target`
3. Maps each target's registry key to its simple name (the `name` part of the keyword)
4. Joins them with `/` to form the path
5. For each active route state with params at `[:routing/parameters <id>]`, collects them
   into a map keyed by state ID
6. Encodes the full params map as transit+base64 in `_p` (omitted if no state has params)

### URL Restoration (URL → state)

On page load or URL change from external source:

1. Parse path segments from URL
2. The **last segment** is the leaf route target — match it against known route targets
   by simple name
3. Decode `_p` query param (if present) to get the state-id→params map
4. Send a single `route-to!` for the leaf with the full decoded params map as event data
5. The statechart transitions to the leaf; intermediate states enter as ancestors, each
   state's `establish-route-params-node` picks up its own slice from the params map

### Intermediate Segments

Intermediate path segments (e.g. `AdminPanel` in `/AdminPanel/AdminUserDetail`) serve as:
- Human-readable context in the URL
- Disambiguation when multiple routes share the same leaf name under different parents

For restoration, the intermediate segments can be used to disambiguate but are not strictly
required if leaf names are unique.

### Bidirectional Sync via `:on-save` Hook

The URL layer hooks into the statechart system via the `:on-save` callback on
`install-fulcro-statecharts!` (`fulcro.cljc:234`). This fires on
`save-working-memory!` (`fulcro_impl.cljc:254`) every time a statechart reaches a
stable configuration after processing an event. It receives `(session-id wmem)`.

**Internal → URL** (event causes screen/param change):
1. Event processed → configuration stabilizes → `save-working-memory!` → `:on-save` fires
2. URL layer reads active routes + params from app state
3. Computes new URL path + `_p` param
4. **Push** new history entry (navigation changed the screen)

**Browser back/forward → Chart** (popstate):
1. popstate event fires → URL layer decodes path + params
2. URL layer sets a flag indicating this is an external URL change
3. Sends `route-to!` for the decoded leaf target
4. Chart processes → stabilizes → `:on-save` fires
5. URL layer checks: was the route allowed or denied?
   - **Allowed**: **replace** current URL (history already has the right entry from popstate)
   - **Denied**: **push forward** to restore the pre-back URL (undo the browser back)

**Param mutation** (assign changes params without route change):
1. State uses `assign` on `[:routing/parameters <id>]`
2. `save-working-memory!` → `:on-save` fires
3. URL layer detects same route, different params
4. **Replace** current URL (no new history entry for param-only changes)

### What Already Exists

- `:route/params` on `rstate`/`istate` — declares accepted param keywords. Already in
  `ui_routing2.cljc`.
- `establish-route-params-node` — stores params from event data into data model. Already works.
- Transit+base64 encoding — exists in `route_url.cljc` (`encode-params`, `decode-params`).
- `route_history.cljc` — HTML5 history integration, `route->url`/`url->route` callbacks.
- `:on-save` callback — `install-fulcro-statecharts!` option, fires on every stable
  configuration save. `fulcro_impl.cljc:256`.

### What Changes

- Drop `:route/path` — path segments are auto-derived from target names
- Simplify URL encoding to single `_p` param (drop `_sc_` and `_rp_` dual encoding)
- Add path generation from active configuration (walk ancestors)
- Add URL restoration that matches leaf by simple name
- URL sync driven by `:on-save` hook — push/replace logic depends on whether the change
  was internal navigation, browser back/forward, or param-only mutation

## Requirements

1. URL paths are auto-derived from the route target hierarchy — no configuration needed
2. Path segments use the simple name of the registry key (no namespace)
3. Route params encoded as single opaque transit+base64 query param `_p`, keyed by state ID
3a. States can mutate their params via `assign`; URL layer syncs changes (replace, not push)
4. URL restoration sends a single route-to event for the leaf target
5. Intermediate path segments present for readability and disambiguation
6. URL/history layer is external to the core routing statechart
7. `route->url` / `url->route` callbacks remain the extension point for custom URL schemes

## Key Files

| File | Role |
|------|------|
| `integration/fulcro/route_url.cljc` | URL encoding/decoding — simplify to single `_p` param |
| `integration/fulcro/route_history.cljc` | History integration — update path generation/restoration |
| `integration/fulcro/ui_routing2.cljc` | Core routing — no changes needed (`:route/params` already exists) |

## Verification

1. [ ] Path auto-derived: `AdminPanel` istate containing `AdminUserDetail` rstate → `/AdminPanel/AdminUserDetail`
2. [ ] Params encoded per state: `{:AdminPanel {:tab "users"} :AdminUserDetail {:user-id 7}}` → `?_p=<base64 transit>`
3. [ ] Params decoded: `?_p=<base64 transit>` → state-id-keyed map passed as event data, each state picks up its slice
4. [ ] Leaf-only restoration: URL `/AdminPanel/AdminUserDetail?_p=...` sends single route-to for `AdminUserDetail`
5. [ ] Intermediate states enter automatically (AdminPanel entered as ancestor of AdminUserDetail)
6. [ ] Routes with no params produce clean URLs with no `_p` param
6a. [ ] State using `assign` to change its params triggers URL update (replace, not push)
7. [ ] `route->url` / `url->route` callbacks still work for custom schemes
8. [ ] Internal navigation pushes new history entry
9. [ ] Browser back/forward with allowed route replaces URL (no duplicate entry)
10. [ ] Browser back/forward with denied route pushes forward to undo the back
11. [ ] URL sync driven by `:on-save` hook on `install-fulcro-statecharts!`
12. [ ] routing-demo2 updated to demonstrate URL integration as an external system
