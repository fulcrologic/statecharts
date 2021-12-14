(ns com.fulcrologic.statecharts.algorithms.v20150901
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [com.fulcrologic.statecharts.algorithms.v20150901-impl :as impl]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    com.fulcrologic.statecharts.specs))

(deftype Processor [base-env]
  sp/Processor
  (start! [this session-id]
    (let [env (impl/runtime-env base-env
                {::sc/session-id session-id})]
      (impl/initialize! env)))
  (process-event! [this wmem event]
    (let [env (impl/runtime-env base-env wmem)]
      (impl/process-event! env event))))

(>defn new-processor
  "Create a processor that can initialize and process events for the given machine definition.

   * `machine-spec` - A valid state machine definition.
   * `options` - A map containing:
   ** :data-model      (REQUIRED) A DataModel
   ** :execution-model (REQUIRED) An ExecutionModel
   ** :event-queue     (REQUIRED) An event queue

   Anything else you put in the options map becomes part of the runtime `env`.

   Returns a Processor. See protocols/Processor.
   "
  [machine-spec {:keys [data-model execution-model event-queue]
                 :as   options}]
  [::sc/machine (s/keys :req-un
                  [::sc/data-model
                   ::sc/execution-model
                   ::sc/event-queue]) => ::sc/processor]
  (->Processor (merge
                 (dissoc options :data-model :execution-model :event-queue)
                 {::sc/machine         machine-spec
                  ::sc/data-model      data-model
                  ::sc/execution-model execution-model
                  ::sc/event-queue     event-queue})))
