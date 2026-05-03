(ns com.fulcrologic.statecharts.irp.test-352
  "IRP test 352 — _event.origintype is set to the SCXML processor URL.

   SKIPPED: library does not populate _event.origintype (same library issue
   as test 198 — origintype never set in v20150901_impl.cljc).

   Source: https://www.w3.org/Voice/2013/scxml-irp/352/test352.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 352 — _event.origintype set to SCXML processor URL" :irp/skip
  (assertions
    "skipped: library does not populate _event.origintype"
    true => true))
