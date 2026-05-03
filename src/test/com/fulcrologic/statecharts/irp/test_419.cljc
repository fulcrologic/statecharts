(ns com.fulcrologic.statecharts.irp.test-419
  "IRP test 419 — eventless transitions take precedence over event-driven ones.

   Source: https://www.w3.org/Voice/2013/scxml-irp/419/test419.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry raise Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-419
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s1}
      (state {:id :s1}
        (on-entry {}
          (raise {:event :internalEvent})
          (Send {:event :externalEvent}))
        (transition {:event :* :target :fail})
        (transition {:target :pass}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 419 — eventless transitions beat event-driven ones"
  (assertions
    "reaches pass"
    (runner/passes? chart-419 events) => true))
