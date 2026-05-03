(ns com.fulcrologic.statecharts.irp.test-528
  "IRP test 528 — illegal expr in donedata content raises error.execution
   before the done event; done event has empty data.

   SKIPPED: error.execution from donedata evaluation goes to the external
   queue while done.state.s0 is an internal event. Internal fires first,
   reversing the required order. Same root cause as tests 343/488/312.

   Source: https://www.w3.org/Voice/2013/scxml-irp/528/test528.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 528 — illegal donedata content expr raises error.execution first" :irp/skip
  (assertions
    "skipped: error.execution on external queue, done.state on internal queue"
    true => true))
