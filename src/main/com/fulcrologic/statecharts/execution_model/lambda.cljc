(ns com.fulcrologic.statecharts.execution-model.lambda
  "An execution model that expects expressions a conditions to be CLJ(C)
   expressions or functions. It integrates with the data model so that return values
   of scripts/expressions can return updates for the data model. It also requires the event queue
   so it can send error events back to the machine if the expression has an error."
  (:require
    [com.fulcrologic.statecharts.protocols :as sp]
    [taoensso.timbre :as log]))

(deftype CLJCExecutionModel [data-model event-queue explode-events?]
  sp/ExecutionModel
  (run-expression! [_this env expr]
    (if (fn? expr)
      (let [data    (sp/current-data data-model env)
            result  (log/spy :trace "expr => " (if explode-events?
                                                 (let [{event-name :name
                                                        event-data :data} (:_event data)]
                                                   (expr env data event-name event-data))
                                                 (expr env data)))
            update? (vector? result)]
        (when update?
          (log/trace "trying vector result as a data model update" result)
          (sp/update! data-model env {:ops result}))
        result)
      (let [update? (vector? expr)]
        (when update?
          (log/trace "trying vector result as a data model update" expr)
          (sp/update! data-model env {:ops expr}))
        expr))))

(defn new-execution-model
  "Create a new execution model that expects state machine expressions to be `(fn [env data])`. Such expressions
   can return arbitrary values, can use the data model from the `env` to side-effect into the data model. If
   an expression (NOT as a condition) returns a vector, then an attempt will be made to run it as a data model
   update.

   The options map can contain:

   * :explode-event? - Boolean (default false). If true the execution model will pre-extract the event name
     and event data from the data model (:_event). In this case your statechart must use arity-4 functions
     (at least in CLJ) for `(fn [env data event-name event-data] ...)`."
  ([data-model event-queue]
   (new-execution-model data-model event-queue {}))
  ([data-model event-queue {:keys [explode-event?]}]
   (->CLJCExecutionModel data-model event-queue true)))


