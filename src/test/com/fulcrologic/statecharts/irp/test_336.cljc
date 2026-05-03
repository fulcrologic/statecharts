(ns com.fulcrologic.statecharts.irp.test-336
  "IRP test 336 — origin field of external event contains a URL usable for
   sending back to the originator.

   SKIPPED: this library does not populate `_event.origin` for sent events,
   so the send-to-origin pattern cannot be exercised.

   Source: https://www.w3.org/Voice/2013/scxml-irp/336/test336.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 336 — origin field usable for reply send" :irp/skip
  (assertions
    "skipped: library does not populate _event.origin"
    true => true))
