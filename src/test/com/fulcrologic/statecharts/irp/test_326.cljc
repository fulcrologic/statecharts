(ns com.fulcrologic.statecharts.irp.test-326
  "IRP test 326 — _ioprocessors cannot be reassigned; the value stays constant.

   SKIPPED: this library does not expose _ioprocessors, so the precondition
   (Var1 is bound) fails and no meaningful assertion can be made.

   Source: https://www.w3.org/Voice/2013/scxml-irp/326/test326.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 326 — _ioprocessors cannot be reassigned" :irp/skip
  (assertions
    "skipped: library does not expose _ioprocessors"
    true => true))
