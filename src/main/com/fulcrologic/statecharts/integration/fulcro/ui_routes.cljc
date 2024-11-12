(ns com.fulcrologic.statecharts.integration.fulcro.ui-routes
  "A composable statechart-driven UI routing system"
  (:require
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.raw.components :as comp]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :as ele :refer [on-entry script state transition parallel]]
    [com.fulcrologic.statecharts.environment :as senv]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.protocols :as scp]
    [com.fulcrologic.statecharts.integration.fulcro.ui-routes-options :as ro]
    [edn-query-language.core :as eql]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(defn- coerce-to-keyword [v]
  (cond
    (keyword? v) v
    (or (symbol? v) (string? v)) (keyword v)
    (comp/component-class? v) (comp/class->registry-key v)))

(>defn route-to-event-name [target-key]
  [[:or
    [:fn comp/component-class?]
    :qualified-symbol
    :qualified-keyword] => :qualified-keyword]
  (let [[nspc nm] [(namespace target-key) (name target-key)]
        new-ns (str "route-to." nspc)]
    (keyword new-ns nm)))

(defn initialize-route! [{:fulcro/keys [app] :as env} {::keys [target] :as data}]
  (let [state-map     (app/current-state app)
        Target        (comp/registry-key->class target)
        options       (comp/component-options Target)
        initialize    (or (ro/initialize options) :once)
        initial-props (ro/initial-props options)
        props         (if initial-props
                        (initial-props env data)
                        (comp/get-initial-state Target (or
                                                         (-> data :_event :data)
                                                         {})))
        ident         (comp/get-ident Target props)
        exists?       (some? (get-in state-map ident))]
    (when (or
            (and (= :once initialize) (not exists?))
            (= :always initialize))
      (log/trace "Initializing target" target)
      (merge/merge-component! app Target props))
    ident))

(defn- replace-join! [app Parent parent-ident join-key Target target-ident]
  (let [{::app/keys [state-atom]} app
        state-map @state-atom
        old-query (comp/get-query Parent state-map)
        oq-ast    (eql/query->ast old-query)
        nq-ast    (update oq-ast :children
                    (fn [cs]
                      (conj (vec (remove #(= join-key (:dispatch-key %)) cs))
                        (eql/query->ast1 [{join-key (comp/get-query Target state-map)}]))))
        new-query (log/spy :info "New Query: " (eql/ast->query nq-ast))]
    (swap! state-atom assoc-in (conj parent-ident join-key) target-ident)
    (comp/set-query! app Parent {:query new-query})))

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
        Parent               (comp/registry-key->class parent-registry-key)
        Target               (comp/registry-key->class route-target)
        parent-ident         (get-in data [:route/idents parent-registry-key])
        target-ident         (get-in data [:route/idents route-target])]
    (if parallel?
      (replace-join! app Parent parent-ident route-target Target target-ident)
      (replace-join! app Parent parent-ident :ui/current-route Target target-ident)))
  nil)

(defn rstate
  "Create a routing state. Requires a :route/target attribute which should be
   anything accepted by comp/registry-key->class.  If `id` is not specified it
   will default to the keyword version of `target`. If `parallel?` is true, then
   this node will be a parallel state, where all immediate children will be active at the
   same time."
  [{:keys       [id parallel?]
    :route/keys [target] :as props} & children]
  (let [target-key (coerce-to-keyword target)
        id         (or id target-key)]
    ;; TODO: See which kind of management the component wants. Simple routing, invocation
    ;; of a nested chart, start a separate chart with an actor?
    (apply (if parallel? parallel state) (merge props {:id           id
                                                       :route/target target-key})
      (on-entry {}
        (script {:expr (fn [env data & _]
                         (let [ident (initialize-route! env (assoc data ::target target-key))]
                           [(ops/assign [:route/idents target-key] ident)]))})
        (script {:expr (fn [env data & _] (update-parent-query! env data id))}))
      children)))

(defn busy? [{:fulcro/keys [app] :as env} {:keys [_event]} & args]
  (if (-> _event :data ::force?)
    false
    (let [state-ids (senv/current-configuration env)
          {::sc/keys [elements-by-id]} (senv/normalized-chart env)
          busy?     (some (fn [state-id]
                            (let [t      (get-in elements-by-id [state-id :route/target])
                                  Target (comp/registry-key->class t)
                                  {::keys [busy?] :as opts} (some-> Target (comp/component-options))]
                              (if busy?
                                ;; TODO: Would be nice to pass the component props, but need live actor
                                (boolean (busy? app state-id))
                                false)))
                      state-ids)]
      busy?)))

(defn record-failed-route! [env {:keys [_event]} & args]
  [(ops/assign ::failed-route-event _event)])

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
    (log/trace "There was no prior routing request that failed"))
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
                    [:fn comp/component-class?]]]]
   => ::sc/parallel-element]
  (parallel {:id :state/top-parallel}
    routing-info-state
    routes))

(defn ui-current-subroute
  "Render the current subroute. factory-fn is the function wrapper that generates a proper element (e.g. comp/factory),
   and parent-component-instance is usually `this`.

   NOTE: This will NOT properly render a parallel route. You must use `ui-parallel-route`"
  [parent-component-instance factory-fn]
  (let [this         parent-component-instance
        q            (comp/get-query this (app/current-state this))
        {:ui/keys [current-route]} (comp/props this)
        {:keys [component]} (first
                              (filter
                                (fn [{:keys [dispatch-key]}] (= dispatch-key :ui/current-route))
                                (:children (eql/query->ast q))))
        render-child (when component (factory-fn component))]
    (if render-child
      (render-child current-route)
      (log/error "No subroute to render for " (comp/component-name parent-component-instance)))))

(defn ui-parallel-route
  "Render ONE of the possible routes underneath a parallel routing node.
   parent-component-instance is usually `this`, and factory-fn is usually comp/factory.

   The target-registry-key can be anything the component registry will recognize for the target you're trying
   to render.

   NOTE: This will NOT properly render a standard route. You must use `ui-current-subroute`"
  [parent-component-instance target-registry-key factory-fn]
  (let [this          parent-component-instance
        Target        (comp/registry-key->class target-registry-key)
        k             (comp/class->registry-key Target)
        current-route (get (comp/props this) k {})
        render-child  (when Target (factory-fn Target))]
    (if render-child
      (render-child current-route)
      (log/error "No subroute to render for" target-registry-key "in" (comp/component-name parent-component-instance)))))

(def session-id
  "The global statechart session ID that is used for the application statechart."
  ::session)

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

(defn start-routing!
  "Installs the statechart and starts it."
  [app statechart]
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

