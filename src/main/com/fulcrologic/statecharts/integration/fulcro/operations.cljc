(ns com.fulcrologic.statecharts.integration.fulcro.operations
  "Operations that can be returned from executable elements in a statechart. Extends the normal set of `assign` et al."
  (:refer-clojure :exclude [load]))

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
  "
  [txn {:keys [target returning ok-event error-event ok-data error-data mutation-remote]
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
   "
  [query-root component-or-actor {:keys [] :as options}]
  {:op                 :fulcro/load
   :query-root         query-root
   :component-or-actor component-or-actor
   :options            options})
