(ns com.fulcrologic.statecharts.irp.test-158
  "IRP test 158 — executable content runs in document order. The two `raise`
   calls in s0's on-entry should produce event1 then event2 in that order.

   Source: https://www.w3.org/Voice/2013/scxml-irp/158/test158.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition on-entry raise]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-158
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (raise {:event :event1})
          (raise {:event :event2}))
        (transition {:event :event1 :target :s1})
        (transition {:event :*      :target :fail}))
      (state {:id :s1}
        (transition {:event :event2 :target :pass})
        (transition {:event :*      :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 158 — executable content runs in document order"
  (assertions
    "reaches pass"
    (runner/passes? chart-158 []) => true))
