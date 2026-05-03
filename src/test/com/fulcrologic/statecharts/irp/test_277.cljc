(ns com.fulcrologic.statecharts.irp.test-277
  "IRP test 277 — verifies that an illegal `<data>` expression leaves the
   variable unbound (and raises `error.execution`), so a later `<assign>` in
   another state can still bind it.

   SKIPPED: this library's lambda execution model has no portable notion of
   an \"illegal data expression\" at chart-init time — `<data>` initializers
   are arbitrary Clojure functions; throwing in one is a host runtime error,
   not a SCXML `error.execution`. The ECMAScript-specific concern this test
   exercises does not map cleanly to this library, so a port would either be
   tautological or would invent non-equivalent behavior. Source:
   https://www.w3.org/Voice/2013/scxml-irp/277/test277.txml"
  (:require
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "IRP test 277 — illegal data expr leaves variable unbound" :irp/skip
  (assertions
    "skipped: ECMAScript-specific illegal-expression semantics; lambda model n/a"
    true => true))
