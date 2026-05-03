(ns com.fulcrologic.statecharts.irp.test-176
  "IRP test 176 — `<param>` (modeled here as `:namelist`) on <send> must use
   the CURRENT value of the referenced variable. Var1 starts at 1 then is
   reassigned to 2; the send packs aParam=Var1 (so 2). On receipt of event1,
   Var2 is set from event.data.aParam — should be 2.

   Source: https://www.w3.org/Voice/2013/scxml-irp/176/test176.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send assign
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-176
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 1}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (assign {:location [:Var1] :expr (fn [_ _] 2)})
          (Send {:event :event1 :namelist {:aParam [:Var1]}}))
        (transition {:event :event1 :target :s1}
          (assign {:location [:Var2]
                   :expr     (fn [_ d] (get-in d [:_event :data :aParam]))}))
        (transition {:event :* :target :fail}))
      (state {:id :s1}
        (transition {:cond (fn [_ d] (= 2 (:Var2 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 176 — send <param> uses current value of Var1"
  (assertions
    "reaches pass"
    (runner/passes? chart-176 []) => true))
