# Design: Deterministic Denied-Route Handling

**Status**: proposal
**Created**: 2026-02-18
**Spec**: `docs/ai/specs/denied-route-timing-redesign.md`

## Problem Statement

The current CLJS denied-route detection uses a 300ms `setTimeout` in `install-url-sync!`
(ui_routes.cljc:796-803). This timer is a heuristic — it guesses that async `process-event!`
will complete within 300ms. If processing is slower (heavy on-entry, network), the check
fires too early and may miss a denial. If processing is fast, the user sees unnecessary delay
before URL restoration.

The timer exists because:
1. In async mode, child invocation saves (`on-save`) fire BEFORE the root session saves.
2. The `on-save` handler cannot distinguish "root session saved" from "child session saved,
   delegated to root handler" — both arrive with the same root session-id.
3. Without knowing whether the root has saved, the handler cannot determine if the route
   transition is final, so it defers to a timer.

## Selected Design: Root-Save Gate + Outstanding Nav Counter

**Combines candidate directions 1 (correlated intents) and 2 (root commit acknowledgement).**

### Core Mechanism

Two changes eliminate the timer dependency:

#### Change 1: Root-Save Identity Signal

Modify `url-sync-on-save` to pass the **original saving session-id** alongside the root
session-id when delegating child saves:

```clojure
;; Current (ui_routes.cljc:681-691):
(handler root-sid wmem)          ;; handler can't tell who actually saved

;; Proposed:
(handler root-sid wmem saving-session-id)  ;; handler knows the actual saver
```

The `on-save-handler` closure (ui_routes.cljc:817) then gates browser-initiated nav
resolution on `(= saving-sid session-id)` — i.e., only resolves when the root session
itself saved. Child saves are ignored for nav resolution but still trigger programmatic
URL updates (Branch 3/4).

#### Change 2: Outstanding Nav Counter

A monotonic counter tracks how many browser-initiated navigations are "in flight" (event
sent but root save not yet observed):

```
popstate fires  →  outstanding += 1
root save fires →  outstanding -= 1
                    if outstanding == 0 → resolve acceptance/denial
                    if outstanding  > 0 → skip (more events in queue)
```

This handles **superseded popstates** (rapid back/forward) deterministically: only the
last navigation's root save triggers resolution, because all prior saves decrement the
counter but don't reach zero.

### Invariants

1. **Root-save finality**: When `save-working-memory!` fires for the root routing session
   after `process-event!`, the root's `::sc/configuration` is final. All state transitions,
   on-entry/on-exit scripts, and child invocations have completed (the async promise chain
   resolved before save).

2. **Sequential event processing**: Event queues (both manually-polled and core.async)
   process events sequentially per session. Two popstate-triggered events for the same
   session never overlap.

3. **Outstanding counter consistency**: The counter increments exactly once per popstate
   that passes the debounce filter, and decrements exactly once per root-save while
   `nav-state` is set. The counter can only go negative if a non-popstate event triggers
   a root save while nav-state is set — handled by clamping to zero.

## Sequence Diagrams

### Accepted Popstate (Happy Path)

```
Browser          popstate-fn       on-save-handler       Statechart
   |                  |                   |                   |
   |--popstate------->|                   |                   |
   |                  |  outstanding=1    |                   |
   |                  |  nav-state=set    |                   |
   |                  |  route-to!--------|------------------>|
   |                  |                   |                   |
   |                  |                   |    [async processing]
   |                  |                   |                   |
   |                  |                   |  child-save       |
   |                  |                   |<---(sid=child)----|
   |                  |                   |  saving-sid≠root  |
   |                  |                   |  SKIP             |
   |                  |                   |                   |
   |                  |                   |  root-save        |
   |                  |                   |<---(sid=root)-----|
   |                  |                   |  outstanding=0    |
   |                  |                   |  config-url =     |
   |                  |                   |    browser-url    |
   |                  |                   |  → ACCEPTED       |
   |                  |                   |  nav-state=nil    |
   |                  |                   |  prev-url=new-url |
```

### Denied Popstate (Busy Guard Blocks)

