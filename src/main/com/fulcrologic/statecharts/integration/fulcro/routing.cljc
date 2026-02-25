(ns com.fulcrologic.statecharts.integration.fulcro.routing
  "A composable statechart-driven UI routing system for Fulcro applications.

   Provides statechart-based routing with deep busy-checking, cross-chart routing
   via `:route/reachable`, and optional bidirectional URL synchronization.

   Uses a well-known `session-id` constant for the routing statechart session,
   keeping the public API simple. Parallel regions handle multiple independent
   routing areas within a single chart."
  (:require
    [clojure.string :as cstr]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.raw.components :as rc]

    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :as ele :refer [on-entry on-exit parallel script script-fn state transition]]
    [com.fulcrologic.statecharts.environment :as senv]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-history :as ruh]
    [com.fulcrologic.statecharts.integration.fulcro.routing-options :as sfro]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-codec :as ruc]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-codec-transit :as ruct]
    [com.fulcrologic.statecharts.protocols :as scp]
    [edn-query-language.core :as eql]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]
    #?@(:cljs [[com.fulcrologic.statecharts.integration.fulcro.routing.browser-history :as bh]])))

(declare reachable-targets)

(def session-id
  "The well-known statechart session ID used for the routing statechart."
  ::session)

(defn form?
  "Returns true if the given component looks like a Fulcro form using form-state."
  [Component] (boolean (rc/component-options Component :form-fields)))

(defn rad-report?
  "Returns true if the given component looks like a RAD report."
  [Component] (boolean (rc/component-options Component :com.fulcrologic.rad.report/source-attribute)))

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

(defn route-to-event-name
  "Given a registry key or component class, returns the event name that can be sent to the
   routing statechart in order to directly go to that state/route."
  [target]
  (let [target-key (coerce-to-keyword target)
        [nspc nm] [(namespace target-key) (name target-key)]
        new-ns     (str "route-to." nspc)]
    (keyword new-ns nm)))

;; ---------------------------------------------------------------------------
;; Route configuration validation
;; ---------------------------------------------------------------------------

(defn- report-issue!
  "Reports a route configuration issue. In `:strict` mode, throws ex-info.
   In `:warn` mode (default), logs a warning."
  [mode warning-key message data]
  (let [payload (assoc data :warning-key warning-key)]
    (if (= mode :strict)
      (throw (ex-info (str "Routing configuration error: " message) payload))
      (log/warn message payload))))

(defn- find-all-route-targets
  "Walks all elements in a normalized chart and returns a sequence of maps
   `{:id state-id :route/target target-key}` for every element with a `:route/target`."
  [{::sc/keys [elements-by-id]}]
  (into []
    (comp
      (filter (fn [[_ element]] (:route/target element)))
      (map (fn [[id element]] {:id id :route/target (:route/target element)})))
    elements-by-id))

(defn- find-all-reachable-targets
  "Returns a sequence of maps `{:owner-id id :target kw}` for every keyword
   declared in a `:route/reachable` set on any element."
  [{::sc/keys [elements-by-id]}]
  (into []
    (mapcat (fn [[id element]]
              (when-let [reachable (:route/reachable element)]
                (mapv (fn [kw] {:owner-id id :target kw}) reachable))))
    elements-by-id))

(defn validate-duplicate-leaf-names
  "Checks for route targets whose simple name (used in URL matching) collides.
   Returns a sequence of issue maps, empty if no duplicates."
  [chart]
  (let [targets  (find-all-route-targets chart)
        by-name  (group-by (fn [{:route/keys [target]}] (name target)) targets)]
    (into []
      (comp
        (filter (fn [[_ entries]] (> (count entries) 1)))
        (map (fn [[leaf-name entries]]
               {:warning-key :routing/duplicate-leaf-name
                :leaf-name   leaf-name
                :targets     (mapv :route/target entries)
                :message     (str "Duplicate route leaf name \"" leaf-name
                              "\" — URL matching will be ambiguous. Targets: "
                              (mapv :route/target entries))})))
      by-name)))

(defn validate-reachable-collisions
  "Checks for `:route/reachable` targets that share a simple name with a direct route target.
   Returns a sequence of issue maps, empty if no collisions."
  [chart]
  (let [direct-targets  (find-all-route-targets chart)
        direct-by-name  (into {} (map (fn [{:route/keys [target]}] [(name target) target])) direct-targets)
        reachable       (find-all-reachable-targets chart)]
    (into []
      (comp
        (filter (fn [{:keys [target]}]
                  (contains? direct-by-name (name target))))
        (map (fn [{:keys [owner-id target]}]
               {:warning-key   :routing/reachable-collision
                :reachable     target
                :direct-target (get direct-by-name (name target))
                :owner-id      owner-id
                :message       (str "Reachable target " target " on " owner-id
                                 " collides (by leaf name) with direct route target "
                                 (get direct-by-name (name target)))})))
      reachable)))

(defn validate-routing-root
  "Checks that a routes node has a valid `:routing/root`.
   Returns a sequence of issue maps, empty if valid."
  [chart]
  (let [{::sc/keys [elements-by-id]} chart]
    (into []
      (comp
        (filter (fn [[_ element]] (contains? element :routing/root)))
        (mapcat (fn [[id element]]
                  (let [root-ref (:routing/root element)]
                    (cond
                      (nil? root-ref)
                      [{:warning-key :routing/missing-root
                        :route-id    id
                        :message     (str "Routes node " id " has nil :routing/root")}]

                      (nil? (coerce-to-keyword root-ref))
                      [{:warning-key :routing/invalid-root
                        :route-id    id
                        :root-ref    root-ref
                        :message     (str "Routes node " id " has :routing/root that cannot be coerced to a keyword: " root-ref)}]

                      :else [])))))
      elements-by-id)))

(defn- compute-segment-chain
  "Walks up from `state-id` through the element tree, collecting URL segments.
   Uses `:route/segment` when present, otherwise `(name (:route/target element))`.
   Stops at `:routing/root` boundaries. Returns a vector of segment strings."
  [elements-by-id state-id]
  (vec
    (loop [id       state-id
           segments ()]
      (let [element (get elements-by-id id)]
        (cond
          (nil? element) segments
          (contains? element :routing/root) segments
          :else
          (let [seg (ruc/element-segment element)]
            (recur (:parent element)
              (if seg (cons seg segments) segments))))))))

