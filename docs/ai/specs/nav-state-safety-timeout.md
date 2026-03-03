# nav-state-safety-timeout

**Status**: done
**Priority**: P3
**Created**: 2026-02-19

## Context

The denied-route timing redesign (2026-02-19) replaced the 300ms `setTimeout` heuristic
with a deterministic root-save gate + outstanding nav counter. This eliminated timing
sensitivity but introduced a liveness risk: if `process-event!` fails during a
browser-initiated navigation, no root save occurs and `nav-state` + `outstanding-navs`
remain set permanently. All subsequent programmatic URL updates are silently skipped
because the `on-save-handler` sees `(:browser-initiated? nav)` and waits for a root
save that will never come.

## Requirements

1. Add a safety-net timeout (CLJS only) that clears stuck `nav-state` after ~5 seconds
2. The timeout is crash recovery only — NOT a correctness mechanism
3. Log an error when the timeout fires so the root cause can be investigated
4. Reset both `nav-state` and `outstanding-navs` to restore URL sync functionality
5. Also reset `settling?` (added in forward-button-after-back-istate fix) — if stuck true,
   all programmatic URL changes silently use replaceState instead of pushState
6. Cancel the timeout on normal resolution (acceptance or denial)

## Approach

In `do-popstate`, after setting `nav-state`, start a 5-second timer:

```clojure
#?(:cljs
   (do
     (when-let [t @safety-timer] (js/clearTimeout t))
     (reset! safety-timer
       (js/setTimeout
         (fn []
           (when @nav-state
             (log/error "URL sync: nav-state stuck for 5s — process-event! likely failed")
             (reset! nav-state nil)
             (reset! outstanding-navs 0)
             (reset! settling? false)))
         5000))))
```

Cancel in the `on-save-handler` when resolution occurs (acceptance or denial branches).
Cancel in the cleanup function.

## Verification

- Unit test: simulate stuck nav-state by setting it manually without processing events,
  verify that after 5s the timeout clears it (requires JS timer mocking or real delay)
- Verify normal flows still work (timeout is cancelled before it fires)
- Verify the timeout does NOT fire during slow-but-successful async processing (<5s)
