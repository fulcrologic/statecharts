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

   The child chart is registered (via `runner/with-helper-charts`) and completes
   immediately, so a wrongly started invocation WOULD produce `:done.invoke`. A
   control spec proves that by invoking it with arguments that evaluate cleanly."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send invoke]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=>]]))

(def child-554
  "Completes immediately, so starting it yields done.invoke in the parent."
  (chart/statechart {}
    (final {:id :done})))

(defn- invoking-chart
  "The test chart, invoking `:irp/child-554` with `params-expr` as its `:params`."
  [params-expr]
  (runner/with-helper-charts
    (chart/statechart {:initial :_root}
      (state {:id :_root :initial :s0}
        (state {:id :s0}
          (on-entry {}
            (Send {:event :timer :delay 200}))
          (invoke {:type   :statechart
                   :src    :irp/child-554
                   :params params-expr})
          (transition {:event :timer :target :pass})
          (transition {:event :done.invoke :target :fail}))
        (final {:id :pass})
        (final {:id :fail})))
    {:irp/child-554 child-554}))

(def chart-554
  "Argument evaluation throws, so per W3C the invocation must be cancelled (no done.invoke) and the timer wins."
  (invoking-chart (fn [_ _] (throw (ex-info "invalid namelist evaluation" {})))))

(def control-chart-554
  "Same chart with arguments that evaluate cleanly: the child starts, completes, and done.invoke routes to fail."
  (invoking-chart (fn [_ _] {:x 1})))

(runner/async-specification "IRP test 554 — invoke arg-eval error cancels the invocation"
  "reaches pass"
  (runner/passes-with-delays? chart-554 []) => true)

(runner/async-specification "IRP test 554 control — invocation with valid args starts and completes"
  "reaches fail via done.invoke"
  (runner/fails? control-chart-554 []) => true)
