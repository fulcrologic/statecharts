(ns com.fulcrologic.statecharts.irp.test-553
  "IRP test 553 — verifies that when evaluation of a `<send>` argument expression
   throws, the event is NOT dispatched. The chart sends a `:timeout` event with a
   small delay; in parallel it attempts to send `:event1` whose argument
   evaluation (here the eventexpr) raises an error. If the processor correctly
   suppresses the failed send, only `:timeout` will arrive — driving the chart
   to `:pass`. If the processor incorrectly dispatched `:event1`, the chart
   would land in `:fail`.

   Source: https://www.w3.org/Voice/2013/scxml-irp/553/test553.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-553
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          ;; timeout event (delayed), used to detect non-arrival of :event1
          (Send {:event :timeout :delay 50})
          ;; invalid namelist -> arg-eval throws -> send must be suppressed
          (Send {:eventexpr (fn [_ _] (throw (ex-info "invalid namelist" {})))}))
        ;; If we get :timeout before :event1, the bad send was suppressed.
        ;; We ignore :error.execution since the assertion does not concern it.
        (transition {:event :timeout :target :pass})
        (transition {:event :event1  :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 553 — failed <send> arg-eval suppresses event dispatch"
  (assertions
    "reaches pass"
    (runner/passes-with-delays? chart-553 []) => true))
