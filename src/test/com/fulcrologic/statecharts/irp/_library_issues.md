# Library Issues from IRP Tests

Library deviations from W3C SCXML 2015-09-01 found while porting the IRP test suite.
The coordinator decides whether/when to fix.

## Test 159 — error in executable content does not skip subsequent siblings

- Source: https://www.w3.org/Voice/2013/scxml-irp/159/test159.txml
- Test file: `src/test/com/fulcrologic/statecharts/irp/test_159.cljc`

### Expected (W3C SCXML §4.4)

When an executable-content element raises an error, the SCXML processor MUST skip
all remaining executable-content elements in the same block. In test 159 a `<send>`
with an illegal target should raise `error.execution`; the following increment of
`Var1` must therefore not run, leaving `Var1 == 0` so the chart routes to `pass`.

W3C 6.2 also requires that if the processor cannot deliver a `<send>` (unsupported
type or invalid target) it MUST place `error.execution` on the internal queue.

### Actual

Two related deviations:

1. `:send` handler at `src/main/com/fulcrologic/statecharts/algorithms/v20150901_impl.cljc:335-340`
   does not validate the target/type at execution time. It calls `sp/send!` and only
   raises `error.execution` if `send!` returns falsy. Bad targets/types are silently
   queued (or rejected later by the queue) — no error is raised during the executable
   block, so subsequent siblings run.
2. `run-many!` at `src/main/com/fulcrologic/statecharts/algorithms/v20150901_impl.cljc:635-647`
   wraps each element in its own `try`/`catch` and continues with the next element on
   exception. Per §4.4 it must abort the rest of the block whenever the current element
   raises an error (whether by exception or by queueing `error.execution`).

### Suspected fix sites

- `v20150901_impl.cljc:335` — validate `target` / `type` against registered IO processors;
  raise `error.execution` synchronously instead of (or in addition to) queueing.
- `v20150901_impl.cljc:635` — change `run-many!` to halt the loop after any element
  raises an error in the current block.

## Test 189 — `<send target="#_internal">` not supported

- Source: https://www.w3.org/Voice/2013/scxml-irp/189/test189.txml
- Test file: not yet ported (blocked by missing feature)

### Expected (W3C SCXML §6.2)

`<send target="#_internal">` MUST place the event on the internal event queue, so
internal-queued events take priority over external events in the next macrostep.

### Actual

The `:send` handler at `v20150901_impl.cljc:335` does not special-case the
`#_internal` target string. The event is dispatched through the event-queue
processor like any other Send, ending up on the external queue. Internal queueing
is only reachable via `raise` (the `<raise>` element / `raise` helper).

### Suspected fix sites

- `v20150901_impl.cljc:303-340` (the `send!` letfn / `:send` defmethod) — when
  `target` resolves to `"#_internal"` (or `_internal`), append to
  `::sc/internal-queue` directly instead of dispatching to the event queue.

## Tests 191 / 192 — `#_parent` and `#_<invokeid>` send-target prefixes not parsed

Send-target parsing for `#_parent` and `#_<invokeid>` is still needed for
non-inline invocations (e.g., a parent chart invoking a registered statechart by
registry key). `v20150901_impl.cljc:303-340` must be extended to resolve these
prefixes to the corresponding session ids regardless of how the child was started.
The IRP tests that exercise these targets in combination with *inline*
`<invoke><content>` are intentionally out of scope and have been moved to
`_skipped.md`.

## Test 198 — `_event.origintype` not populated

- Source: https://www.w3.org/Voice/2013/scxml-irp/198/test198.txml
- Test file: not yet ported (blocked by missing field)

### Expected (W3C SCXML §5.10.1)

For events delivered as the result of a `<send>`, `_event.origintype` MUST be set
to the type of IO Processor that received the send. When `<send>` has no explicit
`type`, this is the SCXML Event I/O Processor URL
(`http://www.w3.org/TR/scxml/#SCXMLEventProcessor`).

### Actual

`origintype` is declared in `specs.cljc:56` and `malli_specs.cljc:57` but never
populated: no occurrences of `origintype` exist in `src/main` outside the specs.
Neither `v20150901_impl.cljc:send!` nor `event_queue/manually_polled_queue.cljc`
sets it on outgoing/delivered events.

### Suspected fix sites

- `v20150901_impl.cljc:303-340` — record `:origintype` (default the SCXML
  processor URL/keyword when no `type` provided) on the dispatched event.