(defn validate-duplicate-segments
  "Checks for leaf route states whose full segment chains collide. Two states with
   identical segment chains produce ambiguous URL matching. Returns a sequence of
   issue maps, empty if no duplicates."
  [{::sc/keys [elements-by-id] :as chart}]
  (let [route-states (into []
                       (comp
                         (filter (fn [[_ element]] (:route/target element)))
                         (map (fn [[id _]] id)))
                       elements-by-id)
        chains       (mapv (fn [id] {:id id :chain (compute-segment-chain elements-by-id id)}) route-states)
        by-chain     (group-by :chain chains)]
    (into []
      (comp
        (filter (fn [[chain entries]] (and (seq chain) (> (count entries) 1))))
        (map (fn [[chain entries]]
               {:warning-key :routing/duplicate-segment-chain
                :chain       chain
                :state-ids   (mapv :id entries)
                :message     (str "Duplicate URL segment chain " (pr-str chain)
                              " — URL matching will be ambiguous. States: "
                              (mapv :id entries))})))
      by-chain)))

(defn validate-route-configuration
  "Runs all route configuration validators on `chart`. Reports issues according
   to `mode` (`:warn` or `:strict`). Returns the chart unchanged."
  [chart mode]
  (let [issues (concat
                 (validate-duplicate-leaf-names chart)
                 (validate-duplicate-segments chart)
                 (validate-reachable-collisions chart)
                 (validate-routing-root chart))]
    (doseq [{:keys [warning-key message] :as issue} issues]
      (report-issue! mode warning-key message (dissoc issue :message :warning-key)))
    chart))

