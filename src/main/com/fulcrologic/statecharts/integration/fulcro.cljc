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
    * Fulcro state map: Any path starting with `[:fulcro/state-map ...]` or `[:fulcro/state ...]` is a path relative to the root of the entire Fulcro state map.

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

  See also 'Integration with Fulcro' in Guide.adoc.
  "
  (:require
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.guardrails.malli.core :refer [=> >def >defn ?]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.algorithms.v20150901 :as alg]
    [com.fulcrologic.statecharts.environment]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.event-queue.core-async-event-loop :as cael]
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [com.fulcrologic.statecharts.execution-model.lambda :as lambda]
    [com.fulcrologic.statecharts.integration.fulcro-impl :as impl]
    [com.fulcrologic.statecharts.invocation.statechart :as i.statechart]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.registry.local-memory-registry :as lmr]
    [com.fulcrologic.statecharts.util :refer [new-uuid]]))

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

(>def ::expression-data [:map
                         [:_event {:optional true} [:map [:target ::sc/id]]]
                         [:fulcro/state-map map?]])
(>def ::env [:map
             [:fulcro/app :any]
             [:com.fulcrologic.statecharts/working-memory-store :any]
             [:com.fulcrologic.statecharts/processor :any]
             [:com.fulcrologic.statecharts/event-queue :any]
             [:com.fulcrologic.statecharts/statechart-registry :any]
             [:com.fulcrologic.statecharts/statechart :any]
             [:com.fulcrologic.statecharts/execution-model :any]
             [:com.fulcrologic.statecharts/vwmem :any]])

