(ns com.fulcrologic.statecharts.irp.test-422
  "IRP test 422 — invokes in active states execute; exited states' invokes
   do not start.

   SKIPPED: requires inline <invoke><content><scxml> + #_parent sends —
   blocked by same invocation gaps as tests 191/192/225-253.

   Source: https://www.w3.org/Voice/2013/scxml-irp/422/test422.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 422 — invoke execution at macrostep end" :irp/skip
  (assertions
    "skipped: inline invoke + #_parent not supported"
    true => true))