```
Browser          popstate-fn       on-save-handler       Statechart
   |                  |                   |                   |
   |--popstate------->|                   |                   |
   |                  |  outstanding=1    |                   |
   |                  |  nav-state=set    |                   |
   |                  |  route-to!--------|------------------>|
   |                  |                   |                   |
   |                  |                   |    [busy? = true]
   |                  |                   |    [transition to |
   |                  |                   |     routing-info/ |
   |                  |                   |     open]         |
   |                  |                   |                   |
   |                  |                   |  root-save        |
   |                  |                   |<---(sid=root)-----|
   |                  |                   |  outstanding=0    |
   |                  |                   |  config-url ≠     |
   |                  |                   |    browser-url    |
   |                  |                   |  → DENIED         |
   |                  |                   |  go-back!/forward!|
   |<--(history.back)-|-------------------|  restoring?=true  |
   |                  |                   |  on-route-denied  |
   |                  |                   |  nav-state=nil    |
   |--popstate------->|                   |                   |
   |                  |  restoring?=true  |                   |
   |                  |  consume silently |                   |
```

### Superseded Popstate (Rapid Back/Forward)

```
Browser          popstate-fn       on-save-handler       Statechart
   |                  |                   |                   |
   |--popstate-1----->|                   |                   |
   |                  |  outstanding=1    |                   |
   |                  |  nav-state=nav1   |                   |
   |                  |  route-to /A------|------------------>|
   |                  |                   |                   |
   |--popstate-2----->|  (>50ms later)    |                   |
   |                  |  outstanding=2    |                   |
   |                  |  nav-state=nav2   |                   |
   |                  |  route-to /B------|------------------>|
   |                  |                   |                   |
   |                  |                   |  root-save (evt1) |
   |                  |                   |<---(sid=root)-----|
   |                  |                   |  outstanding=1    |
   |                  |                   |  >0 → SKIP        |
   |                  |                   |                   |
   |                  |                   |  root-save (evt2) |
   |                  |                   |<---(sid=root)-----|
   |                  |                   |  outstanding=0    |
   |                  |                   |  resolve nav2:    |
   |                  |                   |  config-url = /B  |
   |                  |                   |  browser-url = /B |
   |                  |                   |  → ACCEPTED       |
```

## Detailed Changes

### 1. `url-sync-on-save` (ui_routes.cljc:666-691)

Add third parameter to handler calls — the actual saving session-id:

```clojure
(defn url-sync-on-save [saving-session-id wmem app]
  (let [{:keys [handlers child-to-root]} (url-sync-state app)]
    (if-let [handler (get handlers saving-session-id)]
      ;; Direct match: this IS the root session
      (handler saving-session-id wmem saving-session-id)     ;; <-- NEW: pass saving-sid
      ;; Child session: delegate to root handler
      (let [root-sid (or (get child-to-root saving-session-id)
                       (let [state-map (rapp/current-state app)
                             root      (find-root-session app state-map saving-session-id)]
                         (when root
                           (swap-url-sync! app update :child-to-root assoc saving-session-id root)
                           root)))]
        (when-let [handler (and root-sid (get handlers root-sid))]
          (handler root-sid wmem saving-session-id))))))      ;; <-- NEW: pass saving-sid
```

This is backward-compatible: existing handlers that accept `[sid wmem]` still work via
Clojure's arity tolerance (extra args are ignored in `(fn [a b & _])` patterns, and
the existing handler already uses `[_sid _wmem]` — it just gets an extra arg it ignores
until updated).

### 2. `install-url-sync!` on-save-handler (ui_routes.cljc:817-877)

Replace the handler closure:

