(ns com.fulcrologic.statecharts.execution-model.lambda
  "An execution model that expects expressions a conditions to be CLJ(C)
   expressions or functions. It integrates with the data model so that return values
   of scripts/expressions can return transactions for the data model. It also requires the event queue
   so it can send error events back to the machine if the expression has an error."
  (:require
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    [taoensso.timbre :as log]
    [com.fulcrologic.statecharts.environment :as env]))

(deftype CLJCExecutionModel [data-model event-queue]
  sp/ExecutionModel
  (run-expression! [this env expr]
    (if (fn? expr)
      (let [data       (sp/current-data data-model env)
            session-id (env/session-id env)
            result     (try
                         (log/trace "Trying to run function in" session-id)
                         (expr env data)
                         (catch #?(:clj Throwable :cljs :default) e
                           (sp/send! event-queue {:event             (evts/new-event {:name :error.execution
                                                                                      :data {:error e}
                                                                                      :type :platform})
                                                  :send-id           :error.execution
                                                  :source-session-id session-id})
                           nil))
            update?    (vector? result)]
        (when update?
          (log/trace "replacing data model with" result)
          (sp/transact! data-model env {:txn result}))
        result)
      (log/warn "Execution model didn't understand" expr))))

(defn new-execution-model
  "Create a new execution model that expects state machine expressions to be `(fn [env expr])`. Such expressions
   can return arbitrary values, can use the data model from the `env` to side-effect into the data model. If
   an expression (NOT as a condition) returns a vector, then an attempt will be made to run it as a data model
   transaction."
  [data-model event-queue]
  (->CLJCExecutionModel data-model event-queue))
