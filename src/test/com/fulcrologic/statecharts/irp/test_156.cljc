(ns com.fulcrologic.statecharts.irp.test-156
  "IRP test 156 — verifies that an error in executable content inside a
   `<foreach>` stops the iteration. The body increments Var1 then assigns
   to a non-existent var via an illegal expression. The error should halt
   the foreach after the first item, leaving Var1 = 1.

   Source: https://www.w3.org/Voice/2013/scxml-irp/156/test156.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry assign foreach
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-156
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 0 :Var2 nil :Var3 [1 2 3]}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (foreach {:array (fn [_ d] (:Var3 d)) :item [:Var2]}
            (assign {:location [:Var1] :expr (fn [_ d] (inc (:Var1 d)))})
            ;; illegal expression — throws an exception — stops the foreach
            (assign {:location [:Var1] :expr (fn [_ _] (throw (ex-info "illegal" {})))})))
        (transition {:cond (fn [_ d] (= 1 (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 156 — error inside foreach body stops iteration (Var1 = 1)"
  (assertions
    "reaches pass"
    (runner/passes? chart-156 []) => true))
