(ns com.fulcrologic.statecharts.irp.test-191
  "IRP test 191 — child invocation can `<send target=\"#_parent\">` to deliver
   an event to its parent session.
   Source: https://www.w3.org/Voice/2013/scxml-irp/191/test191.txml

   SKIPPED: requires SCXML-type invoke of an inline child statechart and the
   `#_parent` send target. Neither is exercised by existing library tests
   (invocation system reportedly has zero coverage)."
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 191 — invoked child sends to #_parent (skipped)" :irp/skip
  (assertions
    "skipped: requires SCXML invoke + #_parent target"
    true => true))
