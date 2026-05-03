(ns com.fulcrologic.statecharts.irp.test-324
  "IRP test 324 — verifies that `_name` cannot be reassigned: an attempt to
   assign to it must raise `error.execution` and the value must persist.

   SKIPPED: this library writes `_name` into the user data model but does
   not protect it from user reassignment. Same reason as test 322. Source:
   https://www.w3.org/Voice/2013/scxml-irp/324/test324.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 324 — _name cannot be reassigned" :irp/skip
  (assertions
    "skipped: library does not protect _name from reassignment"
    true => true))
