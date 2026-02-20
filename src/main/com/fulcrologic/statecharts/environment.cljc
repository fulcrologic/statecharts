(ns com.fulcrologic.statecharts.environment
  "Helper functions related to the environment that can be used in a lambda execution environment on
   `env`."
  (:require
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn ?]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]))

;; TODO: Several implentations in this ns assume the base implementation. If we add new algorithms, then they will need
;; to be updated. Probably will have to be added to some kind of protocol or the env.

(>defn session-id
  "Returns the session ID from an env."
  [{::sc/keys [vwmem] :as _env}]
  [[:map ::sc/vwmem] => (? ::sc/session-id)]
  (some-> vwmem deref ::sc/session-id))

(defn parent-session-id
  "Returns the session ID of the parent that invoked this chart (if any)."
  [{::sc/keys [vwmem] :as _env}]
  (some-> vwmem deref ::sc/parent-session-id))

(defn invoke-id
  "Returns the ID of the invocation, if the `env` of the current statechart indicates it is one."
  [{::sc/keys [vwmem] :as _env}]
  (some-> vwmem deref :org.w3.scxml.event/invokeid))

(defn current-configuration
  "Returns the set of active states."
  [{::sc/keys [vwmem] :as _env}]
  [::sc/env => [:set :keyword]]
  (some-> vwmem deref ::sc/configuration))

(defn is-in-state?
  "Returns true if the given `state-id` is active (in the configuration) of the state chart."
  [env state-id]
  [::sc/env ::sc/id => boolean?]
  (let [active-states (current-configuration env)]
    (contains? active-states state-id)))

(>defn normalized-chart
  "Returns the normalized statechart definition"
  [env]
  [::sc/processing-env => ::sc/statechart]
  (::sc/statechart env))

(def In
  "[env state-id]

   Alias for `is-in-state?`"
  is-in-state?)

(>defn context-element-id
  "Returns the ID of the context (state of interest for the current operation) from an env, if set."
  [env]
  [[:map ::sc/context-element-id] => (? ::sc/id)]
  (::sc/context-element-id env))

(defn assign!
  "Side effect against the data model in `env`, with the given path-value pairs."
  [{::sc/keys [data-model] :as env} & {:as path-value-pairs}]
  (sp/update! data-model env {:ops (ops/set-map-ops path-value-pairs)})
  nil)

(defn delete!
  "Side effect against the data model in `env`, with the given keys/paths"
  [{::sc/keys [data-model] :as env} & ks]
  (sp/update! data-model env {:ops [(ops/delete ks)]})
  nil)

(>defn raise
  "Place an event on the internal event queue for immediate processing. Only callable from within active runnable content"
  ([env event-name data]
   [::sc/processing-env ::sc/event-name map? => nil?]
   (raise env (evts/new-event {:name event-name :data data})))
  ([{::sc/keys [vwmem] :as env} event]
   [::sc/processing-env ::sc/event-or-name => nil?]
   (vswap! vwmem update ::sc/internal-queue conj (evts/new-event event))
   nil))
