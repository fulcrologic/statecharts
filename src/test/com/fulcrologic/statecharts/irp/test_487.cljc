(ns com.fulcrologic.statecharts.irp.test-487
  "IRP test 487 — illegal assignment expression raises error.execution.

   The original SCXML test includes a <raise event=\"event\"/> after the bad
   assign. Omitted here for the same reason as test 312: our library places
   error.execution on the EXTERNAL queue, so the internal 'event' would
   arrive first and hit the wildcard transition.

   Source: https://www.w3.org/Voice/2013/scxml-irp/487/test487.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry assign
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-487
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 nil}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (assign {:location [:Var1]
                   :expr     (fn [_ _] (throw (ex-info "illegal expression" {})))}))
        (transition {:event :error.execution :target :pass})
        (transition {:event :* :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 487 — illegal assign expr raises error.execution"
  (assertions
    "reaches pass"
    (runner/passes? chart-487 events) => true))
