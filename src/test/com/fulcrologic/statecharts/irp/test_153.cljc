(ns com.fulcrologic.statecharts.irp.test-153
  "IRP test 153 — verifies that `<foreach>` iterates in document order (array
   [1,2,3]). For each item the previous value (Var1) is compared to the current
   (Var2); if current >= previous, Var4 is set to 0 (failure). Var4 starts at 1.

   Source: https://www.w3.org/Voice/2013/scxml-irp/153/test153.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry assign foreach
                                                   If else data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-153
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 0 :Var2 nil :Var3 [1 2 3] :Var4 1}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (foreach {:array (fn [_ d] (:Var3 d)) :item [:Var2]}
            (If {:cond (fn [_ d] (< (:Var1 d) (:Var2 d)))}
              (assign {:location [:Var1] :expr (fn [_ d] (:Var2 d))})
              (else {}
                (assign {:location [:Var4] :expr (fn [_ _] 0)})))))
        (transition {:cond (fn [_ d] (= 0 (:Var4 d))) :target :fail})
        (transition {:target :pass}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 153 — foreach iterates in document order (ascending [1,2,3])"
  (assertions
    "reaches pass"
    (runner/passes? chart-153 []) => true))
