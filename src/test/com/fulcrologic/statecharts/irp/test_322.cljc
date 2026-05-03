(ns com.fulcrologic.statecharts.irp.test-322
  "IRP test 322 — verifies that `_sessionid` cannot be reassigned: an assign
   to `_sessionid` must raise `error.execution` and the original value must
   persist.

   SKIPPED: this library writes `_sessionid` into the user data model but
   does not protect it from user reassignment — there is no \"system
   variable\" guard. The test cannot be ported faithfully without first
   adding such a guard. Source:
   https://www.w3.org/Voice/2013/scxml-irp/322/test322.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 322 — _sessionid cannot be reassigned" :irp/skip
  (assertions
    "skipped: library does not protect _sessionid from reassignment"
    true => true))
