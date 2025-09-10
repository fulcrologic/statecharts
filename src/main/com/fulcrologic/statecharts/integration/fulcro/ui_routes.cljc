(ns com.fulcrologic.statecharts.integration.fulcro.ui-routes
  "A composable statechart-driven UI routing system.

   Statechart have arbitrary nesting and parallelism. This routing system attempts to support that by allowing
   you to nest routable states, and also place siblings within a parallel element. It allows you to specify
   which UI component corresponds to the immediate UI parent of a route so that any amount of UI composition
   can happen in the actual UI between routable areas.

   ## Parallel Routes

   When you use parallel routes, it is important to put the :route/path on the parallel node, and NOT on any of the
   child sibling routes (since they will all be active at once). You MAY place additional children in such a tree, but
   they should not include :route/path since they are not directly routeable and must be re-established via your own
   logic on entry to the parallel route. Using a statechart `history` support is one way to preserve the
   overall setup, but such support is not bookmarkable.

   ALPHA. This namespace's API is subject to change."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.routing.system :as rsys]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :as ele :refer [on-entry on-exit parallel script script-fn state transition]]
    [com.fulcrologic.statecharts.environment :as senv]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.route-url :as ru]
    [com.fulcrologic.statecharts.integration.fulcro.ui-routes-options :as ro]
    [com.fulcrologic.statecharts.protocols :as scp]
    [edn-query-language.core :as eql]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(defn form?
  "Returns true if the given component looks like a Fulcro form using form-state."
  [Component] (boolean (rc/component-options Component :form-fields)))
(defn rad-report?
  "Returns true if the given component looks like a RAD report."
  [Component] (boolean (rc/component-options Component :com.fulcrologic.rad.report/source-attribute)))

(def session-id
  "The global statechart session ID that is used for the application statechart."
  ::session)

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

(>defn route-to-event-name
  "Given a registry key or component class, this function returns the event name that can be sent to the
   routing statechart in order to directly go to that state/route."
  [target]
  [[:or
    [:fn rc/component-class?]
    :qualified-symbol
    :qualified-keyword] => :qualified-keyword]
  (let [target-key (coerce-to-keyword target)
        [nspc nm] [(namespace target-key) (name target-key)]
        new-ns     (str "route-to." nspc)]
    (keyword new-ns nm)))

(defn initialize-route!
  "Logic that the statechart will run to initialize the target route component's state in the Fulcro database.
   This is controlled primarily by two options:

   uo/initialize
   ui/initial-props

   If the target component has a constant ident and initial-state, then neither option is necessary and the default
   for uo/initialize will be :once. If the ident isn't a constant, then you must make sure the the initial-props or initial-state
   will lead to data that can be used with `(get-ident Class data)` to get a valid ident.

   Returns the ident of the initialized component.
   "
  [{:fulcro/keys [app] :as env} {::keys [target] :as data}]
  (let [state-map     (rapp/current-state app)
        event-data    (get-in data [:_event :data])
        Target        (rc/registry-key->class target)
        form?         (form? Target)
        report?       (rad-report? Target)
        options       (rc/component-options Target)
        initialize    (or (ro/initialize options)
                        (cond
                          form? :always
                          report? :once
                          :else :once))
        initial-props (ro/initial-props options)
        props         (if initial-props
                        (?! initial-props env data)
                        (if form?
                          (let [{:keys [id]} event-data
                                id-key (first (rc/get-ident Target {}))]
                            {id-key id})
                          (rc/get-initial-state Target (or event-data {}))))
        ident         (rc/get-ident Target props)
        exists?       (some? (get-in state-map ident))]
    (when (or
            (and (= :once initialize) (not exists?))
            (= :always initialize))
      (log/debug "Initializing target" target)
      (merge/merge-component! app Target props))
    ident))

