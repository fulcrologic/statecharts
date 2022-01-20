(ns custom-invocation
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :refer [statechart]]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [state transition script-fn invoke]]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.event-queue.core-async-event-loop :as loop]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.simple :as simple]
    [taoensso.timbre :as log]))

(deftype Timer [active-invocations]
  sp/InvocationProcessor
  (supports-invocation-type? [_this typ] (= typ :timer))
  (start-invocation! [_this {::sc/keys [event-queue]
                             :as       env} {:keys [invokeid params]}]
    (let [source-session-id (env/session-id env)
          {:keys [interval]
           :or   {interval 1000}} params
          time-id           (str source-session-id "." invokeid)
          notify!           (fn []
                              (log/info "sending notification")
                              (sp/send! event-queue env
                                {:target            source-session-id
                                 :send-id           time-id
                                 ;; IMPORTANT: If you don't include the invokeid, then it won't register in finalize
                                 :invoke-id         invokeid
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
  (stop-invocation! [_ env {:keys [invokeid] :as data}]
    (log/spy :info data)
    (log/info "Invocation" invokeid "asked to stop")
    (let [source-session-id (env/session-id env)
          time-id           (str source-session-id "." invokeid)]
      (swap! active-invocations dissoc time-id)
      true))
  (forward-event! [_this _env _event] nil))

(defn new-timer-service
  "Create a new time service that can be invoked from a state chart."
  [] (->Timer (atom {})))

(def demo-chart
  (statechart {}
    (state {:id :A}
      (transition {:event :swap :target :B}))
    (state {:id :B}
      (transition {:event :swap :target :A})
      (transition {:event :interval-timer/timeout}
        (script-fn [_ data] (log/info "Main transition got data" (select-keys data [:B :timer-calls]))))

      (invoke {:idlocation [:B :invocation-id]
               :type       :timer
               :params     {:interval 500}
               :finalize   (fn [_env {:keys [_event timer-calls]}]
                             (log/info "Finalize got timer calls data: " [timer-calls _event])
                             ;; Finalize gets to update the model before the event is delivered...
                             [(ops/assign :timer-calls (inc (or timer-calls 0)))])}))))

(comment
  (do
    (def env (simple/simple-env {::sc/invocation-processors [(new-timer-service)]}))
    (simple/register! env `demo-chart demo-chart)
    (def queue (::sc/event-queue env))
    (def processor (::sc/processor env))
    (def session-id 42)
    (def wmem (atom {}))
    (def running? (loop/run-event-loop! env 100)))

  (simple/start! env `demo-chart session-id)

  ;; Send this one multiple times
  (simple/send! env {:target session-id
                     :event  :swap})

  ;; Send this one to exit the top level machine
  (simple/send! env {:target session-id
                     :event  evts/cancel-event})

  ;; stop the event loop
  (reset! running? false)
  )
