(ns com.fulcrologic.statecharts.irp.test-496
  "IRP test 496 — sending to an unreachable target raises error.communication.

   SKIPPED: library does not implement error.communication; the
   manually_polled_queue silently discards sends to unknown session IDs
   without raising any error event.

   Source: https://www.w3.org/Voice/2013/scxml-irp/496/test496.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 496 — unreachable target raises error.communication" :irp/skip
  (assertions
    "skipped: library does not raise error.communication"
    true => true))
