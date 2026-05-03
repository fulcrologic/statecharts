(ns com.fulcrologic.statecharts.irp.test-500
  "IRP test 500 — _ioprocessors contains a location entry for the SCXML I/O processor.

   SKIPPED: library does not expose _ioprocessors (same as tests 325/326).

   Source: https://www.w3.org/Voice/2013/scxml-irp/500/test500.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 500 — _ioprocessors.scxml.location exists" :irp/skip
  (assertions
    "skipped: library does not expose _ioprocessors"
    true => true))
