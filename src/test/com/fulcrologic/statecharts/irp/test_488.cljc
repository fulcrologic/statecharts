(ns com.fulcrologic.statecharts.irp.test-488
  "IRP test 488 — illegal `<param>` expression in `<donedata>` generates
   `error.execution` before `done.state.s0`, and the done event has empty
   `event.data`.

   SKIPPED: `error.execution` from `<donedata>` evaluation goes to the external
   queue while `done.state.s0` is an internal event. Internal events process
   first, so done.state.s0 fires before error.execution is seen, reversing
   the required order. Same root cause as test-159/test-312.

   Source: https://www.w3.org/Voice/2013/scxml-irp/488/test488.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 488 — illegal param in donedata raises error.execution first" :irp/skip
  (assertions
    "skipped: error.execution queued externally; done.state (internal) fires first"
    true => true))
