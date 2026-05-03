(ns com.fulcrologic.statecharts.irp.test-323
  "IRP test 323 — `_name` system variable is bound on startup. We capture it
   into Var1 from on-entry and assert it is non-nil.

   Source: https://www.w3.org/Voice/2013/scxml-irp/323/test323.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry assign data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-323
  (chart/statechart {:initial :_root :name "machineName"}
    (data-model {:expr {:Var1 nil}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (assign {:location [:Var1] :expr (fn [_ d] (:_name d))}))
        (transition {:cond (fn [_ d] (some? (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 323 — _name is bound at startup"
  (assertions
    "reaches pass"
    (runner/passes? chart-323 []) => true))
