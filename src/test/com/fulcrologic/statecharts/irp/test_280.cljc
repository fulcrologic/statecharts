(ns com.fulcrologic.statecharts.irp.test-280
  "IRP test 280 — verifies late-binding semantics: with `binding=\"late\"`, a
   `<data>` declared inside state s1 is *not* bound until s1 is entered, so
   accessing it from s0 must raise `error.execution`.

   SKIPPED: this library uses early binding (all `data-model` declarations
   are initialized at chart start, regardless of nesting). The SCXML
   `binding=\"late\"` attribute is not supported. Source:
   https://www.w3.org/Voice/2013/scxml-irp/280/test280.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 280 — late binding (binding=\"late\")" :irp/skip
  (assertions
    "skipped: library uses early binding only"
    true => true))
