(ns com.fulcrologic.statecharts.irp.test-339
  "IRP test 339 — invokeid is blank in an event not from an invoked process.

   Source: https://www.w3.org/Voice/2013/scxml-irp/339/test339.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry raise]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-339
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (raise {:event :foo}))
        (transition {:event :foo
                     :cond  (fn [_ d] (nil? (get-in d [:_event :invokeid])))
                     :target :pass})
        (transition {:event :* :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 339 — invokeid is blank for non-invoked events"
  (assertions
    "reaches pass"
    (runner/passes? chart-339 events) => true))
