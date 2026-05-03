(ns com.fulcrologic.statecharts.irp.test-405
  "IRP test 405 — within a parallel state, exits fire across regions then
   transition executable content fires in document order.

   event1 (s01p21 onexit), event2 (s01p11 onexit), event3 (s01p11 transition),
   event4 (s01p21 transition) — in that order.

   Source: https://www.w3.org/Voice/2013/scxml-irp/405/test405.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state parallel final transition
                                                   on-exit raise]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-405
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0 :initial :s01p}
        (parallel {:id :s01p}
          (transition {:event :event1 :target :s02})
          (state {:id :s01p1 :initial :s01p11}
            (state {:id :s01p11}
              (on-exit {}
                (raise {:event :event2}))
              (transition {:target :s01p12}
                (raise {:event :event3})))
            (state {:id :s01p12}))
          (state {:id :s01p2 :initial :s01p21}
            (state {:id :s01p21}
              (on-exit {}
                (raise {:event :event1}))
              (transition {:target :s01p22}
                (raise {:event :event4})))
            (state {:id :s01p22})))
        (state {:id :s02}
          (transition {:event :event2 :target :s03})
          (transition {:event :* :target :fail}))
        (state {:id :s03}
          (transition {:event :event3 :target :s04})
          (transition {:event :* :target :fail}))
        (state {:id :s04}
          (transition {:event :event4 :target :pass})
          (transition {:event :* :target :fail})))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 405 — parallel region exit/transition content ordering"
  (assertions
    "reaches pass"
    (runner/passes? chart-405 events) => true))
