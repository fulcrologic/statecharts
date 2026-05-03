(ns com.fulcrologic.statecharts.irp.test-567
  "IRP test 567 — Basic HTTP Event I/O Processor test.

   SKIPPED: Basic HTTP I/O Processor not implemented in this library.
   Source: https://www.w3.org/Voice/2013/scxml-irp/567/test567.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 567 — Basic HTTP I/O Processor" :irp/skip
  (assertions
    "skipped: Basic HTTP I/O Processor not implemented"
    true => true))
