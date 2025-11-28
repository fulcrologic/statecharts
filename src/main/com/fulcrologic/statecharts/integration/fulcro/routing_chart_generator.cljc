(ns com.fulcrologic.statecharts.integration.fulcro.routing-chart-generator
  "EXPERIMENTAL: Generate a statechart from existing defrouter/route-segment declarations.

   The idea: Walk the component tree starting from Root, find all routers and their targets,
   and emit an equivalent statechart structure. This allows gradual migration from dynamic
   routing to statecharts while preserving existing :will-enter hooks and component structure.

   Key benefits over nested UISMs:
   - Single statechart means auth failures/errors can transition to ANY state
   - Entry/exit logic for the entire route tree is coordinated
   - Parallel states can model things like 'sidebar + main content' routing
   - History states work across the whole tree"
  (:require
   [com.fulcrologic.fulcro.raw.components :as rc]
   [com.fulcrologic.fulcro.components :as comp]
   [com.fulcrologic.statecharts.chart :refer [statechart]]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements :as ele :refer [state transition script script-fn on-entry on-exit parallel]]
   [com.fulcrologic.statecharts.integration.fulcro :as scf]
   [com.fulcrologic.statecharts.integration.fulcro.operations :as fop]
   [com.fulcrologic.statecharts.integration.fulcro.ui-routes :as uir]
   [edn-query-language.core :as eql]
   [taoensso.timbre :as log]))

;; =============================================================================
;; Component Tree Walking
;; =============================================================================

(defn router?
  "Returns true if the component is a defrouter"
  [component]
  (boolean (rc/component-options component :router-targets)))

(defn route-target?
  "Returns true if the component has a route-segment"
  [component]
  (boolean (rc/component-options component :route-segment)))

(defn get-router-targets
  "Get the router targets for a router component"
  [router-component]
  (rc/component-options router-component :router-targets))

(defn get-route-segment
  "Get the route segment for a route target"
  [target-component]
  (rc/component-options target-component :route-segment))

(defn get-will-enter
  "Get the will-enter function for a target"
  [target-component]
  (rc/component-options target-component :will-enter))

(defn get-will-leave
  "Get the will-leave function for a target"
  [target-component]
  (rc/component-options target-component :will-leave))

(defn get-allow-route-change?
  "Get the allow-route-change? predicate for a target"
  [target-component]
  (rc/component-options target-component :allow-route-change?))

(defn find-nested-routers
  "Given a component, find any routers in its query (children).
   Returns a seq of {:join-key :router-class} maps."
  [component state-map]
  (when-let [query (rc/get-query component state-map)]
    (let [ast (eql/query->ast query)]
      (->> (:children ast)
           (keep (fn [{:keys [dispatch-key component]}]
                   (when (and component (router? component))
                     {:join-key dispatch-key
                      :router-class component})))))))

(defn build-component-tree
  "Walk the component tree starting from root-class and build a data structure
   representing the routing hierarchy.

   Returns nested maps like:
   {:component Root
    :routers [{:join-key :root/router
               :component MainRouter
               :targets [{:component HomePage
                          :segment ['home']
                          :routers [...]}
                         {:component UserPage
                          :segment ['user' :user-id]
                          :routers [...]}]}]}"
  ([root-class] (build-component-tree root-class {}))
  ([component state-map]
   (let [nested-routers (find-nested-routers component state-map)]
     {:component component
      :registry-key (rc/class->registry-key component)
      :routers (vec
                (for [{:keys [join-key router-class]} nested-routers]
                  {:join-key join-key
                   :component router-class
                   :registry-key (rc/class->registry-key router-class)
                   :targets (vec
                             (for [target (get-router-targets router-class)]
                               (merge
                                {:component target
                                 :registry-key (rc/class->registry-key target)
                                 :segment (get-route-segment target)
                                 :will-enter (get-will-enter target)
                                 :will-leave (get-will-leave target)
                                 :allow-route-change? (get-allow-route-change? target)}
                                (build-component-tree target state-map))))}))})))

;; =============================================================================
;; Path Computation
;; =============================================================================

(defn segment->path-pattern
  "Convert a route segment like ['user' :user-id 'posts'] to a path pattern"
  [segment]
  (mapv (fn [s]
          (if (keyword? s)
            s  ; parameter placeholder
            (str s)))
        segment))

(defn compute-full-path
  "Given a parent path and a segment, compute the full path.
   Parent: ['users']
   Segment: [:user-id 'posts']
   Result: ['users' :user-id 'posts']"
  [parent-path segment]
  (into (vec parent-path) (segment->path-pattern segment)))

(defn extract-path-params
  "Extract parameter keywords from a segment"
  [segment]
  (set (filter keyword? segment)))

;; =============================================================================
;; Will-Enter Adaptation
;; =============================================================================

