(ns com.fulcrologic.statecharts.irp.test-421
  "IRP test 421 — internal events take priority over external events; the
   processor drains internal events until one triggers a transition.

   Source: https://www.w3.org/Voice/2013/scxml-irp/421/test421.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry raise Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-421
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s1}
      (state {:id :s1 :initial :s11}
        (on-entry {}
          (Send {:event :externalEvent})
          (raise {:event :internalEvent1})
          (raise {:event :internalEvent2})
          (raise {:event :internalEvent3})
          (raise {:event :internalEvent4}))
        (transition {:event :externalEvent :target :fail})
        (state {:id :s11}
          (transition {:event :internalEvent3 :target :s12}))
        (state {:id :s12}
          (transition {:event :internalEvent4 :target :pass})))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 421 — internal events drain before external events"
  (assertions
    "reaches pass"
    (runner/passes? chart-421 events) => true))
