(ns com.fulcrologic.statecharts.algorithms.v20150901
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.algorithms.v20150901-impl :as impl]
    [com.fulcrologic.statecharts.protocols :as sp]))

(deftype Processor []
  sp/Processor
  (start! [_this env statechart-src params]
    (let [env (impl/processing-env env statechart-src params)]
      (impl/initialize! env (assoc params ::sc/statechart-src statechart-src))))
  (process-event! [_this env wmem event]
    (let [{::sc/keys [statechart-src]} wmem
          env (impl/processing-env env statechart-src wmem)]
      (impl/process-event! env event)))
  (exit! [this env wmem skip-done-event?]
    (let [{::sc/keys [statechart-src]} wmem
          env (impl/processing-env env statechart-src wmem)]
      (impl/exit-interpreter! env skip-done-event?)
      nil)))

(defn new-processor
  "Create a processor that can initialize and process events for the given machine definition.

   Returns a Processor. See protocols/Processor.
   "
  []
  (->Processor))
