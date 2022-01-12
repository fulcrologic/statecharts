(ns com.fulcrologic.statecharts.simple
  "Functions that set up the simplest version of a state chart that uses the v20150901
   implementation (version), a working memory data model,
   CLJC execution support, and an event queue that requires manual polling."
  (:require
    [com.fulcrologic.statecharts :as sc]
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
  (update! [provider env args] (sp/update! DM env args))
  sp/EventQueue
  (send! [event-queue req] (sp/send! Q req))
  (cancel! [event-queue session-id send-id] (sp/cancel! Q session-id send-id))
  (receive-events! [event-queue options handler] (sp/receive-events! Q options handler))
  sp/ExecutionModel
  (run-expression! [model env expr] (sp/run-expression! EX env expr))
  sp/Processor
  (get-base-env [this] (sp/get-base-env P))
  (start! [this session-id] (sp/start! P session-id))
  (process-event! [this working-memory external-event]
    (sp/process-event! P working-memory external-event)))

(defn new-simple-machine
  "Creates a machine that defauts uses the standard processing with a working memory data model,
   a manual event queue, and the lambda executor.

   `extra-env` can contain anything extra you want in `env`, and can override any of the above by
   key (e.g. :data-model)."
  [machine-def {::sc/keys [data-model execution-model event-queue] :as extra-env}]
  (let [dm (or data-model (wmdm/new-flat-model))
        q  (or event-queue (mpq/new-queue))
        ex (or execution-model (lambda/new-execution-model dm q))]
    (->SimpleMachine
      (alg/new-processor machine-def (merge {:data-model      dm
                                             :execution-model ex
                                             :event-queue     q}
                                       extra-env))
      dm q ex)))