```clojure
;; NEW atoms (replace denial-timer):
outstanding-navs (atom 0)          ;; replaces denial-timer

;; REMOVE:
;; denial-timer, do-denial-check

;; HANDLER:
on-save-handler
  (fn [_root-sid _wmem saving-sid]
    (let [root-save?     (= saving-sid session-id)      ;; <-- deterministic signal
          state-map      (rapp/current-state app)
          root-wmem      (get-in state-map [::sc/session-id session-id])
          configuration  (::sc/configuration root-wmem)]
      (when configuration
        (let [registry    (-> (rc/any->app app) ...)
              new-url     (route-url/deep-configuration->url ...)
              old-url     @prev-url
              nav         @nav-state
              browser-url (route-url/current-href provider)]
          (cond
            ;; === BROWSER-INITIATED: only resolve on root save ===

            ;; Root save + browser-initiated → resolve
            (and root-save? (:browser-initiated? nav))
            (let [remaining (swap! outstanding-navs dec)]
              (cond
                ;; More navs pending — skip (superseded)
                (pos? remaining) nil

                ;; Last nav resolved — check acceptance/denial
                (zero? remaining)
                (if (and new-url (= new-url browser-url))
                  ;; ACCEPTED
                  (do (reset! nav-state nil)
                      (reset! prev-url new-url))
                  ;; DENIED
                  (let [{:keys [popped-index pre-nav-index pre-nav-url]} nav]
                    (reset! nav-state nil)
                    (reset! restoring? true)
                    (if (< popped-index pre-nav-index)
                      (route-url/go-forward! provider)
                      (route-url/go-back! provider))
                    (reset! prev-url pre-nav-url)
                    (when on-route-denied (on-route-denied browser-url))))

                ;; Negative = spurious root save, clamp
                :else (reset! outstanding-navs 0)))

            ;; Child save + browser-initiated → skip (wait for root)
            (:browser-initiated? nav) nil

            ;; === PROGRAMMATIC NAVIGATION (no nav-state) ===

            ;; Initial load
            (and new-url (nil? old-url))
            (do (route-url/-replace-url! provider new-url)
                (reset! prev-url new-url))

            ;; URL changed programmatically
            (and new-url (not= new-url old-url))
            (do (route-url/-push-url! provider new-url)
                (reset! prev-url new-url)))))))
```

### 3. `do-popstate` (ui_routes.cljc:784-804)

Remove the `setTimeout` denial timer. Increment outstanding counter:

```clojure
do-popstate
  (fn [popped-index]
    (if @restoring?
      (reset! restoring? false)
      (do
        (swap! outstanding-navs inc)           ;; <-- NEW
        (let [pre-nav-url   @prev-url
              pre-nav-index (route-url/current-index provider)]
          (reset! nav-state {:browser-initiated? true
                             :pre-nav-index      pre-nav-index
                             :popped-index       popped-index
                             :pre-nav-url        pre-nav-url})
          (resolve-route-and-navigate! app elements-by-id provider)))))
          ;; No setTimeout — root save handles resolution
```

### 4. Cleanup function (ui_routes.cljc:889-897)

