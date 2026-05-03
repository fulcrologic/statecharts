(ns com.fulcrologic.statecharts.irp.test-406
  "IRP test 406 — states are entered in entry order (parents before children,
   document order). After transition content fires, on-entry handlers run in
   entry order: s0p2 (parent) → event2, s01p21 → event3, s01p22 → event4.

   Source: https://www.w3.org/Voice/2013/scxml-irp/406/test406.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state parallel final transition
                                                   on-entry raise]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-406
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0 :initial :s01}
        (state {:id :s01}
          (transition {:target :s0p2}
            (raise {:event :event1})))
        (parallel {:id :s0p2}
          (on-entry {}
            (raise {:event :event2}))
          (transition {:event :event1 :target :s03})
          (state {:id :s01p21}
            (on-entry {}
              (raise {:event :event3})))
          (state {:id :s01p22}
            (on-entry {}
              (raise {:event :event4}))))
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

(specification "IRP test 406 — entry order: parents before children, document order"
  (assertions
    "reaches pass"
    (runner/passes? chart-406 events) => true))