(defn adapt-will-enter
  "Create statechart entry logic that invokes the component's will-enter hook.

   The will-enter contract returns:
   - (route-immediate ident) -> immediate routing
   - (route-deferred ident thunk) -> async routing

   We translate this to:
   - immediate: initialize state and proceed
   - deferred: initialize, call thunk, wait for target-ready event"
  [target-key will-enter-fn]
  (script
   {:expr
    (fn [{:fulcro/keys [app] :as env} data _ event-data]
      (let [params (dissoc event-data ::uir/force? ::uir/external?)
            result (will-enter-fn app params)
            {:keys [immediate fn]} (when result (meta result))]
        (cond
           ;; No will-enter or nil result: use default initialization
          (nil? result)
          (let [Target (rc/registry-key->class target-key)
                ident (rc/get-ident Target {})]
            [(ops/assign [:route/idents target-key] ident)
             (ops/assign [:route/status target-key] :ready)])

           ;; Immediate: store ident and mark ready
          immediate
          [(ops/assign [:route/idents target-key] result)
           (ops/assign [:route/status target-key] :ready)]

           ;; Deferred: store ident, mark pending, invoke thunk
          fn
          (do
             ;; The thunk will eventually call (dr/target-ready {:target ident})
             ;; which we intercept and convert to an event
            (fn)  ; invoke the loading thunk
            [(ops/assign [:route/idents target-key] result)
             (ops/assign [:route/status target-key] :pending)]))))}))

(defn adapt-allow-route-change
  "Create a condition function from allow-route-change?"
  [target-key allow-fn]
  (fn [{:fulcro/keys [app] :as env} data]
    (if allow-fn
      (let [Target (rc/registry-key->class target-key)
            instances (comp/class->all app Target)]
        ;; Check all mounted instances
        (every? (fn [inst]
                  (allow-fn inst))
                instances))
      true)))

;; =============================================================================
;; State Generation
;; =============================================================================

(declare generate-target-state)