(>defn resolve-actors
  "Resolve the UI tree(s) for the given actors in a statechart executable element. For example:

   ```
   (ele/script
     {:expr (fn [env data]
              (let [{:actor/keys [form]} (resolve-actors env :actor/form)] ...))})
   ```

   NOTE: You can use `data` instead of `env`, BUT in cases where you don't have an :_event that will fail. It is recommended
   therefore that you use env.
  "
  [{:keys [_event fulcro/state-map fulcro/app] :as data-or-env} & actor-names]
  [[:or
    ::env
    ::expression-data] [:* :keyword] => [:map-of :keyword map?]]
  (let [session-id (or (:target _event) (env/session-id data-or-env))
        state-map  (or state-map (rapp/current-state app))
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

(>defn resolve-actor
  "Returns the UI props of a single actor. Use env. `data` is allowed to prevent a non-breaking change, but doesn't work
   when a non-even predicate is evaluated."
  [data-or-env actor-name]
  [[:or
    ::env
    ::expression-data] [:* :keyword] => (? map?)]
  (get (resolve-actors data-or-env actor-name) actor-name))

(>defn resolve-actor-class
  "Returns the current Fulcro component class that is representing `actor-key` if any."
  [data actor-key]
  [[:map [:fulcro/actors {:optional true} map?]] :keyword => (? [:fn rc/component-class?])]
  (some-> data :fulcro/actors actor-key :component rc/registry-key->class))

(>def ::ident [:tuple :some :some])
(>def ::actor [:map
               [:component qualified-keyword?]
               [:ident [:or :nil
                        [:tuple some? some?]]]])
(>def ::class [:fn rc/component-class?])

(>defn actor
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
   [::class => ::actor]
   {:component (rc/class->registry-key component-class)
    :ident     (rc/get-ident component-class {})})
  ([component-class ident]
   [::class ::ident => ::actor]
   {:component (rc/class->registry-key component-class)
    :ident     ident}))

(>def ::fulcro-app [:map [:com.fulcrologic.fulcro.application/runtime-atom :some]])
(>def ::fulcro-appish [:or
                       [:map [:com.fulcrologic.fulcro.application/runtime-atom :some]]
                       [:fn rc/component?]])

(>defn register-statechart!
  "Register a `statechart` definition under the (unique) key `k` on the given Fulcro application. You MUST register
   a chart or you will not be able to send events to instances of it."
  [app k statechart]
  [::fulcro-app :keyword ::sc/statechart => :any]
  (let [registry (-> app :com.fulcrologic.fulcro.application/runtime-atom
                   deref ::sc/env ::sc/statechart-registry)]
    (sp/register-statechart! registry k statechart)))

(>defn lookup-statechart
  "Attempt to return the statechart definition of the given registration k."
  [app-ish k]
  [::fulcro-appish :keyword => (? ::sc/statechart)]
  (let [registry (-> (rc/any->app app-ish) :com.fulcrologic.fulcro.application/runtime-atom
                   deref ::sc/env ::sc/statechart-registry)]
    (sp/get-statechart registry k)))

(>defn statechart-env
  "Returns the installed statechart env. "
  [app-ish]
  [::fulcro-appish => ::sc/env]
  (impl/statechart-env app-ish))

(>defn start!
  "Starts a statechart that is registered as `machine` (keyword) under the provided session-id (default is a random UUID),
   and passes it the provided invocation `data`.

   The statechart is stored in the Fulcro state atom under [::id session-id], and is removed if the statechart reaches
   a final state.

   Returns the new session-id of the statechart."
  [app-ish {:keys [machine session-id data]
            :or   {session-id (new-uuid)
                   data       {}}}]
  [::fulcro-appish [:map
                    [:machine :keyword]
                    [:session-id {:optional true} ::sc/id]
                    [:data {:optional true} map?]] => (? ::sc/session-id)]
  (when machine
    (let [env (or
                (statechart-env app-ish)
                (throw (ex-info "Statecharts are not installed." {})))
          {::sc/keys [processor working-memory-store]} env
          s0  (sp/start! processor env machine (cond-> {::sc/session-id                                session-id
                                                        :org.w3.scxml.event/invokeid                   (new-uuid)
                                                        :com.fulcrologic.statecharts/parent-session-id impl/master-chart-id}
                                                 (map? data) (assoc :com.fulcrologic.statecharts/invocation-data data)))]
      (sp/save-working-memory! working-memory-store env session-id s0)
      session-id)))

;; Might need to defonce this so things don't restart. One coreasync queue. Timers???
(>defn install-fulcro-statecharts!
  "Create a statecharts environment that is set up to work with the given Fulcro app.

  Options can include:

  `:extra-env` - A map of things to include in the `env` parameter that all executable content receives.
  `:on-save` - A `(fn [session-id EDN])` that is called every time the statechart reaches a stable state and
           has working memory saved to the Fulcro app database. Allows you to do things like make statechart
           data durable across sessions.
  `:on-delete` - A `(fn [session-id])` that is called when a statechart reaches a final state and is removed.

   IMPORTANT: The execution model for Fulcro calls expressions with 4 args: env, data, event-name, and event-data. The
   last two are available in `:_event` of `data`, but are passed as addl args for convenience. If you use this
   in CLJ the arity must match or your expression will crash.

   Additionally, the data model will automatically resolve all aliases into the `data` map in expressions as well.

   The statecharts components are installed onto the app itself.

   NOTE: There is currently no way to make the event queue durable (across browser reload), so if you do add some kind
   of statechart durability realize that timed events can get lost.
   "
  ([app]
   [::fulcro-app => ::sc/env]
   (install-fulcro-statecharts! app {}))
  ([app {:keys [extra-env on-save on-delete]}]
   [::fulcro-app [:map
                  [:extra-env {:optional true} map?]
                  [:on-save {:optional true} fn?]
                  [:on-delete {:optional true} fn?]] => ::sc/env]
   (let [runtime-atom (:com.fulcrologic.fulcro.application/runtime-atom app)]
     (when-not (contains? @runtime-atom ::sc/env)
       (let [dm                 (impl/new-fulcro-data-model app)
             real-queue         (mpq/new-queue)
             instrumented-queue (reify sp/EventQueue
                                  (send! [_ env send-request] (sp/send! real-queue env send-request))
                                  (cancel! [event-queue env session-id send-id] (sp/cancel! real-queue env session-id send-id))
                                  (receive-events! [this env handler] (sp/receive-events! this env handler {}))
                                  (receive-events! [_ env handler options]
                                    (let [wrapped-handler (fn [{::sc/keys    [working-memory-store]
                                                                :fulcro/keys [app] :as env} event]
                                                            (handler env event)
                                                            (inspect/ilet [session-id (:target event)
                                                                           {::sc/keys [configuration] :as wmem} (sp/get-working-memory working-memory-store env session-id)]
                                                              (if (map? event)
                                                                (impl/statechart-event! app session-id (:name event) (:data event) configuration)
                                                                (impl/statechart-event! app session-id event {} configuration))))]
                                      (sp/receive-events! real-queue env wrapped-handler options))))
             ex                 (lambda/new-execution-model dm instrumented-queue {:explode-event? true})
             registry           (lmr/new-registry)
             wmstore            (impl/->FulcroWorkingMemoryStore app on-save on-delete)
             env                (merge {:fulcro/app                app
                                        ::sc/statechart-registry   registry
                                        ::sc/data-model            dm
                                        ::sc/event-queue           instrumented-queue
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
