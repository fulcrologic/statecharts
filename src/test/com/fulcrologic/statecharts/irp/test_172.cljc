(ns com.fulcrologic.statecharts.irp.test-172
  "IRP test 172 — `eventexpr` on <send> must use the CURRENT value of the
   referenced variable, not its initial value. Var1 is initialised to :event1
   then assigned :event2 in the same on-entry block before the send.

   Source: https://www.w3.org/Voice/2013/scxml-irp/172/test172.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send assign
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-172
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 :event1}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (assign {:location [:Var1] :expr (fn [_ _] :event2)})
          (Send {:eventexpr (fn [_ d] (:Var1 d))}))
        (transition {:event :event2 :target :pass})
        (transition {:event :*      :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 172 — send eventexpr uses current value of Var1"
  (assertions
    "reaches pass"
    (runner/passes? chart-172 []) => true))
