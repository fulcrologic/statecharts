(ns com.fulcrologic.statecharts.irp.test-396
  "IRP test 396 — `_event.name` matches the event name used for transition matching.
   A raised `foo` event has `_event.name == :foo`, which is used as a condition on
   the first transition to route to pass.

   Source: https://www.w3.org/Voice/2013/scxml-irp/396/test396.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition on-entry raise]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-396
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {} (raise {:event :foo}))
        (transition {:event :foo :cond (fn [_ d] (= :foo (:name (:_event d)))) :target :pass})
        (transition {:event :foo :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 396 — _event.name matches event name used for transitions"
  (assertions
    "reaches pass"
    (runner/passes? chart-396 []) => true))
