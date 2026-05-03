(ns com.fulcrologic.statecharts.irp.test-147
  "IRP test 147 — verifies that the first clause that evaluates to true (and
   only that clause) is executed. Var1 starts at 0. An `<if>` with false
   condition, an `<elseif>` with true condition (raises bar and increments
   Var1), and an `<else>` (raises baz). A trailing `<raise bat>` after the
   if-block is always executed. The chart must see `bar` first and Var1 must
   equal 1.

   Source: https://www.w3.org/Voice/2013/scxml-irp/147/test147.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry raise assign
                                                   If elseif else data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-147
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 0}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (If {:cond (fn [_ _] false)}
            (raise {:event :foo})
            (assign {:location [:Var1] :expr (fn [_ d] (inc (:Var1 d)))})
            (elseif {:cond (fn [_ _] true)}
              (raise {:event :bar})
              (assign {:location [:Var1] :expr (fn [_ d] (inc (:Var1 d)))}))
            (else {}
              (raise {:event :baz})
              (assign {:location [:Var1] :expr (fn [_ d] (inc (:Var1 d)))})))
          (raise {:event :bat}))
        (transition {:event :bar :cond (fn [_ d] (= 1 (:Var1 d))) :target :pass})
        (transition {:event :* :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 147 — if/elseif/else selects first true branch (bar)"
  (assertions
    "reaches pass"
    (runner/passes? chart-147 []) => true))
