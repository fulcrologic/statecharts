(ns com.fulcrologic.statecharts.irp.test-187
  "IRP test 187 — delayed `<send>` from a child invocation must NOT be
   delivered after the child session terminates. Requires SCXML-type invoke
   of an inline child statechart, `target=\"#_parent\"`, and cancellation of
   pending delayed events on session termination.
   Source: https://www.w3.org/Voice/2013/scxml-irp/187/test187.txml

   SKIPPED: this library's invocation system has minimal/zero coverage of
   inline-content child statecharts and cross-session delayed-send
   cancellation. Porting requires verified support for `:type :scxml` invoke
   with inline content plus `#_parent` routing — neither is exercised by
   existing library tests. Recorded as skip pending invoke-system work."
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 187 — child-session termination cancels its delayed sends (skipped)" :irp/skip
  (assertions
    "skipped: requires SCXML invoke + #_parent routing + cross-session cancel"
    true => true))
