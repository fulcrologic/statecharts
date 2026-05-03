(ns com.fulcrologic.statecharts.irp.test-377
  "IRP test 377 — multiple `<onexit>` handlers execute in document order.
   The first raises `event1`; the second raises `event2`. After leaving s0,
   event1 must be processed before event2, leading to pass.

   Source: https://www.w3.org/Voice/2013/scxml-irp/377/test377.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition on-exit raise]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-377
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-exit {} (raise {:event :event1}))
        (on-exit {} (raise {:event :event2}))
        (transition {:target :s1}))
      (state {:id :s1}
        (transition {:event :event1 :target :s2})
        (transition {:event :* :target :fail}))
      (state {:id :s2}
        (transition {:event :event2 :target :pass})
        (transition {:event :* :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 377 — multiple onexit handlers execute in document order"
  (assertions
    "reaches pass"
    (runner/passes? chart-377 []) => true))
