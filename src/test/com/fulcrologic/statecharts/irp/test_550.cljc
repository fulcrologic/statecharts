(ns com.fulcrologic.statecharts.irp.test-550
  "IRP test 550 — `expr` can assign a value to a data-model variable with early
   binding. The data model for s1 (with Var1=2) is initialized before any state
   is entered (early binding), so s0 can see Var1==2 → pass.

   Source: https://www.w3.org/Voice/2013/scxml-irp/550/test550.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-550
  (chart/statechart {:initial :_root :binding :early}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (transition {:cond (fn [_ d] (= 2 (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (state {:id :s1}
        (data-model {:expr {:Var1 2}}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 550 — early binding initializes all data models before entering states"
  (assertions
    "reaches pass"
    (runner/passes? chart-550 []) => true))
