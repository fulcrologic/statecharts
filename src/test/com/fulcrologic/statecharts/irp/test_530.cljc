(ns com.fulcrologic.statecharts.irp.test-530
  "IRP test 530 — invoke content evaluated at invoke time.

   SKIPPED: requires inline <invoke><content> — blocked by invocation gaps.

   Source: https://www.w3.org/Voice/2013/scxml-irp/530/test530.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 530 — invoke content evaluation timing" :irp/skip
  (assertions
    "skipped: inline invoke not supported"
    true => true))
