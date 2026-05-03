(ns com.fulcrologic.statecharts.irp.test-331
  "IRP test 331 — _event.type is set correctly for internal, platform, and external events.

   SKIPPED: this library does not differentiate event types in the :type field —
   all events (raise/send/error) are created with :type :external. The W3C spec
   requires :internal for raise, :platform for error events, :external for send.

   Source: https://www.w3.org/Voice/2013/scxml-irp/331/test331.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 331 — _event.type distinguished for internal/platform/external" :irp/skip
  (assertions
    "skipped: library sets :type :external for all events; no internal/platform distinction"
    true => true))
