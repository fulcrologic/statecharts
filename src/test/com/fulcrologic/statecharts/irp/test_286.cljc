(ns com.fulcrologic.statecharts.irp.test-286
  "IRP test 286 — verifies that assigning to an undeclared variable raises
   `error.execution`.

   SKIPPED: this library uses a flat working-memory data model with no
   notion of \"declared\" vs \"undeclared\" variables — any assignment simply
   sets the keyword path. The ECMAScript-style declared-binding semantics
   the test exercises are not portable. Source:
   https://www.w3.org/Voice/2013/scxml-irp/286/test286.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 286 — assign to undeclared var raises error.execution" :irp/skip
  (assertions
    "skipped: flat data model has no declared/undeclared distinction"
    true => true))
