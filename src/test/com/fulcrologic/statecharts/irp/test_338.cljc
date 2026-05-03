(ns com.fulcrologic.statecharts.irp.test-338
  "IRP test 338 — invokeid is set correctly in events received from invoked process.

   SKIPPED: requires inline <invoke><content><scxml> + #_parent send + invokeid
   correlation — blocked by the same invocation gaps as tests 191/192/225-253.

   Source: https://www.w3.org/Voice/2013/scxml-irp/338/test338.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 338 — invokeid in events from invoked process" :irp/skip
  (assertions
    "skipped: inline invoke + #_parent not supported"
    true => true))
