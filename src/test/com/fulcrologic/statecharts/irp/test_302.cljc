(ns com.fulcrologic.statecharts.irp.test-302
  "IRP test 302 — verifies that a top-level `<script>` element is evaluated at
   load time, so its side-effects on the data model are visible from the
   initial state.

   SKIPPED: this library does not support top-level `<script>` as a
   load-time chart child. Initial data is expressed via `data-model {:expr
   ...}` (which is exactly equivalent for the purpose of this test, but
   makes the test tautological — there is no separate \"load-time script\"
   pathway to exercise). Source:
   https://www.w3.org/Voice/2013/scxml-irp/302/test302.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 302 — top-level <script> runs at load time" :irp/skip
  (assertions
    "skipped: no top-level load-time script pathway in this library"
    true => true))
