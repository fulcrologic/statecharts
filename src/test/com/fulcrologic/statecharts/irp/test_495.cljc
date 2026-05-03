(ns com.fulcrologic.statecharts.irp.test-495
  "IRP test 495 — SCXML I/O processor puts events in correct queues;
   target=\"#_internal\" routes to internal queue.

   SKIPPED: library does not support target=\"#_internal\" (same library
   issue as test 189).

   Source: https://www.w3.org/Voice/2013/scxml-irp/495/test495.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 495 — #_internal target routes to internal queue" :irp/skip
  (assertions
    "skipped: #_internal target not supported (test 189 library issue)"
    true => true))
