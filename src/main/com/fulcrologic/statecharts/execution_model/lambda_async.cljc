(ns com.fulcrologic.statecharts.execution-model.lambda-async
  "An execution model that supports expressions returning promises (via promesa).
   When an expression returns a plain value, it behaves identically to the sync lambda
   execution model. When an expression returns a promise, data model updates are deferred
   until the promise resolves."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [promesa.core :as p]
    [taoensso.timbre :as log]))

(defmulti run-async-op!
  "Dispatches an async operation map (one with `::sc/async?` true). Returns a promise.
   Implementations are registered by the namespace that defines the operation (e.g.
   `fulcro.async-operations` registers `:fulcro/async-load` and `:fulcro/async-mutation`)."
  (fn [_env op] (:op op)))

(defmethod run-async-op! :default [_env op]
  (throw (ex-info (str "Unknown async operation: " (:op op)) {:op op})))

(defn- maybe-then
  "If `v` is a promise, chain `f` via `p/then`; otherwise call `f` directly."
  [v f]
  (if (p/promise? v) (p/then v f) (f v)))

(defn- process-ops
  "Process a vector of operation maps that may contain a mix of sync and async ops.
   Sync ops are batched and sent to the data model. When an async op (`::sc/async?` true)
   is encountered, any accumulated sync batch is flushed first, then `run-async-op!` is
   called and its promise is chained before continuing with the remaining ops."
  [data-model env ops]
  (let [n (count ops)]
    (loop [i         0
           sync-batch []
           chain     nil]
      (if (< i n)
        (let [op (nth ops i)]
          (if (::sc/async? op)
            ;; Flush sync batch, then chain the async op
            (let [flush-and-async (fn [_]
                                   (when (seq sync-batch)
                                     (sp/update! data-model env {:ops sync-batch}))
                                   (run-async-op! env op))]
              (recur (inc i) [] (maybe-then chain flush-and-async)))
            ;; Accumulate sync op
            (recur (inc i) (conj sync-batch op) chain)))
        ;; End of ops — flush remaining sync batch
        (if (seq sync-batch)
          (let [flush (fn [_] (sp/update! data-model env {:ops sync-batch}))]
            (maybe-then chain flush))
          chain)))))

(defn- handle-result
  "Process the resolved `result` of an expression. If it is a vector and the env does not
   have `::sc/raw-result?` set, treat it as data model operations. If the vector contains
   any async ops (`::sc/async?` true), they are dispatched via `run-async-op!`."
  [data-model env result]
  (let [update? (and (not (::sc/raw-result? env))
                  (vector? result))]
    (when update?
      (if (some ::sc/async? result)
        (process-ops data-model env result)
        (do
          (log/trace "trying vector result as a data model update" result)
          (sp/update! data-model env {:ops result}))))
    result))

(deftype AsyncCLJCExecutionModel [data-model event-queue explode-events?]
  sp/ExecutionModel
  (run-expression! [_this env expr]
    (if (fn? expr)
      (let [data       (sp/current-data data-model env)
            raw-result (try
                         (if explode-events?
                           (let [{event-name :name
                                  event-data :data} (:_event data)]
                             (expr env data event-name event-data))
                           (expr env data))
                         (catch #?(:clj Throwable :cljs :default) e
                           (log/error e "Sync expression threw")
                           (throw e)))]
        (if (p/promise? raw-result)
          (p/then raw-result (fn [resolved-value]
                               (handle-result data-model env resolved-value)))
          (handle-result data-model env raw-result)))
      ;; Non-function expression (literal data)
      (let [update? (and (not (::sc/raw-result? env))
                      (vector? expr))]
        (when update?
          (log/trace "trying vector result as a data model update" expr)
          (sp/update! data-model env {:ops expr}))
        expr))))

(defn new-execution-model
  "Create a new execution model that supports async expressions. Expressions are `(fn [env data] ...)`
   and may return a plain value OR a promesa promise. When a promise is returned, data model updates
   are applied after the promise resolves.

   The `options` map can contain:

   * `:explode-event?` - Boolean (default false). If true, expressions receive four arguments:
     `(fn [env data event-name event-data] ...)`."
  ([data-model event-queue]
   (new-execution-model data-model event-queue {}))
  ([data-model event-queue {:keys [explode-event?]}]
   (->AsyncCLJCExecutionModel data-model event-queue explode-event?)))
