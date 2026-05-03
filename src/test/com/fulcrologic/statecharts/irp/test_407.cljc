(ns com.fulcrologic.statecharts.irp.test-407
  "IRP test 407 — on-exit handlers execute when a state is left.

   Source: https://www.w3.org/Voice/2013/scxml-irp/407/test407.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-exit assign
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-407
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 0}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-exit {}
          (assign {:location [:Var1] :expr (fn [_ d] (inc (:Var1 d)))}))
        (transition {:target :s1}))
      (state {:id :s1}
        (transition {:cond (fn [_ d] (= 1 (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 407 — on-exit handler increments Var1 when leaving s0"
  (assertions
    "reaches pass"
    (runner/passes? chart-407 events) => true))
