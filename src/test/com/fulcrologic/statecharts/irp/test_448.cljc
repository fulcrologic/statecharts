(ns com.fulcrologic.statecharts.irp.test-448
  "IRP test 448 — ECMAScript data model test.

   SKIPPED: this test targets the ECMAScript data model (datamodel=\"ecmascript\").
   This library uses the lambda/Clojure execution model.

   Source: https://www.w3.org/Voice/2013/scxml-irp/448/test448.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 448 — ECMAScript data model" :irp/skip
  (assertions
    "skipped: ECMAScript-specific test; library uses lambda execution model"
    true => true))
