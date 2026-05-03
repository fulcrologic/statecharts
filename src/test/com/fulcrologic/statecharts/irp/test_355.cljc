(ns com.fulcrologic.statecharts.irp.test-355
  "IRP test 355 — the default initial state is the first child in document order.
   When no `:initial` is specified, the first child state (s0) is entered, which
   transitions immediately to pass.

   Source: https://www.w3.org/Voice/2013/scxml-irp/355/test355.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-355
  (chart/statechart {:initial :_root}
    (state {:id :_root}
      (state {:id :s0}
        (transition {:target :pass}))
      (state {:id :s1}
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 355 — default initial state is first in document order"
  (assertions
    "reaches pass"
    (runner/passes? chart-355 []) => true))
