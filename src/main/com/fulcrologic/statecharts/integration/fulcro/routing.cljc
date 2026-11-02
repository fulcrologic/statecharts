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
    [com.fulcrologic.statecharts.util :refer [new-uuid]]
    [edn-query-language.core :as eql]
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
  (let [targets (find-all-route-targets chart)
        by-name (group-by (fn [{:route/keys [target]}] (name target)) targets)]
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
  (let [direct-targets (find-all-route-targets chart)
        direct-by-name (into {} (map (fn [{:route/keys [target]}] [(name target) target])) direct-targets)
        reachable      (find-all-reachable-targets chart)]
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
              (if (and seg (not= "" seg)) (cons seg segments) segments))))))))

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

(defn- has-invoke-child?
  "True when the state element with `state-id` has an `:invoke` child in the normalized chart."
  [elements-by-id state-id]
  (boolean
    (some (fn [child-id]
            (= :invoke (:node-type (get elements-by-id child-id))))
      (:children (get elements-by-id state-id)))))

(defn- enclosing-routes-node-id
  "Walks up from `state-id` through `:parent` links to find the nearest ancestor
   whose element carries `:routing/root` (i.e. a `routes` node). Returns the
   ancestor's id, or nil if none found."
  [elements-by-id state-id]
  (loop [id (:parent (get elements-by-id state-id))]
    (let [el (get elements-by-id id)]
      (cond
        (nil? el) nil
        (contains? el :routing/root) id
        :else (recur (:parent el))))))

