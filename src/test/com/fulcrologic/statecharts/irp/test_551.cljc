(ns com.fulcrologic.statecharts.irp.test-551
  "IRP test 551 — inline content can be used to initialize a data-model variable.
   With early binding, s1's data model is initialized (Var1 = [1 2 3]) before
   entering s0. `conf:isBound` checks that Var1 is defined (not nil) → pass.

   Source: https://www.w3.org/Voice/2013/scxml-irp/551/test551.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-551
  (chart/statechart {:initial :_root :binding :early}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (transition {:cond (fn [_ d] (some? (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (state {:id :s1}
        (data-model {:expr {:Var1 [1 2 3]}}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 551 — inline data content initializes variable (early binding)"
  (assertions
    "reaches pass"
    (runner/passes? chart-551 []) => true))
