(ns com.fulcrologic.statecharts.simple
  "Functions that set up the simplest version of a state chart that uses the v20150901
   implementation (version), a working memory data model,
   CLJC execution support, and an event queue that requires manual polling."
  (:require
    [com.fulcrologic.statecharts.algorithms.v20150901 :as alg]
    [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
    [com.fulcrologic.statecharts.execution-model.lambda :as lambda]
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [com.fulcrologic.statecharts.protocols :as sp]))

(deftype SimpleMachine [P DM Q EX]
  sp/DataModel
  (load-data [provider env src] (sp/load-data DM env src))
  (current-data [provider env] (sp/current-data DM env))
  (get-at [provider env path] (sp/get-at DM env path))
  (transact! [provider env args] (sp/transact! DM env args))
  sp/EventQueue
  (send! [event-queue req] (sp/send! Q req))
  (cancel! [event-queue session-id send-id] (sp/cancel! Q session-id send-id))
  (receive-events! [event-queue options handler] (sp/receive-events! Q options handler))
  sp/ExecutionModel
  (run-expression! [model env expr] (sp/run-expression! EX env expr))
  sp/Processor
  (start! [this session-id] (sp/start! P session-id))
  (process-event! [this working-memory external-event]
    (sp/process-event! P working-memory external-event)))

(defn new-simple-machine [machine-def extra-env]
  (let [dm (wmdm/new-model)
        q  (mpq/new-queue)
        ex (lambda/new-execution-model dm q)]
    (->SimpleMachine
      (alg/new-processor machine-def (merge extra-env
                                       {:data-model      dm
                                        :execution-model ex
                                        :event-queue     q}))
      dm q ex)))
