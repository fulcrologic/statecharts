# Spec: Add Event Loop Tests

**Status**: backlog
**Priority**: P2
**Created**: 2026-02-08
**Owner**: conductor

## Context

The core.async event loop (`event_queue/core_async_event_loop.cljc`) has zero test coverage. This is the primary production runtime for event processing. Only the manually polled queue has tests.

## Requirements

1. Test `new-queue` creation
2. Test `start-event-loop!` lifecycle
3. Test event delivery through the loop
4. Test delayed event handling
5. Test event cancellation
6. Test error handling during event processing
7. Test loop shutdown behavior
8. Test concurrent event processing

## Affected Modules

- `src/test/com/fulcrologic/statecharts/event_queue/` - New test file
- `src/main/com/fulcrologic/statecharts/event_queue/core_async_event_loop.cljc` - Under test

## Verification

1. [ ] Events delivered to correct sessions
2. [ ] Delayed events fire after specified delay
3. [ ] Cancelled events don't fire
4. [ ] Loop processes events in order
5. [ ] Loop handles errors without crashing
6. [ ] Loop shuts down cleanly
