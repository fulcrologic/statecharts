# Event Queue — CLAUDE.md

Manages external event delivery between sessions. Protocol-based.

## Implementations
- **`manually_polled_queue`** — Synchronous, call `process-next-event!` explicitly. Good for testing and simple use. Well-tested.
- **`core_async_event_loop`** — Async, production runtime. **Zero test coverage — critical gap.**
- Custom via EventQueue protocol

## Key Protocol Methods
- `send!` — Enqueue event (with optional delay, target session)
- `cancel!` — Cancel delayed event by send-id
- `process-next-event!` — (manual queue only) Process one event

## Delayed Events
- `send` element supports `delay`/`delayexpr` attributes
- Delayed events stored with target timestamp: `(+ now delay)`
- Non-delayed events use `(dec now)` to be immediately available (magic number, undocumented)

## Inter-Session Communication
Events routed by session-id. Cross-chart communication uses `send` with target session.

## Gotcha
The `(dec now)` trick in `send!` of manually_polled_queue.cljc makes non-delayed events "past due" so they're always picked up. This is intentional but not documented.
- `has-due-events?` and `receive-events!` share the private `due?` (delivery time <= now), so a drain loop on
  `has-due-events?` stops exactly when `receive-events!` would deliver nothing.
- `next-event-time` is broken: `(get event-queue [:session-queues session-id])` is always nil (and the queue is an
  atom). Unused in the lib; not fixed.
- `receive-events!` takes each event off just before its handler runs, so a cancel during an earlier
  event's handler works; option `::session-held?` (checked first, then before each event) leaves a held session's due
  events queued, in order. A handler returning `::requeue` puts its event back (by send order) and stops its session
  for the call. It iterates a snapshot seq of the session's queue: events queued meanwhile wait for a later call.
- The per-session value is a `PersistentQueue` (O(1) head pop) until an event comes off elsewhere (a due one
  behind a not-due delayed one, a requeue), then a sorted set by `::send-order` meta (O(log n)) until it empties;
  empty sessions are dropped. `::send-order` comes from `::last-send-order` in the outer map's META (one swap with the
  insert). Never go back to an O(n) vector copy per take-off (10k events: 0.5 s).
