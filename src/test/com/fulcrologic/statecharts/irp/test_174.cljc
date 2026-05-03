(ns com.fulcrologic.statecharts.irp.test-174
  "IRP test 174 — `typeexpr` on <send> must use the CURRENT value of the
   referenced variable, not its initial value. Var1 starts as an invalid send
   type and is reassigned to a valid SCXML event-processor URL before the
   send. The send must therefore succeed and event1 be delivered.

   Source: https://www.w3.org/Voice/2013/scxml-irp/174/test174.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send assign
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-174
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 "x-not-a-valid-type"}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (assign {:location [:Var1]
                   :expr     (fn [_ _] "http://www.w3.org/TR/scxml/#SCXMLEventProcessor")})
          (Send {:event :event1
                 :typeexpr (fn [_ d] (:Var1 d))}))
        (transition {:event :event1 :target :pass})
        (transition {:event :*      :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 174 — send typeexpr uses current value of Var1"
  (assertions
    "reaches pass"
    (runner/passes? chart-174 []) => true))
