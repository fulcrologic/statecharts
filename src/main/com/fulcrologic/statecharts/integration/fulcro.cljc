(ns com.fulcrologic.statecharts.integration.fulcro
  "Support for statecharts in Fulcro. The statechart support defined here has the following attributes:

  * It installs the statechart components (event queue, etc) on a Fulcro app. Thus, apps have their own private
  statechart system.
  * The statechart registry is also stored in the app.
  * The statechart working session is normalized into Fulcro's app state at [::id session-id], which makes it easy
    to include a statechart in a component query if you want to cause rendering changes based on state changes.
  * The working sessions are automatically GC'd from the state map when they reach a final state.
  * The data model of the charts has two layers:
    * Statechart local: Arbitrary operation keys/paths are local to the statechart's working session
    * Fulcro state map: Any path starting with [:fulcro/state-map ...] is a path relative to the root of the entire Fulcro state map.

  The data passed to executable content will include the standard `:_event`, the current data (local statechart data),
  and a `:fulcro/state-map` key that has the current snapshot of the Fulcro database.

  Returning ops from expressions would look like:

  ```
  [(op/assign [:fulcro/state-map :root-key] {:a 1})
   (op/assign [:local] 100)]
  ```

  would put `{:a 1}` in the fulcro state map under the top-level `:root-key`, and would store the
  `:local` key under the state machine's session.

  Future expressions would see the `:local` key in the `data` argument, and would also find the full Fulcro
  state map in `:fulcro/state-map`.
  "
  (:require
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.algorithms.v20150901 :as alg]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as op]
    [com.fulcrologic.statecharts.elements :as ele]
    [com.fulcrologic.statecharts.event-queue.core-async-event-loop :as cael]
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [com.fulcrologic.statecharts.execution-model.lambda :as lambda]
    [com.fulcrologic.statecharts.integration.fulcro-impl :as impl]
    [com.fulcrologic.statecharts.integration.fulcro.operations :as fops]
    [com.fulcrologic.statecharts.invocation.statechart :as i.statechart]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.registry.local-memory-registry :as lmr]
    [com.fulcrologic.statecharts.util :refer [new-uuid]]
    [taoensso.timbre :as log]))

(def local-data-path
  "[session-id & ks]

   Returns the Fulcro app state ident location of the local data of a specific instance of a running state machine."
  impl/local-data-path)

(def statechart-session-ident
  "[session-id]

   Returns the Fulcro app state ident location of the working memory of a specific statechart session."
  impl/statechart-session-ident)

(def resolve-aliases
  "[statechart-event-data]

  Resolve the value of all aliases defined on the statechart within executable content. For example:

  ```
  (ele/script {:expr (fn [env data] (let [values (resolve-aliases data)] ...))})
  ```
  "
  impl/resolve-aliases)

(defn resolve-actors
  "Resolve the UI tree(s) for the given actors in a statechart executable element. For example:

   ```
   (ele/script
     {:expr (fn [env data]
              (let [{:actor/keys [form]} (resolve-actors data :actor/form)] ...))})
   ```
  "
  [{:keys [_event fulcro/state-map] :as data} & actor-names]
  (let [session-id (:target _event)
        local-data (get-in state-map (local-data-path session-id))
        actors     (:fulcro/actors local-data)]
    (reduce
      (fn [acc actor]
        (let [{:keys [component ident]} (get actors actor)]
          (if (and component ident)
            (assoc acc actor (fns/ui->props state-map (rc/registry-key->class component) ident))
            acc)))
      {}
      actor-names)))

(defn resolve-actor
  "Returns the UI props of a single actor"
  [data actor-name]
  (get (resolve-actors data actor-name) actor-name))

(defn resolve-actor-class
  "Returns the current Fulcro component class that is representing `actor-key` if any."
  [data actor-key]
  (some-> data :fulcro/actors actor-key :component rc/registry-key->class))

(defn actor
  "Create an actor as part of the statechart data model. Give the actor a unique key at the top level of the data model,
   e.g.:

   ```
   (sf/start! app {:machine :machine-key
                   :session-id (new-uuid)
                   :data {:fulcro/actors {:actor/form (sf/actor LoginForm)}}})
   ```

   and then you can use that actor in an abstract way via data operations in executable content.

   See also:

   `resolve-aliases`, `resolve-actors`, `load-actor`

   "
  ([component-class]
   {:component (rc/class->registry-key component-class)
    :ident     (rc/get-ident component-class {})})
  ([component-class ident]
   {:component (rc/class->registry-key component-class)
    :ident     ident}))


