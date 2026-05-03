(ns com.fulcrologic.statecharts.irp.test-312
  "IRP test 312 — an assign with an illegal expression raises error.execution.

   Source: https://www.w3.org/Voice/2013/scxml-irp/312/test312.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry assign
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-312
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 1}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          ;; illegal expr — throws, causing the algorithm to raise error.execution
          (assign {:location [:Var1]
                   :expr     (fn [_ _] (throw (ex-info "illegal expression" {})))}))
        (transition {:event :error.execution :target :pass})
        (transition {:event :* :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 312 — assign with illegal expr raises error.execution"
  (assertions
    "reaches pass"
    (runner/passes? chart-312 events) => true))
