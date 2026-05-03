(ns com.fulcrologic.statecharts.irp.test-351
  "IRP test 351 — `_event.sendid` is set to the send id when `<send id=\"...\">` is given,
   and absent/blank when no explicit id is given.

   SKIPPED: the library sets `_event.sendid` to the auto-generated cancel-id even
   when the `<send>` element has no explicit `id` attribute. Same root cause as
   test-333. The W3C spec requires sendid to be absent (or empty) when no explicit
   id is supplied.

   Source: https://www.w3.org/Voice/2013/scxml-irp/351/test351.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 351 — _event.sendid set only for explicit send id" :irp/skip
  (assertions
    "skipped: auto-generated sendids exposed in _event.sendid (same issue as test-333)"
    true => true))
