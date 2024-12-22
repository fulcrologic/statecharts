(ns com.fulcrologic.statecharts.integration.fulcro.ui-routes
  "A composable statechart-driven UI routing system"
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :as ele :refer [on-entry parallel script state transition]]
    [com.fulcrologic.statecharts.environment :as senv]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.route-history :as rhist]
    [com.fulcrologic.statecharts.integration.fulcro.route-url :as ru]
    [com.fulcrologic.statecharts.integration.fulcro.ui-routes-options :as ro]
    [com.fulcrologic.statecharts.protocols :as scp]
    [edn-query-language.core :as eql]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(def session-id
  "The global statechart session ID that is used for the application statechart."
  ::session)

(def history (volatile! nil))

(defn ?!
  "Run if the argument is a fn. This function can accept a value or function. If it is a
  function then it will apply the remaining arguments to it; otherwise it will just return
  `v`."
  [v & args]
  (if (and (fn? v) (not (rc/component-class? v)))
    (apply v args)
    v))

(defn- coerce-to-keyword [v]
  (cond
    (keyword? v) v
    (or (symbol? v) (string? v)) (keyword v)
    (rc/component-class? v) (rc/class->registry-key v)))

(>defn route-to-event-name [target-key]
  [[:or
    [:fn rc/component-class?]
    :qualified-symbol
    :qualified-keyword] => :qualified-keyword]
  (let [[nspc nm] [(namespace target-key) (name target-key)]
        new-ns (str "route-to." nspc)]
    (keyword new-ns nm)))

(defn initialize-route! [{:fulcro/keys [app] :as env} {::keys [target] :as data}]
  (let [state-map     (app/current-state app)
        Target        (rc/registry-key->class target)
        options       (rc/component-options Target)
        initialize    (or (ro/initialize options) :once)
        initial-props (ro/initial-props options)
        props         (if initial-props
                        (initial-props env data)
                        (rc/get-initial-state Target (or
                                                       (-> data :_event :data)
                                                       {})))
        ident         (rc/get-ident Target props)
        exists?       (some? (get-in state-map ident))]
    (when (or
            (and (= :once initialize) (not exists?))
            (= :always initialize))
      (log/debug "Initializing target" target)
      (merge/merge-component! app Target props))
    ident))

