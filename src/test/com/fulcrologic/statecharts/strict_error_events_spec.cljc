(ns com.fulcrologic.statecharts.strict-error-events-spec
  "Strict mode (`::sc/errors-abort-siblings?`) rethrows executable-content errors so the enclosing block aborts. Each
   failure must still queue exactly ONE `error.execution` (W3C SCXML §4.4), not one per enclosing catch.

   Every chart below handles the first `error.execution` by moving to `:check` and raising `:probe`. If a duplicate
   error was queued it is processed before `:probe` and routes to `:fail`. Charts run through the IRP runner, which
   uses strict envs under the sync processor, the async processor, and promise-returning expressions."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [assign final foreach on-entry raise Send state transition]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=>]]))

(defn- single-error-chart
  "A chart whose `:s0` entry runs `failing-content`, expecting exactly one resulting `error.execution`."
  [failing-content]
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {} failing-content)
        (transition {:event :error.execution :target :check}
          (raise {:event :probe})))
      (state {:id :check}
        (transition {:event :probe :target :pass})
        (transition {:event :* :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def failing-expression-chart
  (single-error-chart (assign {:location [:x] :expr (fn [_ _] (throw (ex-info "boom" {})))})))

(def failing-send-chart
  (single-error-chart (Send {:event :never :type "x-unsupported-target-type" :target "#_nonexistent"})))

(def non-iterable-foreach-chart
  (single-error-chart (foreach {:array (fn [_ _] 42) :item [:item]}
                        (raise {:event :never}))))

(def failing-foreach-body-chart
  (single-error-chart (foreach {:array (fn [_ _] [1 2]) :item [:item]}
                        (assign {:location [:x] :expr (fn [_ _] (throw (ex-info "boom" {})))}))))

(runner/async-specification "strict mode: a failing expression queues one error.execution"
  "reaches pass"
  (runner/passes? failing-expression-chart) => true)

(runner/async-specification "strict mode: a failed <send> dispatch queues one error.execution"
  "reaches pass"
  (runner/passes? failing-send-chart) => true)

(runner/async-specification "strict mode: <foreach> over a non-iterable queues one error.execution"
  "reaches pass"
  (runner/passes? non-iterable-foreach-chart) => true)

(runner/async-specification "strict mode: an error inside a <foreach> body queues one error.execution"
  "reaches pass"
  (runner/passes? failing-foreach-body-chart) => true)
