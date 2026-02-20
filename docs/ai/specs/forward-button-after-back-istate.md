# forward-button-after-back-istate

**Status**: done
**Priority**: P0
**Created**: 2026-02-19
**Completed**: 2026-02-19

## Context

After navigating through pages (including istate/cross-chart routes), using browser back,
then browser forward didn't work. The forward history entries were destroyed.

## Root Cause

When browser back navigated to a page with an `istate` (child chart invocation), the
acceptance of the browser nav cleared `nav-state`. Child chart initialization then fired
async saves that changed the deep URL. These saves hit Branch 4 (programmatic navigation)
in `on-save-handler`, which called `pushState`. In browsers, `pushState` while in the
middle of history destroys all forward entries.

## Approach

Added `settling?` atom to `install-url-sync!` that tracks the brief window after browser
nav acceptance where child saves may fire:

1. **On acceptance**: set `settling? = true`, schedule `setTimeout(0)` in CLJS to auto-clear
2. **On new popstate**: clear `settling?` immediately
3. **In Branch 4**: check `(and @settling? (not root-save?))`:
   - True (child save during settling): `replaceState` to preserve forward history
   - False (normal programmatic nav or root save): `pushState`, clear `settling?`
4. **Final cond clause**: `@settling?` with no URL change clears it (CLJ fallback)

The `(not root-save?)` check distinguishes child chart init saves (replaceState) from
user-initiated programmatic navigation (pushState).

## Files Changed

- `src/main/com/fulcrologic/statecharts/integration/fulcro/ui_routes.cljc` — settling? atom + Branch 4 logic
- `src/test/com/fulcrologic/statecharts/integration/fulcro/url_sync_headless_spec.cljc` — new test

## Verification

- New test: "forward after back through istate preserves forward history" (7 assertions)
- All existing tests pass: 31 tests, 164 assertions, 0 failures
- CLJS compiles cleanly (shadow-cljs)
