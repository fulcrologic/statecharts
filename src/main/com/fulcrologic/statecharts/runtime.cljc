(ns com.fulcrologic.statecharts.runtime
  "This ns provides utility functions for working with a statecharts runtime environment, where you have some
   number of statecharts running using the setup defined as a statechart env. Note that a statechart env is
   a map that defines a statechart system's environment: That is to say the processing algorithm,
   event queue, the working memory store, the invocation processor, the execution model, and the data model. All of
   these components are pluggable.

   This namespace also assumes that you are doing the *recommended* (but not required) step of using a working memory
   store and event queue via those protocols (the algorithm can also be used as a pure function).

   So, the functions in this namespace that ask for an ::sc/env (see the function definition) require that such
   both of those are provided.

   The `env` INSIDE of statechart lambdas is known as a *processing environment*. A *processing environment* IS
   a system env that ALSO contains keys relating to the current execution context. Such a map can be used with
   these functions as well (in other words these functions work both outside AND inside of a statechart),
   though they assume you're passing a processing environment (and ignore the processing keys)."
  (:require
    [com.fulcrologic.guardrails.malli.core :refer [>defn => ?]]
    [com.fulcrologic.statecharts.algorithms.v20150901-impl :as impl]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.malli-specs]
    [taoensso.timbre :as log]))

(>defn current-configuration
  "Returns the current configuration of the statechart with the given session-id. You MUST be using an implementation
   that uses a WorkingMemoryStore for this to work.

   Returns a set of keywords for the states that are active, or nil if there is no such session."
  [{::sc/keys [working-memory-store] :as env} session-id]
  [[:map ::sc/working-memory-store] ::sc/session-id => (? [:set :keyword])]
  (let [{::sc/keys [configuration]} (sp/get-working-memory working-memory-store env session-id)]
    configuration))

(>defn processing-env
  "Returns the processing env (as would be seen from within a statechart expression) for the given system environment
  and session-id.

  Assumes you are using the v20150901 implementation of statecharts (the default), and that your system has a working
  memory store.
  "
  [{::sc/keys [working-memory-store] :as system-env} session-id]
  [::sc/env ::sc/session-id => (? ::sc/processing-env)]
  (let [{::sc/keys [statechart-src] :as wmem} (sp/get-working-memory working-memory-store system-env session-id)]
    (when statechart-src
      (impl/processing-env system-env statechart-src wmem))))

(>defn session-data
  "Return the statechart-local data for the given system environment, statechart session id, and abstract data path
   (defined by the data model itself). Note that data models that use runtime context (such as active state) will not
   work properly with this method. If no `data-path` is provided, then the entire data model value is returned.

   Assumes you are using the v20150901 implementation of statecharts (the default).
   "
  ([{::sc/keys [data-model working-memory-store] :as env} session-id]
   [[:map ::sc/data-model ::sc/working-memory-store] ::sc/session-id => :any]
   (when-let [penv (processing-env env session-id)]
     (sp/current-data data-model penv)))
  ([{::sc/keys [data-model working-memory-store] :as env} session-id data-path]
   [[:map ::sc/data-model ::sc/working-memory-store] ::sc/session-id vector? => :any]
   (when-let [penv (processing-env env session-id)]
     (sp/get-at data-model penv data-path))))

(>defn send!
  "Send an event to a particular statechart session.

   event-name - The keyword name of the event
   event-data - A map of data
   options - A map with event options. Supported options are:
     :delay - (implementation specific) delay before sending. Default implementation uses ms.
     :type - (implementation specific) how to transport the event. The default is to deliver to statecharts in the local system.
  "
  ([{::sc/keys [event-queue] :as env} session-id event-name event-data options]
   [::sc/env ::sc/session-id :keyword map? [:map {:closed true}
                                            [:type {:optional true} :any]
                                            [:delay {:optional true} :any]] => :boolean]
   (sp/send! event-queue env (merge options
                               {:event  event-name
                                :target session-id
                                :data   event-data})))
  ([{::sc/keys [event-queue] :as env} session-id event-name event-data]
   [::sc/env ::sc/session-id :keyword map? => :boolean]
   (sp/send! event-queue env {:event  event-name
                              :target session-id
                              :data   event-data}))
  ([{::sc/keys [event-queue] :as env} session-id event-name]
   [::sc/env ::sc/session-id :keyword => :boolean]
   (send! env session-id event-name {})))
