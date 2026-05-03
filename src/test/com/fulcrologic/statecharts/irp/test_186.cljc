(ns com.fulcrologic.statecharts.irp.test-186
  "IRP test 186 — `<send>` evaluates its arguments when it is evaluated, NOT
   when the delay interval expires. After scheduling event1 with aParam=Var1
   (Var1=1), Var1 is reassigned to 2. When event1 fires, aParam must still
   be 1.

   Source: https://www.w3.org/Voice/2013/scxml-irp/186/test186.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send assign
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-186
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 1}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (Send {:event :event1 :delay 1000 :namelist {:aParam [:Var1]}})
          (assign {:location [:Var1] :expr (fn [_ _] 2)}))
        (transition {:event :event1 :target :s1}
          (assign {:location [:Var2]
                   :expr     (fn [_ d] (get-in d [:_event :data :aParam]))}))
        (transition {:event :* :target :fail}))
      (state {:id :s1}
        (transition {:cond (fn [_ d] (= 1 (:Var2 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 186 — send args evaluated at send time, not delivery"
  (assertions
    "reaches pass"
    (runner/passes-with-delays? chart-186 []) => true))
