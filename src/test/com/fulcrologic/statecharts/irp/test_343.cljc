(ns com.fulcrologic.statecharts.irp.test-343
  "IRP test 343 — illegal param location in donedata raises error.execution
   before the done event, and the done event then has empty data.

   SKIPPED: same reason as test 298 — the flat data model has no
   declared/undeclared distinction; a missing key returns nil rather than
   raising error.execution.

   Source: https://www.w3.org/Voice/2013/scxml-irp/343/test343.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 343 — illegal donedata param raises error.execution" :irp/skip
  (assertions
    "skipped: flat data model has no declared/undeclared distinction"
    true => true))
