(ns com.fulcrologic.statecharts.irp.test-417
  "IRP test 417 — done.state.id is generated when all parallel regions enter
   final states.

   Source: https://www.w3.org/Voice/2013/scxml-irp/417/test417.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state parallel final transition]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-417
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s1}
      (state {:id :s1 :initial :s1p1}
        (parallel {:id :s1p1}
          (transition {:event :done.state.s1p1 :target :pass})
          (state {:id :s1p11 :initial :s1p111}
            (state {:id :s1p111}
              (transition {:target :s1p11final}))
            (final {:id :s1p11final}))
          (state {:id :s1p12 :initial :s1p121}
            (state {:id :s1p121}
              (transition {:target :s1p12final}))
            (final {:id :s1p12final}))))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 417 — done.state.id generated when all parallel regions are final"
  (assertions
    "reaches pass"
    (runner/passes? chart-417 events) => true))
