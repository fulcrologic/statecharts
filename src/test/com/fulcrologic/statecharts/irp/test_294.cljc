(ns com.fulcrologic.statecharts.irp.test-294
  "IRP test 294 — a param inside donedata ends up in event.data, and content
   inside donedata sets the full event.data value.

   Source: https://www.w3.org/Voice/2013/scxml-irp/294/test294.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry done-data
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-294
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 0}})
    (state {:id :_root :initial :s0}
      (state {:id :s0 :initial :s01}
        (transition {:event :done.state.s0
                     :cond  (fn [_ d] (= 1 (get-in d [:_event :data :Var1])))
                     :target :s1})
        (transition {:event :done.state.s0 :target :fail})
        (state {:id :s01}
          (transition {:target :s02}))
        (final {:id :s02}
          (done-data {:expr (fn [_ _] {:Var1 1})})))
      (state {:id :s1 :initial :s11}
        (transition {:event :done.state.s1
                     :cond  (fn [_ d] (= "foo" (get-in d [:_event :data])))
                     :target :pass})
        (transition {:event :done.state.s1 :target :fail})
        (state {:id :s11}
          (transition {:target :s12}))
        (final {:id :s12}
          (done-data {:expr (fn [_ _] "foo")})))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 294 — donedata param populates event.data; content sets event.data directly"
  (assertions
    "reaches pass"
    (runner/passes? chart-294 events) => true))
