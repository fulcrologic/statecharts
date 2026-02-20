# Spec: Cross-Platform URL Parsing

**Status**: done
**Completed**: 2026-02-18
**Priority**: P1
**Created**: 2026-02-18
**Owner**: conductor

## Context

`url->route-target` in `route_url.cljc` uses `js/URL` for URL parsing and is CLJS-only (returns nil on CLJ). For headless testing, the CLJ branch must parse URLs using `java.net.URI`. The function already accepts an `href` argument (1-arity), so only the parsing implementation needs a CLJ branch.

## Requirements

1. Add CLJ branch to `url->route-target` using `java.net.URI`:
   - Extract pathname → split into segments
   - Extract query string → find `_p` parameter → decode via `decode-params`
   - Return same structure: `{:leaf-name :segments :params}`

2. Add CLJ import: `(:import (java.net URI))` in reader conditional

3. `current-url-path` should also get a CLJ branch (used indirectly) — same `java.net.URI` approach for the 1-arity form that takes an href

4. The 0-arity forms (which call `current-url`) remain CLJS-only (they read from the browser). Headless callers always pass an explicit href.

## Affected Modules

- `integration/fulcro/route_url.cljc` — `url->route-target`, `current-url-path`, ns imports

## Approach

Add `#?(:clj ...)` branches alongside existing `#?(:cljs ...)` in the 1-arity forms. Use `java.net.URI` for path extraction and manual query string parsing (split on `&`, find `_p=` key). The `decode-params` function already works on CLJ.

## Verification

1. [ ] CLJ: `(url->route-target "http://localhost/Dashboard")` → `{:leaf-name "Dashboard" :segments ["Dashboard"]}`
2. [ ] CLJ: `(url->route-target "http://localhost/Admin/Users?_p=encoded")` → correct segments + decoded params
3. [ ] CLJ: `(url->route-target "http://localhost/")` → nil (no segments)
4. [ ] CLJ: `(url->route-target "/Dashboard")` → works with relative paths (java.net.URI handles this)
5. [ ] CLJ: `(current-url-path "http://localhost/Foo/Bar")` → `["Foo" "Bar"]`
6. [ ] CLJS: existing behavior unchanged (js/URL still used)
