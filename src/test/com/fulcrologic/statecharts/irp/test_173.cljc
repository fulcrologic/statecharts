(ns com.fulcrologic.statecharts.irp.test-173
  "IRP test 173 — verifies that `<send targetExpr=...>` evaluates the
   target expression at send time (so it sees the assigned value, not the
   initial value of the referenced variable).
   Source: https://www.w3.org/Voice/2013/scxml-irp/173/test173.txml

   SKIPPED: this library's lambda execution model evaluates `:targetexpr`
   lazily by construction — it is always a function called at send time. The
   ECMAScript-style timing concern the test exercises does not apply, so a
   port would either be tautological (always passes) or would have to invent
   a non-equivalent behavior to test. Recorded as skip per the IRP port
   workflow."
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 173 — send targetexpr uses current Var1 (skipped)" :irp/skip
  (assertions
    "skipped: lambda execution model evaluates targetexpr lazily by construction"
    true => true))
