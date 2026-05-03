(ns com.fulcrologic.statecharts.irp.test-570
  "IRP test 570 — done.state.id generated when all parallel children reach final states.

   Source: https://www.w3.org/Voice/2013/scxml-irp/570/test570.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state parallel final transition
                                                   on-entry raise assign data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-570
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :p0}
      (data-model {:expr {:Var1 0}})
      (parallel {:id :p0}
        (on-entry {}
          (raise {:event :e1})
          (raise {:event :e2}))
        ;; targetless — just assigns Var1
        (transition {:event :done.state.p0s1}
          (assign {:location [:Var1] :expr (fn [_ _] 1)}))
        ;; exits parallel
        (transition {:event :done.state.p0s2 :target :s1})
        (state {:id :p0s1 :initial :p0s11}
          (state {:id :p0s11}
            (transition {:event :e1 :target :p0s1final}))
          (final {:id :p0s1final}))
        (state {:id :p0s2 :initial :p0s21}
          (state {:id :p0s21}
            (transition {:event :e2 :target :p0s2final}))
          (final {:id :p0s2final})))
      (state {:id :s1}
        (transition {:event :done.state.p0
                     :cond  (fn [_ d] (= 1 (:Var1 d)))
                     :target :pass})
        (transition {:event :* :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 570 — done.state.p0 generated when all parallel regions reach final"
  (assertions
    "reaches pass"
    (runner/passes? chart-570 events) => true))
