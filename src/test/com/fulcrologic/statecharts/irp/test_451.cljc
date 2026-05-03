(ns com.fulcrologic.statecharts.irp.test-451
  "IRP test 451 — ECMAScript data model test.

   SKIPPED: targets ECMAScript data model. Library uses lambda execution model.

   Source: https://www.w3.org/Voice/2013/scxml-irp/451/test451.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 451 — ECMAScript data model" :irp/skip
  (assertions
    "skipped: ECMAScript-specific test"
    true => true))
