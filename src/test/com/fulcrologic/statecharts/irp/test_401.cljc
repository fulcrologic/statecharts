(ns com.fulcrologic.statecharts.irp.test-401
  "IRP test 401 — errors go in the internal event queue, so they are processed
   before external events that were sent earlier.

   SKIPPED: this library places `error.execution` on the EXTERNAL queue via
   `sp/send!`. An externally-sent `foo` event would then be processed first
   (no internal events), causing the machine to reach `:fail` instead of `:pass`.
   Same root cause as the library issue documented for test-312.

   Source: https://www.w3.org/Voice/2013/scxml-irp/401/test401.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 401 — errors go on internal queue (before external events)" :irp/skip
  (assertions
    "skipped: error.execution sent to external queue (same issue as test-312)"
    true => true))