(defn- initial-chain-leaf
  "Walks the initial chain from `start-id` (a compound state) by following the
   `:initial` attribute of each compound state until landing on a leaf state.
   Returns the leaf's id, or nil if the chain cannot be resolved."
  [elements-by-id start-id]
  (loop [id start-id seen #{}]
    (cond
      (nil? id) nil
      (contains? seen id) nil
      :else
      (let [el      (get elements-by-id id)
            initial (:initial el)
            target  (cond
                      (::initial-route el) (::initial-route el)
                      (keyword? initial) initial
                      (and (vector? initial) (= 1 (count initial))) (first initial)
                      :else nil)]
        (if target
          (recur target (conj seen id))
          id)))))

(defn validate-empty-segments
  "Checks each route state with `:route/segment \"\"` against the rules for the
   sanctioned 'no URL contribution' marker:

   * Cannot host a child-chart invocation (i.e. cannot be an `istate`).
   * Cannot have descendants that are themselves route states.
   * Must lie on the initial chain of its enclosing `routes` node — i.e. walking
     `:initial` from the routes node down through compound states must arrive
     at this state.

   Returns a sequence of issue maps, empty when all empty-segment markers are valid."
  [{::sc/keys [elements-by-id]}]
  (let [empty-marked  (into []
                        (comp
                          (filter (fn [[_ el]]
                                    (and (:route/target el) (= "" (:route/segment el)))))
                          (map first))
                        elements-by-id)
        invalid-issue (fn [state-id reason]
                        {:warning-key :routing/invalid-empty-segment
                         :state-id    state-id
                         :reason      reason
                         :message     (str "State " state-id " has :route/segment \"\" but " reason
                                        ". The empty-segment marker is only valid on a leaf rstate "
                                        "that lies on the initial chain of its enclosing routes node.")})]
    (into []
      (keep
        (fn [state-id]
          (let [el           (get elements-by-id state-id)
                routes-id    (enclosing-routes-node-id elements-by-id state-id)
                chain-leaf   (when routes-id (initial-chain-leaf elements-by-id routes-id))
                child-route? (some
                               (fn [cid]
                                 (and (not= cid state-id)
                                   (:route/target (get elements-by-id cid))))
                               (:children el))]
            (cond
              (has-invoke-child? elements-by-id state-id)
              (invalid-issue state-id "it invokes a child chart (istate) — its segment is the bridge into the child's URL")

              child-route?
              (invalid-issue state-id "it has a routed descendant — only leaf routes may use the marker")

              (nil? routes-id)
              (invalid-issue state-id "it is not enclosed by a routes node (no ancestor with :routing/root)")

              (not= chain-leaf state-id)
              (invalid-issue state-id (str "it is not the initial-chain leaf of its routes node "
                                        "(initial chain resolves to " (pr-str chain-leaf) ")"))

              :else nil))))
      empty-marked)))

(defn validate-route-configuration
  "Runs all route configuration validators on `chart`. Reports issues according
   to `mode` (`:warn` or `:strict`). Returns the chart unchanged."
  [chart mode]
  (let [issues (concat
                 (validate-duplicate-leaf-names chart)
                 (validate-duplicate-segments chart)
                 (validate-reachable-collisions chart)
                 (validate-routing-root chart)
                 (validate-empty-segments chart))]
    (doseq [{:keys [warning-key message] :as issue} issues]
      (report-issue! mode warning-key message (dissoc issue :message :warning-key)))
    chart))

(defn- routing-key? [k] (and (keyword? k) (= (namespace k) (namespace ::session))))

(defn state-route-params
  "Returns the route params the route event's `event-data` gives the route state `state-id`. A URL restore's event
   carries each state's own (allowed) params at `::params-by-state`: then this is that state's entry (`{}` when it
   has none), plus every top-level param of `event-data` that the restore didn't put there with that value
   (`::restored-params`), so a replayed restore event with a changed or added param gives the route that value.
   Such a param goes to every state, whatever the state declares: what a state stores is still only its declared
   `:route/params` (`establish-route-params-node`), but `initialize-route!` uses all of it (a form's `:id`, or a
   component's initial-state params). Otherwise it is `event-data` itself."
  [event-data state-id]
  (let [by-state (get event-data ::params-by-state)]
    (if (map? by-state)
      (let [restored (get event-data ::restored-params {})]
        (into (get by-state state-id {})
          (remove (fn [[k v]] (or (routing-key? k) (and (contains? restored k) (= v (get restored k))))))
          event-data))
      event-data)))

(defn initialize-route!
  "Logic that the statechart will run to initialize the target route component's state in the Fulcro database.
   This is controlled primarily by two options:

   sfro/initialize
   sfro/initial-props

   If the target component has a constant ident and initial-state, then neither option is necessary and the default
   for sfro/initialize will be :once. If the ident isn't a constant, then you must make sure the the initial-props or initial-state
   will lead to data that can be used with `(get-ident Class data)` to get a valid ident.

   Without `sfro/initial-props`, a form's `:id`, or a non-form's initial-state params, come from the route event's
   data for this state (`state-route-params`): on a URL restore, only the state's own params.

   Returns the ident of the initialized component."
  [{:fulcro/keys [app] :as env} {::keys [target] :as data}]
  (when app
    (let [state-map     (rapp/current-state app)
          event-data    (state-route-params (get-in data [:_event :data]) target)
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
      {:parent   (rc/component-name Parent)
       :target   (rc/component-name Target)
       :join-key join-key})
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
   to `[:routing/parameters id]` in the data model: the event data's keys that are in `:route/params` (never
   `:route/url-params`, which therefore stay out of the URL).

   When the event restores a URL (it has `::params-by-state`), the state takes only its own entry there, so states
   that declare the same keys (e.g. in parallel regions) each get their own values, and a state with no params in
   the URL gets none."
  [{:keys       [id]
    :route/keys [params]}]
  (script
    {:expr
     (fn [env _dm _e event-data]
       (let [actual-params (select-keys (or (state-route-params event-data id) {}) (or params #{}))]
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
     `[:routing/parameters <state-id>]` in the data model, and URL sync writes them to the URL.
   * `:route/url-params` — (optional) A set of keywords that a URL restore (bookmark, back/forward,
     `route-sync-from-url!`) also accepts for this state, in addition to `:route/params`. URL params are untrusted, so
     a restore passes on only these two sets. These keys reach the route event like route params do (the state's
     entry at `::params-by-state`, and the event's top level for the restored path), but they are never stored in
     `[:routing/parameters <state-id>]`, so URL sync never writes them to the URL. For a state that reads its own
     params from a URL it is opened from, without tracking them in the URL. Keys of this namespace are always
     dropped.
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
   * `:route/params`, `:route/url-params` — (optional) As on `rstate`.
   * `:route/reachable` — A set of route target keywords reachable through the child chart,
     or a map of `{target-keyword \"segment\"}` when child targets use custom `:route/segment` values.
     When a map is provided, the keys become the reachable set and the values are stored as
     `:route/reachable-segments` for URL restoration. When omitted, it is derived from the target's
     `sfro/statechart` (a chart map). A chart given by keyword (`sfro/statechart-id` or `statechart-id`) can't be
     walked when the chart is built, so then the istate has no reachable routes (logged at debug): list them here.
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
  (let [target-key         (coerce-to-keyword target)
        id                 target-key
        ;; Normalize reachable: accept set (legacy) or map {kw "segment"} (new)
        reachable-input    (or reachable
                             (let [cls (rc/registry-key->class target-key)]
                               (when (nil? cls)
                                 (throw (ex-info (str "istate: component " target-key " is not registered. "
                                                   "Either require the component namespace or provide explicit :route/reachable.")
                                          {:route/target target-key})))
                               (let [child-chart (rc/component-options cls sfro/statechart)
                                     chart-id    (or statechart-id (rc/component-options cls sfro/statechart-id))]
                                 (cond
                                   child-chart (not-empty (reachable-targets child-chart))
                                   ;; A keyword chart can't be walked at chart-build time (it may not be registered yet)
                                   chart-id (do
                                              (log/debug "istate:" target-key "invokes the chart" chart-id
                                                "and has no :route/reachable, so it has no reachable routes.")
                                              nil)
                                   :else
                                   (throw (ex-info (str "istate: component " target-key " has no sfro/statechart option. "
                                                     "Either co-locate the chart on the component or provide explicit :route/reachable.")
                                            {:route/target target-key}))))))
        reachable-map?     (and reachable-input (map? reachable-input))
        reachable          (cond
                             (nil? reachable-input) nil
                             reachable-map? (not-empty (set (keys reachable-input)))
                             :else reachable-input)
        ;; Build segment map: when user provides a map, values are segments.
        ;; When auto-derived, walk child chart elements to extract :route/segment overrides.
        reachable-segments (cond
                             reachable-map?
                             (not-empty (into {}
                                          (map (fn [[kw seg]] [(coerce-to-keyword kw) seg]))
                                          reachable-input))

                             (and reachable (not reachable-input))
                             nil

                             reachable
                             (let [cls         (rc/registry-key->class target-key)
                                   child-chart (some-> cls (rc/component-options sfro/statechart))]
                               (when child-chart
                                 (not-empty
                                   (into {}
                                     (comp
                                       (filter (fn [[_id el]]
                                                 (and (:route/target el) (:route/segment el))))
                                       (map (fn [[_id el]]
                                              [(coerce-to-keyword (:route/target el)) (:route/segment el)])))
                                     (::sc/elements-by-id child-chart)))))

                             :else nil)
        state-props        (cond-> state-props
                             reachable (assoc :route/reachable reachable)
                             reachable-segments (assoc :route/reachable-segments reachable-segments))]
    (apply state (cond-> (-> (assoc state-props :id id :route/target target-key)
                           (dissoc :invoke-params :finalize :autoforward :namelist :on-done :exit-target :statechart-id))
                   statechart-id (assoc ::statechart-id statechart-id))
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
                                          {:fulcro/actors        (fn [env data & _]
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
                                          (if-let [Target (rc/registry-key->class target-key)]
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

(defn- component-busy?
  "True when the component `Target` is busy at `ident`: its `sfro/busy?` (called with `app` and its props there) is
   true, or, without one, it is a form whose props are `fs/dirty?`. The props are built only for a component that has
   one of those checks."
  [app state-map Target ident]
  (let [busy-fn (or (sfro/busy? (rc/component-options Target))
                  ;; Default: dirty forms are busy
                  (when (form? Target)
                    (fn [_app form-props] (and form-props (fs/dirty? form-props)))))]
    (boolean
      (when busy-fn
        (busy-fn app (when ident (fns/ui->props state-map Target ident)))))))

(defn- check-component-busy?
  "Check a single route target component for busy conditions (dirty form, custom sfro/busy? fn).
   Returns true if the component is busy, false otherwise.
   Pre-resolves props for the target component and passes (app, props) to the busy-fn."
  [app state-map local-data target-key]
  (if-let [Target (rc/registry-key->class target-key)]
    (component-busy? app state-map Target
      (get-in local-data [:route/idents target-key]
        (when (rc/has-ident? Target) (rc/get-ident Target {}))))
    false))

(defn- actors-busy?
  "True when one of the `:fulcro/actors` in a session's `local-data` is a busy component (see `component-busy?`)."
  [app state-map local-data]
  (boolean
    (some (fn [[_ {:keys [component ident]}]]
            (when-let [Target (and component ident (rc/registry-key->class component))]
              (component-busy? app state-map Target ident)))
      (:fulcro/actors local-data))))

(defn- child-sessions
  "Returns `{session-id [child-session-id ...]}`: the ids of the running sessions each session in `state-map` invoked
   (every session whose parent it is; stopping an invocation deletes the child's working memory, so these are all
   invocations of active states, routed or not). Built once per busy check: scanning every session for each one
   visited made the check quadratic in the number of sessions."
  [state-map]
  (reduce-kv
    (fn [acc sid wmem]
      (if-let [parent (::sc/parent-session-id wmem)]
        (update acc parent (fnil conj []) sid)
        acc))
    {}
    (::sc/session-id state-map)))

(defn- deep-busy?
  "Recursively checks busy conditions through invoked child charts.
   A session is busy when an active route state's target is busy, or (for an invoked session) when one of its
   actors is, or when a session it invoked is: every child invocation of an active state is walked, routed
   (an istate's child chart, at `[:invocation/id target]`) or not (e.g. an editor a host's chart invokes: any
   running session whose parent is this one).
   `children` is `(child-sessions state-map)`. `seen` is a set of already-visited session IDs to prevent infinite
   recursion."
  ([app registry state-map session-id seen]
   (deep-busy? app registry state-map (child-sessions state-map) session-id seen))
  ([app registry state-map children session-id seen]
   (when-not (contains? seen session-id)
     (let [wmem           (get-in state-map [::sc/session-id session-id])
           configuration  (::sc/configuration wmem)
           statechart-src (::sc/statechart-src wmem)]
       (when (and configuration statechart-src)
         (let [chart          (scp/get-statechart registry statechart-src)
               elements-by-id (::sc/elements-by-id chart)
               local-data     (get-in state-map (scf/local-data-path session-id))
               invoked?       (seq seen)
               seen           (conj seen session-id)]
           (or
             (some (fn [state-id]
                     (when-let [target-key (get-in elements-by-id [state-id :route/target])]
                       (check-component-busy? app state-map local-data target-key)))
               configuration)
             ;; The guarding session's own actors aren't left by its route change; an invoked session's are.
             (when invoked?
               (actors-busy? app state-map local-data))
             (some (fn [child-sid] (deep-busy? app registry state-map children child-sid seen))
               (distinct
                 (concat
                   ;; Routed: the istates' recorded invocations
                   (keep (fn [state-id]
                           (when-let [target-key (get-in elements-by-id [state-id :route/target])]
                             (get-in local-data [:invocation/id target-key])))
                     configuration)
                   ;; Every other running child invocation
                   (get children session-id)))))))))))

(defn- proper-ancestors
  "The ids of the proper ancestors of element `id` in `elements-by-id`, nearest first (not the chart root, `:ROOT`)."
  [elements-by-id id]
  (loop [acc [] pid (:parent (get elements-by-id id))]
    (if (contains? elements-by-id pid)
      (recur (conj acc pid) (:parent (get elements-by-id pid)))
      acc)))

(defn- route-transitions-for
  "The `routes` route transitions (`::route-kind` `:direct`, `:enter-owner` or `:forward`) that the route event
   `event-name` takes in `configuration` of the chart with `elements-by-id`: for each active atomic state, the first
   applicable one on it or an ancestor (nearest first, then in document order), as SCXML selects them. The busy
   guard is skipped: these are what it guards."
  [elements-by-id configuration event-name]
  (let [parents (into #{} (map #(:parent (get elements-by-id %))) configuration)
        atomic  (remove parents configuration)]
    (into #{}
      (keep (fn [atomic-id]
              (some (fn [state-id]
                      (some (fn [child-id]
                              (let [{::keys [route-kind route-owner] :keys [event] :as t} (get elements-by-id child-id)]
                                (when (and route-kind (= event-name event)
                                        (case route-kind
                                          :direct true
                                          :enter-owner (not (contains? configuration route-owner))
                                          :forward (contains? configuration route-owner)
                                          false))
                                  t)))
                        (:children (get elements-by-id state-id))))
                (cons atomic-id (proper-ancestors elements-by-id atomic-id)))))
      atomic)))

(defn- exited-states
  "The states of `configuration` that the route transition `t` (see `route-transitions-for`) exits, by SCXML's
   transition domain: a direct route transition is external and its target is under its routes node, so it exits
   everything under the routes node's nearest compound ancestor (the routes node too); entering an owner istate is
   internal, so it exits what is under the routes node; forwarding to the owner's child session exits nothing in this
   session."
  [elements-by-id configuration {::keys [route-kind] :keys [parent]}]
  (let [domain (case route-kind
                 :direct (or (some (fn [id] (when (= :state (:node-type (get elements-by-id id))) id))
                               (proper-ancestors elements-by-id parent))
                           :ROOT)
                 :enter-owner parent
                 nil)]
    (cond
      (nil? domain) #{}
      (= :ROOT domain) (set configuration)
      :else (into #{} (filter #(some #{domain} (proper-ancestors elements-by-id %))) configuration))))

(defn- generated-invoker
  "The id string of the state that invoked the session `child-session-id` when that is an id the invocation generated
   (`<state id>.<uuid>`, for an invoke with no `:id`), else nil."
  [child-session-id]
  (when (string? child-session-id)
    (let [n (count child-session-id)]
      (when (and (> n 37) (= \. (nth child-session-id (- n 37))))
        (subs child-session-id 0 (- n 37))))))

(defn- kept-invocation?
  "Returns a predicate of child session ids: true for a session that an invoke of a state of `configuration` that
   isn't in `exited` started: by its explicit `:id`, or by the id the invoke generated (with or without an
   `:idlocation`, see `generated-invoker`). Those sessions stay when the route change runs."
  [elements-by-id configuration exited]
  (let [invokes (for [state-id (remove exited configuration)
                      child-id (:children (get elements-by-id state-id))
                      :let [{:keys [node-type explicit-id? id]} (get elements-by-id child-id)]
                      :when (= :invoke node-type)]
                  {:state-id state-id :id (when explicit-id? id)})
        ids     (into #{} (keep :id) invokes)
        states  (into #{} (map (comp str :state-id)) invokes)]
    (fn [sid] (boolean (or (contains? ids sid) (contains? states (generated-invoker sid)))))))

(defn- route-change-busy?
  "True when the route event `event-name` would exit something busy in the session `session-id` (or below it).
   Only what the route change exits is checked (see `exited-states`): the route targets of the exited states, and
   every session invoked by an exited state (all of it, see `deep-busy?`: such a session stops). When the event is
   forwarded to an active owner istate's child session, the route change it makes there is checked the same way.
   The session's own actors aren't checked, nor what an active state that stays invoked. `children` is
   `(child-sessions state-map)`; `seen` is a set of already-visited session IDs."
  [app registry state-map children session-id event-name seen]
  (when-not (contains? seen session-id)
    (let [wmem           (get-in state-map [::sc/session-id session-id])
          configuration  (::sc/configuration wmem)
          statechart-src (::sc/statechart-src wmem)]
      (when (and configuration statechart-src)
        (let [elements-by-id (::sc/elements-by-id (scp/get-statechart registry statechart-src))
              local-data     (get-in state-map (scf/local-data-path session-id))
              seen           (conj seen session-id)
              transitions    (route-transitions-for elements-by-id configuration event-name)
              exited         (into #{} (mapcat #(exited-states elements-by-id configuration %)) transitions)
              owners         (into [] (keep #(when (= :forward (::route-kind %)) (::route-owner %))) transitions)
              kept?          (kept-invocation? elements-by-id configuration exited)]
          (boolean
            (or
              (some (fn [state-id]
                      (when-let [target-key (get-in elements-by-id [state-id :route/target])]
                        (check-component-busy? app state-map local-data target-key)))
                exited)
              (some (fn [child-sid] (deep-busy? app registry state-map children child-sid seen))
                (remove kept? (get children session-id)))
              (some (fn [owner-id]
                      (when-let [child-sid (get-in local-data [:invocation/id owner-id])]
                        (route-change-busy? app registry state-map children child-sid event-name seen)))
                owners))))))))

(defn- route-event? [event-name]
  (and (keyword? event-name) (some-> (namespace event-name) (cstr/starts-with? "route-to."))))

(defn busy?
  "Returns true when the current route change would exit something busy: a dirty form or a component whose
   `sfro/busy?` is true, as a route target, or as an actor of a session it would stop, at any depth (see
   `route-change-busy?`). What stays active when the route changes (e.g. an editor that a state beside the routes
   invokes, or an owner istate whose child chart gets the route) isn't checked. For an event that isn't a route event,
   true when anything active is busy at any depth (see `deep-busy?`). Used as the sole guard condition on route
   transitions."
  [{:fulcro/keys [app] :as env} {:keys [_event] :as data} & args]
  (if-not app
    false
    (if (-> _event :data ::force?)
      false
      (let [registry   (::sc/statechart-registry env)
            state-map  (rapp/current-state app)
            session    (senv/session-id env)
            event-name (:name _event)]
        (boolean
          (if (route-event? event-name)
            (route-change-busy? app registry state-map (child-sessions state-map) session event-name #{})
            (deep-busy? app registry state-map session #{})))))))

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

(defn- restore-forwarded-ops
  "The ops of a route transition that forwards the route event (`event-data`) to a module's session: when it is a
   deferred URL restore's (`route-sync-from-url!`, see `install-url-sync!`'s `:defer-restore?`), one that clears the
   `::restored-token` this session noted when it dequeued the event. The module's session notes the token when it
   dequeues the forwarded event, so the deferral ends at that session's save instead (once it routes), not at this
   one's. When the module can't save it (it was stopped before it dequeued the event, or its step is superseded or
   fails), the event processing hands the token to the routing session (`::restore-dropped`), whose save ends it."
  [event-data]
  (when (contains? event-data ::restore-token)
    [(ops/assign ::restored-token nil)]))

(defn- routes-state-id
  "The id of the routes node `routes-id`'s generated state `kind` (`\"routes-initial\"`, `\"routes-pending\"`; see
   `routes`): in the namespace `<this ns>.<kind>`, followed by `.<namespace of routes-id>` when it has one, with the
   name of `routes-id`. Distinct routes ids give distinct ids (`:a.b/c` and `:a/b.c` too), readable as EDN."
  [kind routes-id]
  (keyword (str (namespace ::session) "." kind (some->> (namespace routes-id) (str ".")))
    (name routes-id)))

(defn routes
  "Emits a state that represents the region containing all of the routes.
   Generates transitions for direct navigation to any substate that has a routing target.

   Also generates cross-chart transitions for targets declared in `:route/reachable` sets
   on `istate` children. These transitions target the declaring `istate` and store a
   `::pending-child-route` in the data model so the child chart can self-route on startup.

   When a chart starts with a pending child route (it is an istate's child chart and the route event was for one of
   its routes), the routes node enters that route directly: its initial route is entered only when there is no
   pending route (or the pending route is denied). To do that, the routes node's initial state is a transient state
   (id `...routing.routes-initial[.<id's namespace>]/<id's name>`) whose eventless transitions go on at once: to a
   waiting state (`...routing.routes-pending...`) when the routes node raised a pending route on entry, else to the initial route
   (`:initial`, else the first route state). The raised route event's transition leaves the waiting state. A routes
   node with an explicit `initial` element child keeps that element instead.

   The generated transitions carry `::route-kind` (`:direct`; `:enter-owner` and `:forward` for a reachable target of
   an istate that isn't, or is, active) and, on the cross-chart ones, `::route-owner` (the istate's id), which the busy
   guard (`busy?`) uses. A direct route transition is external (SCXML's default): it exits and re-enters everything
   under this node's nearest compound ancestor, this node and the regions beside it in a parallel state included. So
   an `invoke` that must survive route changes (e.g. an editor) belongs on a state above this node (above its parallel
   state, if it is in one), not on this node, a route state, or a sibling region.

   The :routing/root must be a component with a constant ident (or the app root itself),
   and a query."
  [{:routing/keys [root]
    :keys         [id]   :as props} & route-states]
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
        handled-events     (into #{} (map route-to-event-name) (concat all-direct-targets reachable-only))
        child-states       (filterv #(#{:state :parallel :final} (:node-type %)) route-states)
        initial-route      (or (:initial props) (:id (first child-states)))
        dispatch-id        (when (and id initial-route (not-any? :initial? child-states))
                             (routes-state-id "routes-initial" id))
        waiting-id         (when dispatch-id (routes-state-id "routes-pending" id))
        raised-path        [::raised-pending-route id]
        clear-raised       (ops/assign raised-path nil)
        dispatch-states    (when dispatch-id
                             ;; The unconditional transition comes last, so a chart whose conds don't run (e.g. the
                             ;; testing env's unmocked conds) still enters its initial route.
                             [(state {:id dispatch-id ::initial-route initial-route}
                                (transition {:cond   (fn [_env data & _] (boolean (get-in data raised-path)))
                                             :target waiting-id})
                                (transition {:target initial-route}))
                              ;; Waits for the raised pending route; goes on to the initial route if it was denied.
                              (state {:id waiting-id ::initial-route initial-route}
                                (transition {:cond   (fn [_env data & _] (not (get-in data raised-path)))
                                             :target initial-route}))])
        direct-transitions (mapv
                             (fn [t]
                               (transition {:event       (route-to-event-name t)
                                            :target      t
                                            ::route-kind :direct}
                                 (ele/raise {:event :event.routing-info/close})
                                 (script {:expr (fn [& _] [clear-raised])})))
                             all-direct-targets)
        cross-transitions  (mapcat
                             (fn [reachable-target]
                               (let [owner-id (find-reachable-owner route-states reachable-target)]
                                 [;; Transition 1: When NOT already in owner-id — enter it and store pending route
                                  ;; :type :internal prevents re-entering the routes state, which would
                                  ;; clear pending-child-route before the invoke can read it.
                                  (transition {:event        (route-to-event-name reachable-target)
                                               :type         :internal
                                               :target       owner-id
                                               ::route-kind  :enter-owner
                                               ::route-owner owner-id
                                               :cond         (fn [env _data & _]
                                                               (not (contains? (senv/current-configuration env) owner-id)))}
                                    (ele/raise {:event :event.routing-info/close})
                                    (script {:expr (fn [_env _data _event-name event-data]
                                                     [clear-raised
                                                      (ops/assign ::pending-child-route
                                                        {:event (route-to-event-name reachable-target)
                                                         :data  event-data})])}))
                                  ;; Transition 2: When ALREADY in owner-id — forward to child chart's session.
                                  ;; If the owner was entered in this macrostep its invocation hasn't started yet:
                                  ;; then the route becomes its pending route.
                                  (transition {:event        (route-to-event-name reachable-target)
                                               :type         :internal
                                               ::route-kind  :forward
                                               ::route-owner owner-id
                                               :cond         (fn [env _data & _]
                                                               (contains? (senv/current-configuration env) owner-id))}
                                    (ele/raise {:event :event.routing-info/close})
                                    (script {:expr (fn [env data _event-name event-data]
                                                     (let [child-session-id (get-in data [:invocation/id owner-id])
                                                           {::sc/keys [event-queue]} env
                                                           event            (route-to-event-name reachable-target)]
                                                       (if child-session-id
                                                         (do
                                                           (scp/send! event-queue env
                                                             {:target            child-session-id
                                                              :source-session-id (senv/session-id env)
                                                              :event             event
                                                              :data              (or event-data {})})
                                                           (restore-forwarded-ops event-data))
                                                         [(ops/assign ::pending-child-route {:event event
                                                                                             :data  event-data})])))}))]))
                             reachable-only)]
    (apply state (cond-> props dispatch-id (assoc :initial dispatch-id))
      (remove nil?
        (concat
          [(on-entry {}
             (script {:expr (fn [env data & _]
                              (let [root-key      (coerce-to-keyword root)
                                    Root          (rc/registry-key->class root-key)
                                    root-ident    (when (and Root (rc/has-ident? Root)) (rc/get-ident Root {}))
                                    pending-route (get data ::pending-child-route)
                                    handled?      (contains? handled-events (:event pending-route))
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
                                ;; Clear pending-child-route after use. When this node routes the raised event, its
                                ;; transient initial state waits for it (every route transition clears the mark).
                                (cond-> (vec assign-ops)
                                  pending-route (conj (ops/assign ::pending-child-route nil))
                                  (and pending-route handled?) (conj (ops/assign raised-path true)))))}))]
          [(transition {:event :route-to.*
                        :cond  busy?}
             (script {:expr record-failed-route!})
             (script {:expr (fn [& _] [clear-raised])})
             (ele/raise {:event :event.routing-info/show}))]
          direct-transitions
          cross-transitions
          dispatch-states
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
   has an invocation session ID in the routing session's local-data, and whose session is running (has a
   configuration). The routing session keeps an `:invocation/id` entry after its route exits, so an ended session's
   id is skipped and the walk goes on up. Returns the child session ID, or nil if none found."
  [this state-map]
  (loop [c this]
    (when c
      (let [key (rc/class->registry-key (rc/component-type c))
            sid (get-in state-map [::sc/local-data session-id :invocation/id key])]
        (if (and sid (seq (get-in state-map [::sc/session-id sid ::sc/configuration])))
          sid
          (recur (rc/isoget-in c [:props :fulcro$parent])))))))

(defn send-to-self!
  "Send an event to the nearest running invoked statechart co-located on `this` or an ancestor (a route that was left
   keeps its invocation id in the routing session; its ended session is skipped)."
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
           (if-let [cls (rc/registry-key->class target-key)]
             (if-let [child (rc/component-options cls sfro/statechart)]
               (into acc (reachable-targets child (conj seen target-key)))
               acc)
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

(defn- prev-url
  "The URL url-sync last wrote or accepted for the routing session, or nil before its first save."
  [app]
  (get-in (url-sync-state app) [:prev-urls session-id]))

(defn- set-prev-url! [app url] (swap-url-sync! app assoc-in [:prev-urls session-id] url))

(defn- restore-deferred?
  "True while URL sync was installed with `:defer-restore?` and the restore of `route-sync-from-url!` hasn't been
   processed yet. The value at `[:restore-deferred session-id]` is `true` until `route-sync-from-url!` runs, then
   `{:token t}` until the save of the session that took its route event (see `restore-processed?`)."
  [app]
  (boolean (get-in (url-sync-state app) [:restore-deferred session-id])))

(defn- restore-processed?
  "True when the session `saving-sid` (the routing session or one of its modules, local data in `state-map`) took the
   route event of the deferred restore that `route-sync-from-url!` started: the event processing noted the restore's
   token at `::restored-token` in its local data when the session dequeued the event, whichever transition took it,
   if any (a session that forwards the event to a module's session clears it, see `restore-forwarded-ops`). The
   routing session also notes it when it dequeues a `::restore-dropped` event: one that hands it the token of a route
   event whose session can't save it."
  [app state-map saving-sid]
  (let [{:keys [token]} (get-in (url-sync-state app) [:restore-deferred session-id])]
    (boolean (and token (= token (get-in state-map (scf/local-data-path saving-sid ::restored-token)))))))

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

(defn- url-sync-codec [app]
  (or (get-in (url-sync-state app) [:codecs session-id])
    (get-in (url-sync-state app) [:codec])))

(defn- route-session-id
  "The id of the routing session in `state-map` that has the active route `target-key`: the routing session, or a
   session an istate invoked below it, at any depth (depth first, the routing session's own routes first). A route
   is active from its state's entry, which records its ident at `[:route/idents target-key]` in that session's local
   data, until its exit. Nil when no session has it."
  [state-map target-key]
  (letfn [(find-in [sid seen]
            (when-not (contains? seen sid)
              (let [local (get-in state-map (scf/local-data-path sid))]
                (if (contains? (:route/idents local) target-key)
                  sid
                  (some #(find-in % (conj seen sid)) (vals (:invocation/id local)))))))]
    (find-in session-id #{})))

(defn replace-route-params!
  "Updates the `:route/params` for the given currently-active route `target` and,
   if URL sync is installed, replaces the current browser URL using the history
   provider's `-replace-url!` method (no new history entry, no rstate re-entry).
   The params are written in the routing session that has the route (`route-session-id`), so a route in a routing
   module (an istate's child chart, at any depth) works too; for a route no session has, in the routing session.

   This is the equivalent of the original UISM RAD library's URL replacement
   signal: components/statecharts can call this when their internal
   URL-relevant state changes (e.g. report sort/page/selection or control
   parameter values) so the URL stays bookmarkable without re-running route
   entry actions.

   No-op when URL sync is not installed, which makes it safe to call
   unconditionally from app code. While the initial restore is deferred (`install-url-sync!`'s `:defer-restore?`)
   only the params are updated.

   Url-sync records the replaced URL as the one it last wrote, so the next save with an unchanged route pushes
   nothing."
  [app-ish target params]
  (when (url-sync-installed? app-ish)
    (let [app        (rc/any->app app-ish)
          target-key (coerce-to-keyword target)
          provider   (url-sync-provider app)
          codec      (url-sync-codec app)
          state-atom (:com.fulcrologic.fulcro.application/state-atom app)]
      (when (and provider codec state-atom)
        (swap! state-atom assoc-in
          (scf/local-data-path (or (route-session-id @state-atom target-key) session-id) :routing/parameters target-key)
          (or params {}))
        (let [registry (-> app runtime-atom deref ::sc/env ::sc/statechart-registry)
              new-url  (ruc/deep-configuration->url
                         (rapp/current-state app) registry session-id scf/local-data-path codec)]
          (when (and new-url (not (restore-deferred? app)))
            (ruh/-replace-url! provider new-url)
            ;; So the next save with an unchanged route sees no change (no duplicate push).
            (set-prev-url! app new-url)))))))

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

(defn- invoked-chart
  "Returns the chart an istate `element` invokes (its `:statechart-id` override, else the target's
   `sfro/statechart-id` or `sfro/statechart`), or nil when it can't be found."
  [app element]
  (let [cls (rc/registry-key->class (:route/target element))
        id  (or (::statechart-id element) (some-> cls (rc/component-options sfro/statechart-id)))]
    (or (when id (scf/lookup-statechart app id))
      (some-> cls (rc/component-options sfro/statechart)))))

(defn- nearest-route-ancestor
  "The id of the nearest ancestor of `state-id` in `elements-by-id` that has a `:route/target`, or nil."
  [elements-by-id state-id]
  (loop [id (:parent (get elements-by-id state-id))]
    (when-let [el (get elements-by-id id)]
      (if (:route/target el) id (recur (:parent el))))))

(defn- route-state-index
  "Returns `{state-id {:url-keys accepted-keys :up state-id}}` for every route state of the chart with
   `elements-by-id` and of the charts its istates invoke (as far as they can be found). `:url-keys` is the set of
   param keys a URL restore may pass to the state: its `:route/params` and `:route/url-params`. `:up` is the nearest
   route state above (the invoking istate for a child chart's top route states), or nil at the root."
  ([app elements-by-id] (route-state-index app elements-by-id nil #{}))
  ([app elements-by-id owner seen]
   (reduce-kv
     (fn [acc id {:route/keys [target params url-params] :as el}]
       (if-not target
         acc
         (let [acc   (assoc acc id {:url-keys (into (set params) url-params)
                                    :up       (or (nearest-route-ancestor elements-by-id id) owner)})
               child (when (and (has-invoke-child? elements-by-id id) (not (contains? seen target)))
                       (invoked-chart app el))]
           (if child
             (merge (route-state-index app (::sc/elements-by-id child) id (conj seen target)) acc)
             acc))))
     {}
     elements-by-id)))

(defn- allowed-url-params
  "Returns the URL-decoded `params-by-state` (route state id -> params) with, for each state, only the keys it
   declares in `:route/params` or `:route/url-params`, never a key of this namespace (routing control keys such as
   `::force?`). States that aren't in `index` (see `route-state-index`) and states left with no params are dropped."
  [index params-by-state]
  (reduce-kv
    (fn [acc state-id params]
      (if-let [{:keys [url-keys]} (get index state-id)]
        (let [kept (when (map? params)
                     (into {} (filter (fn [[k _]] (and (contains? url-keys k) (not (routing-key? k))))) params))]
          (cond-> acc (seq kept) (assoc state-id kept)))
        (do
          (log/warn "URL sync: ignoring URL params of" state-id "(not a known route state)")
          acc)))
    {}
    (if (map? params-by-state) params-by-state {})))

(defn- url-route-event-data
  "Returns the data of the route event that restores a URL whose decoded params are `params-by-state`, for the route
   state `leaf-id`: the allowed params (`allowed-url-params`) of `leaf-id` and the route states above it (deeper
   states win), plus every state's own allowed params at `::params-by-state` and a copy of those top-level params at
   `::restored-params` (see `state-route-params`)."
  [index leaf-id params-by-state]
  (let [allowed (allowed-url-params index params-by-state)
        path    (loop [id leaf-id path () seen #{}]
                  (if (or (nil? id) (contains? seen id))
                    path
                    (recur (get-in index [id :up]) (cons id path) (conj seen id))))
        params  (into {} (map #(get allowed %)) path)]
    (assoc params
      ::params-by-state allowed
      ::restored-params params)))

(defn- resolve-route-and-navigate!
  "Parses the current URL from `provider`, finds the matching route target, and sends
   a route-to event. Used by both popstate handling and initial URL restoration.
   Uses the codec for decoding, with fallback to `find-target-by-leaf-name-deep` for
   cross-chart `:route/reachable` targets.

   URL params are untrusted: the route event gets only the params each route state declares in `:route/params` or
   `:route/url-params` (see `url-route-event-data`). `extra-data` (the routing namespace's own keys) is added to it.
   Returns the routed target, or nil when the URL matches no route (nothing is sent then)."
  ([app elements-by-id provider codec] (resolve-route-and-navigate! app elements-by-id provider codec {}))
  ([app elements-by-id provider codec extra-data]
   (let [href    (ruh/current-href provider)
         decoded (ruc/decode-url codec href elements-by-id)
         index   (delay (route-state-index app elements-by-id))]
     (if-let [leaf-id (:leaf-id decoded)]
       (do
         (route-to! app leaf-id (merge (url-route-event-data @index leaf-id (:params decoded)) extra-data))
         leaf-id)
       ;; Codec didn't match directly — try reachable targets via deep search
       ;; (needed for cross-chart :route/reachable targets not in this chart's elements)
       (let [segments  (ruh/current-url-path href)
             leaf-name (peek segments)]
         (when leaf-name
           (when-let [{:keys [target-key child? owner-id]} (ruh/find-target-by-leaf-name-deep elements-by-id leaf-name)]
             (when child?
               (route-to! app target-key (merge (url-route-event-data @index
                                                  (if (contains? @index target-key) target-key owner-id)
                                                  (:params decoded))
                                           extra-data))
               target-key))))))))

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
     `(ruct/transit-base64-codec)`. Pass `(ruct/transit-base64-codec \"/app\")` to
     deploy under a sub-path prefix.
   * `:prefix` — A shorthand for that: the default codec with this path prefix (e.g. `\"/app\"`). Ignored, with a
     warning, when a `:url-codec` is given too.
   * `:on-route-denied` — `(fn [url])` called when back/forward navigation is denied by the busy guard
   * `:defer-restore?` — When true, don't restore the current URL now (e.g. until the app knows whether the user is
     logged in). Until the app calls `route-sync-from-url!`, URL sync writes no URL (so the bookmarked entry is kept,
     whatever the chart routes to meanwhile) and ignores back/forward. `route-sync-from-url!` then routes to the
     current URL, on that same history entry, and URL sync goes on as usual from the save of the session that takes
     its route event: the routing session's first save after it dequeued the event, whichever of its transitions took
     it, if any (a route transition, a gate state's own `route-to.*` transition, the busy guard, or none at all, e.g.
     while the chart waits outside its routes). When the routing session forwards the event to an active module
     (a reachable route of an istate that is already active), it is that module's save. Also when it's called from a
     step, of any session: that step's own save writes no URL. A route event that no route transition takes still
     ends the deferral, and URL sync writes the chart's current route on the bookmarked entry (at that save, or at
     the first one with a route). When the session that should take the event can't save it (a module stopped
     before it dequeues the forwarded event, or whose step that took it is superseded, e.g. stopped while its promise
     is pending, or fails), the event processing hands the token to the routing session in an event of its own
     (`::restore-dropped`, which no transition takes), and the deferral ends at the routing session's save of it:
     URL sync writes the current route on the bookmarked entry then, without waiting for another route."
  [app & [{:keys [on-route-denied provider url-codec prefix routing/checks defer-restore?]
           :or   {checks :warn}}]]
  (when (and prefix url-codec)
    (log/warn "install-url-sync! got both :prefix and :url-codec. :url-codec wins; :prefix" (pr-str prefix) "is ignored."))
  (let [statechart-id                           (or (session-statechart-id app)
                                                  (throw (ex-info "install-url-sync! called but no routing session found. Call start! first." {})))
        url-codec                               (or url-codec
                                                  (if prefix (ruct/transit-base64-codec prefix) (ruct/transit-base64-codec)))
        provider                                (or provider
                                                  #?(:cljs (bh/browser-url-history)
                                                     :clj  (throw (ex-info "install-url-sync! requires a :provider on CLJ (e.g. simulated-url-history)" {}))))
        chart                                   (scf/lookup-statechart app statechart-id)
        _                                       (validate-route-configuration chart checks)
        elements-by-id                          (::sc/elements-by-id chart)
        ;; The URL url-sync last wrote (or accepted), kept in the app's url-sync runtime state so that
        ;; `replace-route-params!` can update it too.
        _                                       (set-prev-url! app nil)
        nav-state                               (atom nil)
        restoring?                              (atom false)
        ;; True briefly after a browser-initiated nav is accepted. While true,
        ;; any programmatic URL change (e.g. from child chart initialization)
        ;; uses replaceState instead of pushState so that forward history is
        ;; not destroyed.
        settling?                               (atom false)
        debounce-timer #?(:cljs (atom nil) :clj nil)
        safety-timer #?(:cljs (atom nil) :clj   nil)

        ;; Outstanding browser-initiated navigations counter. Increments on each
        ;; popstate that passes the debounce filter, decrements on each root save
        ;; while nav-state is set. Only resolves acceptance/denial when counter
        ;; reaches zero (i.e., the last root save for the last popstate).
        outstanding-navs                        (atom 0)

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

        do-popstate                             (fn [popped-index]
                                                  (cond
                                                    ;; Not synced until the deferred restore
                                                    (restore-deferred? app) nil
                                                    @restoring?
                                                    (reset! restoring? false) ;; consume restoration popstate silently
                                                    :else
                                                    (do
                                                      (reset! settling? false)
                                                      (swap! outstanding-navs inc)
                                                      (let [pre-nav-url   (prev-url app)
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
            ;; A deferred restore ends at the save of the session that took its route event (the routing session, or
            ;; a module's that the event was forwarded to), so saves before it (e.g. of the step that called
            ;; `route-sync-from-url!`) write no URL, and this one writes the restored route's on the bookmarked entry.
            (when (restore-processed? app state-map saving-sid)
              (swap-url-sync! app update :restore-deferred dissoc session-id))
            (when (and configuration (not (restore-deferred? app)))
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
                    old-url     (prev-url app)
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
                          (set-prev-url! app browser-url)
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
                          (set-prev-url! app pre-nav-url)
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
                    (set-prev-url! app new-url)
                    (reset! settled-index (ruh/current-index provider)))

                                                          ;; Branch 4: Programmatic navigation (no nav-state, URL changed)
                  (and new-url (not= new-url old-url))
                  (if (and @settling? (not root-save?))
                                                            ;; Post-acceptance settle: child chart init changed the deep
                                                            ;; URL. Replace (not push) to preserve forward history.
                    (do
                      (log/debug "URL sync: settling (replace)" new-url)
                      (ruh/-replace-url! provider new-url)
                      (set-prev-url! app new-url))
                                                            ;; Normal programmatic nav: push a new history entry
                    (do
                      (reset! settling? false)
                      (log/debug "URL sync: pushing" new-url)
                      (ruh/-push-url! provider new-url)
                      (set-prev-url! app new-url)
                      (reset! settled-index (ruh/current-index provider)))))))))]

    ;; Register the on-save handler, provider, and codec in the app's runtime atom
    (swap-url-sync! app update :handlers assoc session-id on-save-handler)
    (swap-url-sync! app update :providers assoc session-id provider)
    (when url-codec
      (swap-url-sync! app update :codecs assoc session-id url-codec))

    ;; Listen for browser back/forward via provider
    (ruh/set-popstate-listener! provider popstate-fn)

    ;; Initial URL restoration: check if the current URL maps to a route
    (if defer-restore?
      (swap-url-sync! app assoc-in [:restore-deferred session-id] true)
      (do
        (swap-url-sync! app update :restore-deferred dissoc session-id)
        (resolve-route-and-navigate! app elements-by-id provider url-codec)))

    ;; Return cleanup function
    (fn url-sync-cleanup! []
      (swap-url-sync! app update :handlers dissoc session-id)
      (swap-url-sync! app update :providers dissoc session-id)
      (swap-url-sync! app update :codecs dissoc session-id)
      (swap-url-sync! app update :prev-urls dissoc session-id)
      (swap-url-sync! app update :restore-deferred dissoc session-id)
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
   Returns the routed target keyword, or nil when no provider/session/URL target is available.

   This is also the deferred initial restore (`install-url-sync!`'s `:defer-restore?`): it ends the deferral at the
   save of the session that takes its route event (the routing session's first save after it dequeued the event,
   whichever transition took it, if any; a module's when the routing session forwarded the event to it; the routing
   session's again when that module can't save it), so URL sync writes URLs again from then on. Saves before that
   (e.g. of the step that calls it, in any session) write no URL, so the restore stays on the bookmarked entry. When
   the URL matches no route, the deferral ends at once."
  [app-ish]
  (when-let [app (rc/any->app app-ish)]
    (when-let [provider (url-sync-provider app)]
      (let [statechart-id (session-statechart-id app)
            chart         (some->> statechart-id (scf/lookup-statechart app))
            codec         (or (get-in (url-sync-state app) [:codecs session-id])
                            (ruct/transit-base64-codec))
            routed        (when chart
                            (if (restore-deferred? app)
                              (let [token (new-uuid)]
                                (swap-url-sync! app assoc-in [:restore-deferred session-id] {:token token})
                                (resolve-route-and-navigate! app (::sc/elements-by-id chart) provider codec
                                  {::restore-token token}))
                              (resolve-route-and-navigate! app (::sc/elements-by-id chart) provider codec)))]
        (when-not routed
          (swap-url-sync! app update :restore-deferred dissoc session-id))
        routed))))

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
