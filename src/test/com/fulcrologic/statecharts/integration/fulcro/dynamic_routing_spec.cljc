(ns com.fulcrologic.statecharts.integration.fulcro.dynamic-routing-spec
  "Tests for the statechart-based dynamic routing compatibility layer.

   These tests verify that the new statechart-based routing works correctly
   by checking both the API behavior and the rendered UI output via hiccup."
  (:require
    #?@(:cljs [[com.fulcrologic.fulcro.dom :as dom]]
        :clj  [[com.fulcrologic.fulcro.dom-server :as dom]
               [com.fulcrologic.fulcro.headless :as ct]
               [com.fulcrologic.fulcro.headless.hiccup :as hic]])
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    ;; Use the NEW statechart-based routing
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.statecharts.integration.fulcro.dynamic-routing :as dr]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.event-queue.event-processing :as evp]
    [fulcro-spec.core :refer [=> assertions behavior component specification]]))

;; =============================================================================
;; Rendering Helpers
;; =============================================================================

#?(:clj
   (defn element->hiccup
     "Convert a Fulcro DOM element to hiccup for inspection."
     [element]
     (when element
       (hic/rendered-tree->hiccup element))))

#?(:clj
   (defn render-root
     "Render the app's root component and return the hiccup representation.

      IMPORTANT: We bind comp/*app* so that nested components can access
      the app via `current-state` and other functions. This works around
      a Fulcro quirk where CLJ factories fall back to {} for *app*."
     [{::app/keys [state-atom runtime-atom] :as app}]
     (let [state-map    @state-atom
           {::app/keys [root-class]} @runtime-atom
           root-factory (comp/factory root-class)
           query        (comp/get-query root-class state-map)
           tree         (fdn/db->tree query state-map state-map)]
       ;; Bind comp/*app* so nested components can access the app
       (binding [comp/*app* app]
         (element->hiccup (root-factory tree))))))

#?(:clj
   (defn find-in-hiccup
     "Find all elements in hiccup with the given tag or matching predicate."
     [hiccup pred]
     (let [results (atom [])]
       (letfn [(walk [form]
                 (when (pred form)
                   (swap! results conj form))
                 (cond
                   (vector? form) (doseq [child form] (walk child))
                   (seq? form) (doseq [child form] (walk child))))]
         (walk hiccup))
       @results)))

;; =============================================================================
;; Test Components - Simple routing
;; =============================================================================

(defsc HomePage [this {:home/keys [id]}]
  {:query         [:home/id]
   :ident         (fn [] [:component/id :home])
   :initial-state {:home/id :home}
   :route-segment ["home"]}
  (dom/div {:id "home-page"} "Welcome Home"))

(defsc AboutPage [this {:about/keys [id]}]
  {:query         [:about/id]
   :ident         (fn [] [:component/id :about])
   :initial-state {:about/id :about}
   :route-segment ["about"]}
  (dom/div {:id "about-page"} "About Us"))

(defsc UserPage [this {:user/keys [id] user-name :user/name}]
  {:query         [:user/id :user/name]
   :ident         :user/id
   :initial-state {:user/id :none :user/name "Unknown"}
   :route-segment ["user" :user-id]
   :will-enter    (fn [app {:keys [user-id]}]
                    (dr/route-immediate [:user/id (keyword user-id)]))}
  (dom/div {:id "user-page"}
    (dom/span {:id "user-id"} (str "User: " (name id)))
    (dom/span {:id "user-name"} (str "Name: " user-name))))

;; =============================================================================
;; Test Components - Nested routing (Settings with sub-pages)
;; =============================================================================

(defsc ProfileSettings [this {:profile/keys [id]}]
  {:query         [:profile/id]
   :ident         (fn [] [:component/id :profile-settings])
   :initial-state {:profile/id :profile}
   :route-segment ["profile"]}
  (dom/div {:id "profile-settings"} "Profile Settings"))

(defsc SecuritySettings [this {:security/keys [id]}]
  {:query         [:security/id]
   :ident         (fn [] [:component/id :security-settings])
   :initial-state {:security/id :security}
   :route-segment ["security"]}
  (dom/div {:id "security-settings"} "Security Settings"))

(defsc NotificationSettings [this {:notification/keys [id]}]
  {:query         [:notification/id]
   :ident         (fn [] [:component/id :notification-settings])
   :initial-state {:notification/id :notification}
   :route-segment ["notifications"]}
  (dom/div {:id "notification-settings"} "Notification Settings"))

;; Nested router for settings sub-pages
(dr/defrouter SettingsRouter [this props]
  {:router-targets [ProfileSettings SecuritySettings NotificationSettings]})

(defsc SettingsPage [this {:settings/keys [id] :as props}]
  {:query         [:settings/id {:settings/router (comp/get-query SettingsRouter)}]
   :ident         (fn [] [:component/id :settings])
   :initial-state {:settings/id     :settings
                   :settings/router {}}
   :route-segment ["settings"]}
  (let [router-factory (comp/factory SettingsRouter)]
    (dom/div {:id "settings-page"}
      (dom/h2 {} "Settings")
      (router-factory (:settings/router props)))))

;; =============================================================================
;; Test Components - Route guards
;; =============================================================================

(defonce busy-state (atom false))

(defsc BusyPage [this {:busy/keys [id data]}]
  {:query               [:busy/id :busy/data]
   :ident               (fn [] [:component/id :busy])
   :initial-state       {:busy/id :busy :busy/data "Important unsaved data"}
   :route-segment       ["busy"]
   :allow-route-change? (fn [this] (not @busy-state))}
  (dom/div {:id "busy-page"}
    (dom/span {} "Busy Page")
    (dom/span {:id "busy-data"} (:busy/data (comp/props this)))))

;; =============================================================================
;; Test Components - Lifecycle tracking
;; =============================================================================

(defonce will-leave-calls (atom []))
(defonce route-cancelled-calls (atom []))

(defsc TrackingPage [this {:tracking/keys [id]}]
  {:query         [:tracking/id]
   :ident         (fn [] [:component/id :tracking])
   :initial-state {:tracking/id :tracking}
   :route-segment ["tracking"]
   :will-leave    (fn [this props]
                    (swap! will-leave-calls conj {:component :tracking :props props})
                    true)}
  (dom/div {:id "tracking-page"} "Tracking Page"))

;; =============================================================================
;; Test Components - Deferred routing
;; =============================================================================

(defonce deferred-ready-signal (atom nil))

(defsc DeferredPage [this {:deferred/keys [id loaded-data]}]
  {:query           [:deferred/id :deferred/loaded-data]
   :ident           :deferred/id
   :initial-state   {:deferred/id :none :deferred/loaded-data nil}
   :route-segment   ["deferred" :deferred-id]
   :will-enter      (fn [app {:keys [deferred-id]}]
                      (let [ident [:deferred/id (keyword deferred-id)]]
                        (reset! deferred-ready-signal
                          (fn [] (dr/target-ready! app ident)))
                        (dr/route-deferred ident
                          (fn []
                            ;; Simulate async load
                            nil))))
   :route-cancelled (fn [params]
                      (swap! route-cancelled-calls conj {:component :deferred :params params}))}
  (dom/div {:id "deferred-page"}
    (dom/span {:id "deferred-id"} (str "Deferred: " id))
    (dom/span {:id "deferred-data"} (str "Data: " loaded-data))))

;; =============================================================================
;; Main Router and Root
;; =============================================================================

(dr/defrouter MainRouter [this props]
  {:router-targets [HomePage AboutPage UserPage SettingsPage BusyPage TrackingPage DeferredPage]})

(defsc Root [this {:root/keys [router]}]
  {:query         [{:root/router (comp/get-query MainRouter)}]
   :initial-state {:root/router {}}}
  (let [router-factory (comp/factory MainRouter)]
    (dom/div {:id "app-root"}
      (dom/h1 {} "Test App")
      (router-factory router))))

;; =============================================================================
;; Test App Setup
;; =============================================================================

(defn test-app
  "Create a test app with routing initialized."
  []
  #?(:clj
     ;; Use ct/build-test-app for synchronous transaction processing
     (let [a (ct/build-test-app {:root-class Root})]
       ;; Install statechart infrastructure for this app, with event-loop disabled
       ;; so we can process events manually for deterministic testing
       (scf/install-fulcro-statecharts! a {:event-loop? false})
       a)
     :cljs
     (let [a (app/fulcro-app {})]
       (app/set-root! a Root {:initialize-state? true})
       (scf/install-fulcro-statecharts! a)
       a)))

(defn start-and-process!
  "Start routing and process any pending events."
  [app]
  (dr/start-routing! app)
  ;; Process any statechart events
  (let [env (scf/statechart-env app)]
    (evp/process-events env))
  app)

(defn route-and-process!
  "Change route and process events."
  [app path]
  (dr/change-route! app path)
  (let [env (scf/statechart-env app)]
    (evp/process-events env)))

;; =============================================================================
;; API Function Tests
;; =============================================================================

(specification "Route result constructors"
  (component "route-immediate"
    (let [ident  [:user/id 123]
          result (dr/route-immediate ident)]
      (assertions
        "Returns the ident unchanged"
        result => ident
        "Has :immediate true in metadata"
        (:immediate (meta result)) => true
        "immediate? returns true"
        (dr/immediate? result) => true)))

  (component "route-deferred"
    (let [ident  [:user/id 123]
          called (atom false)
          result (dr/route-deferred ident #(reset! called true))]
      (assertions
        "Returns the ident"
        result => ident
        "Has :immediate false in metadata"
        (:immediate (meta result)) => false
        "Has the completion fn in metadata"
        (fn? (:fn (meta result))) => true
        "immediate? returns false"
        (dr/immediate? result) => false)
      ;; Call the completion fn
      ((:fn (meta result)))
      (assertions
        "Completion fn is callable"
        @called => true))))

(specification "Component option accessors"
  (component "route-segment"
    (assertions
      "Returns the route segment for a component"
      (dr/route-segment HomePage) => ["home"]
      "Returns segment with parameters"
      (dr/route-segment UserPage) => ["user" :user-id]
      "Returns nested route segment"
      (dr/route-segment ProfileSettings) => ["profile"]))

  (component "route-target?"
    (assertions
      "Returns true for route targets"
      (dr/route-target? HomePage) => true
      (dr/route-target? UserPage) => true
      (dr/route-target? ProfileSettings) => true
      "Returns false for non-targets"
      (dr/route-target? Root) => false))

  (component "router?"
    (assertions
      "Returns true for routers"
      (dr/router? MainRouter) => true
      (dr/router? SettingsRouter) => true
      "Returns false for non-routers"
      (dr/router? HomePage) => false
      (dr/router? Root) => false))

  (component "get-targets"
    (assertions
      "Returns the main router's targets"
      (contains? (dr/get-targets MainRouter) HomePage) => true
      (contains? (dr/get-targets MainRouter) UserPage) => true
      (contains? (dr/get-targets MainRouter) SettingsPage) => true
      "Returns nested router's targets"
      (contains? (dr/get-targets SettingsRouter) ProfileSettings) => true
      (contains? (dr/get-targets SettingsRouter) SecuritySettings) => true)))

(specification "path-to generates correct paths"
  (assertions
    "Single target without params"
    (dr/path-to HomePage) => ["home"]
    (dr/path-to AboutPage) => ["about"]

    "Single target with params map"
    (dr/path-to UserPage {:user-id "789"}) => ["user" "789"]

    "Nested targets"
    (dr/path-to SettingsPage ProfileSettings) => ["settings" "profile"]
    (dr/path-to SettingsPage SecuritySettings) => ["settings" "security"]))

;; =============================================================================
;; Routing Behavior Tests with Hiccup Verification
;; =============================================================================

#?(:clj
   (specification "Basic routing with UI verification"
     (let [app (test-app)]
       (start-and-process! app)

       (behavior "Initial state shows first target (HomePage)"
         (let [hiccup    (render-root app)
               home-divs (find-in-hiccup hiccup #(and (vector? %)
                                                   (= :div (first %))
                                                   (map? (second %))
                                                   (= "home-page" (:id (second %)))))]
           (assertions
             "Home page div is rendered"
             (count home-divs) => 1)))

       (behavior "Routes to about page"
         (route-and-process! app ["about"])
         (let [hiccup     (render-root app)
               about-divs (find-in-hiccup hiccup #(and (vector? %)
                                                    (= :div (first %))
                                                    (map? (second %))
                                                    (= "about-page" (:id (second %)))))]
           (assertions
             "About page div is rendered"
             (count about-divs) => 1)))

       (behavior "Routes to user page with parameter"
         (route-and-process! app ["user" "alice"])
         (let [hiccup    (render-root app)
               user-divs (find-in-hiccup hiccup #(and (vector? %)
                                                   (= :div (first %))
                                                   (map? (second %))
                                                   (= "user-page" (:id (second %)))))]
           (assertions
             "User page div is rendered"
             (count user-divs) => 1))))))

#?(:clj
   (specification "Nested routing with UI verification"
     (let [app (test-app)]
       (start-and-process! app)

       (behavior "Routes to settings with profile sub-page"
         (route-and-process! app ["settings" "profile"])
         (let [hiccup        (render-root app)
               settings-divs (find-in-hiccup hiccup #(and (vector? %)
                                                       (= :div (first %))
                                                       (map? (second %))
                                                       (= "settings-page" (:id (second %)))))
               profile-divs  (find-in-hiccup hiccup #(and (vector? %)
                                                       (= :div (first %))
                                                       (map? (second %))
                                                       (= "profile-settings" (:id (second %)))))]
           (assertions
             "Settings page container is rendered"
             (count settings-divs) => 1
             "Profile settings sub-page is rendered"
             (count profile-divs) => 1)))

       (behavior "Routes to settings with security sub-page"
         (route-and-process! app ["settings" "security"])
         (let [hiccup        (render-root app)
               security-divs (find-in-hiccup hiccup #(and (vector? %)
                                                       (= :div (first %))
                                                       (map? (second %))
                                                       (= "security-settings" (:id (second %)))))]
           (assertions
             "Security settings sub-page is rendered"
             (count security-divs) => 1)))

       (behavior "Routes to different nested route"
         (route-and-process! app ["settings" "notifications"])
         (let [hiccup     (render-root app)
               notif-divs (find-in-hiccup hiccup #(and (vector? %)
                                                    (= :div (first %))
                                                    (map? (second %))
                                                    (= "notification-settings" (:id (second %)))))]
           (assertions
             "Notification settings sub-page is rendered"
             (count notif-divs) => 1))))))

#?(:clj
   (specification "current-route reflects routing state"
     (let [app (test-app)]
       (start-and-process! app)

       (behavior "Returns current route path"
         (route-and-process! app ["home"])
         (assertions
           "Shows home path"
           (dr/current-route app) => ["home"])

         (route-and-process! app ["about"])
         (assertions
           "Shows about path"
           (dr/current-route app) => ["about"])

         (route-and-process! app ["user" "bob"])
         (assertions
           "Shows user path with param"
           (dr/current-route app) => ["user" "bob"])))))

;; =============================================================================
;; Route Guard Tests
;; =============================================================================

#?(:clj
   (specification "Route guards (allow-route-change?)"
     (let [app (test-app)]
       (reset! busy-state false)
       (start-and-process! app)

       (behavior "Can route to busy page when not busy"
         (route-and-process! app ["busy"])
         (let [hiccup    (render-root app)
               busy-divs (find-in-hiccup hiccup #(and (vector? %)
                                                   (= :div (first %))
                                                   (map? (second %))
                                                   (= "busy-page" (:id (second %)))))]
           (assertions
             "Busy page is rendered"
             (count busy-divs) => 1)))

       (behavior "Can leave busy page when not busy"
         (route-and-process! app ["home"])
         (assertions
           "Can change route when not busy"
           (dr/can-change-route? app) => true)
         (let [hiccup    (render-root app)
               home-divs (find-in-hiccup hiccup #(and (vector? %)
                                                   (= :div (first %))
                                                   (map? (second %))
                                                   (= "home-page" (:id (second %)))))]
           (assertions
             "Home page is rendered after leaving"
             (count home-divs) => 1)))

       (behavior "Routing is blocked when busy"
         (route-and-process! app ["busy"])
         (reset! busy-state true)

         (assertions
           "can-change-route? returns false when busy"
           (dr/can-change-route? app) => false)

         ;; Try to route away
         (route-and-process! app ["home"])

         ;; Should still be on busy page
         (let [hiccup    (render-root app)
               busy-divs (find-in-hiccup hiccup #(and (vector? %)
                                                   (= :div (first %))
                                                   (map? (second %))
                                                   (= "busy-page" (:id (second %)))))]
           (assertions
             "Still on busy page after blocked route"
             (count busy-divs) => 1))))))

;; =============================================================================
;; Force Route Tests
;; =============================================================================

;; TODO: The force-route! mechanism needs investigation into the event queue
;; timing in the Fulcro statechart integration. The event is sent but not
;; processed in the same process-events call. For now, testing the core
;; route guard functionality which IS working.

;; =============================================================================
;; Force Route Tests
;; =============================================================================

;; The force-route! mechanism works as follows:
;; 1. Route to ["home"] is blocked by busy-check guard
;; 2. record-denied stores the blocked event in ::denied-route-event
;; 3. raise sends :event.routing/show-denied, transitions to :denied/open
;; 4. force-route! sends :event.routing/force-route
;; 5. retry-denied queues the original event with ::force? true
;; 6. The ::force? flag makes busy-check return false, allowing the route
;;
;; Key: force-route! requires TWO process-events calls after the initial one:
;; - First: processes force-route event, queues retry
;; - Second: processes retried route event with ::force? flag (bypasses guard)

#?(:clj
   (specification "Force route overrides guards"
     ;; Reset all shared test state
     (reset! busy-state false)
     (reset! will-leave-calls [])
     (reset! route-cancelled-calls [])
     (reset! deferred-ready-signal nil)
     (let [app (test-app)]
       (start-and-process! app)

       ;; Go to busy page and make it busy
       (route-and-process! app ["busy"])
       (reset! busy-state true)

       ;; Try normal route (should be blocked)
       (route-and-process! app ["home"])

       (behavior "route is blocked when busy"
         (assertions
           "Still on busy page after blocked route"
           (dr/current-route app) => ["busy"]))

       ;; Check the statechart's data model for the denied event
       (behavior "denied-route-event is stored in data model"
         (let [state        (rapp/current-state app)
               local-data   (get-in state (scf/local-data-path dr/session-id))
               denied-event (::dr/denied-route-event local-data)]
           (assertions
             "denied event was recorded"
             (some? denied-event) => true)))

       (behavior "force-route! overrides the block even when busy"
         ;; Note: busy-state is STILL true here
         ;; force-route! should work because ::force? bypasses busy-check
         (dr/force-route! app)
         ;; Process the force-route event - the retry now uses env/raise
         ;; which processes synchronously in the same macrostep
         (let [env (scf/statechart-env app)]
           (evp/process-events env))

         ;; Clean up shared state
         (reset! busy-state false)

         (assertions
           "Home page is reached after force (despite busy)"
           (dr/current-route app) => ["home"])))))

;; =============================================================================
;; Tests using new CLJ Testing Infrastructure
;; =============================================================================

#?(:clj
   (specification "Routing state inspection via rapp/current-state"
     (let [app (test-app)]
       (start-and-process! app)

       (behavior "current-state works with test app"
         (route-and-process! app ["home"])
         (let [state (rapp/current-state app)]
           (assertions
             "state map is accessible"
             (map? state) => true
             "router state is present"
             (contains? state ::dr/id) => true))))))

#?(:clj
   (specification "Deferred routing with target-ready!"
     (let [app (test-app)]
       (start-and-process! app)
       (reset! deferred-ready-signal nil)

       (behavior "deferred route registers callback"
         (route-and-process! app ["deferred" "test-item"])

         (assertions
           "ready signal callback was stored"
           (fn? @deferred-ready-signal) => true))

       (behavior "old route remains current until target signals ready"
         (assertions
           "route has not changed to deferred target yet"
           (dr/current-route app) =fn=> (complement #(= % ["deferred" "test-item"]))
           "route is still empty or at previous location"
           (or (empty? (dr/current-route app))
             (not= (dr/current-route app) ["deferred" "test-item"])) => true))

       (behavior "calling target-ready! completes the route"
         ;; Call the stored ready signal
         (when-let [ready-fn @deferred-ready-signal]
           (ready-fn))
         ;; Process the target-ready event
         (let [env (scf/statechart-env app)]
           (evp/process-events env))

         (assertions
           "current route reflects deferred target"
           (dr/current-route app) => ["deferred" "test-item"])))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

#?(:clj
   (specification "Complete routing workflow integration"
     (let [app (test-app)]
       (start-and-process! app)

       (behavior "multi-step routing workflow"
         ;; Step 1: Start at home
         (route-and-process! app ["home"])
         (assertions
           "at home page"
           (dr/current-route app) => ["home"])

         ;; Step 2: Navigate to about
         (route-and-process! app ["about"])
         (assertions
           "at about page"
           (dr/current-route app) => ["about"])

         ;; Step 3: Navigate to user with param
         (route-and-process! app ["user" "bob"])
         (assertions
           "at user page with param"
           (dr/current-route app) => ["user" "bob"])

         ;; Step 4: Navigate to nested settings
         (route-and-process! app ["settings" "profile"])
         (assertions
           "at nested settings"
           (first (dr/current-route app)) => "settings")

         ;; Step 5: Back to home
         (route-and-process! app ["home"])
         (assertions
           "back at home"
           (dr/current-route app) => ["home"])))))

#?(:clj
   (specification "Route blocking workflow integration"
     (let [app (test-app)]
       (reset! busy-state false)
       (start-and-process! app)

       (behavior "complete blocking and unblocking workflow"
         ;; Go to busy page
         (route-and-process! app ["busy"])
         (assertions
           "at busy page"
           (dr/current-route app) => ["busy"]
           "can change route when not busy"
           (dr/can-change-route? app) => true)

         ;; Make busy
         (reset! busy-state true)
         (assertions
           "cannot change route when busy"
           (dr/can-change-route? app) => false)

         ;; Try to leave (should be blocked)
         (route-and-process! app ["home"])
         (assertions
           "still on busy page"
           (dr/current-route app) => ["busy"])

         ;; Clear busy state
         (reset! busy-state false)
         (assertions
           "can change route after clearing busy"
           (dr/can-change-route? app) => true)

         ;; Now can leave
         (route-and-process! app ["home"])
         (assertions
           "successfully left busy page"
           (dr/current-route app) => ["home"])))))

;; =============================================================================
;; Lifecycle Callback Tests
;; =============================================================================

#?(:clj
   (specification "will-leave lifecycle callback"
     (let [app (test-app)]
       (reset! will-leave-calls [])
       (start-and-process! app)

       (behavior "will-leave is called when leaving a route"
         ;; Navigate to tracking page
         (route-and-process! app ["tracking"])
         (assertions
           "at tracking page"
           (dr/current-route app) => ["tracking"]
           "will-leave not called yet"
           (count @will-leave-calls) => 0)

         ;; Navigate away - this should trigger will-leave
         (route-and-process! app ["home"])
         (assertions
           "left tracking page"
           (dr/current-route app) => ["home"]
           "will-leave was called"
           (count @will-leave-calls) => 1
           "will-leave received correct component"
           (:component (first @will-leave-calls)) => :tracking))

       (behavior "will-leave is NOT called when routing to same page"
         (reset! will-leave-calls [])
         ;; Navigate to tracking page
         (route-and-process! app ["tracking"])
         (assertions
           "at tracking page"
           (dr/current-route app) => ["tracking"])

         (let [call-count-before (count @will-leave-calls)]
           ;; Route to same page again
           (route-and-process! app ["tracking"])
           (assertions
             "still at tracking page"
             (dr/current-route app) => ["tracking"]
             "will-leave was NOT called for self-routing"
             (count @will-leave-calls) => call-count-before))))))

#?(:clj
   (specification "route-cancelled lifecycle callback"
     (let [app (test-app)]
       (reset! route-cancelled-calls [])
       (reset! deferred-ready-signal nil)
       (start-and-process! app)

       (behavior "route-cancelled is called when deferred route is abandoned"
         ;; Start navigating to deferred page (will be pending)
         (route-and-process! app ["deferred" "item-1"])
         (assertions
           "deferred route started"
           (fn? @deferred-ready-signal) => true
           "route-cancelled not called yet"
           (count @route-cancelled-calls) => 0)

         ;; Navigate elsewhere before completing - should trigger route-cancelled
         (route-and-process! app ["home"])

         (assertions
           "now at home"
           (dr/current-route app) => ["home"]
           "route-cancelled was called"
           (count @route-cancelled-calls) => 1
           "route-cancelled received params"
           (:params (first @route-cancelled-calls)) => {:deferred-id "item-1"})))))
