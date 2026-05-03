(ns com.fulcrologic.statecharts.irp.test-560
  "IRP test 560 — ECMAScript or HTTP I/O processor test. SKIPPED: not applicable.
   Source: https://www.w3.org/Voice/2013/scxml-irp/560/test560.txml"
  (:require [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 560 — ECMAScript/HTTP" :irp/skip
  (assertions
    "skipped: ECMAScript data model or HTTP I/O processor not applicable"
    true => true))
