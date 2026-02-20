(ns com.fulcrologic.statecharts.integration.fulcro.routing-test
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [on-entry parallel script state]]
    [com.fulcrologic.statecharts.event-queue.event-processing :refer [process-events]]
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.routing.simulated-history :as rsh]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as sroute]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-history :as ruh]
    [com.fulcrologic.statecharts.integration.fulcro.routing-options :as sfro]
    [com.fulcrologic.statecharts.protocols :as scp]
    [com.fulcrologic.statecharts.registry.local-memory-registry :as reg]
    [com.fulcrologic.statecharts.testing :as testing]
    [fulcro-spec.core :refer [=> assertions behavior component specification]]))

;; ---------------------------------------------------------------------------
;; deep-leaf-routes tests
;; ---------------------------------------------------------------------------

(def deep-leaf-routes @#'sroute/deep-leaf-routes)

;; A root chart with two rstates and one istate
(def root-chart
  (chart/statechart {:initial :route/home}
    (sroute/rstate {:route/target :route/home})
    (sroute/istate {:route/target :route/admin
                    :route/reachable #{:route/admin-users :route/admin-settings}})))

;; A child chart invoked by istate :route/admin
(def child-chart
  (chart/statechart {:initial :route/admin-users}
    (sroute/rstate {:route/target :route/admin-users})
    (sroute/rstate {:route/target :route/admin-settings})))

;; A child chart with parallel regions
(def parallel-child-chart
  (chart/statechart {:initial :route/dashboard}
    (sroute/rstate {:route/target :route/dashboard :parallel? true}
      (sroute/rstate {:route/target :route/sidebar})
      (sroute/rstate {:route/target :route/main-panel}))))

;; A child chart that itself has an istate (nested invocation)
(def mid-chart
  (chart/statechart {:initial :route/mid-leaf}
    (sroute/istate {:route/target :route/mid-leaf
                    :route/reachable #{:route/deep-leaf}})))

(def deep-chart
  (chart/statechart {:initial :route/deep-leaf}
    (sroute/rstate {:route/target :route/deep-leaf})))

(defn- make-registry
  "Creates a registry pre-populated with the given chart-id->chart pairs."
  [& pairs]
  (let [reg (reg/new-registry)]
    (doseq [[k v] (partition 2 pairs)]
      (scp/register-statechart! reg k v))
    reg))

(defn- make-session
  "Builds the state-map entries for a single session: working memory + local data."
  [session-id statechart-src configuration local-data]
  {::sc/session-id {session-id {::sc/configuration  configuration
                                ::sc/statechart-src statechart-src}}
   ::sc/local-data {session-id (or local-data {})}})

(defn- merge-state-maps
  "Deep-merges multiple state-map fragments."
  [& maps]
  (apply merge-with (fn [a b]
                      (if (and (map? a) (map? b))
                        (merge a b)
                        b))
    maps))

(specification "deep-leaf-routes"
  (let [registry (make-registry
                   ::root root-chart
                   ::child child-chart
                   ::parallel-child parallel-child-chart
                   ::mid mid-chart
                   ::deep deep-chart)]

    (behavior "Flat chart (rstate only, no istates)"
      (let [state-map (make-session ::root-sid ::root
                        #{:route/home} {})]
        (assertions
          "Returns the active rstate as a leaf"
          (deep-leaf-routes state-map registry ::root-sid #{}) => #{:route/home})))

    (behavior "Single istate with active child session"
      (let [state-map (merge-state-maps
                        (make-session ::root-sid ::root
                          #{:route/admin} {:invocation/id {:route/admin ::child-sid}})
                        (make-session ::child-sid ::child
                          #{:route/admin-users} {}))]
        (assertions
          "Returns the child chart's leaf, not the istate itself"
          (deep-leaf-routes state-map registry ::root-sid #{}) => #{:route/admin-users})))

    (behavior "istate with no child session yet (fallback)"
      (let [state-map (make-session ::root-sid ::root
                        #{:route/admin} {})]
        (assertions
          "Falls back to the istate as the leaf"
          (deep-leaf-routes state-map registry ::root-sid #{}) => #{:route/admin})))

    (behavior "Parallel regions in child chart"
      (let [state-map (merge-state-maps
                        (make-session ::root-sid ::root
                          #{:route/admin} {:invocation/id {:route/admin ::par-child-sid}})
                        (make-session ::par-child-sid ::parallel-child
                          #{:route/dashboard :route/sidebar :route/main-panel} {}))]
        (assertions
          "Returns leaves from all parallel regions"
          (deep-leaf-routes state-map registry ::root-sid #{}) => #{:route/sidebar :route/main-panel})))

    (behavior "Nested istates (istate > child with istate > grandchild)"
      (let [state-map (merge-state-maps
                        (make-session ::root-sid ::root
                          #{:route/admin} {:invocation/id {:route/admin ::mid-sid}})
                        (make-session ::mid-sid ::mid
                          #{:route/mid-leaf} {:invocation/id {:route/mid-leaf ::deep-sid}})
                        (make-session ::deep-sid ::deep
                          #{:route/deep-leaf} {}))]
        (assertions
          "Returns the deepest leaf"
          (deep-leaf-routes state-map registry ::root-sid #{}) => #{:route/deep-leaf})))

    (behavior "No session exists at all"
      (assertions
        "Returns nil (no working memory)"
        (deep-leaf-routes {} registry ::nonexistent #{}) => nil))))

;; ---------------------------------------------------------------------------
;; Route configuration validation tests
;; ---------------------------------------------------------------------------

(specification "validate-duplicate-leaf-names"
  (behavior "No duplicates in a clean chart"
    (let [chart (chart/statechart {}
                  (sroute/routes {:id :region/routes :routing/root `Foo}
                    (sroute/rstate {:route/target :ns-a/page})
                    (sroute/rstate {:route/target :ns-b/other})))]
      (assertions
        "Returns empty"
        (sroute/validate-duplicate-leaf-names chart) => [])))

  (behavior "Two targets with the same simple name"
    (let [chart (chart/statechart {}
                  (sroute/routes {:id :region/routes :routing/root `Foo}
                    (sroute/rstate {:route/target :ns-a/page})
                    (sroute/rstate {:route/target :ns-b/page})))]
      (assertions
        "Returns one issue for the collision"
        (count (sroute/validate-duplicate-leaf-names chart)) => 1
        "Issue contains both targets"
        (set (:targets (first (sroute/validate-duplicate-leaf-names chart)))) => #{:ns-a/page :ns-b/page}
        "Issue has the correct warning key"
        (:warning-key (first (sroute/validate-duplicate-leaf-names chart))) => :routing/duplicate-leaf-name))))

(specification "validate-reachable-collisions"
  (behavior "No collisions"
    (let [chart (chart/statechart {}
                  (sroute/routes {:id :region/routes :routing/root `Foo}
                    (sroute/rstate {:route/target :route/home})
                    (sroute/istate {:route/target :route/admin
                                    :route/reachable #{:route/users}})))]
      (assertions
        "Returns empty"
        (sroute/validate-reachable-collisions chart) => [])))

  (behavior "Reachable target shares leaf name with direct target"
    (let [chart (chart/statechart {}
                  (sroute/routes {:id :region/routes :routing/root `Foo}
                    (sroute/rstate {:route/target :route/home})
                    (sroute/istate {:route/target :route/admin
                                    :route/reachable #{:child/home}})))]
      (assertions
        "Returns one collision issue"
        (count (sroute/validate-reachable-collisions chart)) => 1
        "Issue has the correct warning key"
        (:warning-key (first (sroute/validate-reachable-collisions chart))) => :routing/reachable-collision))))

(specification "validate-routing-root"
  (behavior "Valid routing root"
    (let [chart (chart/statechart {}
                  (sroute/routes {:id :region/routes :routing/root `Foo}
                    (sroute/rstate {:route/target :route/home})))]
      (assertions
        "Returns empty"
        (sroute/validate-routing-root chart) => [])))

  (behavior "Nil routing root"
    (let [chart (chart/statechart {}
                  (sroute/routes {:id :region/routes :routing/root nil}
                    (sroute/rstate {:route/target :route/home})))]
      (assertions
        "Returns one issue"
        (count (sroute/validate-routing-root chart)) => 1
        "Issue has the correct warning key"
        (:warning-key (first (sroute/validate-routing-root chart))) => :routing/missing-root))))

(specification "validate-duplicate-segments"
  (behavior "No duplicate chains"
    (let [chart (chart/statechart {}
                  (sroute/routes {:id :region/routes :routing/root `Foo}
                    (sroute/rstate {:route/target :ns-a/page :route/segment "alpha"})
                    (sroute/rstate {:route/target :ns-b/other :route/segment "beta"})))]
      (assertions
        "Returns empty"
        (sroute/validate-duplicate-segments chart) => [])))

  (behavior "Two states with same custom segment"
    (let [chart (chart/statechart {}
                  (sroute/routes {:id :region/routes :routing/root `Foo}
                    (sroute/rstate {:route/target :ns-a/page :route/segment "home"})
                    (sroute/rstate {:route/target :ns-b/other :route/segment "home"})))]
      (assertions
        "Returns one issue"
        (count (sroute/validate-duplicate-segments chart)) => 1
        "Issue has the correct warning key"
        (:warning-key (first (sroute/validate-duplicate-segments chart))) => :routing/duplicate-segment-chain)))

  (behavior "Custom segment collides with default segment"
    (let [chart (chart/statechart {}
                  (sroute/routes {:id :region/routes :routing/root `Foo}
                    (sroute/rstate {:route/target :ns-a/page})
                    (sroute/rstate {:route/target :ns-b/other :route/segment "page"})))]
      (assertions
        "Returns one issue — default 'page' collides with explicit 'page'"
        (count (sroute/validate-duplicate-segments chart)) => 1)))

  (behavior "No collision when segments differ"
    (let [chart (chart/statechart {}
                  (sroute/routes {:id :region/routes :routing/root `Foo}
                    (sroute/rstate {:route/target :ns-a/page})
                    (sroute/rstate {:route/target :ns-b/page :route/segment "custom"})))]
      (assertions
        "Different segments despite same leaf name — no segment chain collision"
        (sroute/validate-duplicate-segments chart) => []))))

(specification "validate-route-configuration"
  (behavior "Warn mode (default) does not throw"
    (let [chart (chart/statechart {}
                  (sroute/routes {:id :region/routes :routing/root `Foo}
                    (sroute/rstate {:route/target :ns-a/page})
                    (sroute/rstate {:route/target :ns-b/page})))]
      (assertions
        "Returns the chart unchanged"
        (sroute/validate-route-configuration chart :warn) => chart)))

  (behavior "Strict mode throws on duplicate leaf names"
    (let [chart (chart/statechart {}
                  (sroute/routes {:id :region/routes :routing/root `Foo}
                    (sroute/rstate {:route/target :ns-a/page})
                    (sroute/rstate {:route/target :ns-b/page})))]
      (assertions
        "Throws ex-info"
        (sroute/validate-route-configuration chart :strict) =throws=> #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo))))

  (behavior "Strict mode throws on duplicate segment chains"
    (let [chart (chart/statechart {}
                  (sroute/routes {:id :region/routes :routing/root `Foo}
                    (sroute/rstate {:route/target :ns-a/one :route/segment "home"})
                    (sroute/rstate {:route/target :ns-b/two :route/segment "home"})))]
      (assertions
        "Throws ex-info"
        (sroute/validate-route-configuration chart :strict) =throws=> #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)))))

;; ---------------------------------------------------------------------------
;; Slice 1: Pure utility functions
;; ---------------------------------------------------------------------------

;; Minimal component for testing ?! and route-to-event-name with classes
(defsc PageA [_ _]
  {:query [:page/id]
   :ident :page/id
   :initial-state {:page/id :a}})

(specification "route-to-event-name"
  (let [page-a-key (rc/class->registry-key PageA)]
    (assertions
      "converts a qualified keyword to a route-to event name"
      (sroute/route-to-event-name :app/page-a) => :route-to.app/page-a
      "converts a qualified symbol to a route-to event name"
      (sroute/route-to-event-name 'app/page-a) => :route-to.app/page-a
      "converts a component class to the same event name as its registry key"
      (sroute/route-to-event-name PageA) => (sroute/route-to-event-name page-a-key))))

(specification "?! (call-or-return)"
  (assertions
    "returns a non-fn value as-is"
    (sroute/?! 42) => 42
    "returns nil as-is"
    (sroute/?! nil) => nil
    "returns a keyword as-is"
    (sroute/?! :hello) => :hello
    "invokes a fn with remaining args"
    (sroute/?! + 1 2) => 3
    (sroute/?! (fn [x] (* x 2)) 5) => 10
    "does NOT invoke a component class (even though it is fn-like)"
    (sroute/?! PageA :ignored) => PageA))

(specification "reachable-targets"
  (component "chart with direct route targets"
    (let [chart (chart/statechart {}
                  (sroute/rstate {:route/target :route/home})
                  (sroute/rstate {:route/target :route/about}))]
      (assertions
        "returns all direct targets"
        (sroute/reachable-targets chart) => #{:route/home :route/about})))

  (component "chart with reachable sets"
    (let [chart (chart/statechart {}
                  (sroute/istate {:route/target    :route/admin
                                  :route/reachable #{:route/users :route/settings}}))]
      (assertions
        "includes both direct and reachable targets"
        (sroute/reachable-targets chart) => #{:route/admin :route/users :route/settings})))

  (component "empty chart"
    (let [chart (chart/statechart {}
                  (state {:id :idle}))]
      (assertions
        "returns empty set"
        (sroute/reachable-targets chart) => #{})))

  (component "deduplication"
    (let [chart (chart/statechart {}
                  (sroute/rstate {:route/target :route/page})
                  (sroute/istate {:route/target    :route/admin
                                  :route/reachable #{:route/page}}))]
      (assertions
        "duplicate targets are deduplicated"
        (count (sroute/reachable-targets chart)) => 2
        (sroute/reachable-targets chart) => #{:route/page :route/admin}))))

;; Test components for form?/rad-report?
(defsc PlainComp [_ _]
  {:query [:item/id]
   :ident :item/id
   :initial-state {:item/id 1}})

(defsc FormComp [_ _]
  {:query       [:form/id]
   :ident       :form/id
   :form-fields #{:form/name :form/email}})

(defsc ReportComp [_ _]
  {:query                                                 [:report/id]
   :ident                                                 :report/id
   :com.fulcrologic.rad.report/source-attribute :report/rows})

(specification "form?"
  (assertions
    "returns true for a component with :form-fields"
    (sroute/form? FormComp) => true
    "returns false for a plain component"
    (sroute/form? PlainComp) => false
    "returns false for a report component"
    (sroute/form? ReportComp) => false))

(specification "rad-report?"
  (assertions
    "returns true for a component with RAD report source-attribute"
    (sroute/rad-report? ReportComp) => true
    "returns false for a plain component"
    (sroute/rad-report? PlainComp) => false
    "returns false for a form component"
    (sroute/rad-report? FormComp) => false))

;; ---------------------------------------------------------------------------
;; Slice 2: Route initialization helpers
;; ---------------------------------------------------------------------------

;; Test component with :route/params
(defsc ParamsPage [_ _]
  {:query         [:page/id :page/filter :page/sort]
   :ident         :page/id
   :initial-state {:page/id :params}})

(specification "establish-route-params-node"
  (component "with matching params"
    (let [node       (sroute/establish-route-params-node {:id          :test-page
                                                          :route/params #{:filter :sort}})
          ;; Extract the expr fn from the script node
          expr-fn    (get node :expr)
          ;; Call with event-data containing matching and extra keys
          result     (expr-fn {} {} nil {:filter "active" :sort "name" :extra "ignored"})]
      (assertions
        "filters event-data to declared params and assigns to [:routing/parameters id]"
        result => [(ops/assign [:routing/parameters :test-page] {:filter "active" :sort "name"})])))

  (component "with empty event-data"
    (let [node    (sroute/establish-route-params-node {:id :test-page :route/params #{:filter}})
          expr-fn (get node :expr)
          result  (expr-fn {} {} nil nil)]
      (assertions
        "produces empty params map"
        result => [(ops/assign [:routing/parameters :test-page] {})])))

  (component "with no declared params"
    (let [node    (sroute/establish-route-params-node {:id :test-page})
          expr-fn (get node :expr)
          result  (expr-fn {} {} nil {:filter "active"})]
      (assertions
        "produces empty params even when event-data has values"
        result => [(ops/assign [:routing/parameters :test-page] {})]))))

;; ---------------------------------------------------------------------------
;; Slice 3: Busy checking depth
;; ---------------------------------------------------------------------------

;; Component with custom sroute/busy? fn
(defsc BusyComp [_ _]
  {:query [:comp/id]
   :ident :comp/id
   sfro/busy?
   (fn [_env _data] true)})

(defsc NotBusyComp [_ _]
  {:query [:comp/id]
   :ident :comp/id
   sfro/busy?
   (fn [_env _data] false)})

(def ^:private check-component-busy? @#'sroute/check-component-busy?)

(specification "check-component-busy?"
  (assertions
    "returns true when component has custom busy? fn that returns true"
    (check-component-busy? {} {} (rc/class->registry-key BusyComp)) => true
    "returns false when component has custom busy? fn that returns false"
    (check-component-busy? {} {} (rc/class->registry-key NotBusyComp)) => false
    "returns false when component has no busy? fn and is not a form"
    (check-component-busy? {} {} (rc/class->registry-key PlainComp)) => false))

;; deep-busy? tests using the same state-map/registry pattern as deep-leaf-routes
(def ^:private deep-busy-fn @#'sroute/deep-busy?)

(def busy-chart
  (chart/statechart {:initial :route/busy-page}
    (sroute/rstate {:route/target (rc/class->registry-key BusyComp)})))

(def not-busy-chart
  (chart/statechart {:initial :route/clean-page}
    (sroute/rstate {:route/target (rc/class->registry-key NotBusyComp)})))

(specification "deep-busy?"
  (let [busy-key     (rc/class->registry-key BusyComp)
        not-busy-key (rc/class->registry-key NotBusyComp)
        registry     (make-registry
                       ::root root-chart
                       ::busy-chart busy-chart
                       ::not-busy-chart not-busy-chart)]

    (component "single session with busy component"
      (let [state-map (make-session ::root-sid ::busy-chart
                        #{busy-key} {})]
        (assertions
          "returns truthy"
          (boolean (deep-busy-fn {} {} registry state-map ::root-sid #{})) => true)))

    (component "single session with not-busy component"
      (let [state-map (make-session ::root-sid ::not-busy-chart
                        #{not-busy-key} {})]
        (assertions
          "returns falsy"
          (boolean (deep-busy-fn {} {} registry state-map ::root-sid #{})) => false)))

    (component "nested sessions — child is busy"
      (let [state-map (merge-state-maps
                        (make-session ::root-sid ::root
                          #{:route/admin} {:invocation/id {:route/admin ::child-sid}})
                        (make-session ::child-sid ::busy-chart
                          #{busy-key} {}))]
        (assertions
          "returns truthy when child has busy component"
          (boolean (deep-busy-fn {} {} registry state-map ::root-sid #{})) => true)))

    (component "prevents infinite recursion via seen set"
      (let [state-map (make-session ::root-sid ::busy-chart
                        #{busy-key} {})]
        (assertions
          "returns nil when session already in seen set"
          (deep-busy-fn {} {} registry state-map ::root-sid #{::root-sid}) => nil)))

    (component "nonexistent session"
      (assertions
        "returns nil"
        (deep-busy-fn {} {} registry {} ::missing #{}) => nil))))

;; ---------------------------------------------------------------------------
;; Slice 4: Route denial
;; ---------------------------------------------------------------------------

(let [b (volatile! false)]
  (defn set-busy! [v] (vreset! b v) nil)
  (defn busy? [env {:keys [_event]} & args]
    (if (-> _event :data ::sroute/force?)
      false
      (and @b (not (-> _event :data ::force?))))))

(defonce entry-count (volatile! 0))
(defn entered! [& _]
  (vswap! entry-count inc))

(def application-chart
  (chart/statechart {}
    (sroute/routing-regions
      (sroute/routes {:id           :region/routes
                      :routing/root `Foo}
        (sroute/rstate {:route/target :route/foo}
          (sroute/rstate {:route/target :route/routeA1})
          (sroute/rstate {:route/target :route/routeA2}
            (sroute/rstate {:route/target :route/routeA21})
            (sroute/rstate {:route/target :route/routeA22}
              (on-entry {}
                (script {:expr entered!})))))))
    (state {:id :state/other})))

;; NOTE: route-denied? and abandon-route-change! require a running Fulcro app with
;; a statechart session. Testing via statechart testing env instead.

(specification "Route denial and abandonment"
  (let [event-queue (mpq/new-queue)
        env         (testing/new-testing-env {:statechart  application-chart
                                              :event-queue event-queue} {entered!                 entered!
                                                                         sroute/busy?             busy?
                                                                         sroute/override-route!      sroute/override-route!
                                                                         sroute/clear-override!      sroute/clear-override!
                                                                         sroute/record-failed-route! sroute/record-failed-route!})]
    (set-busy! false)
    (vreset! entry-count 0)
    (testing/start! env)

    ;; Navigate to a specific route first
    (testing/run-events! env (sroute/route-to-event-name :route/routeA22))

    (component "abandon-route-change! (close routing info)"
      ;; Make busy, attempt to route — should deny
      (set-busy! true)
      (testing/run-events! env (sroute/route-to-event-name :route/routeA1))

      (assertions
        "routing info is open after denied route"
        (testing/in? env :routing-info/open) => true
        "stays on original route"
        (testing/in? env :route/routeA22) => true)

      ;; Abandon the route change (close the modal)
      (testing/run-events! env :event.routing-info/close)

      (assertions
        "closes routing info back to idle"
        (testing/in? env :routing-info/idle) => true
        "remains on original route"
        (testing/in? env :route/routeA22) => true
        "clears the failed route event"
        (::sroute/failed-route-event (testing/data env)) => nil))))

;; ---------------------------------------------------------------------------
;; URL sync helper wrappers
;; ---------------------------------------------------------------------------

(defsc HelperRootComp [_ _]
  {:query         [:ui/current-route]
   :ident         (fn [] [:component/id ::helper-root])
   :initial-state {:ui/current-route {}}})

(defsc HelperPageA [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :helper-a}})

(defsc HelperPageB [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :helper-b}})

(defsc HelperPageC [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :helper-c}})

(def helper-page-a-key (comp/class->registry-key HelperPageA))
(def helper-page-b-key (comp/class->registry-key HelperPageB))
(def helper-page-c-key (comp/class->registry-key HelperPageC))

(def helper-routing-chart
  (chart/statechart {:initial :state/route-root}
    (sroute/routing-regions
      (sroute/routes {:id :region/routes :routing/root `HelperRootComp :initial helper-page-a-key}
        (sroute/rstate {:route/target `HelperPageA})
        (sroute/rstate {:route/target `HelperPageB})
        (sroute/rstate {:route/target `HelperPageC})))))

(defn- helper-test-app []
  (let [a (app/fulcro-app)]
    (app/set-root! a HelperRootComp {:initialize-state? true})
    (scf/install-fulcro-statecharts! a {:event-loop? false})
    a))

(defn- helper-active-leaf [app]
  (let [cfg       (scf/current-configuration app sroute/session-id)
        route-ids #{helper-page-a-key helper-page-b-key helper-page-c-key}]
    (into #{} (filter route-ids) cfg)))

(specification "url-sync-installed?"
  (component "when URL sync is not installed"
    (let [app (helper-test-app)]
      (sroute/start! app helper-routing-chart)
      (scf/process-events! app)
      (assertions
        "returns false"
        (sroute/url-sync-installed? app) => false)))

  (component "when URL sync is installed"
    (let [app      (helper-test-app)
          provider (rsh/simulated-url-history "/")]
      (sroute/start! app helper-routing-chart)
      (scf/process-events! app)
      (let [cleanup (sroute/install-url-sync! app {:provider provider})]
        (assertions
          "returns true"
          (sroute/url-sync-installed? app) => true)
        (cleanup)
        (assertions
          "returns false after cleanup"
          (sroute/url-sync-installed? app) => false)))))

(specification "route-current-url"
  (component "when URL sync is not installed"
    (let [app (helper-test-app)]
      (sroute/start! app helper-routing-chart)
      (scf/process-events! app)
      (assertions
        "returns nil"
        (sroute/route-current-url app) => nil)))

  (component "when URL sync is installed"
    (let [app      (helper-test-app)
          provider (rsh/simulated-url-history "/")]
      (sroute/start! app helper-routing-chart)
      (scf/process-events! app)
      (let [cleanup (sroute/install-url-sync! app {:provider provider})]
        (sroute/url-sync-on-save sroute/session-id nil app)
        (assertions
          "returns current URL from provider"
          (sroute/route-current-url app) => "/HelperPageA")
        (cleanup)))))

(specification "route-history-index"
  (component "when URL sync is not installed"
    (let [app (helper-test-app)]
      (sroute/start! app helper-routing-chart)
      (scf/process-events! app)
      (assertions
        "returns nil"
        (sroute/route-history-index app) => nil)))

  (component "when URL sync is installed"
    (let [app      (helper-test-app)
          provider (rsh/simulated-url-history "/")]
      (sroute/start! app helper-routing-chart)
      (scf/process-events! app)
      (let [cleanup (sroute/install-url-sync! app {:provider provider})]
        (sroute/url-sync-on-save sroute/session-id nil app)
        (assertions
          "returns current index from provider"
          (sroute/route-history-index app) => 0)
        (cleanup)))))

(specification "route-back!"
  (component "when URL sync is not installed"
    (let [app (helper-test-app)]
      (sroute/start! app helper-routing-chart)
      (scf/process-events! app)
      (assertions
        "returns nil (no-op)"
        (sroute/route-back! app) => nil)))

  #?(:clj
     (component "when URL sync is installed"
       (let [app      (helper-test-app)
             provider (rsh/simulated-url-history "/")]
         (sroute/start! app helper-routing-chart)
         (scf/process-events! app)
         (let [cleanup (sroute/install-url-sync! app {:provider provider})]
           (sroute/url-sync-on-save sroute/session-id nil app)
           ;; Navigate A -> B
           (sroute/route-to! app `HelperPageB)
           (scf/process-events! app)
           (sroute/url-sync-on-save sroute/session-id nil app)

           (assertions
             "returns true when provider exists"
             (sroute/route-back! app) => true)
           (scf/process-events! app)
           (sroute/url-sync-on-save sroute/session-id nil app)

           (assertions
             "navigates back"
             (helper-active-leaf app) => #{helper-page-a-key})
           (cleanup))))))

(specification "route-forward!"
  (component "when URL sync is not installed"
    (let [app (helper-test-app)]
      (sroute/start! app helper-routing-chart)
      (scf/process-events! app)
      (assertions
        "returns nil (no-op)"
        (sroute/route-forward! app) => nil)))

  #?(:clj
     (component "when URL sync is installed"
       (let [app      (helper-test-app)
             provider (rsh/simulated-url-history "/")]
         (sroute/start! app helper-routing-chart)
         (scf/process-events! app)
         (let [cleanup (sroute/install-url-sync! app {:provider provider})]
           (sroute/url-sync-on-save sroute/session-id nil app)
           ;; Navigate A -> B
           (sroute/route-to! app `HelperPageB)
           (scf/process-events! app)
           (sroute/url-sync-on-save sroute/session-id nil app)
           ;; Back to A
           (sroute/route-back! app)
           (scf/process-events! app)
           (sroute/url-sync-on-save sroute/session-id nil app)

           (assertions
             "returns true when provider exists"
             (sroute/route-forward! app) => true)
           (scf/process-events! app)
           (sroute/url-sync-on-save sroute/session-id nil app)

           (assertions
             "navigates forward"
             (helper-active-leaf app) => #{helper-page-b-key})
           (cleanup))))))

(specification "route-sync-from-url!"
  (component "when URL sync is not installed"
    (let [app (helper-test-app)]
      (sroute/start! app helper-routing-chart)
      (scf/process-events! app)
      (assertions
        "returns nil (no-op)"
        (sroute/route-sync-from-url! app) => nil)))

  (component "when URL sync is installed"
    (let [app      (helper-test-app)
          provider (rsh/simulated-url-history "/")]
      (sroute/start! app helper-routing-chart)
      (scf/process-events! app)
      (let [cleanup (sroute/install-url-sync! app {:provider provider})]
        (sroute/url-sync-on-save sroute/session-id nil app)
        ;; Manually change provider URL
        (ruh/-replace-url! provider "/HelperPageC")
        (assertions
          "returns routed target keyword"
          (sroute/route-sync-from-url! app) => helper-page-c-key)
        (scf/process-events! app)
        (sroute/url-sync-on-save sroute/session-id nil app)
        (assertions
          "routes statechart to match URL"
          (helper-active-leaf app) => #{helper-page-c-key})
        (cleanup)))))
