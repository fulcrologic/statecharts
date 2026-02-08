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
The `(dec now)` trick in manually_polled_queue.cljc:42 makes non-delayed events "past due" so they're always picked up. This is intentional but not documented.
