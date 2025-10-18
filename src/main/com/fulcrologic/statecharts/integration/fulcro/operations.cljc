(ns com.fulcrologic.statecharts.integration.fulcro.operations
  "Operations that can be returned from executable elements in a statechart. Extends the normal set of `assign` et al."
  (:refer-clojure :exclude [load])
  (:require
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]))

(defn apply-action
  "An operation that runs the given `(apply f state-map params) on the Fulcro state map as if doing an optimistic mutation.` "
  [f & args]
  {:op :fulcro/apply-action :f f :args args})

(defn invoke-remote
  "An operation that will invoke the given remote `txn` (a single mutation). The options can
  include:

  target - The data-targeting target. Vector path into Fulcro state, a statechart alias (keyword). The path can
           start with `:actor/x` which will splice that actor's current ident into the target (if known).
  returning - A component class to use for normalizing the returned result of the mutation. Can also be a keyword
              naming an actor whose component class will be used instead, if known.
  ok-event - An event to send to the statechart when the mutation succeeds
  error-event - An event to send to the statechart when the mutation fails
  ok-data - Data to include in the `ok-event`
  error-data - Data to include in the `error-event`
  mutation-remote - The name of the remote to use. Defaults to `:remote`.
  tx-options - Options passed as the 3rd argument to rc/transact! (e.g. for abort id, etc.)
  "
  [txn {:keys [target returning ok-event error-event ok-data error-data mutation-remote
               tx-options]
        :as   options}]
  (merge options {:op :fulcro/invoke-remote :txn txn}))

(defn assoc-alias
  "Assign values via aliases."
  [& {:as kvs}] {:op :fulcro/assoc-alias :data kvs})

(defn load
  "Issue a load from a remote.

   query-root - A keyword or ident that will be the root of the remote query
   component-or-actor - A Fulcro component, or a keyword that names a known actor in
     the statechart that will provide the component (for normalization).

   The load options are exactly like `df/load!`, however, there are
   extended options for interacting with statecharts (::sc is :com.fulcrologic.statecharts):

   ::sc/ok-event - Event to send when the load completes without error (overrides :ok-action)
   ::sc/error-event - Event to send with load runs into an error (overrides :error-action)
   ::sc/target-alias - Target the load at a statechart alias (overrides :target)

   Default ok event is `:event.loading/ok` and the default error event is `:error.loading` (to
   match top-level wildcards around errors).
   "
  [query-root component-or-actor options]
  (let [default-options {::sc/ok-event    :event.loading/ok
                         ::sc/error-event :event.loading/error}
        options         (merge default-options options)]
    {:op                 :fulcro/load
     :query-root         query-root
     :component-or-actor component-or-actor
     :options            options}))

(defn set-actor
  "Change an actor to a new class/ident.

   data - The data argument of the expression you are running within.
   actor-name - The name of the actor you want to establish/change.

   The options map has:

   * :class The component class
   * :ident The ident of the class

   If you specify ident without class, then the old class with be retained. If you specify a class without an ident,
   then `get-ident` will be used to get the (presumably constant) ident of the class.
   "
  [data actor-name {:keys [class ident] :as options}]
  (let [class? (rc/component-class? class)
        ident? (eql/ident? ident)]
    (cond
      (and ident? class?) (ops/assign [:fulcro/actors actor-name] (scf/actor class ident))
      (and ident? (not class?)) (let [old-class (scf/resolve-actor-class data actor-name)]
                                  (ops/assign [:fulcro/actors actor-name] (scf/actor old-class ident)))
      (and (not ident?) class?) (ops/assign [:fulcro/actors actor-name] (scf/actor class))
      :else (do
              (log/error "Cannot set actor. Arguments were invalid: " options)
              nil))))
