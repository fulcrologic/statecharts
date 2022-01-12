(ns custom-invocation
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.elements :refer [state parallel transition raise on-entry script-fn
                                                  assign data-model invoke Send]]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.event-queue.core-async-event-loop :as loop]
    [com.fulcrologic.statecharts.events :as evts :refer [new-event]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.simple :refer [new-simple-machine]]
    [com.fulcrologic.statecharts.state-machine :refer [machine]]
    [com.fulcrologic.statecharts.util :refer [extend-key]]
    [taoensso.timbre :as log]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]))

(deftype Timer [active-invocations]
  sp/InvocationProcessor
  (supports-invocation-type? [this typ] (= typ :timer))
  (start-invocation! [this {::sc/keys [event-queue]
                            :as       env} {:keys [invokeid params]}]
    (let [source-session-id (env/session-id env)
          {:keys [interval]
           :or   {interval 1000}} params
          time-id           (str source-session-id "." invokeid)
          notify!           (fn []
                              (log/info "sending notification")
                              (sp/send! event-queue
                                {:target            source-session-id
                                 :sendid            time-id
                                 :source-session-id time-id
                                 :event             :interval-timer/timeout}))]
      (swap! active-invocations assoc time-id true)
      (async/go-loop []
        (async/<! (async/timeout interval))
        (if (get @active-invocations time-id)
          (do
            (notify!)
            (recur))
          (log/info "Timer loop exited")))
      true))
  (stop-invocation! [this env {:keys [invokeid] :as data}]
    (log/spy :info data)
    (log/info "Invocation" invokeid "asked to stop")
    (let [source-session-id (env/session-id env)
          time-id           (str source-session-id "." invokeid)]
      (swap! active-invocations dissoc time-id)
      true))
  (forward-event! [this env {:keys [type invokeid event]}] nil))

(defn new-timer-service
  "Create a new time service that can be invoked from a state chart."
  [] (->Timer (atom {})))

(def demo-chart
  (machine {}
    (state {:id :A}
      (transition {:event :swap :target :B}))
    (state {:id :B}
      (transition {:event :swap :target :A})
      (transition {:event :interval-timer/timeout}
        (script-fn [_ data] (log/info "State got timer event" data)))

      (invoke {:idlocation [:B :invocation-id]
               :type       :timer
               :params     {:interval 500}
               :finalize   (fn [env event]
                             (log/info "Finalize got event: " event))}))))

(comment
  (def queue (mpq/new-queue))
  (def processor (new-simple-machine demo-chart {::sc/event-queue           queue
                                                 ::sc/invocation-processors [(new-timer-service)]}))
  (def session-id 42)
  (def wmem (atom {}))

  (loop/run-event-loop! processor wmem session-id 100)

  ;; Send this one multiple times
  (sp/send! queue {:target session-id
                   :event  :swap})

  ;; Send this one to exit the top level machine
  (sp/send! queue {:target session-id
                   :event  evts/cancel-event})
  )
