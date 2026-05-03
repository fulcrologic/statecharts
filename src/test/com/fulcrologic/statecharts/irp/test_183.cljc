(ns com.fulcrologic.statecharts.irp.test-183
  "IRP test 183 — `<send>` stores the generated send-id in `idlocation`.
   After the send, Var1 should be bound (non-nil).

   Source: https://www.w3.org/Voice/2013/scxml-irp/183/test183.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-183
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 nil}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (Send {:event :event1 :idlocation [:Var1]}))
        (transition {:cond (fn [_ d] (some? (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 183 — send writes generated send-id into idlocation"
  (assertions
    "reaches pass"
    (runner/passes? chart-183 []) => true))
