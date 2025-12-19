(ns com.fulcrologic.statecharts.integration.fulcro.dynamic-routing
  "Drop-in replacement for com.fulcrologic.fulcro.routing.dynamic-routing that uses
   statecharts instead of nested UI State Machines.

   USAGE:
   Simply change your require from:
     [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
   to:
     [com.fulcrologic.statecharts.integration.fulcro.dynamic-routing :as dr]

   Then call (dr/start-routing! app) after mounting your app.

   The same defrouter macro, route-segment declarations, will-enter hooks, etc.
   all work the same way. The difference is under the hood: instead of multiple
   nested UISMs, a single statechart manages all routing. This enables:

   - Auth/error events from any route can transition the entire app
   - Coordinated entry/exit across the route tree
   - Better debugging via statechart visualization

   NOTE: add-route-target! is NOT supported since statecharts have static structure.
   If you need dynamic routes, you'll need to pre-declare them in :router-targets."
  #?(:cljs (:require-macros [com.fulcrologic.statecharts.integration.fulcro.dynamic-routing]))
  (:require
   #?@(:cljs [[goog.object :as gobj]])
   [clojure.spec.alpha :as s]
   [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
   [com.fulcrologic.fulcro.algorithms.indexing :as indexing]
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp]
   [com.fulcrologic.fulcro.mutations :refer [defmutation]]
   [com.fulcrologic.fulcro.raw.components :as rc]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :refer [statechart]]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements :as ele :refer [state transition script script-fn on-entry on-exit parallel]]
   [com.fulcrologic.statecharts.environment :as env]
   [com.fulcrologic.statecharts.integration.fulcro :as scf]
   [com.fulcrologic.statecharts.integration.fulcro.ui-routes :as uir]
   [com.fulcrologic.statecharts.protocols :as sp]
   [edn-query-language.core :as eql]
   [taoensso.encore :as enc]
   [taoensso.timbre :as log]))

;; =============================================================================
;; Session ID - single statechart for all routing
;; =============================================================================

(def session-id ::routing-session)
(def chart-id ::routing-chart)

;; =============================================================================
;; Re-export route-segment accessors (unchanged from original)
;; =============================================================================

(defn route-segment
  "Returns a vector that describes the sub-path that a given route target represents."
  [class]
  (rc/component-options class :route-segment))

(defn route-target?
  "Returns true if the component has a :route-segment"
  [component]
  (boolean (rc/component-options component :route-segment)))

(defn router?
  "Returns true if the component is a router (has :router-targets)"
  [component]
  (boolean (rc/component-options component :router-targets)))

(defn get-targets
  "Returns the set of router target classes for a router."
  [router]
  (set (rc/component-options router :router-targets)))

;; =============================================================================
;; Route result constructors (API compatible with original)
;; =============================================================================

(defn route-immediate
  "Used as a return value from `will-enter`. Routes immediately."
  [ident]
  (with-meta ident {:immediate true}))

(defn route-deferred
  "Used as a return value from `will-enter`. Routes after completion-fn signals ready."
  [ident completion-fn]
  (with-meta ident {:immediate false
                    :fn        completion-fn}))

(defn immediate? [ident]
  (some-> ident meta :immediate))

;; =============================================================================
;; Lifecycle hook accessors (unchanged from original)
;; =============================================================================

(defn get-will-enter [class]
  (if-let [will-enter (rc/component-options class :will-enter)]
    will-enter
    (let [ident (rc/get-ident class {})]
      (when-not ident
        (log/error "Component must have an ident for routing:" (rc/component-name class)))
      (fn [_ _] (route-immediate ident)))))

(defn will-enter [class app params]
  (when-let [will-enter (get-will-enter class)]
    (will-enter app params)))

(defn get-will-leave [this]
  (or (rc/component-options this :will-leave) (constantly true)))

(defn will-leave [c props]
  (when-let [f (get-will-leave c)]
    (f c props)))

(defn get-allow-route-change? [this]
  (or (rc/component-options this :allow-route-change?)
      (constantly true)))

(defn allow-route-change? [c]
  (try
    (when-let [f (get-allow-route-change? c)]
      (f c))
    (catch #?(:clj Exception :cljs :default) e
      (log/error "Cannot evaluate route change:" (ex-message e))
      true)))

(defn get-route-cancelled [class]
  (rc/component-options class :route-cancelled))

