(ns com.fulcrologic.statecharts.irp.test-552
  "IRP test 552 — data model src/namelist or invoke test. SKIPPED: not implemented.
   Source: https://www.w3.org/Voice/2013/scxml-irp/552/test552.txml"
  (:require [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 552 — src/namelist/invoke" :irp/skip
  (assertions
    "skipped: file src loading or invalid namelist or invoke not fully supported"
    true => true))
