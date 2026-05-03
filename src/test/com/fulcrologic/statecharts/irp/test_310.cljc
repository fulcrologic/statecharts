(ns com.fulcrologic.statecharts.irp.test-310
  "IRP test 310 — simple test of the In() predicate. Inside a parallel, both
   children s0 and s1 are active simultaneously, so `(In :s1)` evaluated from
   s0 must return true.

   Source: https://www.w3.org/Voice/2013/scxml-irp/310/test310.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state parallel final transition In]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-310
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :p}
      (parallel {:id :p}
        (state {:id :s0}
          (transition {:cond (In :s1) :target :pass})
          (transition {:target :fail}))
        (state {:id :s1}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 310 — simple In() predicate test"
  (assertions
    "reaches pass"
    (runner/passes? chart-310 []) => true))
