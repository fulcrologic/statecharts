(ns com.fulcrologic.statecharts.irp.test-534
  "IRP test 534 — Basic HTTP Event I/O Processor / _scxmleventname test.

   SKIPPED: Basic HTTP processor not implemented.

   Source: https://www.w3.org/Voice/2013/scxml-irp/534/test534.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 534 — HTTP processor / _scxmleventname" :irp/skip
  (assertions
    "skipped: BasicHTTP processor not implemented"
    true => true))
