(ns com.fulcrologic.statecharts.irp.test-149
  "IRP test 149 — verifies that when both `<if>` and `<elseif>` conditions are
   false and there is no `<else>`, neither branch executes. Var1 stays 0 and
   only `bat` (raised after the if-block) is fired.

   Source: https://www.w3.org/Voice/2013/scxml-irp/149/test149.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry raise assign
                                                   If elseif data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-149
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
              (assign {:location [:Var1] :expr (fn [_ d] (inc (:Var1 d)))})))
          (raise {:event :bat}))
        (transition {:event :bat :cond (fn [_ d] (= 0 (:Var1 d))) :target :pass})
        (transition {:event :* :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 149 — neither if/elseif branch executes when both conditions are false"
  (assertions
    "reaches pass"
    (runner/passes? chart-149 []) => true))
