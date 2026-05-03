(ns com.fulcrologic.statecharts.irp.test-375
  "IRP test 375 — multiple `<onentry>` handlers execute in document order.
   The first raises `event1`; the second raises `event2`. `event1` must
   arrive before `event2` so the machine reaches pass.

   Source: https://www.w3.org/Voice/2013/scxml-irp/375/test375.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition on-entry raise]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-375
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {} (raise {:event :event1}))
        (on-entry {} (raise {:event :event2}))
        (transition {:event :event1 :target :s1})
        (transition {:event :* :target :fail}))
      (state {:id :s1}
        (transition {:event :event2 :target :pass})
        (transition {:event :* :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 375 — multiple onentry handlers execute in document order"
  (assertions
    "reaches pass"
    (runner/passes? chart-375 []) => true))
