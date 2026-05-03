(ns com.fulcrologic.statecharts.irp.test-287
  "IRP test 287 — a simple `<assign>` of a legal value to a valid data-model
   location succeeds (Var1 0 → 1).

   Source: https://www.w3.org/Voice/2013/scxml-irp/287/test287.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry assign data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-287
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 0}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (assign {:location [:Var1] :expr (fn [_ _] 1)}))
        (transition {:cond (fn [_ d] (= 1 (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 287 — assign of legal value to valid location"
  (assertions
    "reaches pass"
    (runner/passes? chart-287 []) => true))