(defn initialize-route!
  "Logic that the statechart will run to initialize the target route component's state in the Fulcro database.
   This is controlled primarily by two options:

   sfro/initialize
   sfro/initial-props

   If the target component has a constant ident and initial-state, then neither option is necessary and the default
   for sfro/initialize will be :once. If the ident isn't a constant, then you must make sure the the initial-props or initial-state
   will lead to data that can be used with `(get-ident Class data)` to get a valid ident.

   Returns the ident of the initialized component."
  [{:fulcro/keys [app] :as env} {::keys [target] :as data}]
  (when app
    (let [state-map     (rapp/current-state app)
          event-data    (get-in data [:_event :data])
          Target        (rc/registry-key->class target)
          form?         (form? Target)
          report?       (rad-report? Target)
          options       (rc/component-options Target)
          initialize    (or (sfro/initialize options)
                          (cond
                            form? :always
                            report? :once
                            :else :once))
          initial-props (sfro/initial-props options)
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
      ident)))

(defn replace-join!
  "Logic to update the query of Parent such that `join-key` is an EQL join to `Target`.
   When `parent-ident` is nil and Parent declares an ident, the join rewrite is skipped
   to avoid writing malformed state (e.g. `(assoc-in nil join-key ...)`)."
  [app Parent parent-ident join-key Target target-ident]
  (if (and (not parent-ident) (rc/has-ident? Parent))
    (log/error "Skipping join rewrite — parent has no ident. Route will have no props."
      {:parent     (rc/component-name Parent)
       :target     (rc/component-name Target)
       :join-key   join-key})
    (let [{:com.fulcrologic.fulcro.application/keys [state-atom]} app
          state-map @state-atom
          old-query (rc/get-query Parent state-map)
          oq-ast    (eql/query->ast old-query)
          nq-ast    (update oq-ast :children
                      (fn [cs]
                        (conj (vec (remove #(= join-key (:dispatch-key %)) cs))
                          (eql/query->ast1 [{join-key (rc/get-query Target state-map)}]))))
          new-query (eql/ast->query nq-ast)]
      (swap! state-atom assoc-in (conj parent-ident join-key) target-ident)
      (rc/set-query! app Parent {:query new-query}))))

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

(defn update-parent-query!
  "Dynamically set the query of the parent of `target-id` such that its query includes a join to the given target. For
   parallel routes the join key will be the registry key of the :route/target of state `target-id`,
   and otherwise it will be `:ui/current-route`."
  [{:fulcro/keys [app] :as env} data target-id]
  (when app
    (let [{::sc/keys [elements-by-id]} (senv/normalized-chart env)
          {route-target :route/target} (get elements-by-id target-id)
          {:keys [parallel?
                  route/target
                  routing/root]} (find-parent-route env target-id)
          parent-component-ref (or target root)
          route-target         (coerce-to-keyword route-target)
          parent-registry-key  (coerce-to-keyword parent-component-ref)
          Parent               (rc/registry-key->class parent-registry-key)
          Target               (rc/registry-key->class route-target)
          parent-ident         (get-in data [:route/idents parent-registry-key] (when (rc/has-ident? Parent) (rc/get-ident Parent {})))
          target-ident         (get-in data [:route/idents route-target])]
      (if parallel?
        (replace-join! app Parent parent-ident route-target Target target-ident)
        (replace-join! app Parent parent-ident :ui/current-route Target target-ident))))
  nil)

(defn establish-route-params-node
  "A custom statechart node that reads route parameters from event data and assigns them
   to `[:routing/parameters id]` in the data model."
  [{:keys       [id]
    :route/keys [params]}]
  (script
    {:expr
     (fn [env _dm _e event-data]
       (let [actual-params (select-keys (or event-data {}) (or params #{}))]
         [(ops/assign [:routing/parameters id] actual-params)]))}))

(defn rstate
  "Create a routing state. Requires a `:route/target` attribute which should be
   anything accepted by `comp/registry-key->class`.

   The state `:id` is always derived from `:route/target` — passing an explicit `:id`
   is an error and will throw at chart construction time.

   Options:

   * `:route/target` — (required) Component class or registry key for this route.
   * `:route/segment` — (optional) Custom URL path segment string. When absent,
     defaults to the simple name of the target (e.g. `\"UserList\"`).
   * `:route/params` — (optional) A set of keywords that are route parameters.
     When seen as data in a routing event, params are stored in
     `[:routing/parameters <state-id>]` in the data model.
   * `:parallel?` — (optional) When true, this node is a parallel state."
  [{:keys       [id parallel?]
    :route/keys [target segment params] :as props} & children]
  (when (contains? props :id)
    (throw (ex-info "rstate does not accept :id — the state ID is always derived from :route/target"
             {:route/target target :id id})))
  (let [target-key (coerce-to-keyword target)
        id         target-key]
    (apply (if parallel? parallel state) (merge props {:id           id
                                                       :route/target target-key})
      (on-entry {}
        (establish-route-params-node (assoc props :id id))
        (script {:expr (fn [env data & _]
                         (let [ident (initialize-route! env (assoc data ::target target-key))]
                           [(ops/assign [:route/idents target-key] ident)]))})
        (script {:expr (fn [env data & _] (update-parent-query! env data id))}))
      (on-exit {}
        (script-fn [& _]
          [(ops/delete [:route/idents target-key])]))
      children)))

(defn istate
  "A state that invokes a statechart on the target component. The `target` is any registry-compatible
   key, and the component must have an sfro/statechart or sfro/statechart-id option.

   An `istate` is just like an `rstate` in terms of actual routing. See the docstring of `rstate` for details.
   The state `:id` is always derived from `:route/target` — passing an explicit `:id` is an error.

   The `:fulcro/actors` in the statechart's data model will have :actor/component set to the target itself.

   Options:

   * `:route/target` — The component registry key with a co-located statechart (or statechart-id).
   * `:route/segment` — (optional) Custom URL path segment string. Defaults to `(name target)`.
   * `:route/reachable` — A set of route target keywords reachable through the child chart.
   * `invoke-params` — A map merged into the invoke `params`.
   * `namelist`, `finalize`, `autoforward` — Same as `invoke` element options.
   * `on-done` — `(fn [env data & rest] ops)` run if the child chart hits a final state.
   * `exit-target` — State ID to transition to on child chart completion.
   * `statechart-id` — Override the component's co-located chart/id.
   * `child-session-id` — Explicit session ID for the invoked chart."
  [{:keys       [id child-session-id route/target invoke-params namelist finalize autoforward on-done exit-target statechart-id]
    :route/keys [segment reachable]
    :or         {invoke-params {}}
    :as         state-props} & children]
  (when (contains? state-props :id)
    (throw (ex-info "istate does not accept :id — the state ID is always derived from :route/target"
             {:route/target target :id id})))
  (let [target-key  (coerce-to-keyword target)
        id          target-key
        reachable   (or reachable
                      (let [cls (rc/registry-key->class target-key)]
                        (when (nil? cls)
                          (throw (ex-info (str "istate: component " target-key " is not registered. "
                                           "Either require the component namespace or provide explicit :route/reachable.")
                                   {:route/target target-key})))
                        (let [child (rc/component-options cls sfro/statechart)]
                          (when (nil? child)
                            (throw (ex-info (str "istate: component " target-key " has no sfro/statechart option. "
                                             "Either co-locate the chart on the component or provide explicit :route/reachable.")
                                     {:route/target target-key})))
                          (not-empty (reachable-targets child)))))
        state-props (cond-> state-props
                      reachable (assoc :route/reachable reachable))]
    (apply state (-> (assoc state-props :id id :route/target target-key)
                   (dissoc :invoke-params :finalize :autoforward :namelist :on-done :exit-target :statechart-id))
      (on-exit {}
        (script-fn [& _]
          [(ops/delete [:route/idents target-key])
           (ops/assign ::pending-child-route nil)]))
      (on-entry {}
        (establish-route-params-node (assoc state-props :id id))
        (script {:expr (fn [env data & _]
                         (let [ident (initialize-route! env (assoc data ::target target-key))]
                           [(ops/assign [:route/idents target-key] ident)]))})
        (script {:expr (fn [env data & _] (update-parent-query! env data id))}))
      (ele/invoke (cond-> {:params      (merge
                                          {:fulcro/actors         (fn [env data & _]
                                                                   (let [Target (rc/registry-key->class target-key)
                                                                         ident  (get-in data [:route/idents target-key] (when (rc/has-ident? Target) (rc/get-ident Target {})))
                                                                         actors (merge {:actor/component (scf/actor Target ident)} (?! (rc/component-options Target sfro/actors) env data))]
                                                                     actors))
                                           ::pending-child-route (fn [_env data & _]
                                                                   (get data ::pending-child-route))}
                                          invoke-params)
                           :autoforward (boolean autoforward)
                           :idlocation  [:invocation/id target-key]
                           :type        :statechart
                           :srcexpr     (fn [{:fulcro/keys [app] :as env} data & _]
                                          (enc/if-let [Target (rc/registry-key->class target-key)]
                                            (let [id    (or statechart-id (rc/component-options Target sfro/statechart-id))
                                                  chart (rc/component-options Target sfro/statechart)]
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

(defn- check-component-busy?
  "Check a single route target component for busy conditions (dirty form, custom sfro/busy? fn).
   Returns true if the component is busy, false otherwise.
   Pre-resolves props for the target component and passes (app, props) to the busy-fn."
  [app state-map local-data target-key]
  (when-let [Target (rc/registry-key->class target-key)]
    (let [target-ident (get-in local-data [:route/idents target-key]
                         (when (rc/has-ident? Target) (rc/get-ident Target {})))
          props        (when target-ident (fns/ui->props state-map Target target-ident))
          is-form?     (form? Target)
          opts         (some-> Target rc/component-options)
          busy-fn      (sfro/busy? opts)
          ;; Default: dirty forms are busy
          busy-fn      (if (and is-form? (not busy-fn))
                         (fn [_app form-props] (and form-props (fs/dirty? form-props)))
                         busy-fn)]
      (if busy-fn
        (boolean (busy-fn app props))
        false))))

(defn- deep-busy?
  "Recursively checks busy conditions through invoked child charts.
   Walks the invocation tree via Fulcro state and the statechart registry.
   `seen` is a set of already-visited session IDs to prevent infinite recursion."
  [app registry state-map session-id seen]
  (when-not (contains? seen session-id)
    (let [wmem           (get-in state-map [::sc/session-id session-id])
          configuration  (::sc/configuration wmem)
          statechart-src (::sc/statechart-src wmem)]
      (when (and configuration statechart-src)
        (let [chart          (scp/get-statechart registry statechart-src)
              elements-by-id (::sc/elements-by-id chart)
              local-data     (get-in state-map (scf/local-data-path session-id))
              seen           (conj seen session-id)]
          (some (fn [state-id]
                  (let [target-key (get-in elements-by-id [state-id :route/target])]
                    (or
                      (when target-key
                        (check-component-busy? app state-map local-data target-key))
                      (when-let [child-session-id (and target-key
                                                    (get-in local-data [:invocation/id target-key]))]
                        (deep-busy? app registry state-map child-session-id seen)))))
            configuration))))))

(defn busy?
  "Returns true if any active route state is busy (dirty form, etc.) at any depth
   in the invocation tree. Used as the sole guard condition on route transitions."
  [{:fulcro/keys [app] :as env} {:keys [_event] :as data} & args]
  (if-not app
    false
    (if (-> _event :data ::force?)
      false
      (let [registry  (::sc/statechart-registry env)
            state-map (rapp/current-state app)
            session   (senv/session-id env)]
        (boolean (deep-busy? app registry state-map session #{}))))))

(defn record-failed-route!
  "Stores the failed route event so it can be retried via force-continue-routing!."
  [_env {:keys [_event] :as _data} & _args]
  [(ops/assign ::failed-route-event _event)])

(defn clear-override!
  "Clears the saved failed route event."
  [& _args]
  [(ops/assign ::failed-route-event nil)])

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

(defn- find-reachable-owner
  "Given `route-states` (the children of a routes node), returns the ID of the child istate
   that declares `reachable-target` in its `:route/reachable` set, or nil."
  [route-states reachable-target]
  (some (fn [{:keys [id route/reachable]}]
          (when (and reachable (contains? reachable reachable-target))
            id))
    route-states))

(defn routes
  "Emits a state that represents the region containing all of the routes.
   Generates transitions for direct navigation to any substate that has a routing target.

   Also generates cross-chart transitions for targets declared in `:route/reachable` sets
   on `istate` children. These transitions target the declaring `istate` and store a
   `::pending-child-route` in the data model so the child chart can self-route on startup.

   The :routing/root must be a component with a constant ident (or the app root itself),
   and a query."
  [{:routing/keys [root]
    :keys         [id] :as props} & route-states]
  (let [find-targets       (fn find-targets* [targets]
                             (mapcat
                               (fn [{:keys [route/target children]}]
                                 (into (if target [target] [])
                                   (find-targets* children)))
                               targets))
        all-direct-targets (find-targets route-states)
        all-reachable      (into #{}
                             (mapcat :route/reachable)
                             route-states)
        ;; Remove any reachable targets that are also direct targets (direct wins)
        reachable-only     (into #{} (remove (set all-direct-targets)) all-reachable)
        direct-transitions (mapv
                             (fn [t]
                               (transition {:event  (route-to-event-name t)
                                            :target t}
                                 (ele/raise {:event :event.routing-info/close})))
                             all-direct-targets)
        cross-transitions  (mapcat
                             (fn [reachable-target]
                               (let [owner-id (find-reachable-owner route-states reachable-target)]
                                 [;; Transition 1: When NOT already in owner-id — enter it and store pending route
                                  ;; :type :internal prevents re-entering the routes state, which would
                                  ;; clear pending-child-route before the invoke can read it.
                                  (transition {:event  (route-to-event-name reachable-target)
                                               :type   :internal
                                               :target owner-id
                                               :cond   (fn [env _data & _]
                                                         (not (contains? (senv/current-configuration env) owner-id)))}
                                    (ele/raise {:event :event.routing-info/close})
                                    (script {:expr (fn [_env _data _event-name event-data]
                                                     [(ops/assign ::pending-child-route
                                                        {:event (route-to-event-name reachable-target)
                                                         :data  event-data})])}))
                                  ;; Transition 2: When ALREADY in owner-id — forward to child chart's session
                                  (transition {:event (route-to-event-name reachable-target)
                                               :type  :internal
                                               :cond  (fn [env _data & _]
                                                        (contains? (senv/current-configuration env) owner-id))}
                                    (ele/raise {:event :event.routing-info/close})
                                    (script {:expr (fn [env data _event-name event-data]
                                                     (let [child-session-id (get-in data [:invocation/id owner-id])
                                                           {::sc/keys [event-queue]} env]
                                                       (when child-session-id
                                                         (scp/send! event-queue env
                                                           {:target            child-session-id
                                                            :source-session-id (senv/session-id env)
                                                            :event             (route-to-event-name reachable-target)
                                                            :data              (or event-data {})})))
                                                     nil)}))]))
                             reachable-only)]
    (apply state props
      (remove nil?
        (concat
          [(on-entry {}
             (script {:expr (fn [env data & _]
                              (let [root-key      (coerce-to-keyword root)
                                    Root          (rc/registry-key->class root-key)
                                    root-ident    (when (and Root (rc/has-ident? Root)) (rc/get-ident Root {}))
                                    pending-route (get data ::pending-child-route)
                                    assign-ops    (cond
                                                    (and (vector? root-ident) (nil? (second root-ident)))
                                                    (do (log/error "The routing root of all routes MUST have a constant ident (or be the absolute root of the app)")
                                                        nil)

                                                    (vector? root-ident)
                                                    [(ops/assign [:route/idents root-key] root-ident)]

                                                    :else nil)]
                                (when pending-route
                                  (log/debug "Routes received pending child route, raising" (:event pending-route))
                                  (senv/raise env (:event pending-route) (or (:data pending-route) {})))
                                ;; Clear pending-child-route after use
                                (cond-> assign-ops
                                  pending-route (conj (ops/assign ::pending-child-route nil)))))}))]
          [(transition {:event :route-to.*
                        :cond  busy?}
             (script {:expr record-failed-route!})
             (ele/raise {:event :event.routing-info/show}))]
          direct-transitions
          cross-transitions
          route-states)))))

(defn routing-regions
  "Wraps the routes in a parallel state that includes management of the (optionally modal)
   route info (information when routing is denied, with the option to override).

   Returns a state node `:state/route-root` containing a parallel node with the routing-info
   state and the provided routes."
  [routes]
  (state {:id :state/route-root}
    (parallel {:id :state/top-parallel}
      routing-info-state
      routes)))

(defn ui-current-subroute
  "Render the current subroute. `factory-fn` generates a proper element (e.g. comp/factory),
   and `parent-component-instance` is usually `this`.

   WARNING: You MUST put `:preserve-dynamic-query? true` on the containing component, or hot code reload will wipe the current route.

   NOTE: This will NOT properly render a parallel route. You must use `ui-parallel-route`."
  [parent-component-instance factory-fn]
  (when (contains? (scf/current-configuration parent-component-instance session-id) :region/routing-info)
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
          (log/warn (str "No subroute to render for " nm ". Did you remember to use ui-current-subroute in its parent?")))))))

(defn ui-parallel-route
  "Render ONE of the possible routes underneath a parallel routing node.
   `parent-component-instance` is usually `this` and `factory-fn` is usually comp/factory.

   NOTE: This will NOT properly render a standard route. You must use `ui-current-subroute`."
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
  "Attempt to route to the given `target`."
  ([app-ish target] (route-to! app-ish target {}))
  ([app-ish target data] (scf/send! app-ish session-id (route-to-event-name target) data)))

(defn has-routes?
  "Returns true if the state with the given ID contains routes within `normalized-statechart`.
   NOTE: This inspects a single chart only and does not cross invocation boundaries into child charts."
  [{::sc/keys [elements-by-id] :as normalized-statechart} id]
  (let [{:keys [children] :as state} (get elements-by-id id)
        child-states (mapv elements-by-id children)]
    (boolean
      (or
        (some :route/target child-states)
        (some #(has-routes? normalized-statechart %) children)))))

(defn leaf-route?
  "Returns true if the given state ID IS a leaf route in `normalized-statechart` (has `:route/target` and no child routes).
   NOTE: This inspects a single chart only and does not cross invocation boundaries. An `istate` will appear as a leaf
   even though it invokes a child chart with deeper routes. Use `active-leaf-routes` for cross-chart leaf detection."
  [{::sc/keys [elements-by-id] :as normalized-statechart} id]
  (let [{:route/keys [target]} (get elements-by-id id)]
    (boolean
      (and
        target
        (not (has-routes? normalized-statechart id))))))

(defn- session-statechart-id
  "Returns the statechart registration key for the routing session by reading
   `::sc/statechart-src` from working memory. Returns nil if the session doesn't exist."
  [app-ish]
  (let [state-map (rapp/current-state app-ish)]
    (get-in state-map [::sc/session-id session-id ::sc/statechart-src])))

(defn- deep-leaf-routes
  "Recursively walks the invocation tree from `session-id` to collect the true leaf route states
   across chart boundaries. `seen` prevents infinite recursion on circular references."
  [state-map registry session-id seen]
  (when-not (contains? seen session-id)
    (let [wmem           (get-in state-map [::sc/session-id session-id])
          configuration  (::sc/configuration wmem)
          statechart-src (::sc/statechart-src wmem)]
      (when (and configuration statechart-src)
        (let [chart          (scp/get-statechart registry statechart-src)
              elements-by-id (::sc/elements-by-id chart)
              local-data     (get-in state-map (scf/local-data-path session-id))
              seen           (conj seen session-id)]
          (reduce
            (fn [acc state-id]
              (let [{:route/keys [target]} (get elements-by-id state-id)]
                (if-not target
                  acc
                  (if (has-routes? chart state-id)
                    ;; Compound route state in this chart — not a leaf, keep walking children
                    acc
                    ;; Leaf route in this chart — check for child invocation
                    (let [child-sid (get-in local-data [:invocation/id target])]
                      (if child-sid
                        ;; Child chart exists — recurse into it for deeper leaves
                        (let [child-leaves (deep-leaf-routes state-map registry child-sid seen)]
                          (if (seq child-leaves)
                            (into acc child-leaves)
                            ;; Child session exists but has no route leaves (yet?) — fall back to this state
                            (conj acc state-id)))
                        ;; No child session — this IS a true leaf
                        (conj acc state-id)))))))
            #{}
            configuration))))))

(defn active-leaf-routes
  "Returns the set of IDs of the route states that are active and represent the deepest leaf targets,
   walking through invocation boundaries into child charts. There can be more than one active leaf
   when using parallel nodes. Falls back to the `istate` itself if its child session doesn't exist yet."
  [app-ish]
  (let [state-map (rapp/current-state app-ish)
        registry  (some-> (rc/any->app app-ish) :com.fulcrologic.fulcro.application/runtime-atom
                    deref ::sc/env ::sc/statechart-registry)]
    (if registry
      (or (deep-leaf-routes state-map registry session-id #{}) #{})
      #{})))

(defn route-denied?
  "Returns true if the routing statechart is currently showing the route-denied info."
  [app-ish]
  (let [cfg (scf/current-configuration app-ish session-id)]
    (boolean (contains? cfg :routing-info/open))))

(defn force-continue-routing!
  "Sends an event to the statechart indicating the most-recently-denied route should be forced."
  [app-ish]
  (scf/send! app-ish session-id :event.routing-info/force-route {}))

(defn abandon-route-change!
  "Sends an event to the statechart that will abandon the attempt to route and close the routing info."
  [app-ish]
  (scf/send! app-ish session-id :event.routing-info/close {}))

(defn- find-invocation-session-id
  "Walks up the component parent chain to find the nearest ancestor whose registry key
   has an invocation session ID in the routing session's local-data. Returns the child
   session ID, or nil if none found."
  [this state-map]
  (loop [c this]
    (when c
      (let [key (rc/class->registry-key (rc/component-type c))
            sid (get-in state-map [::sc/local-data session-id :invocation/id key])]
        (if sid
          sid
          (recur (rc/isoget-in c [:props :fulcro$parent])))))))

(defn send-to-self!
  "Send an event to the nearest invoked statechart co-located on `this` or an ancestor."
  ([this event-name] (send-to-self! this event-name {}))
  ([this event-name event-data]
   (let [state-map        (rapp/current-state this)
         child-session-id (find-invocation-session-id this state-map)]
     (when child-session-id
       (scf/send! this child-session-id event-name event-data)))))

(defn current-invocation-configuration
  "Returns the current configuration of the nearest invoked statechart co-located on
   `this` or an ancestor."
  [this]
  (let [state-map        (rapp/current-state this)
        child-session-id (find-invocation-session-id this state-map)]
    (when child-session-id
      (scf/current-configuration this child-session-id))))

(defn reachable-targets
  "Returns the set of all route target keywords reachable from the given statechart definition.
   Walks all elements finding `:route/target` values, and recursively includes targets from
   `:route/reachable` sets. Also descends into components that have co-located charts
   (`sfro/statechart`), collecting their targets transitively.

   Accepts a normalized chart (from `chart/statechart`)."
  ([chart] (reachable-targets chart #{}))
  ([{::sc/keys [elements-by-id] :as chart} seen]
   (let [direct (into #{}
                  (comp
                    (mapcat (fn [{:route/keys [target reachable]}]
                              (cond-> []
                                target (conj target)
                                reachable (into reachable))))
                    (map coerce-to-keyword))
                  (vals elements-by-id))]
     (reduce
       (fn [acc target-key]
         (if (contains? seen target-key)
           acc
           (enc/if-let [cls   (rc/registry-key->class target-key)
                        child (rc/component-options cls sfro/statechart)]
             (into acc (reachable-targets child (conj seen target-key)))
             acc)))
       direct
       direct))))

(defn start!
  "Registers the routing `statechart` and starts a routing session.
   No URL history integration is performed.

   Options (optional second map):

   * `:routing/checks` — `:warn` (default) or `:strict`. Controls whether route
     configuration problems are logged as warnings or thrown as errors."
  ([app statechart] (start! app statechart {}))
  ([app statechart {:keys [routing/checks] :or {checks :warn}}]
   (validate-route-configuration statechart checks)
   (scf/register-statechart! app session-id statechart)
   (scf/start! app {:machine    session-id
                    :session-id session-id
                    :data       {}})))

;; ---------------------------------------------------------------------------
;; Bidirectional URL synchronization
;; ---------------------------------------------------------------------------

(defn- runtime-atom [app] (:com.fulcrologic.fulcro.application/runtime-atom app))
(defn- url-sync-state [app] (-> app runtime-atom deref ::url-sync))
(defn- swap-url-sync! [app f & args] (apply swap! (runtime-atom app) update ::url-sync f args))

(defn url-sync-provider
  "Returns the installed URL history provider for the routing session, or nil when URL sync is not installed."
  [app-ish]
  (when-let [app (rc/any->app app-ish)]
    (or (get-in (url-sync-state app) [:providers session-id])
      ;; Legacy fallback (single-provider storage)
      (get-in (url-sync-state app) [:provider]))))

(defn url-sync-installed?
  "Returns true when URL sync has been installed for the routing session."
  [app-ish]
  (boolean (url-sync-provider app-ish)))

(defn- find-root-session
  "Walks the `::sc/parent-session-id` chain in `state-map` to find a registered root session.
   Returns the root session-id or nil."
  [app state-map session-id]
  (let [handlers (-> (url-sync-state app) :handlers)]
    (loop [sid  session-id
           seen #{}]
      (when-not (contains? seen sid)
        (let [wmem       (get-in state-map [::sc/session-id sid])
              parent-sid (::sc/parent-session-id wmem)]
          (cond
            (contains? handlers sid) sid
            (nil? parent-sid) nil
            :else (recur parent-sid (conj seen sid))))))))

(defn url-sync-on-save
  "An `:on-save` handler that dispatches to registered URL sync handlers.
   Accepts a Fulcro `app`, enabling child chart URL tracking: when a child session
   saves, it walks the parent chain to find the registered root and delegates to
   its handler.

   The handler receives three arguments: `(handler root-sid wmem saving-sid)` where
   `saving-sid` is the session that actually saved (may differ from `root-sid` for
   child invocations). This lets the handler distinguish root saves from child saves.

   Compose with your own `:on-save` when calling `install-fulcro-statecharts!`:

   ```
   (install-fulcro-statecharts! app
     {:on-save (fn [session-id wmem]
                 (url-sync-on-save session-id wmem app)
                 (my-other-on-save session-id wmem))})
   ```"
  [saving-session-id wmem app]
  (let [{:keys [handlers child-to-root]} (url-sync-state app)]
    (if-let [handler (get handlers saving-session-id)]
      ;; Direct match: this IS the root session
      (handler saving-session-id wmem saving-session-id)
      ;; Not a registered root — check if it's a child of one
      (let [root-sid (or (get child-to-root saving-session-id)
                       (let [state-map (rapp/current-state app)
                             root      (find-root-session app state-map saving-session-id)]
                         (when root
                           (swap-url-sync! app update :child-to-root assoc saving-session-id root)
                           root)))]
        (when-let [handler (and root-sid (get handlers root-sid))]
          (handler root-sid wmem saving-session-id))))))

(defn- resolve-route-and-navigate!
  "Parses the current URL from `provider`, finds the matching route target, and sends
   a route-to event. Used by both popstate handling and initial URL restoration.
   Uses the codec for decoding, with fallback to `find-target-by-leaf-name-deep` for
   cross-chart `:route/reachable` targets."
  [app elements-by-id provider codec]
  (let [href    (ruh/current-href provider)
        decoded (ruc/decode-url codec href elements-by-id)]
    (if decoded
      (let [{:keys [leaf-id params]} decoded
            route-params (when params
                           (reduce-kv (fn [acc _state-id state-params]
                                        (merge acc state-params))
                             {} params))]
        (route-to! app leaf-id (or route-params {}))
        leaf-id)
      ;; Codec didn't match directly — try reachable targets via deep search
      ;; (needed for cross-chart :route/reachable targets not in this chart's elements)
      (let [segments  (ruh/current-url-path href)
            leaf-name (peek segments)]
        (when leaf-name
          (when-let [{:keys [target-key child?]} (ruh/find-target-by-leaf-name-deep elements-by-id leaf-name)]
            (when child?
              (route-to! app target-key {})
              target-key)))))))

(defn install-url-sync!
  "Installs bidirectional URL synchronization for the routing statechart session.
   Call AFTER `start!` completes. Returns a cleanup function that removes all listeners.

   The statechart registration key is derived automatically from the routing session's
   working memory (`::sc/statechart-src`).

   For state-to-URL sync, `url-sync-on-save` must be called from the `:on-save`
   handler passed to `install-fulcro-statecharts!`. Pass `app` to
   `url-sync-on-save` to enable child chart URL tracking — when a child chart
   transitions internally, the URL will update to reflect the full nested state.

   Options:

   * `:provider` — A `URLHistoryProvider`. Defaults to `(browser-url-history)` on CLJS.
     Required on CLJ (throws if not provided).
   * `:url-codec` — A `URLCodec` instance for encoding/decoding URLs. Defaults to
     `(ruct/transit-base64-codec)`.
   * `:prefix` — URL path prefix (default \"/\")
   * `:on-route-denied` — `(fn [url])` called when back/forward navigation is denied by the busy guard"
  [app & [{:keys [prefix on-route-denied provider url-codec routing/checks]
           :or   {prefix "/" checks :warn}}]]
  (let [statechart-id                           (or (session-statechart-id app)
                                                  (throw (ex-info "install-url-sync! called but no routing session found. Call start! first." {})))
        url-codec                               (or url-codec (ruct/transit-base64-codec))
        provider                                (or provider
                                                  #?(:cljs (bh/browser-url-history)
                                                     :clj  (throw (ex-info "install-url-sync! requires a :provider on CLJ (e.g. simulated-url-history)" {}))))
        chart                                   (scf/lookup-statechart app statechart-id)
        _                                       (validate-route-configuration chart checks)
        elements-by-id                          (::sc/elements-by-id chart)
        prev-url                                (atom nil)
        nav-state                               (atom nil)
        restoring?                              (atom false)
        ;; True briefly after a browser-initiated nav is accepted. While true,
        ;; any programmatic URL change (e.g. from child chart initialization)
        ;; uses replaceState instead of pushState so that forward history is
        ;; not destroyed.
        settling?                               (atom false)
        debounce-timer #?(:cljs (atom nil) :clj nil)
        safety-timer #?(:cljs (atom nil) :clj nil)

        cancel-safety-timer!                    (fn []
                                                  #?(:cljs (when-let [t @safety-timer]
                                                             (js/clearTimeout t)
                                                             (reset! safety-timer nil))))

        start-safety-timer!                     (fn []
                                                  #?(:cljs
                                                     (do
                                                       (when-let [t @safety-timer] (js/clearTimeout t))
                                                       (reset! safety-timer
                                                         (js/setTimeout
                                                           (fn []
                                                             (reset! safety-timer nil)
                                                             (when @nav-state
                                                               (log/error "URL sync: nav-state stuck for 5s — process-event! likely failed. Clearing to restore URL sync.")
                                                               (reset! nav-state nil)
                                                               (reset! outstanding-navs 0)
                                                               (reset! settling? false)))
                                                           5000)))))

        ;; Tracks the history index after each push/replace/acceptance. Used to
        ;; determine which direction to undo on route denial. We cannot rely on
        ;; provider's `current-index` during popstate because some providers
        ;; (e.g. SimulatedURLHistory) update their cursor before calling the
        ;; listener, making the "current" index actually the post-nav index.
        settled-index                           (atom 0)

        ;; Outstanding browser-initiated navigations counter. Increments on each
        ;; popstate that passes the debounce filter, decrements on each root save
        ;; while nav-state is set. Only resolves acceptance/denial when counter
        ;; reaches zero (i.e., the last root save for the last popstate).
        outstanding-navs                        (atom 0)

        do-popstate                             (fn [popped-index]
                                                  (if @restoring?
                                                    (reset! restoring? false) ;; consume restoration popstate silently
                                                    (do
                                                      (reset! settling? false)
                                                      (swap! outstanding-navs inc)
                                                      (let [pre-nav-url   @prev-url
                                                            pre-nav-index @settled-index]
                                                        (reset! nav-state {:browser-initiated? true
                                                                           :pre-nav-index      pre-nav-index
                                                                           :popped-index       popped-index
                                                                           :pre-nav-url        pre-nav-url})
                                                        (start-safety-timer!)
                                                        (resolve-route-and-navigate! app elements-by-id provider url-codec)))))

        popstate-fn #?(:cljs (fn [popped-index]
                               (when-let [t @debounce-timer] (js/clearTimeout t))
                               (reset! debounce-timer
                                 (js/setTimeout
                                   (fn []
                                     (reset! debounce-timer nil)
                                     (do-popstate popped-index))
                                   50)))
                       :clj                     do-popstate)

        on-save-handler
                                                (fn [_root-sid _wmem saving-sid]
                                                  (let [root-save?    (= saving-sid session-id)
                                                        state-map     (rapp/current-state app)
                                                        root-wmem     (get-in state-map [::sc/session-id session-id])
                                                        configuration (::sc/configuration root-wmem)]
                                                    (when configuration
                                                      (let [registry    (-> (rc/any->app app) :com.fulcrologic.fulcro.application/runtime-atom
                                                                          deref ::sc/env ::sc/statechart-registry)
                                                            new-url     (ruc/deep-configuration->url
                                                                          state-map registry session-id scf/local-data-path url-codec)
                                                            ;; Root-only URL (no child chart segments). Used for
                                                            ;; acceptance checks where the child hasn't initialized yet.
                                                            root-url    (let [chart          (scp/get-statechart registry statechart-id)
                                                                              elements-by-id (::sc/elements-by-id chart)
                                                                              local-data     (get-in state-map (scf/local-data-path session-id))]
                                                                          (ruc/configuration->url elements-by-id configuration local-data url-codec))
                                                            old-url     @prev-url
                                                            nav         @nav-state
                                                            browser-url (ruh/current-href provider)]
                                                        (cond
                                                          ;; === BROWSER-INITIATED: only resolve on root save ===

                                                          ;; Root save + browser-initiated → resolve acceptance/denial
                                                          (and root-save? (:browser-initiated? nav))
                                                          (let [remaining (swap! outstanding-navs dec)]
                                                            (cond
                                                              ;; More navs pending — skip (superseded by later popstate)
                                                              (pos? remaining) nil

                                                              ;; Last nav resolved — check acceptance/denial
                                                              (zero? remaining)
                                                              (if (and new-url
                                                                      (or (= new-url browser-url)
                                                                        ;; Prefix match: root URL matches but child chart
                                                                        ;; hasn't initialized yet, so deep-url is shorter.
                                                                        ;; Accept if browser URL starts with root path.
                                                                        (and root-url
                                                                          (not= new-url root-url)
                                                                          (cstr/starts-with? browser-url root-url))))
                                                                ;; ACCEPTED
                                                                (do
                                                                  (cancel-safety-timer!)
                                                                  (log/debug "URL sync: browser nav accepted" browser-url)
                                                                  (reset! nav-state nil)
                                                                  ;; Use browser-url (not new-url) as prev-url so that
                                                                  ;; child chart saves that refine the deep URL see a
                                                                  ;; change and can replaceState correctly.
                                                                  (reset! prev-url browser-url)
                                                                  (reset! settled-index (:popped-index nav))
                                                                  ;; Child chart invocations may fire saves that change the
                                                                  ;; deep URL (e.g. istate initializes a child). These must
                                                                  ;; use replaceState, not pushState, to preserve forward
                                                                  ;; history entries.
                                                                  (reset! settling? true)
                                                                  ;; Auto-clear after current processing cycle completes.
                                                                  ;; setTimeout(0) fires after all microtasks (promises)
                                                                  ;; from the current event processing resolve.
                                                                  #?(:cljs (js/setTimeout #(reset! settling? false) 0)))
                                                                ;; DENIED
                                                                (let [{:keys [popped-index pre-nav-index pre-nav-url]} nav]
                                                                  (cancel-safety-timer!)
                                                                  (log/debug "URL sync: route denied, undoing browser nav" browser-url "→" pre-nav-url)
                                                                  (reset! nav-state nil)
                                                                  (reset! restoring? true)
                                                                  (if (< popped-index pre-nav-index)
                                                                    (ruh/go-forward! provider)
                                                                    (ruh/go-back! provider))
                                                                  (reset! prev-url pre-nav-url)
                                                                  (when on-route-denied
                                                                    (on-route-denied browser-url))))

                                                              ;; Negative = spurious root save, clamp
                                                              :else (reset! outstanding-navs 0)))

                                                          ;; Child save + browser-initiated → skip (wait for root save)
                                                          (:browser-initiated? nav) nil

                                                          ;; === PROGRAMMATIC NAVIGATION (no nav-state) ===

                                                          ;; Branch 3: Initial load (old-url is nil)
                                                          (and new-url (nil? old-url))
                                                          (do
                                                            (log/debug "URL sync: initial URL" new-url)
                                                            (ruh/-replace-url! provider new-url)
                                                            (reset! prev-url new-url)
                                                            (reset! settled-index (ruh/current-index provider)))

                                                          ;; Branch 4: Programmatic navigation (no nav-state, URL changed)
                                                          (and new-url (not= new-url old-url))
                                                          (if (and @settling? (not root-save?))
                                                            ;; Post-acceptance settle: child chart init changed the deep
                                                            ;; URL. Replace (not push) to preserve forward history.
                                                            (do
                                                              (log/debug "URL sync: settling (replace)" new-url)
                                                              (ruh/-replace-url! provider new-url)
                                                              (reset! prev-url new-url))
                                                            ;; Normal programmatic nav: push a new history entry
                                                            (do
                                                              (reset! settling? false)
                                                              (log/debug "URL sync: pushing" new-url)
                                                              (ruh/-push-url! provider new-url)
                                                              (reset! prev-url new-url)
                                                              (reset! settled-index (ruh/current-index provider)))))))))]

    ;; Register the on-save handler, provider, and codec in the app's runtime atom
    (swap-url-sync! app update :handlers assoc session-id on-save-handler)
    (swap-url-sync! app update :providers assoc session-id provider)
    (when url-codec
      (swap-url-sync! app update :codecs assoc session-id url-codec))

    ;; Listen for browser back/forward via provider
    (ruh/set-popstate-listener! provider popstate-fn)

    ;; Initial URL restoration: check if the current URL maps to a route
    (resolve-route-and-navigate! app elements-by-id provider url-codec)

    ;; Return cleanup function
    (fn url-sync-cleanup! []
      (swap-url-sync! app update :handlers dissoc session-id)
      (swap-url-sync! app update :providers dissoc session-id)
      (swap-url-sync! app update :codecs dissoc session-id)
      ;; Clear any child→root cache entries pointing to this root
      (swap-url-sync! app update :child-to-root
        (fn [m] (into {} (remove (fn [[_ root]] (= root session-id))) m)))
      #?(:cljs (when-let [t @debounce-timer] (js/clearTimeout t)))
      (cancel-safety-timer!)
      (ruh/set-popstate-listener! provider nil))))

(defn route-current-url
  "Returns the current URL from the installed URL sync provider, or nil when unavailable."
  [app-ish]
  (when-let [provider (url-sync-provider app-ish)]
    (ruh/current-href provider)))

(defn route-history-index
  "Returns the current history index from the installed URL sync provider, or nil when unavailable."
  [app-ish]
  (when-let [provider (url-sync-provider app-ish)]
    (ruh/current-index provider)))

(defn route-sync-from-url!
  "Parses the current URL from the installed provider and routes the statechart to match.
   Returns the routed target keyword, or nil when no provider/session/URL target is available."
  [app-ish]
  (when-let [app (rc/any->app app-ish)]
    (when-let [provider (url-sync-provider app)]
      (when-let [statechart-id (session-statechart-id app)]
        (when-let [chart (scf/lookup-statechart app statechart-id)]
          (let [codec (or (get-in (url-sync-state app) [:codecs session-id])
                        (ruct/transit-base64-codec))]
            (resolve-route-and-navigate! app (::sc/elements-by-id chart) provider codec)))))))

(defn route-back!
  "Navigates back through the installed URL sync provider.
   Returns true when navigation was attempted, or nil when URL sync is unavailable."
  [app-ish]
  (when-let [provider (url-sync-provider app-ish)]
    (ruh/go-back! provider)
    true))

(defn route-forward!
  "Navigates forward through the installed URL sync provider.
   Returns true when navigation was attempted, or nil when URL sync is unavailable."
  [app-ish]
  (when-let [provider (url-sync-provider app-ish)]
    (ruh/go-forward! provider)
    true))
