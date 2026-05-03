(ns com.fulcrologic.statecharts.irp.test-553
  "IRP test 553 — data model src/namelist or invoke test. SKIPPED: not implemented.
   Source: https://www.w3.org/Voice/2013/scxml-irp/553/test553.txml"
  (:require [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 553 — src/namelist/invoke" :irp/skip
  (assertions
    "skipped: file src loading or invalid namelist or invoke not fully supported"
    true => true))
