(ns com.fulcrologic.statecharts.irp.test-416
  "IRP test 416 — done.state.id is generated when a compound state's final
   child is entered.

   Source: https://www.w3.org/Voice/2013/scxml-irp/416/test416.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-416
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s1}
      (state {:id :s1 :initial :s11}
        (state {:id :s11 :initial :s111}
          (transition {:event :done.state.s11 :target :pass})
          (state {:id :s111}
            (transition {:target :s11final}))
          (final {:id :s11final})))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 416 — done.state.id generated on compound state final entry"
  (assertions
    "reaches pass"
    (runner/passes? chart-416 events) => true))
