(ns com.fulcrologic.statecharts.irp.test-459
  "IRP test 459 — ECMAScript data model test.

   SKIPPED: targets ECMAScript data model. Library uses lambda execution model.

   Source: https://www.w3.org/Voice/2013/scxml-irp/459/test459.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 459 — ECMAScript data model" :irp/skip
  (assertions
    "skipped: ECMAScript-specific test"
    true => true))