Remove `denial-timer` cleanup. Keep `debounce-timer` cleanup (it's UX, not correctness):

```clojure
(fn url-sync-cleanup! []
  (swap-url-sync! app update :handlers dissoc session-id)
  (swap-url-sync! app update :child-to-root ...)
  #?(:cljs (when-let [t @debounce-timer] (js/clearTimeout t)))
  (route-url/set-popstate-listener! provider nil))
```

## Rejected Alternatives

### Direction 3: Deterministic Queue Semantics (Latest-Wins with Intent Supersession)

**Concept**: The event queue would natively support "intent IDs" — a popstate tags its
route-to event with a nav-id, and the queue cancels older intents when a new one arrives.

**Rejected because**:
- Requires changes to the core `EventQueue` protocol (cross-cutting, high blast radius)
- The event queue is general-purpose; routing-specific semantics don't belong there
- The outstanding-nav-counter achieves the same supersession semantics at the URL sync layer
  without touching core infrastructure

### Direction 4: Explicit Routing Sync State Machine

**Concept**: A local finite state machine (`idle → pending → accepted/denied → restoring → idle`)
tracks the URL sync lifecycle, replacing ad-hoc atoms.

**Partially adopted, partially rejected**:
- The state transitions (`nav-state` atom serving as implicit state) already follow this
  pattern: `nil` = idle, `{:browser-initiated? true}` = pending, resolution = accepted/denied,
  `restoring?` = restoring
- A formal state machine (e.g., another statechart or a `case` dispatch) would add
  abstraction without solving the timing problem — the timer issue is about *when* to
  transition, not *which* transitions exist
- Could be revisited as a cleanup refactor after the core timing fix ships

### Pure Direction 1 (Nav-ID in Data Model)

**Concept**: Store a nav-id in the statechart data model via `ops/assign` so `on-save`
can read which event triggered the save.

**Rejected because**:
- Requires modifying the routing statechart definition (adding assign scripts to every
  route transition, including cross-chart transitions)
- Pollutes the data model with URL-sync-specific state
- The outstanding counter achieves the same correlation without touching the statechart

## Edge Cases and Mitigations

### Spurious Root Save During Navigation

If a non-popstate event is processed for the root session while `nav-state` is set (e.g.,
a programmatic `send!` to the routing session), the root save would decrement the counter.

**Mitigation**: Clamp outstanding-navs to zero on negative. The next root save (from the
actual popstate event) would see `nav-state` still set and `outstanding-navs = 0`, triggering
resolution. Worst case: one extra save cycle delay (microseconds, not 300ms).

### Root Save Never Fires

If `process-event!` fails catastrophically (throws, promise rejects), no root save occurs,
and `nav-state` remains set indefinitely.

**Mitigation**: Add a safety-net timeout (e.g., 5 seconds) that clears `nav-state` and
logs an error. This is NOT a correctness timer — it's a crash recovery heuristic. The
5-second value is intentionally generous and irrelevant to normal operation.

### Programmatic Navigation During Browser Nav

If `route-to!` is called programmatically while a popstate is pending, the root save for
the programmatic event sees `(:browser-initiated? nav)` = true and skips Branch 3/4. The
programmatic URL update is deferred until after the popstate resolves.

**Mitigation**: Acceptable — the browser nav takes priority since the user is actively
navigating. The programmatic route change will fire its own event, get its own root save,
and push the URL on the next cycle.

## Test Matrix

### Unit Tests (SimulatedURLHistory, sync processing)

| # | Scenario | Setup | Assert |
|---|----------|-------|--------|
| 1 | Accepted popstate | Navigate to non-busy route via `go-back!` | config matches, prev-url updated, no restoration |
| 2 | Denied popstate | Navigate to busy route via `go-back!` | config unchanged, `go-forward!` called, `on-route-denied` invoked |
| 3 | Superseded popstate (2 rapid) | Two `go-back!` calls >50ms apart | Only last nav resolves, intermediate skipped |
| 4 | Force-continue after denial | Denied, then `force-continue-routing!` | Route accepted, URL matches |
| 5 | Programmatic during browser nav | `go-back!` then `route-to!` before save | Browser nav resolves first, programmatic URL pushes after |
| 6 | Child save before root save | istate with child chart, popstate | Child save ignored, root save resolves |

### Race-Oriented Tests

| # | Race Class | Injection | Assert |
|---|-----------|-----------|--------|
| R1 | Slow async processing | Delay root save by inserting sleep in on-entry | No timer-based false positive; resolves when root saves |
| R2 | Burst navigation (5 rapid popstates) | Programmatic 5x `go-back!` at 60ms intervals | Last nav wins, counter reaches 0 on final save |
| R3 | Interleaved programmatic + browser | `go-back!` → `route-to!` → root save | Browser nav resolves correctly, programmatic queued |
| R4 | Denial + immediate re-nav | `go-back!` (denied) → `go-forward!` before restoration completes | `restoring?` flag prevents double-undo |

### Integration Tests (CLJS, real async)

| # | Scenario | Notes |
|---|----------|-------|
| I1 | Child invocation async save ordering | Verify child saves don't trigger resolution |
| I2 | Browser back + dirty form | Real form-state dirty check via busy guard |
| I3 | Deep nested istate URL sync | 3-level chart hierarchy, popstate at leaf level |

## Migration Strategy

### Phase 1: Add Saving-Session-ID to Handler (Non-Breaking)

1. Modify `url-sync-on-save` to pass the third `saving-session-id` argument
2. Existing handlers still work (extra arg ignored)
3. Ship independently, no behavior change

### Phase 2: Replace Timer with Root-Save Gate

1. Add `outstanding-navs` atom
2. Update `on-save-handler` to use root-save gate + counter
3. Remove `denial-timer` atom and `do-denial-check` function
4. Remove `setTimeout` call in `do-popstate`
5. Keep `debounce-timer` (50ms) — this is UX smoothing, not correctness

### Phase 3: Add Safety-Net Timeout (Optional)

1. Add a generous (5s) crash-recovery timeout
2. Only fires if `nav-state` stays set for 5s without resolution
3. Logs error, clears nav-state, does NOT attempt denial/acceptance

### Rollback Plan

Each phase is independently revertible:
- Phase 1: Remove third arg from `url-sync-on-save` calls
- Phase 2: Re-add `denial-timer` and `do-denial-check`, restore `setTimeout` in `do-popstate`
- Phase 3: Remove the safety-net timeout

The 50ms popstate debounce is unchanged throughout, providing a safety floor for UX.
