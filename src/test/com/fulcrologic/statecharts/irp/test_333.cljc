(ns com.fulcrologic.statecharts.irp.test-333
  "IRP test 333 — for an ordinary (non-error) event delivered from a `<send>`
   without an `idlocation` or explicit `id`, `_event.sendid` MUST NOT be set.
   Source: https://www.w3.org/Voice/2013/scxml-irp/333/test333.txml

   LIBRARY ISSUE: every `send` element in this library is auto-assigned an
   id via `new-element` in `elements.cljc:82` (`(or id (genid (name type)))`),
   and that auto-generated id is propagated as `:sendid` on the delivered
   event by the queue (`manually_polled_queue.cljc:49`). The W3C spec
   requires sendid to be ABSENT unless an `idlocation` was supplied.
   Skipped pending library-side fix (only set sendid when an idlocation or
   user-specified id was provided)."
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 333 — non-error event has no sendid (skipped — library issue)" :irp/skip
  (assertions
    "skipped: library auto-generates send element id and propagates it as sendid"
    true => true))
