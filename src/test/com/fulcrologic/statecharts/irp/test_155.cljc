(ns com.fulcrologic.statecharts.irp.test-155
  "IRP test 155 — verifies that `<foreach>` executes its body once per item in
   the list [1,2,3]. The body adds the current item (Var2) to the running sum
   (Var1), so Var1 must equal 6 at the end.

   Source: https://www.w3.org/Voice/2013/scxml-irp/155/test155.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry assign foreach
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-155
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 0 :Var2 nil :Var3 [1 2 3]}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (foreach {:array (fn [_ d] (:Var3 d)) :item [:Var2]}
            (assign {:location [:Var1]
                     :expr     (fn [_ d] (+ (:Var1 d) (:Var2 d)))})))
        (transition {:cond (fn [_ d] (= 6 (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 155 — foreach iterates body once per item, summing to 6"
  (assertions
    "reaches pass"
    (runner/passes? chart-155 []) => true))