(defn route-cancelled [class route-params]
  (when-let [f (get-route-cancelled class)]
    (f route-params)))

;; =============================================================================
;; target-ready - bridges to statechart event
;; =============================================================================

(defmutation target-ready
  "Mutation: Indicate that a target is ready. Sends event to routing statechart."
  [{:keys [target]}]
  (action [{:keys [app]}]
          (log/debug "target-ready called for" target)
          (scf/send! app session-id :event/target-ready {:target target}))
  (refresh [_] [::current-route]))

(defn target-ready!
  "Indicate a target is ready. Safe to use from within mutations."
  [component-or-app target]
  (rc/transact! component-or-app [(target-ready {:target target})]))

;; =============================================================================
;; Chart Generation
;; =============================================================================

(defn- find-nested-routers
  "Find routers in a component's query."
  [component state-map]
  (when-let [query (rc/get-query component state-map)]
    (let [ast (eql/query->ast query)]
      (->> (:children ast)
           (keep (fn [{:keys [dispatch-key component]}]
                   (when (and component (router? component))
                     {:join-key dispatch-key
                      :router-class component})))))))

(defn- compute-full-path [parent-path segment]
  (into (vec parent-path)
        (mapv #(if (keyword? %) % (str %)) segment)))

(defn- extract-path-params [segment]
  (set (filter keyword? segment)))

(defn- route-to-event-name
  "Generate the event name for routing to a target."
  [target-key]
  (let [ns-part (namespace target-key)
        nm-part (name target-key)]
    (keyword (str "route-to." ns-part) nm-part)))

(declare generate-router-state)

(defn- update-current-route!
  "Update the parent router's :ui/current-route to point to the target.
   This is our own implementation that works with our chart structure.

   Also updates the router's query via set-query! so that db->tree will
   properly denormalize the new target's data.

   Since finding mounted router instances at runtime is complex, we take a simpler approach:
   We traverse all router idents in the state-map and update the one containing this target.

   `route-status` should be :routed for immediate routes or :pending for deferred routes."
  [{:fulcro/keys [app]} target-class target-ident route-status]
  (let [state-atom (::app/state-atom app)
        state-map @state-atom
        target-key (rc/class->registry-key target-class)
        ;; Find all router idents in the state-map (they use ::id as ident type)
        router-entries (get state-map ::id)
        ;; Find which router's targets contain our target-class by comparing registry keys
        router-info (some (fn [[k v]]
                            (let [Router (rc/registry-key->class k)
                                  target-keys (when Router
                                                (set (map rc/class->registry-key (get-targets Router))))]
                              (when (and Router (contains? target-keys target-key))
                                {:router-key k :router-class Router})))
                          router-entries)
        {:keys [router-key router-class]} router-info]
    (log/debug "update-current-route!" {:target-key target-key
                                        :target-ident target-ident
                                        :router-entries (keys router-entries)
                                        :router-key router-key
                                        :route-status route-status})
    (when router-key
      ;; Update the ident pointer and the router state (for current-state in defrouter)
      (swap! state-atom (fn [sm]
                          (-> sm
                              (assoc-in [::id router-key :ui/current-route] target-ident)
                              ;; Set the current-route-target state so routers can show loading UI
                              (assoc-in [::current-route-target router-key] route-status))))
      ;; Update the router's query to use the target's query
      (let [new-query [::id
                       [::current-route-target router-key]
                       {:ui/current-route (rc/get-query target-class @state-atom)}]]
        (rc/set-query! app router-class {:query new-query})))))

(defn- generate-target-state
  "Generate a state for a route target."
  [target-class parent-path state-map]
  (let [target-key (rc/class->registry-key target-class)
        segment (route-segment target-class)
        full-path (compute-full-path parent-path segment)
        path-params (extract-path-params segment)
        nested-routers (find-nested-routers target-class state-map)
        has-nested? (seq nested-routers)

        ;; Entry logic
        entry-logic
        (on-entry {}
                  (script
                   {:expr
                    (fn [{:fulcro/keys [app] :as env} data _ event-data]
                      (let [params (dissoc event-data ::force? ::external?)
                            will-enter-fn (get-will-enter target-class)
                            result (when will-enter-fn (will-enter-fn app params))
                            {:keys [immediate fn]} (when result (meta result))
                            ident (or result (rc/get-ident target-class {}))]
                        (cond
                   ;; Immediate
                          (or (nil? result) immediate)
                          (do
                            ;; Initialize the route target's state in the Fulcro database
                            (uir/initialize-route! env (assoc data ::uir/target target-key))
                            ;; Update router state - find all routers and update the one containing this target
                            (update-current-route! env target-class ident :routed)
                            [(ops/assign [:route/idents target-key] ident)
                             (ops/assign [:route/status target-key] :ready)
                             (ops/assign [:route/actual-params target-key] params)])

                   ;; Deferred - invoke thunk
                          fn
                          (do
                            (uir/initialize-route! env (assoc data ::uir/target target-key))
                            ;; For deferred routes, set state to :pending so router can show loading UI
                            ;; Don't set :ui/current-route yet - wait for target-ready!
                            (update-current-route! env target-class ident :pending)
                            (fn) ; invoke the loading thunk
                            [(ops/assign [:route/idents target-key] ident)
                             (ops/assign [:route/status target-key] :pending)
                             (ops/assign [:route/actual-params target-key] params)]))))})

          ;; Reindex after route established
                  (script {:expr (fn [{:fulcro/keys [app]} & _]
                                   (rc/transact! app [(indexing/reindex)])
                                   nil)}))

        ;; Exit logic - call will-leave and route-cancelled
        ;; Note: Skip will-leave/route-cancelled if this is a "self-transition" (routing to same page)
        ;; SCXML treats these as exit+re-enter, but Fulcro routing semantics don't call will-leave for self-routing
        exit-logic
        (on-exit {}
                 (script {:expr (fn [{:fulcro/keys [app]} data & _]
                                  (let [instances (comp/class->all app target-class)
                                        status (get-in data [:route/status target-key])
                                        actual-params (get-in data [:route/actual-params target-key])
                                        ;; Check if this is a self-transition (routing to same target)
                                        event-name (get-in data [:_event :name])
                                        self-event-name (route-to-event-name target-key)
                                        is-self-transition? (= event-name self-event-name)]
                                    ;; Only call will-leave/route-cancelled if actually leaving (not self-routing)
                                    (when-not is-self-transition?
                                      ;; Call will-leave
                                      (if (seq instances)
                                        ;; CLJS path: call on mounted instances
                                        (doseq [inst instances]
                                          (will-leave inst (rc/props inst)))
                                        ;; CLJ path: call directly on class with props from state
                                        (when-let [will-leave-fn (rc/component-options target-class :will-leave)]
                                          (let [state-map (app/current-state app)
                                                ident (get-in data [:route/idents target-key])
                                                props (when ident (get-in state-map ident))]
                                            (will-leave-fn nil props))))
                                      ;; Call route-cancelled if this was a pending deferred route
                                      (when (= :pending status)
                                        (route-cancelled target-class actual-params))))
                                  [(ops/delete [:route/idents target-key])
                                   (ops/delete [:route/status target-key])
                                   (ops/delete [:route/actual-params target-key])])}))

        ;; Deferred ready transition - NOW update the router's current route
        deferred-ready
        (transition
         {:event :event/target-ready
          :cond (fn [_ data _ _]
                  (let [event-target (-> data :_event :data :target)
                        our-ident (get-in data [:route/idents target-key])]
                    (and (= :pending (get-in data [:route/status target-key]))
                         (= event-target our-ident))))}
         (script {:expr (fn [env data _ _]
                          (let [ident (get-in data [:route/idents target-key])]
                            ;; Now that target is ready, update the router's current route to :routed
                            (update-current-route! env target-class ident :routed)
                            [(ops/assign [:route/status target-key] :ready)]))}))

        ;; Nested router states
        nested-states (mapv #(generate-router-state (:router-class %) full-path state-map)
                            nested-routers)]

    (if has-nested?
      (apply state {:id target-key
                    :route/target target-key
                    :route/path full-path
                    :route/params path-params}
             entry-logic
             exit-logic
             deferred-ready
             nested-states)
      (state {:id target-key
              :route/target target-key
              :route/path full-path
              :route/params path-params}
             entry-logic
             exit-logic
             deferred-ready))))

(defn- generate-router-state
  "Generate a compound state for a router."
  [router-class parent-path state-map]
  (let [router-key (rc/class->registry-key router-class)
        router-id (keyword "router" (name router-key))
        ;; Use the ordered vector from component options, not the set from get-targets
        targets (vec (rc/component-options router-class :router-targets))
        target-keys (mapv rc/class->registry-key targets)

        ;; Target states (no direct transitions here - they're handled at :region/routes level
        ;; so the busy-check guard can intercept them)
        target-states (mapv #(generate-target-state % parent-path state-map) targets)]

    (apply state {:id router-id
                  :initial (first target-keys)}
           target-states)))

(defn- collect-all-targets
  "Recursively collect all route targets from a component tree."
  [component state-map]
  (let [results (atom [])]
    (letfn [(walk [c path]
              (when-let [routers (find-nested-routers c state-map)]
                (doseq [{:keys [router-class]} routers]
                  (doseq [target (get-targets router-class)]
                    (let [segment (route-segment target)
                          full-path (compute-full-path path segment)]
                      (swap! results conj {:class target
                                           :key (rc/class->registry-key target)
                                           :path full-path
                                           :params (extract-path-params segment)})
                      (walk target full-path))))))]
      (walk component [])
      @results)))

(defn generate-routing-chart
  "Generate a complete routing statechart from the Root component."
  ([Root] (generate-routing-chart Root {}))
  ([Root state-map]
   (let [routers (find-nested-routers Root state-map)
         all-targets (collect-all-targets Root state-map)

         ;; Global transitions for direct routing to any target
         global-direct-transitions
         (mapv (fn [{:keys [key]}]
                 (transition {:event (route-to-event-name key)
                              :target key}))
               all-targets)

         ;; Router states
         router-states (mapv #(generate-router-state (:router-class %) [] state-map) routers)

         ;; Busy check - looks at all active targets' allow-route-change?
         ;; Condition functions receive 4 args: env, data, context, event-data
         busy-check
         (fn [{:fulcro/keys [app] :as env} {:keys [_event] :as data} _ event-data]
           ;; Check for ::force? in both _event and event-data for robustness
           (if (or (-> _event :data ::force?)
                   (::force? event-data))
             false
             (let [cfg (scf/current-configuration app session-id)
                   state-map (app/current-state app)
                   ;; Get elements-by-id from the registered chart, not from env
                   chart (scf/lookup-statechart app chart-id)
                   elements-by-id (::sc/elements-by-id chart)]
               (some (fn [state-id]
                       (let [elem (get elements-by-id state-id)
                             target-key (:route/target elem)]
                         (when target-key
                           (let [Target (rc/registry-key->class target-key)
                                 instances (comp/class->all app Target)]
                             (if (seq instances)
                               ;; CLJS path: check mounted instances
                               (some #(false? (allow-route-change? %)) instances)
                               ;; CLJ path: check class option directly with props from state
                               (when-let [allow-fn (rc/component-options Target :allow-route-change?)]
                                 (let [allow-result (allow-fn nil)]
                                   (false? allow-result))))))))
                     cfg))))

         ;; Handle denied routes
         ;; Script expressions receive 4 args: env, data, context, event-data
         record-denied
         (script-fn [_ {:keys [_event]} _ _]
                    [(ops/assign ::denied-route-event _event)])

         clear-denied
         (script-fn [_ _ _ _]
                    [(ops/assign ::denied-route-event nil)])

         retry-denied
         (fn [env {::keys [denied-route-event]} & _]
           (when denied-route-event
             ;; Use env/raise to place event on internal queue for immediate processing
             ;; This processes in the same macrostep, unlike scf/send! which is async
             ;; Must include :type to satisfy ::sc/event spec with Guardrails
             (env/raise env {:name (:name denied-route-event)
                             :data (merge (:data denied-route-event) {::force? true})
                             :type :internal}))
           nil)]

     (statechart {:id chart-id}
                 (ele/data-model {:expr {:route/idents {}
                                         :route/status {}
                                         ::denied-route-event nil}})

                 (state {:id :state/routing-root}

         ;; Parallel: routing + route-denied dialog
                        (parallel {:id :state/routing-parallel}

           ;; Main routing region
                                  (apply state {:id :region/routes}
                  ;; Global transitions - can route to any target from anywhere
                  ;; But check busy first
                                         (transition {:event :route-to.*
                                                      :cond busy-check}
                                                     record-denied
                                                     (ele/raise {:event :event.routing/show-denied}))

                  ;; Normal routing
                                         (concat global-direct-transitions router-states))

           ;; Route denied dialog region
                                  (state {:id :region/denied-dialog}
                                         (state {:id :denied/closed}
                                                (on-entry {} clear-denied)
                                                (transition {:event :event.routing/show-denied
                                                             :target :denied/open}))
                                         (state {:id :denied/open}
                                                (transition {:event :event.routing/close-denied
                                                             :target :denied/closed})
                                                (transition {:event :event.routing/force-route
                                                             :target :denied/closed}
                                                            (script {:expr retry-denied}))))))))))

;; =============================================================================
;; Path Matching for URL -> Route
;; =============================================================================

(defn- path-matches?
  "Check if a URL path matches a route pattern."
  [pattern path]
  (and (= (count pattern) (count path))
       (every? (fn [[p v]]
                 (or (keyword? p) (= p v)))
               (map vector pattern path))))

(defn- extract-params
  "Extract parameters from path based on pattern."
  [pattern path]
  (reduce (fn [params [p v]]
            (if (keyword? p)
              (assoc params p v)
              params))
          {}
          (map vector pattern path)))

(defn resolve-target
  "Given a URL path, find the matching target class."
  [app path]
  (let [state-map (app/current-state app)
        Root (app/root-class app)
        all-targets (collect-all-targets Root state-map)
        ;; Sort by path length descending for longest match
        sorted (sort-by #(- (count (:path %))) all-targets)]
    (some (fn [{:keys [class path params] :as target}]
            (when (path-matches? path (vec (take (count path) (map str path))))
              target))
          sorted)))

;; =============================================================================
;; Public Routing API (compatible with original)
;; =============================================================================

(defn current-route
  "Returns the current active route as a vector of path segments with actual param values substituted.
   Only returns routes that are :ready (not :pending deferred routes)."
  ([app-or-comp]
   (current-route app-or-comp (app/root-class (rc/any->app app-or-comp))))
  ([app-or-comp relative-class]
   (let [app (rc/any->app app-or-comp)
         cfg (scf/current-configuration app session-id)
         state-map (app/current-state app)]
     ;; Find the leaf route state and return its path with substituted params
     ;; Only include routes that are :ready (not :pending deferred routes)
     (when-let [chart (scf/lookup-statechart app chart-id)]
       (let [{::sc/keys [elements-by-id]} chart
             leaf-routes (filter (fn [state-id]
                                   (let [elem (get elements-by-id state-id)
                                         target-key (:route/target elem)
                                         status (when target-key
                                                  (get-in state-map (scf/local-data-path session-id :route/status target-key)))]
                                     (and target-key
                                          (:route/path elem)
                                          ;; Only include ready routes, not pending
                                          (= status :ready))))
                                 cfg)]
         (when-let [leaf (first leaf-routes)]
           (let [path-pattern (get-in elements-by-id [leaf :route/path])
                 target-key (get-in elements-by-id [leaf :route/target])
                 ;; Get actual params from the statechart's local data in Fulcro state
                 actual-params (get-in state-map (scf/local-data-path session-id :route/actual-params target-key))]
             ;; Substitute keyword placeholders with actual values
             (mapv (fn [segment]
                     (if (keyword? segment)
                       (get actual-params segment segment)
                       segment))
                   path-pattern))))))))

(defn route-to!
  "Route to a specific target class with optional parameters."
  ([app-ish Target] (route-to! app-ish Target {}))
  ([app-ish Target params]
   (let [target-key (rc/class->registry-key Target)
         event-name (route-to-event-name target-key)]
     (scf/send! app-ish session-id event-name params))))

(defn change-route!
  "Change the route using a path vector."
  ([app-or-comp new-route] (change-route! app-or-comp new-route {}))
  ([app-or-comp new-route params]
   (let [app (rc/any->app app-or-comp)
         state-map (app/current-state app)
         Root (app/root-class app)
         all-targets (collect-all-targets Root state-map)
         ;; Find matching target
         match (some (fn [{:keys [class path] :as t}]
                       (when (path-matches? path new-route)
                         t))
                     (sort-by #(- (count (:path %))) all-targets))]
     (if match
       (let [extracted-params (extract-params (:path match) new-route)
             all-params (merge extracted-params params)]
         (route-to! app (:class match) all-params))
       (do
         (log/error "No route matches path:" new-route)
         :invalid)))))

(def change-route change-route!)

(defn change-route-relative!
  "Change route relative to a component."
  ([app-or-comp relative-class new-route] (change-route-relative! app-or-comp relative-class new-route {}))
  ([app-or-comp relative-class new-route params]
   ;; For now, just delegate to change-route!
   ;; A full implementation would handle :.. navigation
   (change-route! app-or-comp new-route params)))

(def change-route-relative change-route-relative!)

(defn can-change-route?
  "Returns true if route change is allowed (no busy targets).
   Checks all active route targets' :allow-route-change? functions."
  ([app-or-comp] (can-change-route? app-or-comp (app/root-class (rc/any->app app-or-comp))))
  ([app-or-comp relative-class]
   (let [app (rc/any->app app-or-comp)
         cfg (scf/current-configuration app session-id)
         state-map (app/current-state app)]
     (when-let [chart (scf/lookup-statechart app chart-id)]
       (let [{::sc/keys [elements-by-id]} chart]
         ;; Check all active route targets - return true only if ALL allow change
         (not (some (fn [state-id]
                      (when-let [target-key (get-in elements-by-id [state-id :route/target])]
                        (let [Target (rc/registry-key->class target-key)
                              instances (comp/class->all app Target)]
                          (if (seq instances)
                            ;; CLJS path: check mounted instances
                            (some #(false? (allow-route-change? %)) instances)
                            ;; CLJ path: check class option directly
                            (when-let [allow-fn (rc/component-options Target :allow-route-change?)]
                              (false? (allow-fn nil)))))))
                    cfg)))))))

(defn force-route!
  "Force the most recently denied route to proceed."
  [app-ish]
  (scf/send! app-ish session-id :event.routing/force-route {}))

(defn abandon-route!
  "Abandon the attempt to change route."
  [app-ish]
  (scf/send! app-ish session-id :event.routing/close-denied {}))

(defn path-to
  "Generate a path vector for routing to the given targets with params."
  [& targets-and-params]
  ;; Reuse original implementation logic
  (let [segments (seq (partition-by #(or (rc/component? %)
                                         (rc/component-class? %))
                                    targets-and-params))]
    (if (and (= 2 (count segments)) (map? (first (second segments))))
      (let [path (mapcat #(rc/component-options % :route-segment) (first segments))
            params (first (second segments))]
        (mapv (fn [i] (get params i i)) path))
      (reduce
       (fn [path [classes params]]
         (-> path
             (into (mapcat #(rc/component-options % :route-segment) (butlast classes)))
             (into (let [last-class (last classes)
                         segment (rc/component-options last-class :route-segment)
                         nargs (count params)
                         static (- (count segment) nargs)]
                     (concat (take static segment) params)))))
       []
       (partition-all 2 segments)))))

;; =============================================================================
;; Initialization
;; =============================================================================

(defn start-routing!
  "Initialize statechart-based routing. Call after mounting the app.

   Usage:
   (app/mount! app Root \"app\")
   (dr/start-routing! app)"
  ([app] (start-routing! app {}))
  ([app {:keys [default-route] :as options}]
   ;; Install statecharts infrastructure if not already installed (check app state, not global atom)
   (when-not (scf/statechart-env app)
     (scf/install-fulcro-statecharts! app))

   (let [Root (app/root-class app)
         state-map (app/current-state app)
         chart (generate-routing-chart Root state-map)
         ;; Check if routing session is already running
         already-running? (seq (scf/current-configuration app session-id))]
     (scf/register-statechart! app chart-id chart)
     (when-not already-running?
       (scf/start! app {:machine chart-id
                        :session-id session-id
                        :data {}})
       (log/info "Statechart routing started")))))

(defn refresh-routing!
  "Regenerate and update the routing chart. Call on hot reload if routes change."
  [app]
  (let [Root (app/root-class app)
        state-map (app/current-state app)
        chart (generate-routing-chart Root state-map)]
    (scf/register-statechart! app chart-id chart)
    (log/info "Routing chart refreshed")))

;; =============================================================================
;; Simple ui-current-subroute for CLJ compatibility
;; =============================================================================

(defn- extract-query-key
  "Extract the property key from an EQL query element."
  [query-elem]
  (cond
    (keyword? query-elem) query-elem
    (map? query-elem) (ffirst query-elem)  ; join like {:foo Query}
    (vector? query-elem) (first query-elem) ; ident-join like [:foo id]
    :else nil))

(defn ui-current-subroute
  "Render the current subroute. Unlike uir/ui-current-subroute, this version
   works in CLJ test context by determining the target class from the ident
   in the current-route props rather than querying the component's AST.

   parent-component-instance is the router component (this).
   factory-fn is the function wrapper that generates a proper element (e.g. comp/factory)."
  [parent-component-instance factory-fn]
  (let [props (rc/props parent-component-instance)
        {:ui/keys [current-route]} props
        ;; Get router-targets from the router component
        targets (rc/component-options parent-component-instance :router-targets)]
    (when current-route
      ;; current-route could be:
      ;; 1. A denormalized map with props (when db->tree worked)
      ;; 2. An ident vector (when denormalization failed or returned the ident)
      (if (map? current-route)
        ;; Try to match by overlapping keys
        (if-let [Target (some (fn [t]
                                (let [target-query (rc/get-query t)
                                      target-props-keys (set (keep extract-query-key target-query))
                                      current-keys (set (keys current-route))]
                                  (when (seq (clojure.set/intersection target-props-keys current-keys))
                                    t)))
                              targets)]
          (let [render-child (factory-fn Target)]
            (render-child current-route))
          ;; Fallback: use first target if no match found
          (when-let [First (first targets)]
            (let [render-child (factory-fn First)]
              (render-child current-route))))
        ;; current-route is an ident - find matching target by ident pattern and denormalize
        (when (vector? current-route)
          (let [ident-key (first current-route)
                Target (some (fn [t]
                               (let [t-ident (rc/get-ident t {})]
                                 (when (and (vector? t-ident) (= ident-key (first t-ident)))
                                   t)))
                             targets)]
            (when Target
              (let [render-child (factory-fn Target)
                    the-app (rc/any->app parent-component-instance)
                    state-map (when the-app (app/current-state the-app))
                    target-query (rc/get-query Target state-map)
                    entity-data (when state-map (get-in state-map current-route))
                    target-props (cond
                                   ;; Entity exists in state - denormalize it
                                   (and entity-data target-query)
                                   (fdn/db->tree target-query entity-data state-map)
                                   ;; No entity - use initial-state if available
                                   :else
                                   (rc/get-initial-state Target {}))]
                (render-child (or target-props {}))))))))))

;; =============================================================================
;; defrouter macro - generates same structure as original
;; =============================================================================

#?(:clj
   (defmacro defrouter
     "Define a router component. API-compatible with fulcro's defrouter.

      The router-targets option specifies the valid route targets.
      The optional body renders during :pending/:failed states."
     [router-sym arglist options & body]
     (let [{:keys [router-targets always-render-body?]} options
           router-ns (str (ns-name *ns*))
           id (keyword router-ns (name router-sym))
           ;; Static query - starts with first target, updated at runtime via set-query!
           query `[::id
                   [::current-route-target ~id]
                   {:ui/current-route (comp/get-query ~(first router-targets))}]
           ;; Use apply list to create proper forms like the original defrouter does
           ident-method (apply list `(fn [] [::id ~id]))
           initial-state-lambda (apply list `(fn [~'params] {::id ~id
                                                             :ui/current-route (comp/get-initial-state ~(first router-targets) {})}))]
       `(comp/defsc ~router-sym [~'this ~'props]
          {:query         ~query
           :ident         ~ident-method
           :initial-state ~initial-state-lambda
           :router-targets ~(vec router-targets)}
          ;; current-state will be nil before routing starts, treat as :initial
          (let [~'current-state (or (get ~'props ::current-route-target) :initial)
                ~'route-props (get ~'props :ui/current-route)]
            ~(if (seq body)
               `(do ~@body)
               `(when ~'route-props
                  (ui-current-subroute ~'this comp/factory))))))))

;; For runtime router creation
(defn dynamic-router
  "Create a router component at runtime."
  [registry-key router-targets options]
  ;; Delegate to comp/sc with router-targets option
  (comp/sc registry-key
           (merge options
                  {:router-targets router-targets
                   :query (fn [_]
                            [::id
                             {:ui/current-route (rc/get-query (first router-targets))}])
                   :ident (fn [_] [::id registry-key])
                   :initial-state (fn [_]
                                    {::id registry-key
                                     :ui/current-route (rc/get-initial-state (first router-targets) {})})})
           (fn [this props]
             (ui-current-subroute this comp/factory))))

;; =============================================================================
;; Re-exports for API compatibility
;; =============================================================================

(def current-route-class uir/ui-current-subroute) ; Not exact but similar purpose

;; Marker for router states
(def ^:const routed :routed)
(def ^:const pending :pending)
(def ^:const failed :failed)
(def ^:const initial :initial)
