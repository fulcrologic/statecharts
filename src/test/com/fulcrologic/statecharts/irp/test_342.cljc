(ns com.fulcrologic.statecharts.irp.test-342
  "IRP test 342 — eventexpr sets the name field of the sent event.

   Source: https://www.w3.org/Voice/2013/scxml-irp/342/test342.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send assign
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-342
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 :foo :Var2 nil}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (Send {:eventexpr (fn [_ d] (:Var1 d))}))
        (transition {:event :foo :target :s1}
          (assign {:location [:Var2] :expr (fn [_ d] (get-in d [:_event :name]))}))
        (transition {:event :* :target :fail}))
      (state {:id :s1}
        (transition {:cond (fn [_ d] (= (:Var1 d) (:Var2 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 342 — eventexpr sets the name of the sent event"
  (assertions
    "reaches pass"
    (runner/passes? chart-342 events) => true))
