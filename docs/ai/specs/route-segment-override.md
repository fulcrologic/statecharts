# Spec: Route Segment Override via `:route/segment`

**Status**: backlog
**Priority**: P1
**Created**: 2026-02-18
**Owner**: conductor

## Context

The prior draft incorrectly treated `:route/path` as a vector/pattern mechanism with placeholder params in path segments.

For this codebase, we want a simpler and clearer contract:

- Route params are **not** encoded via path placeholders.
- Route params continue to travel in event data and URL `_p` payload handling.
- Path customization should be a per-state literal segment override.

To make that explicit, the API should use `:route/segment` (simple string) instead of `:route/path`.

## Migration Note

- `:route/path` is deprecated for routing URL customization in this subsystem.
- If both `:route/path` and `:route/segment` are present, `:route/segment` wins.
- During migration, log a warning when `:route/path` is encountered so teams can remove it.
- Route params must not be encoded in path structure; keep params in event data / `_p` handling.

## Requirements

1. Add optional `:route/segment` on `rstate` and `istate`:
   - value is a simple string path segment
   - no placeholder/path-param syntax
2. Preserve backward compatibility:
   - if no `:route/segment`, default to current target-name-derived segment behavior
3. URL generation (state -> URL) must use `:route/segment` when present, otherwise default segment.
4. URL restoration (URL -> route target) must match by concrete segment chain only; no path param extraction.
5. Route params remain out-of-path and continue using existing event-data + `_p` query handling.
6. Path ambiguity must be detected and surfaced with warning/strict-mode behavior.

## Affected Modules

- `src/main/com/fulcrologic/statecharts/integration/fulcro/ui_routes.cljc` - carry route segment metadata
- `src/main/com/fulcrologic/statecharts/integration/fulcro/route_url.cljc` - segment chain composition/matching
- `src/main/com/fulcrologic/statecharts/integration/fulcro/ui_routes_options.cljc` - optional route segment option key (if desired)
- `Guide.adoc` - replace `:route/path` guidance with `:route/segment` semantics
- `src/test/com/fulcrologic/statecharts/integration/fulcro/url_sync_headless_spec.cljc` - URL behavior tests

## Approach

### Slice A: Segment Model (parallelizable)

- Add pure helpers to compute full segment chain for a route state:
  - explicit `:route/segment` when present
  - default to existing target-derived segment when absent

### Slice B: URL Sync Integration (parallelizable)

- Update state->URL and URL->target matching to use segment chain model.
- Maintain existing `_p` param decoding/encoding path unchanged.

### Slice C: Ambiguity Detection (parallelizable)

- Add validation for duplicate full segment chains and ambiguous leaf restoration paths.
- Integrate with strict/warn policy from safety-checks spec.

### Slice D: Docs + Demo Alignment (depends on A/B/C)

- Update Guide examples to `:route/segment`.
- Add at least one demo route using a custom segment.

## Parallelization Plan

1. Agent 1: Slice A (pure segment model + unit tests)
2. Agent 2: Slice B (URL sync behavior updates)
3. Agent 3: Slice C (ambiguity checks)
4. Agent 4: Slice D (docs/demo updates) after merge

## Verification

1. [ ] Existing routes without `:route/segment` keep current URL behavior
2. [ ] Explicit `:route/segment` overrides URL segment for that state
3. [ ] Nested routes compose explicit and default segments correctly
4. [ ] URL restoration uses concrete segment chains without path-param parsing
5. [ ] Route params still flow through event-data and `_p`, not path placeholders
6. [ ] Ambiguous segment chains are surfaced by warning/strict checks
