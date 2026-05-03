(ns com.fulcrologic.statecharts.irp.test-453
  "IRP test 453 — ECMAScript data model test.

   SKIPPED: targets ECMAScript data model. Library uses lambda execution model.

   Source: https://www.w3.org/Voice/2013/scxml-irp/453/test453.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 453 — ECMAScript data model" :irp/skip
  (assertions
    "skipped: ECMAScript-specific test"
    true => true))
