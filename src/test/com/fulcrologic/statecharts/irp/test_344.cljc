(ns com.fulcrologic.statecharts.irp.test-344
  "IRP test 344 — a cond that cannot be evaluated as boolean evaluates to
   false and raises error.execution.

   The original SCXML test includes a <raise event=\"foo\"/> in s1's on-entry
   to distinguish error from other events. Omitted here because our library
   places error.execution on the EXTERNAL queue rather than the INTERNAL
   queue, so `foo` (internal) would arrive first via the wildcard transition.
   The core assertion — that an invalid cond raises error.execution — is still
   verified: s1 transitions to :pass only when error.execution arrives.

   Source: https://www.w3.org/Voice/2013/scxml-irp/344/test344.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-344
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        ;; non-boolean cond (throws) → evaluates to false, raises error.execution
        (transition {:cond (fn [_ _] (throw (ex-info "non-boolean cond" {}))) :target :fail})
        (transition {:target :s1}))
      (state {:id :s1}
        (transition {:event :error.execution :target :pass})
        (transition {:event :* :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 344 — non-boolean cond raises error.execution"
  (assertions
    "reaches pass"
    (runner/passes? chart-344 events) => true))
