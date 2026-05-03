(ns com.fulcrologic.statecharts.irp.test-347
  "IRP test 347 — SCXML event I/O processor sends events between invoked
   child and parent process.

   SKIPPED: requires inline <invoke><content><scxml>, #_parent/#_child targets
   — same invocation gaps as tests 191/192/225-253.

   Source: https://www.w3.org/Voice/2013/scxml-irp/347/test347.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 347 — SCXML I/O processor parent/child messaging" :irp/skip
  (assertions
    "skipped: inline invoke + #_parent/#_child not supported"
    true => true))
