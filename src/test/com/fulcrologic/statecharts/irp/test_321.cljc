(ns com.fulcrologic.statecharts.irp.test-321
  "IRP test 321 — `_sessionid` system variable is bound on startup. We capture
   it into Var1 from on-entry and assert it is non-nil.

   Source: https://www.w3.org/Voice/2013/scxml-irp/321/test321.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry assign data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-321
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 nil}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (assign {:location [:Var1] :expr (fn [_ d] (:_sessionid d))}))
        (transition {:cond (fn [_ d] (some? (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 321 — _sessionid is bound at startup"
  (assertions
    "reaches pass"
    (runner/passes? chart-321 []) => true))
