# Spec: UI Routes Documentation and Contract Alignment

**Status**: backlog
**Priority**: P1
**Created**: 2026-02-18
**Owner**: conductor

## Context

The Guide currently documents `:route/path` composition and related path-matching helpers as active behavior, while runtime behavior remains primarily target-name-derived URLs with fallback leaf matching.

This creates contract ambiguity for users and increases integration risk.

## Requirements

1. Align Guide and runtime behavior so documentation matches shipped semantics at all times.
2. If `:route/path` override is not yet shipped, document it clearly as pending/not yet available.
3. Add explicit compatibility notes around:
   - target-name-derived default URLs
   - duplicate leaf-name ambiguity warnings/constraints
   - `url-sync-on-save` wiring requirements
4. Add a small "routing invariants" subsection documenting operational assumptions (constant ident roots, dynamic query preservation requirement, strict/warn validation modes).

## Affected Modules

- `Guide.adoc`
- `docs/ai/specs/route-segment-override.md` (cross-link expected contract)
- `docs/ai/specs/ui-routes-safety-checks-and-warnings.md` (cross-link validation behavior)

## Approach

### Slice A: Contract Audit (parallelizable)

- Audit Guide routing section for all behavior claims and map each to current implementation status.

### Slice B: Wording and Examples (parallelizable)

- Rewrite ambiguous sections; include "current behavior" and "planned behavior" labels where needed.

### Slice C: Verification Checklist (parallelizable)

- Add a release-gate checklist for routing docs so behavior/documentation drift is caught during future changes.

## Parallelization Plan

1. Agent 1: Slice A (audit matrix)
2. Agent 2: Slice B (Guide updates)
3. Agent 3: Slice C (release checklist)

## Verification

1. [ ] No Guide claims conflict with current implementation
2. [ ] URL behavior and path semantics are explicitly stated and testable
3. [ ] Integration prerequisites (`install-url-sync!`, `url-sync-on-save`) are clear
4. [ ] Planned-but-unshipped behavior is clearly marked as such
