(ns com.fulcrologic.statecharts.irp.test-525
  "IRP test 525 — foreach shallow copy behavior.

   SKIPPED: foreach not implemented in this library.

   Source: https://www.w3.org/Voice/2013/scxml-irp/525/test525.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 525 — foreach shallow copy" :irp/skip
  (assertions
    "skipped: foreach not implemented"
    true => true))
