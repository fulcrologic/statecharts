(ns com.fulcrologic.statecharts.irp.test-501
  "IRP test 501 — _ioprocessors location entry usable as send target.

   SKIPPED: library does not expose _ioprocessors.

   Source: https://www.w3.org/Voice/2013/scxml-irp/501/test501.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 501 — _ioprocessors location as send target" :irp/skip
  (assertions
    "skipped: library does not expose _ioprocessors"
    true => true))
