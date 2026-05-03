(ns com.fulcrologic.statecharts.irp.test-192
  "IRP test 192 — `<send target=\"#_<invokeid>\">` routes an event to the
   invoked child session.
   Source: https://www.w3.org/Voice/2013/scxml-irp/192/test192.txml

   SKIPPED: requires SCXML invoke + bidirectional send via `#_<invokeid>` /
   `#_parent`. Outside the scope of currently exercised invocation features."
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 192 — send target=#_invokeid (skipped)" :irp/skip
  (assertions
    "skipped: requires SCXML invoke + #_invokeid routing"
    true => true))
