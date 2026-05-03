(ns com.fulcrologic.statecharts.irp.test-348
  "IRP test 348 — the event param of <send> sets the name of the event.

   Source: https://www.w3.org/Voice/2013/scxml-irp/348/test348.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-348
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (Send {:event :s0Event
                 :type  "http://www.w3.org/TR/scxml/#SCXMLEventProcessor"}))
        (transition {:event :s0Event :target :pass})
        (transition {:event :* :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 348 — send event param sets the event name"
  (assertions
    "reaches pass"
    (runner/passes? chart-348 events) => true))
