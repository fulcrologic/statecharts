(ns com.fulcrologic.statecharts.irp.test-337
  "IRP test 337 — origintype field is blank for internal events.

   Source: https://www.w3.org/Voice/2013/scxml-irp/337/test337.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry raise]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-337
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (raise {:event :foo}))
        (transition {:event :foo
                     :cond  (fn [_ d] (nil? (get-in d [:_event :origintype])))
                     :target :pass})
        (transition {:event :* :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 337 — origintype field is blank for internal events"
  (assertions
    "reaches pass"
    (runner/passes? chart-337 events) => true))