(defn generate-router-state
  "Generate a compound state for a router.

   A router becomes a state with:
   - Child states for each target
   - Transitions for direct routing to each target
   - Initial state pointing to first target (or could be configurable)"
  [{:keys [registry-key targets]} parent-path]
  (let [router-id (keyword "router" (name registry-key))
        target-ids (mapv :registry-key targets)
        ;; Generate transitions for direct routing to each target
        direct-transitions (mapv
                            (fn [{:keys [registry-key segment]}]
                              (let [full-path (compute-full-path parent-path segment)]
                                (transition
                                 {:event (uir/route-to-event-name registry-key)
                                  :target registry-key})))
                            targets)]
    (apply state {:id router-id
                  :initial (first target-ids)}
           (concat
            direct-transitions
            (mapv #(generate-target-state % parent-path) targets)))))

(defn generate-target-state
  "Generate a state for a route target.

   A target becomes:
   - Simple rstate if no nested routers
   - Compound state with nested router states if has children"
  [{:keys [registry-key segment will-enter allow-route-change? routers]} parent-path]
  (let [full-path (compute-full-path parent-path segment)
        path-params (extract-path-params segment)
        has-nested-routers? (seq routers)

        ;; Base state properties
        base-props {:id registry-key
                    :route/target registry-key
                    :route/path full-path
                    :route/params path-params}

        ;; Entry logic that adapts will-enter
        entry-elements [(on-entry {}
                                  (when will-enter
                                    (adapt-will-enter registry-key will-enter))
                                  (script {:expr (fn [env data]
                                                   (uir/initialize-route! env (assoc data ::uir/target registry-key))
                                                   (uir/update-parent-query! env data registry-key))}))]

        ;; Handle pending -> ready transition for deferred routes
        deferred-transition (transition
                             {:event :event/target-ready
                              :cond (fn [_ data]
                                      (and (= :pending (get-in data [:route/status registry-key]))
                                            ;; Check if this target-ready is for us
                                           (= (get-in data [:route/idents registry-key])
                                              (-> data :_event :data :target))))}
                             (script-fn [_ _]
                                        [(ops/assign [:route/status registry-key] :ready)]))

        ;; Nested router states
        nested-states (mapv #(generate-router-state % full-path) routers)]

    (if has-nested-routers?
      ;; Compound state with nested routers
      (apply state base-props
             (concat entry-elements
                     [deferred-transition]
                     nested-states))
      ;; Simple leaf state
      (apply state base-props
             (concat entry-elements
                     [deferred-transition])))))

;; =============================================================================
;; Chart Generation
;; =============================================================================

(defn generate-global-transitions
  "Generate transitions that should be available from any state.
   These handle cross-cutting concerns like auth failures."
  []
  [(transition {:event :auth/failed :target :route/login})
   (transition {:event :error/fatal :target :route/error})])

(defn routing-chart
  "Generate a complete routing statechart from a Root component.

   Options:
   - :global-transitions - Additional transitions available from any routing state
   - :error-target - State to transition to on errors (default :route/error)
   - :auth-target - State to transition to on auth failure (default :route/login)

   The generated chart:
   - Has states for each router and target in the component tree
   - Preserves will-enter hooks as entry logic
   - Supports cascading auth/error handling
   - Maintains URL sync via :route/path declarations"
  ([Root] (routing-chart Root {}))
  ([Root {:keys [global-transitions error-target auth-target state-map]
          :or {error-target :route/error
               auth-target :route/login
               state-map {}}}]
   (let [tree (build-component-tree Root state-map)
         root-registry-key (rc/class->registry-key Root)

         ;; Top-level routers from Root
         router-states (mapv #(generate-router-state % []) (:routers tree))

         ;; Global error/auth transitions
         base-global-transitions [(transition {:event :auth/failed :target auth-target})
                                  (transition {:event :error/fatal :target error-target})
                                  (transition {:event :event/target-ready}
                                    ;; Broadcast to potentially multiple waiting states
                                              (script {:expr (fn [_ _] nil)}))]

         all-global-transitions (concat base-global-transitions global-transitions)]

     (statechart {:id ::routing-chart}
                 (ele/data-model {:expr {:route/idents {}
                                         :route/status {}
                                         :route/params {}}})

       ;; Root routing state encompasses all routing
                 (apply state {:id :state/routing}
                        (concat
                         all-global-transitions
                         router-states))))))

;; =============================================================================
;; Usage Example (in comment)
;; =============================================================================

(comment
  ;; Given this component structure:
  ;;
  ;; Root
  ;;   └── MainRouter
  ;;         ├── HomePage [:route-segment ["home"]]
  ;;         ├── UserPage [:route-segment ["user" :user-id]]
  ;;         │     └── UserRouter
  ;;         │           ├── UserProfile [:route-segment ["profile"]]
  ;;         │           └── UserSettings [:route-segment ["settings"]]
  ;;         └── AdminPage [:route-segment ["admin"]]
  ;;
  ;; (routing-chart Root) generates:
  ;;
  ;; (statechart {}
  ;;   (state {:id :state/routing}
  ;;     (transition {:event :auth/failed :target :route/login})
  ;;
  ;;     (state {:id :router/MainRouter}
  ;;       (transition {:event :route-to.app/HomePage :target :app/HomePage})
  ;;       (transition {:event :route-to.app/UserPage :target :app/UserPage})
  ;;       (transition {:event :route-to.app/AdminPage :target :app/AdminPage})
  ;;
  ;;       (state {:id :app/HomePage
  ;;               :route/path ["home"]})
  ;;
  ;;       (state {:id :app/UserPage
  ;;               :route/path ["user" :user-id]
  ;;               :route/params #{:user-id}}
  ;;         ;; Entry invokes will-enter
  ;;         (on-entry ...)
  ;;
  ;;         ;; Nested router becomes nested state
  ;;         (state {:id :router/UserRouter}
  ;;           (state {:id :app/UserProfile
  ;;                   :route/path ["user" :user-id "profile"]})
  ;;           (state {:id :app/UserSettings
  ;;                   :route/path ["user" :user-id "settings"]})))
  ;;
  ;;       (state {:id :app/AdminPage
  ;;               :route/path ["admin"]}))))

  ;; Cascading auth example:
  ;; If UserProfile's will-enter discovers auth is invalid:
  ;; 1. The will-enter thunk can call: (scf/send! app ::uir/session :auth/failed {})
  ;; 2. This bubbles up to :state/routing which has the :auth/failed transition
  ;; 3. The entire routing subtree is exited properly
  ;; 4. We transition to :route/login
  )

;; =============================================================================
;; Composition Patterns
;; =============================================================================

;; The key question: how do you compose the generated routing chart with
;; other statechart behavior?
;;
;; Several patterns are possible:

;; Pattern 1: Parallel Composition
;; ===============================
;; The routing chart becomes one region of a parallel state, alongside
;; other concerns:
;;
;; (statechart {}
;;   (parallel {:id :app}
;;     (routing-chart Root)           ; Generated routing region
;;     (state {:id :region/session}   ; Session management region
;;       (state {:id :session/anonymous})
;;       (state {:id :session/authenticated}))
;;     (state {:id :region/network}   ; Network status region
;;       (state {:id :network/online})
;;       (state {:id :network/offline}))))
;;
;; Events can cross regions - :auth/failed from routing triggers transition
;; in the session region.

;; Pattern 2: Wrapping
;; ===================
;; Wrap the generated chart in additional states:
;;
;; (statechart {}
;;   (state {:id :app/initializing}
;;     (on-entry {} (script {:expr bootstrap-app!}))
;;     (transition {:event :event/initialized :target :app/running}))
;;   (state {:id :app/running}
;;     (routing-chart Root)))
;;
;; This lets you have app-level states that aren't routes.

;; Pattern 3: Injection Points
;; ===========================
;; Generate the chart with "hooks" where custom behavior can be injected:

(defn routing-chart-with-hooks
  "Generate a routing chart with injection points for custom behavior.

   Options:
   - :on-any-entry - Called when ANY route state is entered
   - :on-any-exit - Called when ANY route state is exited
   - :route-guards - Map of route-key -> predicate for guarding routes
   - :route-entry-hooks - Map of route-key -> additional entry logic
   - :route-exit-hooks - Map of route-key -> additional exit logic
   - :custom-transitions - Additional transitions to add to routing root"
  [Root {:keys [on-any-entry on-any-exit route-guards
                route-entry-hooks route-exit-hooks
                custom-transitions]
         :as options}]
  ;; Implementation would walk the generated chart and inject hooks
  ;; at the appropriate points
  (routing-chart Root options))

;; Pattern 4: Invocation
;; =====================
;; Individual routes can INVOKE their own statecharts, which is already
;; supported via istate in ui-routes.cljc. This is ideal for complex
;; components like forms that have their own behavioral state machine.
;;
;; The generated chart can detect if a target has ro/statechart and
;; automatically generate an istate instead of rstate.

(defn target-has-local-chart?
  "Check if a target component has a co-located statechart"
  [target-class]
  (or (rc/component-options target-class :com.fulcrologic.statecharts.integration.fulcro.ui-routes/statechart)
      (rc/component-options target-class :com.fulcrologic.statecharts.integration.fulcro.ui-routes/statechart-id)))

;; Pattern 5: Manual Override
;; ==========================
;; Extract just the route registry from the component tree, then write
;; your own statechart that references it:

(defn extract-route-registry
  "Walk the component tree and extract just the route information,
   without generating statechart elements. Returns a data structure
   you can use to build your own chart.

   Returns:
   [{:target :app/HomePage
     :path ['home']
     :params #{}
     :will-enter fn
     :parent-router :app/MainRouter
     :nested-in nil}
    {:target :app/UserPage
     :path ['user' :user-id]
     :params #{:user-id}
     :will-enter fn
     :parent-router :app/MainRouter
     :nested-in nil}
    {:target :app/UserSettings
     :path ['user' :user-id 'settings']
     :params #{:user-id}
     :will-enter fn
     :parent-router :app/UserRouter
     :nested-in :app/UserPage}]"
  [Root]
  (let [tree (build-component-tree Root)
        routes (atom [])]
    (letfn [(extract [node parent-router parent-path nested-in]
              (doseq [{:keys [component targets] :as router} (:routers node)]
                (let [router-key (rc/class->registry-key component)]
                  (doseq [{:keys [registry-key segment will-enter routers]} targets]
                    (let [full-path (compute-full-path parent-path segment)]
                      (swap! routes conj
                             {:target registry-key
                              :path full-path
                              :params (extract-path-params segment)
                              :will-enter will-enter
                              :parent-router router-key
                              :nested-in nested-in})
                      ;; Recurse for nested routers
                      (when (seq routers)
                        (doseq [nested-router routers]
                          (extract {:routers [nested-router]}
                                   (:registry-key nested-router)
                                   full-path
                                   registry-key))))))))]
      (extract tree nil [] nil)
      @routes)))

;; Then you can write your own chart:
;;
;; (let [routes (extract-route-registry Root)]
;;   (statechart {}
;;     (state {:id :app}
;;       ;; Your custom structure
;;       (state {:id :loading-session}
;;         (transition {:event :session/ready :target :routing}))
;;       (state {:id :routing}
;;         ;; Use routes data to generate or manually define states
;;         ...))))

;; =============================================================================
;; Integration Points
;; =============================================================================

(defn intercept-target-ready!
  "Install an interceptor that converts target-ready mutations into statechart events.
   Call this during app initialization."
  [app]
  ;; This would need to wrap/replace the target-ready mutation
  ;; to also send a statechart event
  (log/info "Installing target-ready interceptor for statechart routing"))

(defn start-generated-routing!
  "Initialize routing from a generated chart.

   Usage:
   (start-generated-routing! app Root)

   This:
   1. Generates the routing chart from the component tree
   2. Installs the target-ready interceptor
   3. Starts the routing statechart
   4. Optionally sets up history integration"
  ([app Root] (start-generated-routing! app Root {}))
  ([app Root options]
   (let [chart (routing-chart Root options)]
     (intercept-target-ready! app)
     (scf/install-fulcro-statecharts! app)
     (uir/start-routing! app chart))))
