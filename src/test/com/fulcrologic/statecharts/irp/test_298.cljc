(ns com.fulcrologic.statecharts.irp.test-298
  "IRP test 298 — verifies that referencing a non-existent data-model location
   in `<param>` inside `<donedata>` raises `error.execution`.

   SKIPPED: this library uses a flat working-memory data model with no
   notion of \"declared\" vs \"undeclared\" locations — reading a missing
   key returns nil rather than raising an error. The ECMAScript-style
   undeclared-binding error this test exercises is not portable. Source:
   https://www.w3.org/Voice/2013/scxml-irp/298/test298.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 298 — donedata param with invalid location" :irp/skip
  (assertions
    "skipped: flat data model has no declared/undeclared distinction"
    true => true))
