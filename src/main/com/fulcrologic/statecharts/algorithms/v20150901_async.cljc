(ns com.fulcrologic.statecharts.algorithms.v20150901-async
  "Async-capable processor implementing the W3C SCXML 2015-09-01 algorithm.

   This processor supports expressions that return promesa promises. When all expressions
   are synchronous, it behaves identically to the sync `v20150901` processor. When an
   expression returns a promise, the algorithm parks until it resolves.

   `start!` and `process-event!` may return a promise that resolves to working memory.
   Callers must handle both plain values and promises (use `promesa.core/promise?` to detect,
   or `promesa.core/let` which handles both transparently)."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.algorithms.v20150901-async-impl :as impl]
    [com.fulcrologic.statecharts.protocols :as sp]
    [promesa.core :as p]))

(deftype AsyncProcessor []
  sp/Processor
  (start! [_this env statechart-src params]
    (let [env (impl/processing-env env statechart-src params)]
      (impl/initialize! env (assoc params ::sc/statechart-src statechart-src))))
  (process-event! [_this env wmem event]
    (let [{::sc/keys [statechart-src]} wmem
          env (impl/processing-env env statechart-src wmem)]
      (impl/process-event! env event)))
  (exit! [_this env wmem skip-done-event?]
    (let [{::sc/keys [statechart-src]} wmem
          env (impl/processing-env env statechart-src wmem)]
      (let [result (impl/exit-interpreter! env skip-done-event?)]
        (if (p/promise? result)
          (p/then result (fn [_] nil))
          nil)))))

(defn new-processor
  "Create an async-capable processor. Expressions may return promesa promises and the
   algorithm will park until they resolve. When all expressions are synchronous, there
   is minimal overhead compared to the sync processor.

   Returns an AsyncProcessor. See `protocols/Processor`."
  []
  (->AsyncProcessor))