(defn register-statechart!
  "Register a `statechart` definition under the (unique) key `k` on the given Fulcro application. You MUST register
   a chart or you will not be able to send events to instances of it."
  [app k statechart]
  (let [registry (-> app :com.fulcrologic.fulcro.application/runtime-atom
                   deref ::sc/env ::sc/statechart-registry)]
    (sp/register-statechart! registry k statechart)))

(defn statechart-env
  "Returns the installed statechart env. "
  [app-ish]
  (impl/statechart-env app-ish))


(defn start!
  "Starts a statechart that is registered as `machine` (keyword) under the provided session-id (default is a random UUID),
   and passes it the provided invocation `data`.

   The statechart is stored in the Fulcro state atom under [::id session-id], and is removed if the statechart reaches
   a final state.

   Returns the new session-id of the statechart."
  [app {:keys [machine session-id data]
        :or   {session-id (new-uuid)
               data       {}}}]
  (when machine
    (let [env (or
                (statechart-env app)
                (throw (ex-info "Statecharts are not installed on that app." {})))
          {::sc/keys [processor working-memory-store]} env
          s0  (sp/start! processor env machine (cond-> {::sc/session-id                                session-id
                                                        :org.w3.scxml.event/invokeid                   (new-uuid)
                                                        :com.fulcrologic.statecharts/parent-session-id impl/master-chart-id}
                                                 (map? data) (assoc :com.fulcrologic.statecharts/invocation-data data)))]
      (sp/save-working-memory! working-memory-store env session-id s0)
      session-id)))

;; Might need to defonce this so things don't restart. One coreasync queue. Timers???
(defn install-fulcro-statecharts!
  "Create a statecharts environment that is set up to work with the given Fulcro app.

   The statecharts components are installed onto the app itself."
  ([app] (install-fulcro-statecharts! app nil))
  ([app extra-env]
   (let [runtime-atom (:com.fulcrologic.fulcro.application/runtime-atom app)]
     (when-not (contains? @runtime-atom ::sc/env)
       (let [dm       (impl/new-fulcro-data-model app)
             q        (mpq/new-queue)
             ex       (lambda/new-execution-model dm q)
             registry (lmr/new-registry)
             wmstore  (impl/->FulcroWorkingMemoryStore app)
             env      (merge {:fulcro/app                app
                              ::sc/statechart-registry   registry
                              ::sc/data-model            dm
                              ::sc/event-queue           q
                              ::sc/working-memory-store  wmstore
                              ::sc/processor             (alg/new-processor)
                              ::sc/invocation-processors [(i.statechart/new-invocation-processor)]
                              ::sc/execution-model       ex}
                        extra-env)]
         (swap! runtime-atom assoc ::sc/env (assoc env :events-running-atom
                                              (cael/run-event-loop! env 16)))))
     (register-statechart! app impl/master-chart-id impl/application-chart)
     (start! app {:machine    impl/master-chart-id
                  :session-id impl/master-chart-id
                  :data       {}})
     (::sc/env @runtime-atom))))

(defn current-configuration
  "Returns the current statechart configuration (set of active states) for the statechart of the given
   session id."
  [app-ish session-id]
  (let [state-map (rapp/current-state app-ish)
        {::sc/keys [configuration]} (get-in state-map [::sc/session-id session-id])]
    (or configuration #{})))

(defn send!
  "Send an event to a running statechart."
  ([app-ish session-id event] (send! app-ish session-id event {}))
  ([app-ish session-id event data]
   (let [{::sc/keys [event-queue] :as env} (statechart-env app-ish)]
     (sp/send! event-queue env {:event  event
                                :data   data
                                :target session-id}))))

(defn mutation-result
  "Extracts the mutation result from an event that was triggered by the completion of a mutation.
   `data` is the second parameter of the executable content lambda."
  [data]
  (some-> data :_event :data :fulcro/mutation-result :body vals first))
