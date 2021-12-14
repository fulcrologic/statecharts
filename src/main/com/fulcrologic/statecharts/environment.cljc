(ns com.fulcrologic.statecharts.environment
  "Helper functions related to the environment used by models."
  (:require
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    com.fulcrologic.statecharts.specs
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.util :refer [queue]]))

(>defn new-env
  ([machine DM Q Ex addl]
   [::sc/machine ::sc/data-model ::sc/event-queue ::sc/execution-model map? => ::sc/env]
   (merge addl (new-env machine DM Q Ex)))
  ([machine DM Q Ex]
   [::sc/machine ::sc/data-model ::sc/event-queue ::sc/execution-model => ::sc/env]
   (cond-> {::sc/machine         machine
            ::sc/data-model      DM
            ::sc/event-queue     Q
            ::sc/execution-model Ex
            ::sc/pending-events  (volatile! (queue))})))

(>defn send-internal-event!
  "Put an event on the pending queue. Only usable from within the implementation of a model (a function
   that receives and env)."
  [env event]
  [::sc/env ::sc/event => nil?]
  (when event
    (vswap! (::sc/pending-events env) conj event))
  nil)

(>defn session-id
  "Returns the session ID from an env."
  [{::sc/keys [vwmem] :as env}]
  [::sc/env => (? ::sc/session-id)]
  (some-> vwmem deref ::sc/session-id))

(>defn context-element-id
  "Returns the ID of the context (state of interest for the current operation) from an env, if set."
  [env]
  [::sc/env => (? ::sc/id)]
  (::sc/context-element-id env))

(>defn send-error-event!
  "Put an error (typically an exception) on the pending internal queue. Only usable from within the implementation of a model (a function
   that receives and env)."
  [env event-name error extra-data]
  [::sc/env ::sc/event-name any? map? => nil?]
  (send-internal-event! env
    (evts/new-event {:name  event-name
                     :data  (merge extra-data {:context-element-id (context-element-id env)
                                               :session-id         (session-id env)})
                     :error error
                     :type  :platform}))
  nil)
