(ns com.fulcrologic.statecharts.irp.test-529
  "IRP test 529 — literal content in donedata sets event.data.

   Source: https://www.w3.org/Voice/2013/scxml-irp/529/test529.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition done-data]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-529
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0 :initial :s01}
        (transition {:event :done.state.s0
                     :cond  (fn [_ d] (= 21 (get-in d [:_event :data])))
                     :target :pass})
        (transition {:event :done.state.s0 :target :fail})
        (state {:id :s01}
          (transition {:target :s02}))
        (final {:id :s02}
          (done-data {:expr (fn [_ _] 21)})))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 529 — literal content in donedata sets event.data"
  (assertions
    "reaches pass"
    (runner/passes? chart-529 events) => true))
