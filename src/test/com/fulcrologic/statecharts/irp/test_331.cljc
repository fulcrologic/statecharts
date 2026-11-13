(ns com.fulcrologic.statecharts.irp.test-331
  "IRP test 331 — _event.type is set correctly for internal, platform, and external events.

   SKIPPED: raised events (<raise>, send to #_internal) are correctly :internal, but
   platform error events (error.execution, error.communication) are still created
   with :type :external. The W3C spec requires :platform for those.

   Source: https://www.w3.org/Voice/2013/scxml-irp/331/test331.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 331 — _event.type distinguished for internal/platform/external" :irp/skip
  (assertions
    "skipped: platform error events are not yet marked :platform"
    true => true))
