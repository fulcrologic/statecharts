# Spec: Denied Route Timing Redesign (Design Proposal)

**Status**: backlog
**Priority**: P0
**Created**: 2026-02-18
**Owner**: conductor

## Context

Current denied-route handling for browser back/forward uses deferred timing checks in CLJS (`setTimeout` + post-hoc URL/state comparison). This is pragmatic, but it is heuristic and time-based.

We need a deterministic design for route acceptance/denial under async routing, invoked child charts, and rapid history navigation.

This spec is intentionally design-first: produce and agree on a robust mechanism before coding.

## Requirements

1. Produce a concrete design proposal that removes fixed-time denial heuristics from core correctness logic.
2. Proposal must define acceptance/denial based on deterministic signals, not elapsed delay.
3. Proposal must handle:
   - async route transitions
   - invoked child chart save ordering
   - rapid back/forward bursts
   - forced override (`force-continue-routing!`)
4. Proposal must include migration strategy from current behavior and rollback plan.
5. Proposal must include test strategy with failure injection and race-oriented scenarios.

## Candidate Directions to Evaluate

1. Correlated navigation intents (`nav-id`) with explicit completion/denial events.
2. Root-session commit acknowledgement hook (only root commit resolves navigation intent).
3. Deterministic queue semantics for popstate intents (latest-wins with intent supersession).
4. Explicit routing sync state machine in local data (`idle`, `pending`, `accepted`, `denied`, `restoring`).

## Affected Modules (for eventual implementation)

- `src/main/com/fulcrologic/statecharts/integration/fulcro/ui_routes.cljc`
- `src/main/com/fulcrologic/statecharts/integration/fulcro/route_url.cljc`
- `src/main/com/fulcrologic/statecharts/integration/fulcro.cljc` (if completion hooks are needed)
- `src/test/com/fulcrologic/statecharts/integration/fulcro/url_sync_headless_spec.cljc`
- `docs/ai/specs/browser-navigation-async.md` (closeout/update after redesign)

## Deliverables

1. Design doc in this spec (or linked plan) with sequence diagrams for:
   - accepted popstate
   - denied popstate
   - superseded popstate
2. Decision record: selected design + rejected alternatives with rationale.
3. Test matrix mapped to race classes and invariants.

## Parallelization Plan

1. Agent 1: Model invariants and state machine proposal
2. Agent 2: Prototype API/hooks needed for deterministic completion signals
3. Agent 3: Enumerate race scenarios and derive test matrix
4. Agent 4: Consolidate final design ADR and migration plan

## Verification

1. [ ] Proposal defines strict invariants for URL/state consistency
2. [ ] Proposal removes fixed timer dependency from correctness path
3. [ ] Proposal covers async child invocation ordering explicitly
4. [ ] Proposal includes incremental migration steps and compatibility notes
5. [ ] Proposal includes concrete tests that can fail under intentionally injected races