(defn replace-join!
  "Logic to update the query of Parent such that `join-key` is an EQL join to `Target`."
  [app Parent parent-ident join-key Target target-ident]
  (let [{:com.fulcrologic.fulcro.application/keys [state-atom]} app
        state-map @state-atom
        old-query (rc/get-query Parent state-map)
        oq-ast    (eql/query->ast old-query)
        nq-ast    (update oq-ast :children
                    (fn [cs]
                      (conj (vec (remove #(= join-key (:dispatch-key %)) cs))
                        (eql/query->ast1 [{join-key (rc/get-query Target state-map)}]))))
        new-query (eql/ast->query nq-ast)]
    (when (and (not parent-ident) (rc/has-ident? Parent))
      (log/error "Unable to fix join. Route will have no props because parent has no ident."
        {:parent (rc/component-name Parent)
         :target (rc/component-name Target)}))
    (swap! state-atom assoc-in (conj parent-ident join-key) target-ident)
    (rc/set-query! app Parent {:query new-query})))

(defn- find-parent-route
  "Find the node in the statechart that is the closest parent to the node with `id` which has a :route/target or :routing/root.
   Returns the node or nil if none is found."
  [env id]
  (let [{::sc/keys [elements-by-id]} (senv/normalized-chart env)]
    (loop [id id]
      (let [pid       (get-in elements-by-id [id :parent])
            parent    (get elements-by-id pid)
            routable? (and parent
                        (or
                          (contains? parent :route/target)
                          (contains? parent :routing/root)))]
        (cond
          (nil? parent) nil
          routable? parent
          :else (recur pid))))))

(>defn update-parent-query!
  "Dynamically set the query of the parent of `target-id` such that it's query includes a join to the given target. For
   parallel routes the join key will be the registry key of the :route/target of state `target-id`,
   and otherwise it will be `:ui/current-route`."
  [{:fulcro/keys [app] :as env} data target-id]
  [::sc/processing-env map? :keyword => :nil]
  (let [{::sc/keys [elements-by-id]} (senv/normalized-chart env)
        {route-target :route/target} (get elements-by-id target-id)
        {:keys [parallel?
                route/target
                routing/root]} (find-parent-route env target-id)
        parent-component-ref (or target root)               ; symbol, class, or keyword
        route-target         (coerce-to-keyword route-target)
        parent-registry-key  (coerce-to-keyword parent-component-ref)
        Parent               (rc/registry-key->class parent-registry-key)
        Target               (rc/registry-key->class route-target)
        ;; The parent might not be an actual route state that got initialized, so we may not have it in the idents.
        parent-ident         (get-in data [:route/idents parent-registry-key] (when (rc/has-ident? Parent) (rc/get-ident Parent {})))
        target-ident         (get-in data [:route/idents route-target])]
    (if parallel?
      (replace-join! app Parent parent-ident route-target Target target-ident)
      (replace-join! app Parent parent-ident :ui/current-route Target target-ident)))
  nil)

(defn establish-route-params-node
  "A custom statechart node that looks at the parameters desired by a route. If those parameters
   are in the event data, then it uses those, and sets them on the URL. If they are
   not in the event data, it attempts to get them from the URL.

   In both cases the obtained parameters (or the lack thereof) are set into
   [:routing/parameters <state-id>] in the data model."
  [{:keys       [id]
    :route/keys [path params]}]
  (script
    {:expr
     (fn [{:fulcro/keys [app]} _dm _e event-data]
       (when path
         (let [{::keys [external?]} event-data
               ks                      (log/spy :info (set (keys event-data)))
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
           (when (rsys/current-routing-system app)
             (if (log/spy :info external?)
               ;; TASK: Fix this. The statechart has already fixed/controlled the UI.
               ;; We're in the state...there's nothing to do in the routing system except control the URL
               ;; and respond to external events...
               (log/info "FIX URL, external")
               (log/info "FIX URL, internal")
               #_#_(rsys/replace-route! app {:route path :params actual-params})
               (rsys/route-to! app {:route path :params actual-params})
               )
             [(ops/assign [:routing/parameters id] actual-params)]))))}))

(defn rstate
  "Create a routing state. Requires a :route/target attribute which should be
   anything accepted by comp/registry-key->class.

   The :route/path is a vector of strings that specifies the path of this route (ALPHA, may change to be nested)
   The :route/params is a *set* of keywords that should be considered parameters that when seen as data in a
   routing event to this state should be persisted in the URL if there is history tracking (also requires :route/path).
   When both of these are specified then the system will pull the route params from the URL if the browser loads the given
   path. This allows the stateful parameters of the nested states to be stored and restored via the URL.

   If `id` is not specified it will default to the keyword version of `target`.

   If `parallel?` is true, then this node will be a parallel state,
   where all immediate children will be active at the same time."
  [{:keys       [id parallel?]
    :route/keys [target path params] :as props} & children]
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
                           ;; FIXME: Route idents should be stored by path; otherwise we can only have one of each kind on-screen
                           ;; at a time.
                           [(ops/assign [:route/idents target-key] ident)]))})
        (script {:expr (fn [env data & _] (update-parent-query! env data id))}))
      (on-exit {}
        (script-fn [& _]
          [(ops/delete [:route/idents target-key])]))
      children)))

