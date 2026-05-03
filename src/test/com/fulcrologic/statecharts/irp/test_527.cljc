(ns com.fulcrologic.statecharts.irp.test-527
  "IRP test 527 — expr in donedata content sets the full event.data value.

   Source: https://www.w3.org/Voice/2013/scxml-irp/527/test527.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition done-data]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-527
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0 :initial :s01}
        (transition {:event :done.state.s0
                     :cond  (fn [_ d] (= "foo" (get-in d [:_event :data])))
                     :target :pass})
        (transition {:event :done.state.s0 :target :fail})
        (state {:id :s01}
          (transition {:target :s02}))
        (final {:id :s02}
          (done-data {:expr (fn [_ _] "foo")})))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 527 — content expr in donedata sets event.data"
  (assertions
    "reaches pass"
    (runner/passes? chart-527 events) => true))
