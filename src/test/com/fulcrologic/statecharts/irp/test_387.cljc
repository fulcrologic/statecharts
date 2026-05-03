(ns com.fulcrologic.statecharts.irp.test-387
  "IRP test 387 — default history states. From s3 we enter s0's shallow-
   history default (s01 → s011, which raises `enteringS011` → s4). From s4
   we enter s1's deep-history default (s122, which raises `enteringS122`
   → pass).

   Source: https://www.w3.org/Voice/2013/scxml-irp/387/test387.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition history
                                                   on-entry raise Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-387
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s3}
      (state {:id :s0 :initial :s01}
        (transition {:event :enteringS011 :target :s4})
        (transition {:event :* :target :fail})
        (history {:id :s0HistShallow :type :shallow}
          (transition {:target :s01}))
        (history {:id :s0HistDeep :type :deep}
          (transition {:target :s022}))
        (state {:id :s01 :initial :s011}
          (state {:id :s011}
            (on-entry {} (raise {:event :enteringS011})))
          (state {:id :s012}
            (on-entry {} (raise {:event :enteringS012}))))
        (state {:id :s02 :initial :s021}
          (state {:id :s021}
            (on-entry {} (raise {:event :enteringS021})))
          (state {:id :s022}
            (on-entry {} (raise {:event :enteringS022})))))
      (state {:id :s1 :initial :s11}
        (transition {:event :enteringS122 :target :pass})
        (transition {:event :* :target :fail})
        (history {:id :s1HistShallow :type :shallow}
          (transition {:target :s11}))
        (history {:id :s1HistDeep :type :deep}
          (transition {:target :s122}))
        (state {:id :s11 :initial :s111}
          (state {:id :s111}
            (on-entry {} (raise {:event :enteringS111})))
          (state {:id :s112}
            (on-entry {} (raise {:event :enteringS112}))))
        (state {:id :s12 :initial :s121}
          (state {:id :s121}
            (on-entry {} (raise {:event :enteringS121})))
          (state {:id :s122}
            (on-entry {} (raise {:event :enteringS122})))))
      (state {:id :s3}
        (on-entry {} (Send {:event :timeout :delay 1000}))
        (transition {:target :s0HistShallow}))
      (state {:id :s4}
        (transition {:target :s1HistDeep}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 387 — default history state transitions"
  (assertions
    "reaches pass"
    (runner/passes? chart-387 []) => true))
