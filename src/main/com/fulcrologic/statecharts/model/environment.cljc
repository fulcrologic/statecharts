(ns com.fulcrologic.statecharts.model.environment
  "Helper functions related to the environment used by models."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.util :refer [queue]]))

(defn new-env
  ([machine working-memory context-element-id DM Q Ex addl]
   (merge addl (new-env machine working-memory context-element-id DM Q Ex)))
  ([machine working-memory context-element-id DM Q Ex]
   (cond-> {:machine         machine
            :data-model      DM
            :event-queue     Q
            :execution-model Ex
            :working-memory  working-memory
            :pending-events  (queue)
            :session-id      (::sc/session-id working-memory)}
     context-element-id (assoc :context-element-id context-element-id))))

(defn send-internal-event!
  "Put an event on the pending queue. Only usable from within the implementation of a model (a function
   that receives and env)."
  [env event]
  (when event
    (swap! (:pending-events env) conj event)))

(defn send-error-event!
  "Put an error (typically an exception) on the pending internal queue. Only usable from within the implementation of a model (a function
   that receives and env)."
  [env event-name error extra-data]
  (send-internal-event! env
    (evts/new-event {:name  event-name
                     :error error
                     :type  :platform} (select-keys env [:session-id :context-element-id]))))
