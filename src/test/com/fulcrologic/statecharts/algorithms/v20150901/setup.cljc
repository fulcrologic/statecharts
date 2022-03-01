(ns com.fulcrologic.statecharts.algorithms.v20150901.setup
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.algorithms.v20150901 :as alg]
    [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [com.fulcrologic.statecharts.execution-model.lambda :as lambda]
    [com.fulcrologic.statecharts.protocols :as sp]))

(defn test-env [machine]
  (let [data-model  (wmdm/new-model)
        event-queue (mpq/new-queue)
        executor    (lambda/new-execution-model data-model event-queue)]
    {::sc/data-model          data-model
     ::sc/execution-model     executor
     ::sc/processor           (alg/new-processor)
     ::sc/statechart-registry (reify
                                sp/StatechartRegistry
                                (get-statechart [_ _] machine))
     ::sc/event-queue         event-queue}))
