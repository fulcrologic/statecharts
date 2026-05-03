(ns com.fulcrologic.statecharts.irp.test-423
  "IRP test 423 — the processor keeps pulling external events until one
   matches a transition; events that don't match are discarded.

   Source: https://www.w3.org/Voice/2013/scxml-irp/423/test423.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry raise Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-423
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (Send {:event :externalEvent1})
          (Send {:event :externalEvent2 :delay 1})
          (raise {:event :internalEvent}))
        (transition {:event :internalEvent :target :s1})
        (transition {:event :* :target :fail}))
      (state {:id :s1}
        (transition {:event :externalEvent2 :target :pass})
        (transition {:event :internalEvent :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 423 — unmatched events discarded; processor pulls until match"
  (assertions
    "reaches pass"
    (runner/passes-with-delays? chart-423 events) => true))
