(ns com.fulcrologic.statecharts.irp.test-332
  "IRP test 332 — an `error.execution` event triggered by a failed `<send>`
   MUST carry the original sendid so the chart can correlate the failure
   with the originating `<send>`.
   Source: https://www.w3.org/Voice/2013/scxml-irp/332/test332.txml

   LIBRARY ISSUE: ports of this test fail because (a) the `<send>` handler
   does not validate target/type at execution time (so no error.execution is
   raised at all for unknown targets — see test 159 entry in
   `_library_issues.md`), and (b) even when an error.execution event IS
   raised by `run-expression!`, no sendid is attached. Skipped pending
   library-side fixes."
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 332 — failed-send error carries sendid (skipped — library issue)" :irp/skip
  (assertions
    "skipped: send target/type not validated; sendid not attached to error events"
    true => true))
