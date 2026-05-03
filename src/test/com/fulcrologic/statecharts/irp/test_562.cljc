(ns com.fulcrologic.statecharts.irp.test-562
  "IRP test 562 — ECMAScript or HTTP I/O processor test. SKIPPED: not applicable.
   Source: https://www.w3.org/Voice/2013/scxml-irp/562/test562.txml"
  (:require [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 562 — ECMAScript/HTTP" :irp/skip
  (assertions
    "skipped: ECMAScript data model or HTTP I/O processor not applicable"
    true => true))
