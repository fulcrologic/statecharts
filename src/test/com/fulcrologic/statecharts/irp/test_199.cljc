(ns com.fulcrologic.statecharts.irp.test-199
  "IRP test 199 — `<send>` with an invalid `type` raises `:error.execution`.
   The library's manual queue rejects unsupported types, which the algorithm
   converts to error.execution.

   Source: https://www.w3.org/Voice/2013/scxml-irp/199/test199.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-199
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          ;; invalid send type → error.execution
          (Send {:event :event1 :type "not-a-real-processor-type"})
          ;; safety net
          (Send {:event :timeout}))
        (transition {:event :error.execution :target :pass})
        (transition {:event :*                :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 199 — invalid send type raises error.execution"
  (assertions
    "reaches pass"
    (runner/passes? chart-199 []) => true))
