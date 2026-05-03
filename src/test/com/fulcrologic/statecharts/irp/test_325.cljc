(ns com.fulcrologic.statecharts.irp.test-325
  "IRP test 325 — verifies that the system variable `_ioprocessors` is bound
   at startup.

   SKIPPED: this library does not expose `_ioprocessors` in the data model
   (the set of I/O processors is not part of the core API). Source:
   https://www.w3.org/Voice/2013/scxml-irp/325/test325.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 325 — _ioprocessors bound at startup" :irp/skip
  (assertions
    "skipped: library does not expose _ioprocessors"
    true => true))
