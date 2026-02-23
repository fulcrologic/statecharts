# Invocation — CLAUDE.md

Handles `invoke` elements — starting external services/nested charts from within states.

## Implementations
- **`statechart.cljc`** — Invoke nested statechart definitions. Cross-platform.
- **`future.clj`** — CLJ-only: invoke JVM futures for async work.
- Custom via InvocationProcessor protocol

## Lifecycle
1. State entered → `start-invocation!` called
2. Child events → `forward-event!` (if autoforward) + `finalize`
3. Child completes → `done.invoke.*` event sent to parent
4. State exited → `stop-invocation!` called (cancels active invocations)

## Known Issues
- **Zero test coverage** — Most critical testing gap in the library
- `statechart.cljc` — Returns `false` when chart not found (sends error.platform event). Returns `true` (sync) or `Promise<true>` (async) on success.
- `future.clj:44` — Potential race condition between `future-cancel` and `finally` cleanup
- No HTTP Event I/O Processor (SCXML spec section 6.2)

## Protocol Methods
- `supports-invocation-type?` — Check if processor handles this type
- `start-invocation!` — Begin invocation
- `stop-invocation!` — Clean up invocation
- `forward-event!` — Forward parent events to child (autoforward)
