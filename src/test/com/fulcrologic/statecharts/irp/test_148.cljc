(ns com.fulcrologic.statecharts.irp.test-148
  "IRP test 148 — verifies that the `<else>` branch executes when both `<if>`
   and `<elseif>` conditions are false. The `<else>` raises `baz`; Var1 is
   incremented in the else clause only, so it must equal 1 when the chart
   transitions. A trailing `<raise bat>` is always raised after the `<if>`.

   Source: https://www.w3.org/Voice/2013/scxml-irp/148/test148.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry raise assign
                                                   If elseif else data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-148
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 0}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (If {:cond (fn [_ _] false)}
            (raise {:event :foo})
            (assign {:location [:Var1] :expr (fn [_ d] (inc (:Var1 d)))})
            (elseif {:cond (fn [_ _] false)}
              (raise {:event :bar})
              (assign {:location [:Var1] :expr (fn [_ d] (inc (:Var1 d)))}))
            (else {}
              (raise {:event :baz})
              (assign {:location [:Var1] :expr (fn [_ d] (inc (:Var1 d)))})))
          (raise {:event :bat}))
        (transition {:event :baz :cond (fn [_ d] (= 1 (:Var1 d))) :target :pass})
        (transition {:event :* :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 148 — else branch executes when if and elseif are both false"
  (assertions
    "reaches pass"
    (runner/passes? chart-148 []) => true))
