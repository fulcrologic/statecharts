(ns com.fulcrologic.statecharts.irp.test-554
  "IRP test 554 — verifies that if the evaluation of `<invoke>`'s argument
   expressions raises an error, the invocation is cancelled. The chart starts a
   timer of 200ms and tries to invoke a child. If the invocation actually starts
   and reaches its (immediate) final state, a `:done.invoke` event would arrive
   first and route to `:fail`. If arg-evaluation errors correctly cancel the
   invocation, no `:done.invoke` arrives, the timer fires, and we route to
   `:pass`.

   This port models the `conf:invalidNamelist` (which the spec defines as a
   namelist referring to an undeclared variable, expected to fail in strict
   ECMAScript) using a `:params` expression that throws synchronously. Per
   W3C SCXML §6.4: any error during `<invoke>` argument evaluation MUST cancel
   the invocation and place `error.execution` on the internal queue.

   Source: https://www.w3.org/Voice/2013/scxml-irp/554/test554.txml

   NOTE: currently expected to FAIL — see `_library_issues.md` (Test 554)."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send invoke]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-554
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (Send {:event :timer :delay 200}))
        ;; Argument evaluation throws — per W3C this must cancel the invocation
        ;; and raise error.execution. The :src refers to a chart that is NOT
        ;; registered; if arg eval were skipped and the invocation proceeded to
        ;; the registry, the processor would emit error.platform — still no
        ;; :done.invoke. The crucial property is: no :done.invoke arrives.
        (invoke {:type   :statechart
                 :src    :irp/child-554
                 :params (fn [_ _] (throw (ex-info "invalid namelist evaluation" {})))})
        (transition {:event :timer :target :pass})
        (transition {:event :done.invoke :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 554 — invoke arg-eval error cancels the invocation"
  (assertions
    "reaches pass"
    (runner/passes-with-delays? chart-554 []) => true))