- The event queue / IO processor delivery path also needs to ensure the field
  survives onto `_event` when the event is dequeued.

## Test 312 — error.execution from executable content goes to external queue

- Source: https://www.w3.org/Voice/2013/scxml-irp/312/test312.txml
- Test file: `src/test/com/fulcrologic/statecharts/irp/test_312.cljc`

### Expected (W3C SCXML §4.4 / §5.10)

When executable content (`<assign>` here) raises `error.execution`, the event
MUST be placed on the INTERNAL queue. With a subsequent `<raise event="foo"/>`
in the same on-entry, the error.execution should be queued first, dequeued
first, and route to pass.

### Actual

`v20150901_impl.cljc:97` (`run-expression!` catch branch) calls
`(sp/send! event-queue ...)` — the EXTERNAL queue. The internally-raised
`foo` arrives first via the internal queue and matches the wildcard
transition before error.execution is ever processed.

### Suspected fix sites

- `v20150901_impl.cljc:86-102` — on expression failure, `vswap! vwmem update
  ::sc/internal-queue conj (evts/new-event {:name :error.execution ...})`
  instead of routing through the external event queue.

## Test 332 — `error.execution` from bad `<send>` target missing sendid (159 family)

- Source: https://www.w3.org/Voice/2013/scxml-irp/332/test332.txml
- Test file: `src/test/com/fulcrologic/statecharts/irp/test_332.cljc`

### Expected (W3C SCXML §5.10 / §6.2)

When `<send>` cannot be delivered (e.g. unknown/invalid target), the resulting
`error.execution` event MUST be on the internal queue and MUST carry
`_event.sendid` matching the original send's id (from `idlocation` or `id`).

### Actual

`v20150901_impl.cljc:303-340` calls `sp/send!` and only raises `error.execution`
if `send!` returns falsy. The `manually-polled-queue` implementation returns
truthy even for bogus targets like `"bogus://nowhere"`, so no error event is
fired and the machine stays in `s0`. Even when an error is raised here, the
emitted event does not include `:sendid` matching the originating send.

### Suspected fix sites

- `v20150901_impl.cljc:303-340` — when validation fails (target/type unknown),
  raise an `error.execution` onto the internal queue with `:sendid` set to the
  send's id (or the value just stored in `idlocation`).
- Same site as Tests 159 / 312 — coordinator should fix as one unit.










## Test 333 — `_event.sendid` exposed for auto-generated sendids

- Source: https://www.w3.org/Voice/2013/scxml-irp/333/test333.txml
- Test file: `src/test/com/fulcrologic/statecharts/irp/test_333.cljc`

### Expected (W3C SCXML §5.10.1)

`_event.sendid` MUST be absent (null/blank) when the `<send>` element does not have an explicit `id` attribute. Only explicitly-specified send ids should be exposed to the data model.

### Actual

The `Send` handler auto-generates a sendid (`:send<N>`) for all sends and includes it in the outgoing event map as `:sendid`. When the event is stored as `_event` in the data model, the auto-generated sendid is visible. Condition `(nil? (get-in d [:_event :sendid]))` fails.

### Suspected fix site

- `v20150901_impl.cljc` `:send` defmethod: only populate `:sendid` in the dispatched event when the `<send>` element has an explicit `:id` attribute. The internal auto-generated id can still exist for cancellation but should not be placed on the `:sendid` field visible to the data model.

## Test 336 — `_event.origin` not populated for sent events

- Source: https://www.w3.org/Voice/2013/scxml-irp/336/test336.txml
- Test file: `src/test/com/fulcrologic/statecharts/irp/test_336.cljc`

### Expected (W3C SCXML §5.10.1)

`_event.origin` MUST be set for external events delivered as the result of a `<send>`. The value is a URI that can be used as the `target` of a subsequent `<send>` to reply to the originator.

### Actual

`_event.origin` is never populated in the library. Events stored as `_event` in the data model always have `nil` for `:origin`.

### Suspected fix site

- `v20150901_impl.cljc` send/deliver path: populate `:origin` with a `#_scxml_<session-id>` URI (or a library-specific equivalent) when queueing external events so the recipient can reply.

## Test 372 — compound state with only final children ignores :initial attribute

- Source: https://www.w3.org/Voice/2013/scxml-irp/372/test372.txml
- Test file: `src/test/com/fulcrologic/statecharts/irp/test_372.cljc`

