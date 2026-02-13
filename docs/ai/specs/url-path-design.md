# Spec: URL Path Design

**Status**: active (Phase 1 complete, Phase 2 pending)
**Priority**: P1
**Created**: 2026-02-11
**Owner**: conductor

## Context

The current routing system has two separate URL encoding mechanisms that overlap and conflict:

1. `route_url.cljc` uses a `_sc_` query parameter with base64-encoded transit for per-state params
2. `route_history.cljc` uses a `_rp_` query parameter with base64-encoded transit for route params

Additionally, route paths are flat — each `rstate` declares its complete path (e.g., `[:route/path ["users" "edit"]]`). There is no automatic composition where a child route inherits its parent's path prefix, and no support for parameterized path segments (e.g., `/users/:id/edit`).

This spec designs a unified URL format and hierarchical path composition for the routing system.

### Current URL Formats

From `route_url.cljc`:
```
https://app.com/users/edit?_sc_=<base64-transit of {state-id {param-key param-val}}>
```

From `route_history.cljc`:
```
https://app.com/users/edit?_rp_=<base64-transit of {param-key param-val}>
```

Both are opaque blobs. Neither supports human-readable query parameters or parameterized path segments.

## Requirements

### Path Composition

1. Route paths must compose hierarchically. A child `rstate` with `:route/path ["edit"]` under a parent with `:route/path ["users"]` produces URL path `/users/edit`
2. Parameterized path segments must be supported: `:route/path ["users" :id "edit"]` where `:id` is resolved from the route's data model or event data
3. Path matching for URL restoration must handle parameterized segments — `/users/42/edit` matches `["users" :id "edit"]` and extracts `{:id "42"}`
4. A route state with no `:route/path` inherits its parent's path (useful for `istate` wrappers)
5. Parallel regions do NOT compose paths — only one "active path" determines the URL path. The other regions contribute via query parameters only.

### URL Format Unification

6. Unify `_sc_` and `_rp_` into a single encoding scheme. Opaque per-state params (`_sc_` style, keyed by state ID) are the right model since multiple states contribute params simultaneously.
7. Named query parameters (human-readable) should also be supported for SEO and shareability: `:route/query-params {:q :search-term}` maps data model key `:search-term` to URL param `?q=value`
8. The unified URL format should be: `/<path-segments>?<named-params>&_s=<opaque-state-params>#<optional-hash>`
9. The opaque state param blob (`_s`) should be optional — only present when states declare `:route/params`

### Backward Compatibility

10. Existing charts using flat `:route/path` with full paths must continue to work
11. The `route->url` and `url->route` callbacks on `new-html5-history` remain the extension point for custom URL schemes

## Affected Modules

- `integration/fulcro/ui_routes.cljc` — `rstate`, `istate`, `routes`, `establish-route-params-node`, `apply-external-route`, `state-for-path`
- `integration/fulcro/route_url.cljc` — Unify with route_history URL functions, add path matching
- `integration/fulcro/route_history.cljc` — Unify URL encoding, update `route->url`/`url->route` defaults

## Approach

### Hierarchical Path Resolution

Add a `resolve-full-path` function that walks the statechart element tree from a state up to the routing root, collecting path segments:

```clojure
;; Given:
(routes {:routing/root :app/Root :id :routes}
  (rstate {:route/target :user/List :route/path ["users"]}
    (rstate {:route/target :user/Detail :route/path [:id]}
      (rstate {:route/target :user/Settings :route/path ["settings"]}))))

;; resolve-full-path for :user/Settings => ["users" :id "settings"]
```

During `apply-external-route`, path matching resolves parameterized segments:
```clojure
;; URL: /users/42/settings
;; Pattern: ["users" :id "settings"]
;; Extracted: {:id "42"}
```

### Parameterized Segment Resolution

When entering a state (on-entry), parameterized segments are resolved from:
1. Event data (highest priority — the routing event carries the params)
2. Data model (for states already populated)
3. URL extraction (during restoration — params come from URL matching)

### URL Format

```
/users/42/settings?q=dark&_s=eyAuLi4gfQ==
 ├── path segments (including resolved params)
 ├── named query params (declared via :route/query-params)
 └── opaque state params blob (base64 transit, keyed by state ID)
```

### Migration Path

Phase 1: Add `resolve-full-path` and parameterized matching. Existing flat paths work unchanged.
Phase 2: Unify URL encoding. Deprecate `_sc_` and `_rp_` in favor of `_s` + named params.
Phase 3: Remove old encoding support in a future version.

## Design Decisions to Resolve During Implementation

- Should parameterized segments be keywords (`:id`) or tagged vectors (`[:param :id]`)? Keywords are concise but could conflict with literal path segments that happen to be keywords.
- Should path composition be opt-in (require a flag) or default behavior? Flat paths are currently the norm.
- How should path conflicts be reported? (Two states resolving to the same URL path)
- Should there be a route table (precomputed path→state mapping) for O(1) lookup, or is linear scan of elements acceptable?

## Verification

### Phase 1 (hierarchical paths + parameterized matching)
1. [x] Child routes compose paths with parent: parent `["users"]` + child `["edit"]` = `/users/edit`
2. [x] Parameterized segments resolve: `["users" :id]` with `{:id 42}` produces `/users/42`
3. [x] URL restoration extracts params: `/users/42` matched against `["users" :id]` yields `{:id "42"}`
4. [x] States with no `:route/path` inherit parent path
5. [x] Flat `:route/path` (existing behavior) still works unchanged
9. [x] `route->url` / `url->route` callbacks still work for custom schemes

### Phase 2 (URL encoding unification — not yet started)
6. [ ] `_sc_` and `_rp_` unified into single `_s` parameter
7. [ ] Named query parameters appear as human-readable URL params
8. [ ] Parallel regions: only one region contributes to path, others contribute query params
