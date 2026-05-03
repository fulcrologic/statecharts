(ns com.fulcrologic.statecharts.irp.test-554
  "IRP test 554 — data model src/namelist or invoke test. SKIPPED: not implemented.
   Source: https://www.w3.org/Voice/2013/scxml-irp/554/test554.txml"
  (:require [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 554 — src/namelist/invoke" :irp/skip
  (assertions
    "skipped: file src loading or invalid namelist or invoke not fully supported"
    true => true))