### Expected (W3C SCXML §3.10)

A compound state with `initial="s0final"` where `s0final` is a `<final>` child
MUST enter `s0final` on startup, execute its on-entry, then generate `done.state.s0`.

### Actual

`chart.cljc:52` — `with-default-initial-state` filters children with:
`(filter #(#{:state :parallel} (:node-type %)) children)`. It excludes `:final`
nodes. When the only child states are finals, `states` is empty, the
`:else` clause is never reached, and no synthetic `<initial>` element is
created. `chart/initial-element` returns nil, so the compound state's
initial child is never entered.

### Suspected fix site

- `chart.cljc:52` — change filter to
  `#(#{:state :parallel :final} (:node-type %))` so that a final child can serve
  as the initial target of a compound state.

## Test 152 — `<foreach>` does not raise `error.execution` on illegal array/item

- Source: https://www.w3.org/Voice/2013/scxml-irp/152/test152.txml
- Test file: `src/test/com/fulcrologic/statecharts/irp/test_152.cljc`

### Expected (W3C SCXML §3.12.2)

When the `array` expression returns a non-iterable value, or the `item` location
is invalid, the processor MUST place `error.execution` on the internal queue and
skip the body. The chart transitions on `error.execution` to verify this.

### Actual

`v20150901_impl.cljc:399-409` (`:for-each` defmethod) calls `doseq` directly on
the result of `array`. When `array` returns a non-collection (e.g., an integer),
`doseq` throws a `ClassCastException`. The outer `execute!` at line 418-424
catches the exception, logs it, and continues — no `error.execution` event is
placed on the internal queue. The chart never receives `error.execution` and
falls through to `:fail`.

### Suspected fix sites

- `v20150901_impl.cljc:399` — validate that the `array` result satisfies `coll?`
  before iterating; if not, place `error.execution` on the internal queue and
  return without executing the body (same fix family as Tests 159 / 312).
- Also validate that `item` (when non-nil) is a valid assignable location before
  starting iteration.

## Test 156 — error inside `<foreach>` body does not stop iteration

- Source: https://www.w3.org/Voice/2013/scxml-irp/156/test156.txml
- Test file: `src/test/com/fulcrologic/statecharts/irp/test_156.cljc`

### Expected (W3C SCXML §3.12.2)

When executable content inside a `<foreach>` body raises an error, the processor
MUST stop iterating and place `error.execution` on the internal queue. With array
[1,2,3], only the first increment should run, leaving Var1 = 1.

### Actual

`v20150901_impl.cljc:399-409` uses plain `doseq` with no per-element or
per-iteration error handling. An exception thrown by a child element propagates
up through `doseq` and is caught by the outer `execute!` at line 418-424 (which
logs and swallows it). However, `run-expression!` at line 86-102 also catches
the exception and attempts to call `sp/update!` with a nil result (second
secondary error). The result is that all 3 iterations run (Var1 = 3) despite
the exception, so the transition to `:pass` (Var1 = 1) never fires.

### Suspected fix sites

- `v20150901_impl.cljc:399` — wrap the inner `doseq` in a try/catch; on
  exception, place `error.execution` on the internal queue and `return` (break
  out of the foreach entirely). This is the same fix family as Tests 159 / 312.

## Test 554 — `<invoke>` argument-evaluation error not handled; invocation not cancelled

- Source: https://www.w3.org/Voice/2013/scxml-irp/554/test554.txml
- Test file: `src/test/com/fulcrologic/statecharts/irp/test_554.cljc`

### Expected (W3C SCXML §6.4)

When evaluation of any of an `<invoke>`'s argument expressions (`namelist`,
`<param>`/`:params`, `srcexpr`, `typeexpr`) raises an error, the processor MUST:
1. Cancel the invocation (do NOT start it; do NOT emit `done.invoke`).
2. Place `error.execution` on the internal queue.
3. Continue running normally so subsequent transitions / events still fire.

The IRP test verifies (1) by sending a 1-tick timer and watching for `done.invoke`
arriving from the (incorrectly) started child invocation. If no `done.invoke`
arrives the timer fires and we route to `:pass`.

### Actual

Two related deviations:

