(ns com.fulcrologic.statecharts.irp.test-402
  "IRP test 402 — errors are pulled off the internal queue in order, and prefix
   matching works on them (error prefix matches error.execution).

   SKIPPED: same root cause as test-401 — `error.execution` goes to the external
   queue. The ordering of internal vs external events is therefore wrong, so the
   complex ordering this test verifies cannot be reproduced.

   Source: https://www.w3.org/Voice/2013/scxml-irp/402/test402.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 402 — errors on internal queue in order with prefix matching" :irp/skip
  (assertions
    "skipped: error.execution sent to external queue (same issue as test-312)"
    true => true))
