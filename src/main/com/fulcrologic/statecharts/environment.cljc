(ns com.fulcrologic.statecharts.environment
  "Helper functions related to the environment that can be used in a lambda execution environment on
   `env`."
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.protocols :as sp]))

(>defn session-id
  "Returns the session ID from an env."
  [{::sc/keys [vwmem] :as _env}]
  [::sc/processing-env => (? ::sc/session-id)]
  (some-> vwmem deref ::sc/session-id))

(>defn is-in-state?
  "Returns true if the given `state-id` is active (in the configuration) of the state chart."
  [{::sc/keys [vwmem] :as _env} state-id]
  [::sc/env ::sc/id => boolean?]
  (let [active-states (some-> vwmem deref ::sc/configuration)]
    (contains? active-states state-id)))

(def In
  "[env state-id]

   Alias for `is-in-state?`"
  is-in-state?)

(>defn context-element-id
  "Returns the ID of the context (state of interest for the current operation) from an env, if set."
  [env]
  [(s/keys :req [::sc/context-element-id]) => (? ::sc/id)]
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
