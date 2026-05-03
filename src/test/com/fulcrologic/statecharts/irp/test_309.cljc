(ns com.fulcrologic.statecharts.irp.test-309
  "IRP test 309 — a transition cond expression that cannot be interpreted as
   a boolean must be treated as false (so the transition is not selected).
   Source: https://www.w3.org/Voice/2013/scxml-irp/309/test309.txml

   Translation: this library coerces cond results with `boolean` and
   converts evaluation errors to `error.execution` (with the cond returning
   nil, i.e. false). To exercise the \"non-boolean → false\" semantics in a
   way that exists in our model, the cond throws — the library's
   `run-expression!` catches and returns nil, which `condition-match` then
   treats as false."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-309
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (transition {:cond   (fn [_ _] (throw (ex-info "non-boolean" {})))
                     :target :fail})
        (transition {:target :pass}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 309 — non-boolean cond is treated as false"
  (assertions
    "reaches pass"
    (runner/passes? chart-309 []) => true))
