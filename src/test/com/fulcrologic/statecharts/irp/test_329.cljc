(ns com.fulcrologic.statecharts.irp.test-329
  "IRP test 329 — verifies that none of the SCXML system variables
   (`_sessionid`, `_event`, `_name`, `_ioprocessors`) can be modified.

   SKIPPED: this library does not protect system variables from
   reassignment, and `_ioprocessors` is not exposed at all. Same root cause
   as 322, 324, 325, 326. Source:
   https://www.w3.org/Voice/2013/scxml-irp/329/test329.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 329 — system variables are read-only" :irp/skip
  (assertions
    "skipped: library does not protect system variables"
    true => true))
