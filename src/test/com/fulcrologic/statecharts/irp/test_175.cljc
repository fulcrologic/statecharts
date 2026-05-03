(ns com.fulcrologic.statecharts.irp.test-175
  "IRP test 175 — `delayexpr` on <send> must use the CURRENT value of the
   referenced variable. Var1 starts at 0 and is reassigned to 1000ms; the
   first <send> uses delayexpr=Var1 (1000ms) for event2, and the second
   uses literal 500ms for event1. event1 must therefore arrive first.

   Source: https://www.w3.org/Voice/2013/scxml-irp/175/test175.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send assign
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-175
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 0}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (assign {:location [:Var1] :expr (fn [_ _] 1000)})
          (Send {:event :event2 :delayexpr (fn [_ d] (:Var1 d))})
          (Send {:event :event1 :delay 500}))
        (transition {:event :event1 :target :s1})
        (transition {:event :event2 :target :fail}))
      (state {:id :s1}
        (transition {:event :event2 :target :pass})
        (transition {:event :*      :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 175 — send delayexpr uses current value of Var1"
  (assertions
    "reaches pass (event1 before event2)"
    (runner/passes-with-delays? chart-175 []) => true))