(defn- replace-join! [app Parent parent-ident join-key Target target-ident]
  (let [{::app/keys [state-atom]} app
        state-map @state-atom
        old-query (rc/get-query Parent state-map)
        oq-ast    (eql/query->ast old-query)
        nq-ast    (update oq-ast :children
                    (fn [cs]
                      (conj (vec (remove #(= join-key (:dispatch-key %)) cs))
                        (eql/query->ast1 [{join-key (rc/get-query Target state-map)}]))))
        new-query (log/spy :debug "New Query: " (eql/ast->query nq-ast))]
    (swap! state-atom assoc-in (conj parent-ident join-key) target-ident)
    (rc/set-query! app Parent {:query new-query})))

(>defn update-parent-query!
  "Dynamically set the query of Parent such that :ui/current-route is a join to Target."
  [{:fulcro/keys [app] :as env} data target-id]
  [::sc/processing-env map? :keyword => :nil]
  (let [{::sc/keys [elements-by-id]} (senv/normalized-chart env)
        {parent-id    :parent
         route-target :route/target} (get elements-by-id target-id)
        {:keys [parallel?
                route/target
                routing/root]} (elements-by-id parent-id)
        parent-component-ref (or target root)               ; symbol, class, or keyword
        route-target         (coerce-to-keyword route-target)
        parent-registry-key  (coerce-to-keyword parent-component-ref)
        Parent               (rc/registry-key->class parent-registry-key)
        Target               (rc/registry-key->class route-target)
        parent-ident         (get-in data [:route/idents parent-registry-key])
        target-ident         (get-in data [:route/idents route-target])]
    (if parallel?
      (replace-join! app Parent parent-ident route-target Target target-ident)
      (replace-join! app Parent parent-ident :ui/current-route Target target-ident)))
  nil)

(defn- establish-route-params-node
  "Statechart node that looks at the parameters desired by a route. If those parameters
   are in the event data, then it uses those, and sets them on the URL. If they are
   not in the event data, it attempts to get them from the URL.

   In both cases the obtained parameters (or the lack thereof) are set into
   [:routing/parameters <state-id>] in the data model."
  [{:keys       [id]
    :route/keys [path params]}]
  (script
    {:expr
     (fn [{:fulcro/keys [app]} _dm _e event-data]
       (let [{::keys [external?]} event-data
             ks                      (set (keys event-data))
             event-has-route-params? (boolean (seq (set/intersection ks params)))
             path-params             (some-> (ru/current-url)
                                       (ru/current-url-state-params)
                                       (get id))
             has-path-params?        (boolean
                                       (seq (set/intersection
                                              (set (keys path-params))
                                              params)))
             actual-params           (select-keys
                                       (cond
                                         event-has-route-params? event-data
                                         has-path-params? path-params
                                         :else {})
                                       params)]
         #_(ru/replace-url!
             (-> (ru/current-url)
               (cond-> path (ru/new-url-path path))
               (ru/update-url-state-param id (constantly actual-params))))
         (when path
           (when (and (not external?) @history)
             (rhist/push-route! @history {:id id :route/path path :route/params actual-params}))
           [(ops/assign [:routing/parameters id] actual-params)])))}))

(defn rstate
  "Create a routing state. Requires a :route/target attribute which should be
   anything accepted by comp/registry-key->class.  If `id` is not specified it
   will default to the keyword version of `target`. If `parallel?` is true, then
   this node will be a parallel state, where all immediate children will be active at the
   same time."
  [{:keys       [id parallel?]
    :route/keys [target path] :as props} & children]
  (let [target-key (coerce-to-keyword target)
        id         (or id target-key)]
    ;; TODO: See which kind of management the component wants. Simple routing, invocation
    ;; of a nested chart, start a separate chart with an actor?
    (apply (if parallel? parallel state) (merge props {:id           id
                                                       :route/target target-key})
      (on-entry {}
        (establish-route-params-node (assoc props :id id))
        (script {:expr (fn [env data & _]
                         (let [ident (initialize-route! env (assoc data ::target target-key))]
                           [(ops/assign [:route/idents target-key] ident)]))})
        (script {:expr (fn [env data & _] (update-parent-query! env data id))}))
      children)))

(defn istate
  "A state is a routing state that invokes a statechart on the target component. The `target` is any registry-compatible
   key, and that component in question must have an ro/statechart or ro/statechart-id option to designate which statechart
   will be invoked. The ro/statechart will be registered under the keyword version of `target`.

   The `:fulcro/actors` in the statechart's data model will have :actor/component set to the target itself. The target
   will be initialized like it is for `rstate` (see ro/initialize, etc.).

   This state will auto-route to the target. The additional options are:

   * target: The component registry key of the component that is the route target, and has the co-located statechart (or statechart-id).
      The :actor/component on the invoked chart will be this component, and the ident will be derived from the initialization
      of state (e.g. ro/initialize). See the initialization for `rstate` for details.
   * invoke-params: A map of params to be merged into the invoke `params`
   * finalize: Same as the option on `invoke`
   * autofoward: Same as the option on `invoke`
   * on-done: A (fn [env data & rest] ops) that will be run IF the invoked statechart hits a final state.
   * exit-target: A state ID which will be transitioned to IF the invoked statechart hits a final state.
   * statechart-id: A registered statechart id. Can be used instead of, or to override the component's co-located chart/id.
   * child-session-id: If supplied, this will be the invoked chart's session ID (instead of an autogenerated one)

   The remaining keys in the props are kept for the top-level emitted state.

   The session ID of the invoked chart will be at data location `[:invocation/id target-key]` of the routing session
   (see uir/session-id), where `target-key` is the `:route/target`'s registry keyword. Use `send-to-self!` to
   send events to the component's chart.

   The invoked component should specify an ro/idlocation so that events can be sent to it from within.
  "
  [{:keys [id child-session-id route/target invoke-params finalize autoforward on-done exit-target statechart-id]
    :or   {invoke-params {}}
    :as   state-props} & children]
  (let [target-key (coerce-to-keyword target)
        id         (or id target-key)]
    (apply state (-> (assoc state-props :id id :route/target target-key)
                   (dissoc :invoke-params :finalize :autoforward :on-done :exit-target :statechart-id))
      (on-entry {}
        (establish-route-params-node (assoc state-props :id id))
        (script {:expr (fn [& _])})
        (script {:expr (fn [env data & _]
                         (let [ident (initialize-route! env (assoc data ::target target-key))]
                           [(ops/assign [:route/idents target-key] ident)]))})
        (script {:expr (fn [env data & _] (update-parent-query! env data id))}))
      (ele/invoke (cond-> {:params      (merge
                                          {:fulcro/actors (fn [env data]
                                                            (let [Target (rc/registry-key->class target-key)
                                                                  ident  (get-in data [:route/idents target-key] (rc/get-ident Target {}))
                                                                  actors (merge {:actor/component (scf/actor Target ident)} (?! (rc/component-options Target ro/actors)))]
                                                              actors))}
                                          invoke-params)
                           :autoforward (boolean autoforward)
                           :idlocation  [:invocation/id target-key]
                           :type        :statechart
                           :srcexpr     (fn [{:fulcro/keys [app] :as env} data]
                                          (enc/if-let [Target (rc/registry-key->class target-key)]
                                            (let [id    (or statechart-id (rc/component-options Target ro/statechart-id))
                                                  chart (rc/component-options Target ro/statechart)]
                                              (cond
                                                id id
                                                chart (do
                                                        (log/debug "Registering state chart during invoke as " target-key)
                                                        (scf/register-statechart! app target-key chart)
                                                        target-key)
                                                :else (log/error "istate could not determine a statechart to invoke.")))
                                            (log/error "istate has no target")))}
                    child-session-id (assoc :id child-session-id)
                    finalize (assoc :finalize finalize)))
      (transition (cond-> {:event :done.invoke.*}
                    exit-target (assoc :target exit-target))
        (ele/script {:expr (or on-done (constantly nil))}))
      children)))

(defn busy? [{:fulcro/keys [app] :as env} {:keys [_event]} & args]
  (if (-> _event :data ::force?)
    false
    (let [state-ids (senv/current-configuration env)
          {::sc/keys [elements-by-id]} (senv/normalized-chart env)
          busy?     (some (fn [state-id]
                            (let [t      (get-in elements-by-id [state-id :route/target])
                                  Target (rc/registry-key->class t)
                                  {::keys [busy?] :as opts} (some-> Target (rc/component-options))]
                              (if busy?
                                ;; TODO: Would be nice to pass the component props, but need live actor
                                (boolean (busy? app state-id))
                                false)))
                      state-ids)]
      busy?)))

(defn record-failed-route! [env {:keys [_event]} & args]
  [(ops/assign ::failed-route-event _event)])

(defn undo-url-change [env dm event-name {:route/keys [uid] :as event-data}]
  (let [id->node (rhist/recent-history @history)
        r        (get id->node uid)]
    (rhist/replace-route! @history r)
    nil))

(defn apply-external-route
  "Look at the URL and figure out which of the statechart states we need to be in,
   compute the parameters, and then trigger an event to go there."
  [{::sc/keys    [statechart-registry]
    :fulcro/keys [app]} & _]
  (let [{::sc/keys [elements-by-id]} (scp/get-statechart statechart-registry ::chart)
        elements         (vals elements-by-id)
        current-path     (ru/current-url-path)
        {target-state-id :id
         :route/keys     [target]} (first
                                     (filter
                                       (fn [{:route/keys [path]}]
                                         (= path current-path))
                                       elements))
        route-event-name (log/spy :info (route-to-event-name target))
        ;; FIXME: Don't tie to HTML
        route-params     (log/spy :info
                           (get (log/spy :info (ru/current-url-state-params (ru/current-url)))
                             (log/spy :info target-state-id)))]
    (scf/send! app ::session route-event-name (assoc route-params
                                                ::external? true))
    nil))

(defn routes
  "Emits a state that represents the region that contains all of the
   routes. This will emit all of the transitions for direct navigation
   to any substate that has a routing target.

   The :routing/root must be a component with a constant ident (or just app root itself),
   and a query."
  [{:routing/keys [root]
    :keys         [id] :as props} & route-states]
  (let [find-targets       (fn find-targets* [targets]
                             (mapcat
                               (fn [{:keys [route/target children]}]
                                 (into (if target [target] [])
                                   (find-targets* children)))
                               targets))
        all-targets        (find-targets route-states)
        direct-transitions (mapv
                             (fn [t]
                               (transition {:event  (route-to-event-name t)
                                            :target t}
                                 (ele/raise {:event :event.routing-info/close})))
                             all-targets)]
    ;; TODO: Need a "Root" for setting parent query and join data
    (apply state props
      (transition {:event :route-to.*
                   :cond  busy?}
        (script {:expr record-failed-route!})
        (ele/raise {:event :event.routing-info/show}))
      (transition {:event :event/external-route-change
                   :cond  busy?}
        (script {:expr undo-url-change})
        (ele/raise {:event :event.routing-info/show}))
      (transition {:event :event/external-route-change}
        (script {:expr apply-external-route}))

      (concat
        direct-transitions
        route-states))))

(defn clear-override! [& args] [(ops/assign ::failed-route-event nil)])

(defn override-route! [{::sc/keys [vwmem event-queue] :as env} {::keys [failed-route-event]} & args]
  (if failed-route-event
    (let [target (::sc/session-id @vwmem)]
      (log/info "Re-sending event" failed-route-event)
      (scp/send! event-queue env {:event  (:name failed-route-event)
                                  :target target
                                  :data   (merge (:data failed-route-event)
                                            {::force? true})}))
    (log/debug "There was no prior routing request that failed"))
  nil)
(def routing-info-state
  (state {:id :region/routing-info}
    (state {:id :routing-info/idle}
      (on-entry {}
        (script {:expr clear-override!}))
      (transition {:event  :event.routing-info/show
                   :target :routing-info/open}))
    (state {:id :routing-info/open}
      (transition {:event  :event.routing-info/close
                   :target :routing-info/idle})
      (transition {:event  :event.routing-info/force-route
                   :target :routing-info/idle}
        (script {:expr override-route!})))))

(>defn routing-regions
  "Wraps the routes application statechart in a parallel state that includes management of the (optionally modal)
   route info (information when routing is denied, with the option to override)"
  [routes]
  [[:map
    [:routing/root [:or
                    :qualified-keyword
                    :qualified-symbol
                    [:fn rc/component-class?]]]]
   => ::sc/parallel-element]
  (state {:id :state/route-root}
    (on-entry {}
      (script {:expr apply-external-route}))
    (parallel {:id :state/top-parallel}
      routing-info-state
      routes)))

(defn ui-current-subroute
  "Render the current subroute. factory-fn is the function wrapper that generates a proper element (e.g. comp/factory),
   and parent-component-instance is usually `this`.

   NOTE: This will NOT properly render a parallel route. You must use `ui-parallel-route`"
  [parent-component-instance factory-fn]
  (let [this         parent-component-instance
        q            (rc/get-query this (app/current-state this))
        {:ui/keys [current-route]} (rc/props this)
        {:keys [component]} (first
                              (filter
                                (fn [{:keys [dispatch-key]}] (= dispatch-key :ui/current-route))
                                (:children (eql/query->ast q))))
        render-child (when component (factory-fn component))]
    (if render-child
      (render-child current-route)
      (log/error "No subroute to render for " (rc/component-name parent-component-instance)))))

(defn ui-parallel-route
  "Render ONE of the possible routes underneath a parallel routing node.
   parent-component-instance is usually `this`, and factory-fn is usually comp/factory.

   The target-registry-key can be anything the component registry will recognize for the target you're trying
   to render.

   NOTE: This will NOT properly render a standard route. You must use `ui-current-subroute`"
  [parent-component-instance target-registry-key factory-fn]
  (let [this          parent-component-instance
        Target        (rc/registry-key->class target-registry-key)
        k             (rc/class->registry-key Target)
        current-route (get (rc/props this) k {})
        render-child  (when Target (factory-fn Target))]
    (if render-child
      (render-child current-route)
      (log/error "No subroute to render for" target-registry-key "in" (rc/component-name parent-component-instance)))))

(defn route-to!
  "Attempt to route to the given target."
  ([app-ish target] (route-to! app-ish target {}))
  ([app-ish target data] (scf/send! app-ish session-id (route-to-event-name target) data)))

(defn update-chart!
  "Updates the statechart definition. You can use this after code reloads; however, BEWARE that if
   you are allowing the statechart to assign IDs to states, then the active configuration will become
   invalid unless you completely restart the chart."
  [app statechart]
  (scf/register-statechart! app ::chart statechart))

(defn state-for-path [{::sc/keys [elements-by-id] :as statechart} current-path]
  (let [elements (vals elements-by-id)]
    (first
      (filter
        (fn [{:route/keys [path]}]
          (= path current-path))
        elements))))

(defn start-routing!
  "Installs the statechart and starts it."
  [app statechart]
  (vreset! history (rhist/new-html5-history app
                     {:route->url (fn [{:keys       [id]
                                        :route/keys [path params]}]
                                    (-> (ru/current-url)
                                      (ru/update-url-state-param id (constantly params))
                                      (ru/new-url-path (str "/" (str/join "/" path)))))
                      :url->route (fn []
                                    (let [url        (ru/current-url)
                                          id->params (ru/current-url-state-params url)
                                          path       (ru/current-url-path url)
                                          {:keys [id] :as state} (state-for-path statechart path)]
                                      {:id           id
                                       :route/path   path
                                       :route/params (get id->params id)}))}))
  (update-chart! app statechart)
  (scf/start! app {:machine    ::chart
                   :session-id session-id
                   :data       {}}))

(>defn has-routes?
  "Returns true if the state with the given ID contains routes."
  [{::sc/keys [elements-by-id] :as normalized-statechart} id]
  [::sc/statechart ::sc/id => :boolean]
  (let [{:keys [children] :as state} (get elements-by-id id)
        child-states (mapv elements-by-id children)]
    (boolean
      (or
        (some :route/target child-states)
        (some #(has-routes? normalized-statechart %) children)))))

(>defn leaf-route?
  "Returns true if the given state ID IS a leaf route in the chart."
  [{::sc/keys [elements-by-id] :as normalized-statechart} id]
  [::sc/statechart ::sc/id => :boolean]
  (let [{:route/keys [target]} (get elements-by-id id)]
    (boolean
      (and
        target
        (not (has-routes? normalized-statechart id))))))

(>defn active-leaf-routes
  "Returns the set of IDs of the route states that are active, and represent the leaf target in a (potentially-nested)
   configuration. There can be more than one active leaf when using parallel nodes within the routing
   tree."
  [app-ish]
  [::scf/fulcro-appish => [:set :qualified-keyword]]
  (enc/if-let [chart         (scf/lookup-statechart app-ish ::chart)
               active-states (into #{}
                               (filter #(leaf-route? chart %))
                               (scf/current-configuration app-ish session-id))]
    active-states
    #{}))

(>defn route-denied? [app-ish]
  [::scf/fulcro-appish => :boolean]
  (let [cfg (scf/current-configuration app-ish session-id)]
    (boolean (contains? cfg :routing-info/open))))

(defn force-continue-routing!
  "Sends an event to the statechart with the given session-id that indicates the most-recently-denied route should
   be forced."
  [app-ish]
  (scf/send! app-ish session-id :event.routing-info/force-route {}))

(defn send-to-self!
  "Send an event to an invoked statechart that is co-locatied on `this`.
   as specified on the component via ro/idlocation (defaults to [:child-session-id])."
  ([this event-name] (send-to-self! this event-name {}))
  ([this event-name event-data]
   (let [target-key (rc/class->registry-key (comp/react-type this))
         state-map  (app/current-state this)
         session-id (get-in state-map [::sc/local-data session-id :invocation/id target-key])]
     (when session-id
       (scf/send! this session-id event-name event-data)))))


(defn current-invocation-configuration [this]
  (let [target-key (rc/class->registry-key (comp/react-type this))
        state-map  (app/current-state this)
        session-id (get-in state-map [::sc/local-data session-id :invocation/id target-key])]
    (when session-id
      (scf/current-configuration this session-id))))