(defn istate
  "A state is a routing state that invokes a statechart on the target component. The `target` is any registry-compatible
   key, and that component in question must have an ro/statechart or ro/statechart-id option to designate which statechart
   will be invoked. The ro/statechart will be registered under the keyword version of `target`.

   An `istate` is just like an `rstate` in terms of actual routing. See the docstring of `rstate` for :route/target,
   :route/path, and :route/params.

   The `:fulcro/actors` in the statechart's data model will have :actor/component set to the target itself. The target
   will be initialized like it is for `rstate` (see ro/initialize, etc.).

   This state will auto-route to the target. The additional options are:

   * target: The component registry key of the component that is the route target, and has the co-located statechart (or statechart-id).
      The :actor/component on the invoked chart will be this component, and the ident will be derived from the initialization
      of state (e.g. ro/initialize). See the initialization for `rstate` for details.
   * invoke-params: A map of params to be merged into the invoke `params`. Must be a map whose keys are the target location paths, and whose values are expressions compatible with the execution model.
   * namelist: Same as the options on `invoke`
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
  [{:keys [id child-session-id route/target invoke-params namelist finalize autoforward on-done exit-target statechart-id]
    :or   {invoke-params {}}
    :as   state-props} & children]
  (let [target-key (coerce-to-keyword target)
        id         (or id target-key)]
    (apply state (-> (assoc state-props :id id :route/target target-key)
                   (dissoc :invoke-params :finalize :autoforward :namelist :on-done :exit-target :statechart-id))
      (on-exit {}
        (script-fn [& _]
          [(ops/delete [:route/idents target-key])]))
      (on-entry {}
        (establish-route-params-node (assoc state-props :id id))
        (script {:expr (fn [env data & _]
                         (let [ident (initialize-route! env (assoc data ::target target-key))]
                           [(ops/assign [:route/idents target-key] ident)]))})
        (script {:expr (fn [env data & _] (update-parent-query! env data id))}))
      (ele/invoke (cond-> {:params      (merge
                                          {:fulcro/actors (fn [env data]
                                                            (let [Target (rc/registry-key->class target-key)
                                                                  ident  (get-in data [:route/idents target-key] (when (rc/has-ident? Target) (rc/get-ident Target {})))
                                                                  actors (merge {:actor/component (scf/actor Target ident)} (?! (rc/component-options Target ro/actors) env data))]
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
                    namelist (assoc :namelist namelist)
                    finalize (assoc :finalize finalize)))
      (transition (cond-> {:event :done.invoke.*}
                    exit-target (assoc :target exit-target))
        (ele/script {:expr (or on-done (constantly nil))}))
      children)))

(defn busy-form-handler
  [FormClass]
  (fn [{:fulcro/keys [app]} {:route/keys [idents]}]
    (let [form-ident (get idents (rc/class->registry-key FormClass))
          form-props (when form-ident (fns/ui->props (rapp/current-state app) FormClass form-ident))]
      (and form-props (fs/dirty? form-props)))))

(defn busy?
  "Returns true if any of the active states are routes that have a `busy?` helper which returns true when asked.

   The incoming data arg's :_event can include a ::uir/force? true to override busy indications (will always return
   false if forced)"
  [{:fulcro/keys [app] :as env} {:keys [_event] :as data} & args]
  (if (-> _event :data ::force?)
    false
    (let [state-ids (senv/current-configuration env)
          {::sc/keys [elements-by-id]} (senv/normalized-chart env)
          busy?     (some (fn [state-id]
                            (let [t      (get-in elements-by-id [state-id :route/target])

                                  Target (rc/registry-key->class t)
                                  form?  (form? Target)
                                  {::keys [busy?] :as opts} (some-> Target (rc/component-options))
                                  busy?  (if (and form? (not busy?))
                                           (busy-form-handler Target)
                                           busy?)]
                              (if busy?
                                (boolean (busy? env data))
                                false)))
                      state-ids)]
      busy?)))

(defn record-failed-route! [env {:keys [_event]} & args]
  [(ops/assign ::failed-route-event _event)])

(defn undo-url-change [env dm event-name {:route/keys [uid] :as popped-or-pushed-event-data}]
  ;; TASK: undo url change
  (log/info "FIX URL")
  #_(let [id->node         (rhist/recent-history @history)
        ids              (reverse (keys id->node))
        most-recent      (first ids)
        next-most-recent (second ids)
        r                (get id->node most-recent)
        back?            (= uid next-most-recent)]
    (if back?
      (rhist/push-route! @history r)
      (rhist/replace-route! @history (get id->node uid)))
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
        route-event-name (when target (route-to-event-name target))
        ;; FIXME: Don't tie to HTML
        route-params     (when route-event-name
                           (get (ru/current-url-state-params (ru/current-url))
                             target-state-id))]
    (when route-event-name
      (scf/send! app ::session route-event-name (assoc route-params
                                                  ::external? true)))
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
    (apply state props
      (on-entry {}
        (script-fn [env data]
          (let [root-key   (coerce-to-keyword root)
                Root       (rc/registry-key->class root-key)
                root-ident (when (rc/has-ident? Root) (rc/get-ident Root {}))]
            (cond
              (and (vector? root-ident) (nil? (second root-ident)))
              (log/error "The routing root of all routes MUST have a constant ident (or be the absolute root of the app)")

              (vector? root-ident)
              [(ops/assign [:route/idents root-key] root-ident)]

              :else nil))))
      (transition {:event :route-to.*
                   :cond  busy?}
        (script {:expr record-failed-route!})
        (ele/raise {:event :event.routing-info/show}))
      (transition {:event :event/external-route-change
                   :cond  busy?}
        (script {:expr record-failed-route!})
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
      (log/trace "Re-sending event" failed-route-event)
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
   => ::sc/element]
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
        q            (rc/get-query this (rapp/current-state this))
        {:ui/keys [current-route]} (rc/props this)
        {:keys [component]} (first
                              (filter
                                (fn [{:keys [dispatch-key]}] (= dispatch-key :ui/current-route))
                                (:children (eql/query->ast q))))
        render-child (when component (factory-fn component))]
    (if render-child
      (render-child current-route)
      (let [nm (rc/component-name parent-component-instance)]
        (log/warn (str "No subroute to render for " nm ". Did you remember to use ui-current-subroute in its parent?"))))))

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

(defn routing-statechart
  "Returns the current version of the routing statechart installed on the app."
  [app-ish]
  (scf/lookup-statechart app-ish ::chart))

(defn state-for-path [{::sc/keys [elements-by-id] :as statechart} current-path]
  (let [elements (vals elements-by-id)]
    (first
      (filter
        (fn [{:route/keys [path]}]
          (= path current-path))
        elements))))

(defn target-for-path
  "Return the Target class component for a given routing `path`."
  [statechart path]
  (let [{:route/keys [target]} (state-for-path statechart path)]
    (some->> target (rc/registry-key->class))))

(defn start-routing!
  "Installs the top-level (root) statechart on the fulcro app and starts it."
  [app statechart]
  ;; TASK: need to store active routed state in URL somehow...path is probably the right thing
  #_(vreset! history (rhist/new-html5-history app
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

(defn abandon-route-change! [app-ish]
  "Sends an event to the statechart that will abandon the attempt to route and close the routing info. "
  [app-ish]
  (scf/send! app-ish session-id :event.routing-info/close {}))

(defn send-to-self!
  "Send an event to an invoked statechart that is co-locatied on `this`.
   as specified on the component via ro/idlocation (defaults to [:child-session-id])."
  ([this event-name] (send-to-self! this event-name {}))
  ([this event-name event-data]
   (let [target-key (rc/class->registry-key (rc/component-type this))
         state-map  (rapp/current-state this)
         session-id (get-in state-map [::sc/local-data session-id :invocation/id target-key])]
     (when session-id
       (scf/send! this session-id event-name event-data)))))

(defn current-invocation-configuration [this]
  (let [target-key (rc/class->registry-key (rc/component-type this))
        state-map  (rapp/current-state this)
        session-id (get-in state-map [::sc/local-data session-id :invocation/id target-key])]
    (when session-id
      (scf/current-configuration this session-id))))

(defn normalized-target [t] (rc/class->registry-key (rc/registry-key->class t)))

(defn path-for-target
  "Return the full path (from root) to the given routing target. If `params` are supplied, then any
   keywords appearing in the resulting path will be replaced with the stringified value from
   `params`.

   WARNING: Building a statechart that uses the same target component in multiple paths
   causes this function to be non-deterministic. Your routing charts should use unique
   components for each route."
  ([app-ish target] (path-for-target app-ish target {}))
  ([app-ish target params]
   (let [app           (rc/any->app app-ish)
         params        (enc/map-vals str params)
         chart         (routing-statechart app)
         t             (normalized-target target)
         target-node   (first
                         (chart/elements chart (fn [{:route/keys [target]}]
                                                 (and
                                                   target
                                                   (= (normalized-target target) t)))))
         path-segments (loop [n    target-node
                              path (list)]
                         (let [parent       (chart/element chart (chart/get-parent-state chart n))
                               path-segment (:route/path n)]
                           (cond
                             (and parent path-segment)
                             (recur parent (cons path-segment path))

                             parent (recur parent path)

                             path-segment (cons path-segment path)

                             :else path)))
         path          (vec
                         (mapcat
                           (fn [eles] (mapv #(get params % %) eles))
                           path-segments))]
     path)))
