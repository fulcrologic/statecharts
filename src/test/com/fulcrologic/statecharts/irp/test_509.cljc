(ns com.fulcrologic.statecharts.irp.test-509
  "IRP test 509 — Basic HTTP Event I/O Processor test.

   SKIPPED: Basic HTTP Event I/O Processor is optional in W3C IRP and is
   not implemented in this library.

   Source: https://www.w3.org/Voice/2013/scxml-irp/509/test509.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 509 — Basic HTTP Event I/O Processor" :irp/skip
  (assertions
    "skipped: BasicHTTP processor not implemented"
    true => true))
