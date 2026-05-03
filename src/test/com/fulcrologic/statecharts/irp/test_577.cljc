(ns com.fulcrologic.statecharts.irp.test-577
  "IRP test 577 — Basic HTTP Event I/O Processor test.

   SKIPPED: Basic HTTP I/O Processor not implemented in this library.
   Source: https://www.w3.org/Voice/2013/scxml-irp/577/test577.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 577 — Basic HTTP I/O Processor" :irp/skip
  (assertions
    "skipped: Basic HTTP I/O Processor not implemented"
    true => true))
