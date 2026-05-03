(ns com.fulcrologic.statecharts.irp.test-311
  "IRP test 311 — `<assign>` to an invalid location must raise
   `error.execution`.
   Source: https://www.w3.org/Voice/2013/scxml-irp/311/test311.txml

   Translation: this library's `run-expression!` catches any thrown
   `Throwable` from an expression and emits `error.execution`. The most
   reliable way to drive that path from a test (without depending on
   data-model-specific notions of \"invalid location\") is to make the
   `:expr` itself throw — semantically equivalent for the algorithm's
   error-event flow."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry assign Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-311
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (Send {:event :timeout :delay 1000})
          (assign {:location [:Var1]
                   :expr     (fn [_ _] (throw (ex-info "invalid location" {})))}))
        (transition {:event :error.execution :target :pass})
        (transition {:event :*                :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 311 — assign failure raises error.execution"
  (assertions
    "reaches pass"
    (runner/passes-with-delays? chart-311 []) => true))
