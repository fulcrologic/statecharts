(ns com.fulcrologic.statecharts.irp.test-412
  "IRP test 412 — executable content in <initial> transition fires after the
   state's on-entry but before the child state's on-entry.

   Order: s01 on-entry (event1) → initial transition content (event2) →
   s011 on-entry (event3).

   Source: https://www.w3.org/Voice/2013/scxml-irp/412/test412.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   initial on-entry raise]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-412
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0 :initial :s01}
        (transition {:event :event1 :target :fail})
        (transition {:event :event2 :target :pass})
        (state {:id :s01}
          (on-entry {}
            (raise {:event :event1}))
          (initial {}
            (transition {:target :s011}
              (raise {:event :event2})))
          (state {:id :s011}
            (on-entry {}
              (raise {:event :event3}))
            (transition {:target :s02})))
        (state {:id :s02}
          (transition {:event :event1 :target :s03})
          (transition {:event :* :target :fail}))
        (state {:id :s03}
          (transition {:event :event2 :target :s04})
          (transition {:event :* :target :fail}))
        (state {:id :s04}
          (transition {:event :event3 :target :pass})
          (transition {:event :* :target :fail})))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 412 — initial transition content fires after state on-entry, before child on-entry"
  (assertions
    "reaches pass"
    (runner/passes? chart-412 events) => true))
