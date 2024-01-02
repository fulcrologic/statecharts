(ns com.fulcrologic.statecharts.invocation.future
  "Support for invoking futures (CLJ only) from statecharts. This support can be added by
   adding it to the env's `::sc/invocation-processors`. An invoke element can specify it wants to
   run a function in a future specifying the type as :future.

   The `src` attribute of the `invoke` element must be a lambda that takes one argument (the
   params of the invocation) and returns a map, which will be sent back to the invoking machine
   as the data of a `:done.invoke.invokeid` event.

   If the invocation is cancelled (the parent state is left), then future-cancel will be called on the future.
   "
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.protocols :as sp]
    [taoensso.timbre :as log]))

(defrecord FutureInvocationProcessor [active-futures]
  sp/InvocationProcessor
  (supports-invocation-type? [_this typ] (= :future typ))
  (start-invocation! [_this {::sc/keys [event-queue]
                             :as       env} {:keys [invokeid src params]}]
    (log/trace "Start future " invokeid src params)
    (let [source-session-id (env/session-id env)
          child-session-id  (str source-session-id "." invokeid)
          done-event-name   (keyword (str "done.invoke." invokeid))]
      (if-not (fn? src)
        (sp/send! event-queue env {:target            source-session-id
                                   :sendid            child-session-id
                                   :source-session-id child-session-id
                                   :event             :error.platform
                                   :data              {:message "Could not invoke future. No function supplied."
                                                       :target  src}})
        (let [f (future
                  (try
                    (let [result (src params)]
                      (sp/send! event-queue env {:target            source-session-id
                                                 :sendid            child-session-id
                                                 :source-session-id child-session-id
                                                 :event             done-event-name
                                                 :data              (if (map? result) result {})}))
                    (finally
                      (swap! active-futures dissoc child-session-id))))]
          (swap! active-futures assoc child-session-id f)))
      true))
  (stop-invocation! [_ env {:keys [invokeid]}]
    (log/trace "Stop future" invokeid)
    (let [source-session-id (env/session-id env)
          child-session-id  (str source-session-id "." invokeid)
          f                 (get @active-futures child-session-id)]
      (when f
        (log/trace "Sending cancel to future")
        (future-cancel f))
      true))
  (forward-event! [_this _env _event]
    (log/warn "Future event forwarding not supported")))

(defn new-future-processor
  "Create an invocation processor that can be used to run functions in futures."
  []
  (->FutureInvocationProcessor (atom {})))
