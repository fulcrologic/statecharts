(ns com.fulcrologic.statecharts.irp.test-346
  "IRP test 346 — assigning to any system variable (_sessionid, _event,
   _ioprocessors, _name) raises error.execution.

   SKIPPED: same reason as tests 322/324/326 — this library writes system
   variables into the user data model but does not protect them from
   reassignment.

   Source: https://www.w3.org/Voice/2013/scxml-irp/346/test346.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 346 — system variable assignment raises error.execution" :irp/skip
  (assertions
    "skipped: library does not protect system variables from reassignment"
    true => true))
