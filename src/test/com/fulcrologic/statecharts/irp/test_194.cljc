(ns com.fulcrologic.statecharts.irp.test-194
  "IRP test 194 — `<send>` with an illegal target/type must raise
   `:error.execution`. We use an unsupported `:type` value (anything that is
   not a recognised SCXML processor type) to trigger the error path in
   `manually_polled_queue/send!` (which returns false for unsupported types,
   causing the algorithm to raise error.execution).

   Source: https://www.w3.org/Voice/2013/scxml-irp/194/test194.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-194
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          ;; illegal type → error.execution
          (Send {:event :event2 :type "http://example.com/not-a-real-processor"})
          ;; safety net so we don't hang
          (Send {:event :timeout}))
        (transition {:event :error.execution :target :pass})
        (transition {:event :*                :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 194 — illegal send target raises error.execution"
  (assertions
    "reaches pass"
    (runner/passes? chart-194 []) => true))
