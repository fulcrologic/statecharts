(ns com.fulcrologic.statecharts.irp.test-189
  "IRP test 189 — `<send target=\"#_internal\">` must place the event on the
   internal queue (so it is processed before any pending external events).
   Source: https://www.w3.org/Voice/2013/scxml-irp/189/test189.txml

   SKIPPED: this library's `<send>` always routes through the event queue
   (external). There is no special-case handling of `#_internal` as a target,
   so a faithful port cannot be written without library changes. (`raise`
   exists for internal queueing but the test specifically exercises `<send
   target=\"#_internal\">`.)"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 189 — send target=#_internal queues internally (skipped)" :irp/skip
  (assertions
    "skipped: library has no target=#_internal special case for <send>"
    true => true))
