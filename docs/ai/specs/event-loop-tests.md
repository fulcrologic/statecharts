# Spec: Add Event Loop Tests

**Status**: done
**Priority**: P2
**Created**: 2026-02-08
**Owner**: event-loop-tester
**Completed**: 2026-02-09

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

1. [x] Events delivered to correct sessions
2. [x] Delayed events fire after specified delay
3. [x] Cancelled events don't fire
4. [x] Loop processes events in order
5. [x] Loop handles errors without crashing
6. [x] Loop shuts down cleanly

## Implementation Notes

Test file created at `src/test/com/fulcrologic/statecharts/event_queue/core_async_event_loop_spec.cljc` with comprehensive coverage:

- **Protocol satisfaction tests**: Verifies EventQueue protocol implementation
- **Event delivery**: Tests immediate event processing with processor mocks
- **Delayed events**: Uses short delays (100ms) to verify timing behavior
- **Event cancellation**: Tests cancel! functionality
- **Loop shutdown**: Verifies run-event-loop! starts/stops correctly
- **Error handling**: Confirms processor exceptions don't crash the loop

All 18 assertions pass successfully. Tests use fulcro-spec with proper mocking of Processor and WorkingMemoryStore protocols.
