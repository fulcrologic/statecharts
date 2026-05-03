(ns com.fulcrologic.statecharts.irp.test-349
  "IRP test 349 — _event.origin can be used as a target to send an event back.

   SKIPPED: library does not populate _event.origin for sent events (same
   library issue as test 336).

   Source: https://www.w3.org/Voice/2013/scxml-irp/349/test349.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 349 — origin field usable for reply send" :irp/skip
  (assertions
    "skipped: library does not populate _event.origin"
    true => true))
