(ns com.fulcrologic.statecharts.environment
  "Helper functions related to the environment used by models."
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    com.fulcrologic.statecharts.specs
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.util :refer [queue]]
    [com.fulcrologic.statecharts.protocols :as sp]))

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
  [(s/keys :req [::sc/context-element-id]) => (? ::sc/id)]
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

(s/def ::assignment-pairs (s/* (s/tuple (s/or :k keyword? :path vector?) any?)))

(>defn assign!
  "Side effect against the data model in `env`, with the given path-value pairs."
  [{::sc/keys [data-model] :as env} & {:as path-value-pairs}]
  [::sc/env ::assignment-pairs => nil?]
  (sp/transact! data-model env {:txn (ops/set-map-txn path-value-pairs)})
  nil)

(>defn delete!
  "Side effect against the data model in `env`, with the given keys/paths"
  [{::sc/keys [data-model] :as env} & ks]
  [::sc/env ::assignment-pairs => nil?]
  (sp/transact! data-model env {:txn [(ops/delete ks)]})
  nil)
