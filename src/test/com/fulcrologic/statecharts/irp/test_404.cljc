(ns com.fulcrologic.statecharts.irp.test-404
  "IRP test 404 — states exit in reverse document order (children before
   parents), then transition executable content fires.

   s01p2 exits first (event1), s01p1 second (event2), s01p third (event3),
   transition content last (event4).

   Source: https://www.w3.org/Voice/2013/scxml-irp/404/test404.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state parallel final transition
                                                   on-exit raise]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-404
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0 :initial :s01p}
        (parallel {:id :s01p}
          (on-exit {}
            (raise {:event :event3}))
          (transition {:target :s02}
            (raise {:event :event4}))
          (state {:id :s01p1}
            (on-exit {}
              (raise {:event :event2})))
          (state {:id :s01p2}
            (on-exit {}
              (raise {:event :event1}))))
        (state {:id :s02}
          (transition {:event :event1 :target :s03})
          (transition {:event :* :target :fail}))
        (state {:id :s03}
          (transition {:event :event2 :target :s04})
          (transition {:event :* :target :fail}))
        (state {:id :s04}
          (transition {:event :event3 :target :s05})
          (transition {:event :* :target :fail}))
        (state {:id :s05}
          (transition {:event :event4 :target :pass})
          (transition {:event :* :target :fail})))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 404 — exit order: children before parents, reverse doc order"
  (assertions
    "reaches pass"
    (runner/passes? chart-404 events) => true))