1. `v20150901_impl.cljc:678-687` (`run-invocations!`) iterates and calls
   `start-invocation!` directly with no try/catch. A throwing `:params` /
   `:srcexpr` / `:typeexpr` propagates out of `start-invocation!`, out of
   `run-invocations!`, out of `before-event!`, and aborts `start!` entirely —
   the chart never even reaches a stable configuration. Running `test_554` today
   surfaces an uncaught `ExceptionInfo` from `lambda.cljc:16` (the execution
   model's `run-expression!`).
2. `v20150901_impl.cljc:71-84` (`named-data`) silently drops any namelist entry
   whose source location returns `nil` rather than raising `error.execution`,
   so a strictly "invalid namelist" cannot be modelled directly. (The W3C test
   uses `conf:invalidNamelist` — a namelist referring to an undeclared variable,
   which strict ECMAScript would throw on. Translating it to a throwing `:params`
   exposes deviation #1 instead.)

### Suspected fix sites

- `v20150901_impl.cljc:635-676` (`start-invocation!` letfn) — wrap the param-map
  evaluation and the call to `sp/start-invocation!` in try/catch. On exception,
  do NOT call `sp/start-invocation!`, place `error.execution` on the internal
  queue (same fix family as Tests 159 / 312 / 152 / 156), and return. The
  invocation is thereby cancelled per §6.4.
- `v20150901_impl.cljc:625-633` (`invocation-details`) — the `srcexpr` / `typeexpr`
  evaluation here is also unguarded. Either wrap there too, or move that
  evaluation inside the same try/catch in `start-invocation!`.
- `v20150901_impl.cljc:71-84` (`named-data`) — consider raising `error.execution`
  when a namelist entry references an unbound location, to match strict
  ECMAScript semantics. (Optional / lower priority — this library's data model
  is intentionally permissive.)

### Runner gap

The IRP runner registers exactly one chart (the chart under test) and exposes no
way to register additional helper charts. Test 554 needs a "trivially-completing"
child chart so that, if the bug above were absent, the invocation could
demonstrably emit `done.invoke`. The current test uses a non-existent registry
key (`:irp/child-554`); even if the param-eval bug is fixed, exercising the
positive path (`done.invoke → :fail`) will require either:
- extending the runner to accept extra registrations, OR
- registering a child chart from inside the parent's on-entry (hacky — relies on
  reaching env at expression time).

This was deferred per the porting briefing ("STOP if you would need to extend
the runner — document the gap and report back").

## Test 402 — error.execution emitted twice when an executable expression throws in strict mode

- Source: https://www.w3.org/Voice/2013/scxml-irp/402/test402.txml
- Test file: `src/test/com/fulcrologic/statecharts/irp/test_402.cljc`

### Expected (W3C SCXML §4.4)

An executable element that fails (e.g. `<assign>` with an illegal expression) must
queue exactly ONE `error.execution` event on the internal queue. Test 402 relies on
a precise queue ordering: after `s01` onentry runs `<raise event="event1"/>` then a
failing `<assign>`, the internal queue must be `[event1, error.execution]`. The
chart then transitions s01→s02 on `event1` (which raises `event2`), s02→s03 on the
prefix `error` (matching `error.execution`), and finally s03→pass on `event2`.

### Actual

`error.execution` is queued twice when a single expression throws.

1. `run-expression!` at `v20150901_impl.cljc:96-102` catches the throw, raises
   `error.execution`, and (in strict mode) rethrows.
2. The rethrown exception is caught again by `execute!` at
   `v20150901_impl.cljc:465-474`, which raises a SECOND `error.execution` and then
   honours the strict-mode abort.

So the actual queue is `[event1, error.execution, error.execution, …]`. After
event1 → s02 (which raises event2) the queue becomes
`[error.execution, error.execution, event2]`. s02 consumes the first
error.execution → s03; s03 then sees the second error.execution before event2,
matches the wildcard transition, and goes to `fail`.

The same async-impl pattern at `v20150901_async_impl.cljc:152` and `:567` exhibits
the same double-emit.

### Suspected fix sites

- `v20150901_impl.cljc:465-474` (and the async equivalent at
  `v20150901_async_impl.cljc:560-575`) — the outer `execute!` catch should NOT
  re-raise `error.execution` if the inner code already did. Either tag the rethrown
  exception (e.g. `(ex-info ... {::already-reported? true})`) and check for that
  flag here, or only raise `error.execution` here for exceptions that escape paths
  other than `run-expression!`.
- Alternatively, have `run-expression!` rethrow a sentinel exception type that
  `execute!` recognises as "error already reported, just abort".
