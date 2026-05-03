(ns com.fulcrologic.statecharts.irp.test-569
  "IRP test 569 — ECMAScript or HTTP I/O processor test. SKIPPED: not applicable.
   Source: https://www.w3.org/Voice/2013/scxml-irp/569/test569.txml"
  (:require [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 569 — ECMAScript/HTTP" :irp/skip
  (assertions
    "skipped: ECMAScript data model or HTTP I/O processor not applicable"
    true => true))
