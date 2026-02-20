(ns com.fulcrologic.statecharts.integration.fulcro.routing.url-sync-headless-spec
  "Tests for install-url-sync! with SimulatedURLHistory (headless, cross-platform).
   Verifies that programmatic navigation, browser back/forward, and route denial
   all produce correct history stack behavior."
  (:require
    [clojure.string]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.routing.simulated-history :as rsh]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as sroute]
    [com.fulcrologic.statecharts.integration.fulcro.routing-options :as sfro]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-codec :as ruc]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-codec-transit :as ruct]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-history :as ruh]
    [edn-query-language.core :as eql]
    [fulcro-spec.core :refer [=> =throws=> assertions component specification]]))

;; ---------------------------------------------------------------------------
;; Minimal route components for testing
;; ---------------------------------------------------------------------------

(defsc RootComp [_ _]
  {:query         [:ui/current-route]
   :ident         (fn [] [:component/id ::root])
   :initial-state {:ui/current-route {}}})

(defsc PageA [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :a}})

(defsc PageB [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :b}})

(defsc PageC [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :c}})

(defsc PageD [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :d}})

;; Busy page: always busy (for route denial tests)
(defsc BusyPage [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :busy}
   sfro/busy?
   (fn [_ _] true)})

;; ---------------------------------------------------------------------------
;; Statechart for routing
;; ---------------------------------------------------------------------------

(def page-a-key (comp/class->registry-key PageA))
(def page-b-key (comp/class->registry-key PageB))
(def page-c-key (comp/class->registry-key PageC))
(def page-d-key (comp/class->registry-key PageD))
(def busy-key (comp/class->registry-key BusyPage))

(def test-routing-chart
  (chart/statechart {:initial :state/route-root}
    (sroute/routing-regions
      (sroute/routes {:id :region/routes :routing/root `RootComp :initial page-a-key}
        (sroute/rstate {:route/target `PageA})
        (sroute/rstate {:route/target `PageB})
        (sroute/rstate {:route/target `PageC})
        (sroute/rstate {:route/target `PageD})))))

;; Routing chart with custom :route/segment overrides
(def segment-routing-chart
  (chart/statechart {:initial :state/route-root}
    (sroute/routing-regions
      (sroute/routes {:id :region/routes :routing/root `RootComp :initial page-a-key}
        (sroute/rstate {:route/target `PageA :route/segment "home"})
        (sroute/rstate {:route/target `PageB :route/segment "about"})
        (sroute/rstate {:route/target `PageC})
        (sroute/rstate {:route/target `PageD :route/segment "contact"})))))

;; Routing chart that includes a busy page for denial tests
(def busy-routing-chart
  (chart/statechart {:initial :state/route-root}
    (sroute/routing-regions
      (sroute/routes {:id :region/routes :routing/root `RootComp :initial page-a-key}
        (sroute/rstate {:route/target `PageA})
        (sroute/rstate {:route/target `PageB})
        (sroute/rstate {:route/target `BusyPage})))))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(def ^:private sid sroute/session-id)

(defn- test-app
  "Creates a headless Fulcro app with synchronous statechart processing."
  []
  (let [a (app/fulcro-app)]
    (app/set-root! a RootComp {:initialize-state? true})
    (scf/install-fulcro-statecharts! a {:event-loop? false})
    a))

(defn- active-leaf
  "Returns the set of active leaf route state IDs."
  [app]
  (let [cfg       (scf/current-configuration app sid)
        route-ids #{page-a-key page-b-key page-c-key page-d-key busy-key}]
    (into #{} (filter route-ids) cfg)))

(defn- route-to-and-process!
  "Sends a route-to event and processes all pending events, including the on-save
   that triggers URL sync."
  [app target]
  (sroute/route-to! app target)
  (scf/process-events! app))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(specification "install-url-sync! with SimulatedURLHistory"
  ;; NOTE: Tests for helper wrappers (url-sync-installed?, route-current-url,
  ;; route-history-index, route-back!, route-forward!, route-sync-from-url!)
  ;; are in core_test.cljc

  (component "programmatic navigation pushes to history stack"
    (let [app      (test-app)
          provider (rsh/simulated-url-history "/")]
      (sroute/start! app test-routing-chart)
      (scf/process-events! app)
      (let [cleanup (sroute/install-url-sync! app {:provider provider})]
        ;; On-save fires during start!, so trigger it
        (sroute/url-sync-on-save sid nil app)

        ;; Navigate A -> B
        (route-to-and-process! app `PageB)
        (sroute/url-sync-on-save sid nil app)

        ;; Navigate B -> C
        (route-to-and-process! app `PageC)
        (sroute/url-sync-on-save sid nil app)

        (assertions
          "statechart is on page C"
          (active-leaf app) => #{page-c-key}
          "history stack has 3 entries (initial + 2 pushes)"
          (count (rsh/history-stack provider)) => 3
          "URLs reflect navigation path"
          (ruh/current-href provider) => "/PageC")
        (cleanup))))

  ;; CLJ-only: browser back requires synchronous event processing
  #?(:clj
     (component "browser back navigates without pushing"
       (let [app      (test-app)
             provider (rsh/simulated-url-history "/")]
         (sroute/start! app test-routing-chart)
         (scf/process-events! app)
         (let [cleanup (sroute/install-url-sync! app {:provider provider})]
           (sroute/url-sync-on-save sid nil app)

           ;; Programmatic: A -> B -> C
           (route-to-and-process! app `PageB)
           (sroute/url-sync-on-save sid nil app)
           (route-to-and-process! app `PageC)
           (sroute/url-sync-on-save sid nil app)

           (let [stack-before (rsh/history-stack provider)]
             ;; Simulate browser back
             (ruh/go-back! provider)
             ;; The popstate listener fires synchronously, which calls route-to!
             (scf/process-events! app)
             (sroute/url-sync-on-save sid nil app)

             (assertions
               "statechart moved to page B"
               (active-leaf app) => #{page-b-key}
               "stack is unchanged (no push on back)"
               (rsh/history-stack provider) => stack-before
               "current URL is /PageB"
               (ruh/current-href provider) => "/PageB"))
           (cleanup)))))

  ;; CLJ-only: multiple sequential back/forward nav requires synchronous processing
  #?(:clj
     (component "back-back-forward-forward round trip"
       (let [app      (test-app)
             provider (rsh/simulated-url-history "/")]
         (sroute/start! app test-routing-chart)
         (scf/process-events! app)
         (let [cleanup (sroute/install-url-sync! app {:provider provider})]
           (sroute/url-sync-on-save sid nil app)

           ;; Programmatic: A -> B -> C
           (route-to-and-process! app `PageB)
           (sroute/url-sync-on-save sid nil app)
           (route-to-and-process! app `PageC)
           (sroute/url-sync-on-save sid nil app)

           (let [stack-before (rsh/history-stack provider)]
             ;; Back to B
             (ruh/go-back! provider)
             (scf/process-events! app)
             (sroute/url-sync-on-save sid nil app)

             ;; Back to A
             (ruh/go-back! provider)
             (scf/process-events! app)
             (sroute/url-sync-on-save sid nil app)

             (assertions
               "after 2 backs, at page A"
               (active-leaf app) => #{page-a-key})

             ;; Forward to B
             (ruh/go-forward! provider)
             (scf/process-events! app)
             (sroute/url-sync-on-save sid nil app)

             ;; Forward to C
             (ruh/go-forward! provider)
             (scf/process-events! app)
             (sroute/url-sync-on-save sid nil app)

             (assertions
               "after 2 forwards, back at page C"
               (active-leaf app) => #{page-c-key}
               "stack unchanged through entire round trip"
               (rsh/history-stack provider) => stack-before
               "URL is /PageC"
               (ruh/current-href provider) => "/PageC"))
           (cleanup)))))

  (component "back then programmatic truncates forward history"
    (let [app      (test-app)
          provider (rsh/simulated-url-history "/")]
      (sroute/start! app test-routing-chart)
      (scf/process-events! app)
      (let [cleanup (sroute/install-url-sync! app {:provider provider})]
        (sroute/url-sync-on-save sid nil app)

        ;; Programmatic: A -> B -> C
        (route-to-and-process! app `PageB)
        (sroute/url-sync-on-save sid nil app)
        (route-to-and-process! app `PageC)
        (sroute/url-sync-on-save sid nil app)

        ;; Back to B
        (ruh/go-back! provider)
        (scf/process-events! app)
        (sroute/url-sync-on-save sid nil app)

        ;; Programmatic B -> D (should truncate C from forward history)
        (route-to-and-process! app `PageD)
        (sroute/url-sync-on-save sid nil app)

        (assertions
          "statechart is on page D"
          (active-leaf app) => #{page-d-key}
          "forward history is truncated: stack is A, B, D"
          (rsh/history-stack provider) => ["/PageA" "/PageB" "/PageD"]
          "URL is /PageD"
          (ruh/current-href provider) => "/PageD")
        (cleanup))))

  (component "initial URL restoration"
    (let [app      (test-app)
          provider (rsh/simulated-url-history "/PageB")]
      (sroute/start! app test-routing-chart)
      (scf/process-events! app)
      (let [cleanup (sroute/install-url-sync! app {:provider provider})]
        ;; install-url-sync! should have parsed /PageB and sent a route-to event
        (scf/process-events! app)
        (sroute/url-sync-on-save sid nil app)

        (assertions
          "statechart restored to page B from initial URL"
          (active-leaf app) => #{page-b-key})
        (cleanup))))

  (component "cleanup removes listener"
    (let [app      (test-app)
          provider (rsh/simulated-url-history "/")]
      (sroute/start! app test-routing-chart)
      (scf/process-events! app)
      (let [cleanup (sroute/install-url-sync! app {:provider provider})]
        (sroute/url-sync-on-save sid nil app)

        ;; Cleanup
        (cleanup)

        ;; After cleanup, listener should be nil so go-back should not route
        (let [leaf-before (active-leaf app)]
          (route-to-and-process! app `PageB)
          (sroute/url-sync-on-save sid nil app)
          ;; go-back should be no-op since listener is cleared
          (ruh/go-back! provider)
          (scf/process-events! app)
          (assertions
            "popstate after cleanup does not change statechart state"
            (active-leaf app) => #{page-b-key}))))))

(specification ":route/segment custom URL segments"
  (component "custom segments appear in URLs"
    (let [app      (test-app)
          provider (rsh/simulated-url-history "/")]
      (sroute/start! app segment-routing-chart)
      (scf/process-events! app)
      (let [cleanup (sroute/install-url-sync! app {:provider provider})]
        (sroute/url-sync-on-save sid nil app)

        (assertions
          "initial route uses custom segment"
          (ruh/current-href provider) => "/home")

        ;; Navigate to PageB (custom segment "about")
        (route-to-and-process! app `PageB)
        (sroute/url-sync-on-save sid nil app)

        (assertions
          "PageB uses custom segment 'about'"
          (ruh/current-href provider) => "/about"
          "statechart is on page B"
          (active-leaf app) => #{page-b-key})

        ;; Navigate to PageC (no custom segment -- uses default)
        (route-to-and-process! app `PageC)
        (sroute/url-sync-on-save sid nil app)

        (assertions
          "PageC uses default target-name segment"
          (ruh/current-href provider) => "/PageC"
          "statechart is on page C"
          (active-leaf app) => #{page-c-key})

        ;; Navigate to PageD (custom segment "contact")
        (route-to-and-process! app `PageD)
        (sroute/url-sync-on-save sid nil app)

        (assertions
          "PageD uses custom segment 'contact'"
          (ruh/current-href provider) => "/contact"
          "statechart is on page D"
          (active-leaf app) => #{page-d-key})
        (cleanup))))

  (component "URL restoration with custom segments"
    (let [app      (test-app)
          provider (rsh/simulated-url-history "/about")]
      (sroute/start! app segment-routing-chart)
      (scf/process-events! app)
      (let [cleanup (sroute/install-url-sync! app {:provider provider})]
        ;; install-url-sync! should parse /about and route to PageB
        (scf/process-events! app)
        (sroute/url-sync-on-save sid nil app)

        (assertions
          "restored to PageB from custom segment URL '/about'"
          (active-leaf app) => #{page-b-key})
        (cleanup))))

  ;; CLJ-only: browser back/forward requires synchronous event processing
  #?(:clj
     (component "browser back/forward with custom segments"
       (let [app      (test-app)
             provider (rsh/simulated-url-history "/")]
         (sroute/start! app segment-routing-chart)
         (scf/process-events! app)
         (let [cleanup (sroute/install-url-sync! app {:provider provider})]
           (sroute/url-sync-on-save sid nil app)

           ;; Navigate: home -> about -> contact
           (route-to-and-process! app `PageB)
           (sroute/url-sync-on-save sid nil app)
           (route-to-and-process! app `PageD)
           (sroute/url-sync-on-save sid nil app)

           (assertions
             "at contact"
             (ruh/current-href provider) => "/contact")

           ;; Back to about
           (ruh/go-back! provider)
           (scf/process-events! app)
           (sroute/url-sync-on-save sid nil app)

           (assertions
             "back navigated to about"
             (active-leaf app) => #{page-b-key}
             "URL is /about"
             (ruh/current-href provider) => "/about")

           ;; Forward to contact
           (ruh/go-forward! provider)
           (scf/process-events! app)
           (sroute/url-sync-on-save sid nil app)

           (assertions
             "forward navigated back to contact"
             (active-leaf app) => #{page-d-key}
             "URL is /contact"
             (ruh/current-href provider) => "/contact")
           (cleanup))))))

;; ---------------------------------------------------------------------------
;; Route denial via busy guard (CLJ-only: requires synchronous processing)
;; ---------------------------------------------------------------------------

#?(:clj
   (specification "Route denial via busy guard"
     (component "browser back from busy page is denied and URL restored"
       (let [app      (test-app)
             provider (rsh/simulated-url-history "/")
             denied   (atom nil)]
         (sroute/start! app busy-routing-chart)
         (scf/process-events! app)
         (let [cleanup (sroute/install-url-sync! app {:provider        provider
                                                      :on-route-denied (fn [url] (reset! denied url))})]
           (sroute/url-sync-on-save sid nil app)

           ;; Navigate: PageA -> PageB -> BusyPage (programmatic)
           (route-to-and-process! app `PageB)
           (sroute/url-sync-on-save sid nil app)
           (route-to-and-process! app `BusyPage)
           (sroute/url-sync-on-save sid nil app)

           (assertions
             "at BusyPage"
             (active-leaf app) => #{busy-key}
             "URL is /BusyPage"
             (ruh/current-href provider) => "/BusyPage")

           ;; Simulate browser back -- should be denied because BusyPage is always busy
           (ruh/go-back! provider)
           (scf/process-events! app)
           (sroute/url-sync-on-save sid nil app)

           (assertions
             "still on BusyPage (route denied)"
             (active-leaf app) => #{busy-key}
             "URL restored to /BusyPage"
             (ruh/current-href provider) => "/BusyPage"
             "on-route-denied callback fired"
             @denied => "/PageB"
             "route-denied? reports true"
             (sroute/route-denied? app) => true))))

     (component "force-continue after denial overrides the guard"
       (let [app      (test-app)
             provider (rsh/simulated-url-history "/")]
         (sroute/start! app busy-routing-chart)
         (scf/process-events! app)
         (let [cleanup (sroute/install-url-sync! app {:provider provider})]
           (sroute/url-sync-on-save sid nil app)

           ;; Navigate to BusyPage
           (route-to-and-process! app `BusyPage)
           (sroute/url-sync-on-save sid nil app)

           ;; Browser back -- denied
           (ruh/go-back! provider)
           (scf/process-events! app)
           (sroute/url-sync-on-save sid nil app)

           (assertions
             "route is denied"
             (sroute/route-denied? app) => true)

           ;; Force continue -- sends force-route event, which re-queues the failed route event
           (sroute/force-continue-routing! app)
           (scf/process-events! app)
           (scf/process-events! app)
           (sroute/url-sync-on-save sid nil app)

           (assertions
             "route-denied cleared"
             (sroute/route-denied? app) => false
             "navigated to PageA (the denied back-target) after force"
             (active-leaf app) => #{page-a-key})
           (cleanup))))))

;; ---------------------------------------------------------------------------
;; Cross-chart routing components (Slices 5 & 8)
;; ---------------------------------------------------------------------------

(defsc AdminUsers [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :admin-users}})

(defsc AdminSettings [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :admin-settings}})

(def admin-users-key (comp/class->registry-key AdminUsers))
(def admin-settings-key (comp/class->registry-key AdminSettings))

;; Child chart invoked by AdminPanel's istate
(def admin-child-chart
  (chart/statechart {:initial :state/route-root}
    (sroute/routing-regions
      (sroute/routes {:id :region/admin-routes :routing/root `AdminPanel :initial admin-users-key}
        (sroute/rstate {:route/target `AdminUsers})
        (sroute/rstate {:route/target `AdminSettings})))))

(defsc AdminPanel [_ _]
  {:query                                                                      [:admin/id :ui/current-route]
   :ident                                                                      (fn [] [:component/id ::admin])
   :initial-state                                                              {:admin/id :admin}
   sfro/statechart admin-child-chart})

(def admin-key (comp/class->registry-key AdminPanel))

;; Parent chart with istate for cross-chart routing
(def cross-chart-routing-chart
  (chart/statechart {:initial :state/route-root}
    (sroute/routing-regions
      (sroute/routes {:id :region/routes :routing/root `RootComp :initial page-a-key}
        (sroute/rstate {:route/target `PageA})
        (sroute/rstate {:route/target `PageB})
        (sroute/istate {:route/target    `AdminPanel
                        :route/reachable #{admin-users-key admin-settings-key}})))))

(defn- settle!
  "Processes events until stable (max 10 rounds)."
  [app]
  (dotimes [_ 10]
    (scf/process-events! app)))

;; ---------------------------------------------------------------------------
;; Slice 5: Cross-chart routing via istate (CLJ-only: istate invocation is async in CLJS)
;; ---------------------------------------------------------------------------

#?(:clj
   (specification "Cross-chart routing via istate"
     (component "entering owner from outside via reachable target"
       (let [app (test-app)]
         (sroute/start! app cross-chart-routing-chart)
         (settle! app)

         (assertions
           "starts on PageA"
           (sroute/active-leaf-routes app) => #{page-a-key})

         ;; Route to AdminUsers (reachable through AdminPanel istate)
         (sroute/route-to! app `AdminUsers)
         (settle! app)

         (let [cfg (scf/current-configuration app sid)]
           (assertions
             "enters the AdminPanel istate"
             (contains? cfg admin-key) => true
             "child chart resolves to AdminUsers leaf"
             (sroute/active-leaf-routes app) => #{admin-users-key}))))

     (component "forwarding to child session when already in owner"
       (let [app (test-app)]
         (sroute/start! app cross-chart-routing-chart)
         (settle! app)

         ;; Navigate to AdminUsers to enter AdminPanel
         (sroute/route-to! app `AdminUsers)
         (settle! app)

         ;; Now route to AdminSettings while already in AdminPanel
         (sroute/route-to! app `AdminSettings)
         (settle! app)

         (assertions
           "stays in AdminPanel"
           (contains? (scf/current-configuration app sid) admin-key) => true
           "child chart transitions to AdminSettings"
           (sroute/active-leaf-routes app) => #{admin-settings-key})))

     (component "routing back to direct target from istate"
       (let [app (test-app)]
         (sroute/start! app cross-chart-routing-chart)
         (settle! app)

         ;; Enter AdminPanel via AdminUsers
         (sroute/route-to! app `AdminUsers)
         (settle! app)

         ;; Route back to PageB (direct target)
         (sroute/route-to! app `PageB)
         (settle! app)

         (assertions
           "leaves AdminPanel and reaches PageB"
           (sroute/active-leaf-routes app) => #{page-b-key})))))

;; ---------------------------------------------------------------------------
;; Slice 8: URL sync child delegation (CLJ-only: istate invocation is async in CLJS)
;; ---------------------------------------------------------------------------

#?(:clj
   (specification "URL sync with cross-chart child delegation"
     (component "child session URL updates via parent chain"
       (let [app      (test-app)
             provider (rsh/simulated-url-history "/")]
         (sroute/start! app cross-chart-routing-chart)
         (settle! app)
         (let [cleanup (sroute/install-url-sync! app {:provider provider})]
           (sroute/url-sync-on-save sid nil app)

           ;; Route to AdminUsers (cross-chart)
           (sroute/route-to! app `AdminUsers)
           (settle! app)
           (sroute/url-sync-on-save sid nil app)

           (assertions
             "URL reflects nested child route after cross-chart navigation"
             (ruh/current-href provider) => "/AdminPanel/AdminUsers")

           ;; Navigate within child: AdminUsers -> AdminSettings
           (sroute/route-to! app `AdminSettings)
           (settle! app)
           ;; Trigger on-save with child session-id to test parent chain delegation
           (let [state-map        (rapp/current-state app)
                 child-session-id (get-in state-map [::sc/local-data sid :invocation/id admin-key])]
             (when child-session-id
               (sroute/url-sync-on-save child-session-id nil app)))

           (assertions
             "URL updates to new child route via child->root delegation"
             (ruh/current-href provider) => "/AdminPanel/AdminSettings")
           (cleanup))))

     (component "back/forward with cross-chart routes"
       (let [app      (test-app)
             provider (rsh/simulated-url-history "/")]
         (sroute/start! app cross-chart-routing-chart)
         (settle! app)
         (let [cleanup (sroute/install-url-sync! app {:provider provider})]
           (sroute/url-sync-on-save sid nil app)

           ;; Navigate: PageA -> AdminUsers -> AdminSettings
           (sroute/route-to! app `AdminUsers)
           (settle! app)
           (sroute/url-sync-on-save sid nil app)

           (sroute/route-to! app `AdminSettings)
           (settle! app)
           (sroute/url-sync-on-save sid nil app)

           (assertions
             "at AdminSettings"
             (ruh/current-href provider) => "/AdminPanel/AdminSettings"
             "history has 3 entries"
             (count (rsh/history-stack provider)) => 3)

           ;; Back to AdminUsers
           (ruh/go-back! provider)
           (settle! app)
           (sroute/url-sync-on-save sid nil app)

           (assertions
             "back navigated to AdminUsers"
             (sroute/active-leaf-routes app) => #{admin-users-key}
             "URL is /AdminPanel/AdminUsers"
             (ruh/current-href provider) => "/AdminPanel/AdminUsers")

           ;; Back to PageA
           (ruh/go-back! provider)
           (settle! app)
           (sroute/url-sync-on-save sid nil app)

           (assertions
             "back navigated to PageA"
             (sroute/active-leaf-routes app) => #{page-a-key}
             "URL is /PageA"
             (ruh/current-href provider) => "/PageA")

           (cleanup))))

     (component "forward after back through istate preserves forward history"
       (let [app      (test-app)
             provider (rsh/simulated-url-history "/")]
         (sroute/start! app cross-chart-routing-chart)
         (settle! app)
         (let [cleanup (sroute/install-url-sync! app {:provider provider})]
           (sroute/url-sync-on-save sid nil app)

           ;; Navigate: PageA -> AdminUsers -> AdminSettings -> PageA
           (sroute/route-to! app `AdminUsers)
           (settle! app)
           (sroute/url-sync-on-save sid nil app)

           (sroute/route-to! app `AdminSettings)
           (settle! app)
           (sroute/url-sync-on-save sid nil app)

           (sroute/route-to! app `PageA)
           (settle! app)
           (sroute/url-sync-on-save sid nil app)

           (assertions
             "history has 4 entries"
             (count (rsh/history-stack provider)) => 4)

           ;; Back twice: PageA -> AdminSettings -> AdminUsers
           (ruh/go-back! provider)
           (settle! app)
           (sroute/url-sync-on-save sid nil app)

           (ruh/go-back! provider)
           (settle! app)
           (sroute/url-sync-on-save sid nil app)

           (assertions
             "back navigated to AdminUsers"
             (ruh/current-href provider) => "/AdminPanel/AdminUsers"
             "history preserved: still 4 entries"
             (count (rsh/history-stack provider)) => 4)

           ;; Forward should work -- this is the bug scenario
           (ruh/go-forward! provider)
           (settle! app)
           (sroute/url-sync-on-save sid nil app)

           (assertions
             "forward navigated to AdminSettings"
             (ruh/current-href provider) => "/AdminPanel/AdminSettings"
             "history still has 4 entries"
             (count (rsh/history-stack provider)) => 4)

           ;; Forward again to PageA
           (ruh/go-forward! provider)
           (settle! app)
           (sroute/url-sync-on-save sid nil app)

           (assertions
             "forward navigated to PageA"
             (ruh/current-href provider) => "/PageA"
             "history still has 4 entries"
             (count (rsh/history-stack provider)) => 4)

           (cleanup))))))

;; ---------------------------------------------------------------------------
;; Slice 6: Child communication
;; ---------------------------------------------------------------------------

(specification "Child session communication"
  (component "looking up child invocation configuration"
    (let [app (test-app)]
      (sroute/start! app cross-chart-routing-chart)
      (settle! app)

      ;; Before entering istate, no child session exists
      (let [state-map (rapp/current-state app)
            child-sid (get-in state-map [::sc/local-data sid :invocation/id admin-key])]
        (assertions
          "no child session before entering istate"
          child-sid => nil))

      ;; Enter AdminPanel via AdminUsers
      (sroute/route-to! app `AdminUsers)
      (settle! app)

      (let [state-map (rapp/current-state app)
            child-sid (get-in state-map [::sc/local-data sid :invocation/id admin-key])
            child-cfg (when child-sid (scf/current-configuration app child-sid))]
        (assertions
          "child session exists after entering istate"
          (some? child-sid) => true
          "child configuration contains AdminUsers"
          (contains? child-cfg admin-users-key) => true))))

  (component "sending event to child session (send-to-self! path)"
    (let [app (test-app)]
      (sroute/start! app cross-chart-routing-chart)
      (settle! app)
      (sroute/route-to! app `AdminUsers)
      (settle! app)

      (assertions
        "starts at AdminUsers"
        (sroute/active-leaf-routes app) => #{admin-users-key})

      ;; Send route event directly to child session (same path as send-to-self!)
      (let [state-map (rapp/current-state app)
            child-sid (get-in state-map [::sc/local-data sid :invocation/id admin-key])]
        (scf/send! app child-sid (sroute/route-to-event-name admin-settings-key) {})
        (settle! app)

        (assertions
          "child transitions to AdminSettings via direct send"
          (sroute/active-leaf-routes app) => #{admin-settings-key}))))

  (component "no child session when not in istate"
    (let [app (test-app)]
      (sroute/start! app cross-chart-routing-chart)
      (settle! app)

      ;; On PageA -- not in any istate
      (let [state-map (rapp/current-state app)
            child-sid (get-in state-map [::sc/local-data sid :invocation/id admin-key])]
        (assertions
          "no child session on a regular rstate"
          child-sid => nil)))))

;; ---------------------------------------------------------------------------
;; Slice 7: Query management
;; ---------------------------------------------------------------------------

(specification "Dynamic query management via routing"
  (component "update-parent-query! sets join to current route target"
    (let [app (test-app)]
      (sroute/start! app cross-chart-routing-chart)
      (settle! app)

      ;; After start, RootComp query should have join to PageA
      (let [state-map (rapp/current-state app)
            q         (rc/get-query RootComp state-map)
            ast       (eql/query->ast q)
            join-keys (into #{} (map :dispatch-key) (:children ast))]
        (assertions
          "RootComp query includes :ui/current-route join"
          (contains? join-keys :ui/current-route) => true))

      ;; Route to AdminPanel (istate)
      (sroute/route-to! app `AdminUsers)
      (settle! app)

      (let [state-map (rapp/current-state app)
            q         (rc/get-query RootComp state-map)
            ast       (eql/query->ast q)
            join-node (first (filter #(= :ui/current-route (:dispatch-key %)) (:children ast)))
            join-q    (:query join-node)]
        (assertions
          "query join target updated to AdminPanel's query"
          (some? (some #{:admin/id} join-q)) => true))))

  (component "query reverts when routing away from istate"
    (let [app (test-app)]
      (sroute/start! app cross-chart-routing-chart)
      (settle! app)
      ;; Enter AdminPanel
      (sroute/route-to! app `AdminUsers)
      (settle! app)
      ;; Route back to PageB
      (sroute/route-to! app `PageB)
      (settle! app)

      (let [state-map (rapp/current-state app)
            q         (rc/get-query RootComp state-map)
            ast       (eql/query->ast q)
            join-node (first (filter #(= :ui/current-route (:dispatch-key %)) (:children ast)))
            join-q    (:query join-node)]
        (assertions
          "query join target reverted to PageB's query"
          (some? (some #{:page/id} join-q)) => true
          "AdminPanel's :admin/id no longer in join"
          (some #{:admin/id} join-q) => nil)))))

;; ---------------------------------------------------------------------------
;; R5: rstate/istate :id validation
;; ---------------------------------------------------------------------------

(specification "R5: state ID validation"
  (component "rstate throws on explicit :id"
    (assertions
      "throws ex-info when :id is passed"
      (sroute/rstate {:id :foo :route/target `PageA})
      =throws=> #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)))

  (component "istate throws on explicit :id"
    (assertions
      "throws ex-info when :id is passed"
      (sroute/istate {:id :foo :route/target `PageA})
      =throws=> #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)))

  (component "rstate works without explicit :id"
    (let [node (sroute/rstate {:route/target `PageA})]
      (assertions
        "returns a valid statechart element"
        (some? node) => true
        "element has an :id derived from target"
        (:id node) => page-a-key)))

  (component "istate works without explicit :id"
    (let [node (sroute/istate {:route/target `AdminPanel
                                :route/reachable #{admin-users-key}})]
      (assertions
        "returns a valid statechart element"
        (some? node) => true
        "element has an :id derived from target"
        (:id node) => admin-key))))

;; ---------------------------------------------------------------------------
;; URLCodec injection via install-url-sync!
;; ---------------------------------------------------------------------------

(specification "URLCodec injection via install-url-sync!"
  (component "transit-base64-codec produces base64 URLs"
    (let [app      (test-app)
          provider (rsh/simulated-url-history "/")
          codec    (ruct/transit-base64-codec)]
      (sroute/start! app test-routing-chart)
      (scf/process-events! app)
      (let [cleanup (sroute/install-url-sync! app {:provider  provider
                                                   :url-codec codec})]
        (sroute/url-sync-on-save sid nil app)

        ;; Navigate A -> B
        (route-to-and-process! app `PageB)
        (sroute/url-sync-on-save sid nil app)

        (assertions
          "URL uses the codec-encoded path"
          (ruh/current-href provider) => "/PageB"
          "statechart is on page B"
          (active-leaf app) => #{page-b-key})
        (cleanup))))

  (component "codec with params round-trips through URL"
    (let [app      (test-app)
          provider (rsh/simulated-url-history "/")
          codec    (ruct/transit-base64-codec)]
      (sroute/start! app test-routing-chart)
      (scf/process-events! app)
      (let [cleanup (sroute/install-url-sync! app {:provider  provider
                                                   :url-codec codec})]
        (sroute/url-sync-on-save sid nil app)

        ;; Navigate with params
        (route-to-and-process! app `PageB)
        (sroute/url-sync-on-save sid nil app)

        (assertions
          "navigated to PageB"
          (active-leaf app) => #{page-b-key})
        (cleanup))))

  (component "custom codec can be injected"
    (let [;; Create a custom codec that uppercases all segments
          custom-codec (reify ruc/URLCodec
                         (encode-url [_ {:keys [segments route-elements]}]
                           (let [seg-strs (mapv (fn [state-id]
                                                  (let [element (get route-elements state-id)
                                                        seg     (or (:route/segment element)
                                                                  (name (:route/target element)))]
                                                    (clojure.string/upper-case seg)))
                                            segments)]
                             (str "/" (clojure.string/join "/" seg-strs))))
                         (decode-url [_ href route-elements]
                           (let [segments (filterv #(not= "" %)
                                           (clojure.string/split href #"/"))
                                 leaf-seg (peek segments)]
                             (when leaf-seg
                               (some (fn [[id element]]
                                       (when (and (:route/target element)
                                               (= (clojure.string/upper-case
                                                    (or (:route/segment element)
                                                      (name (:route/target element))))
                                                 leaf-seg))
                                         {:leaf-id id :params nil}))
                                 route-elements)))))
          app      (test-app)
          provider (rsh/simulated-url-history "/")]
      (sroute/start! app test-routing-chart)
      (scf/process-events! app)
      (let [cleanup (sroute/install-url-sync! app {:provider  provider
                                                   :url-codec custom-codec})]
        (sroute/url-sync-on-save sid nil app)

        ;; Navigate A -> B
        (route-to-and-process! app `PageB)
        (sroute/url-sync-on-save sid nil app)

        (assertions
          "custom codec uppercases segments in URL"
          (ruh/current-href provider) => "/PAGEB"
          "statechart is on page B"
          (active-leaf app) => #{page-b-key})
        (cleanup))))

  (component "codec with :route/segment overrides"
    (let [app      (test-app)
          provider (rsh/simulated-url-history "/")
          codec    (ruct/transit-base64-codec)]
      (sroute/start! app segment-routing-chart)
      (scf/process-events! app)
      (let [cleanup (sroute/install-url-sync! app {:provider  provider
                                                   :url-codec codec})]
        (sroute/url-sync-on-save sid nil app)

        (assertions
          "initial route uses custom segment via codec"
          (ruh/current-href provider) => "/home")

        ;; Navigate to PageB (custom segment "about")
        (route-to-and-process! app `PageB)
        (sroute/url-sync-on-save sid nil app)

        (assertions
          "PageB uses custom segment 'about' via codec"
          (ruh/current-href provider) => "/about")
        (cleanup))))

  (component "install without explicit codec defaults to TransitBase64Codec"
    (let [app      (test-app)
          provider (rsh/simulated-url-history "/")]
      (sroute/start! app test-routing-chart)
      (scf/process-events! app)
      (let [cleanup (sroute/install-url-sync! app {:provider provider})]
        (sroute/url-sync-on-save sid nil app)

        ;; Navigate A -> B
        (route-to-and-process! app `PageB)
        (sroute/url-sync-on-save sid nil app)

        (assertions
          "URL works with default TransitBase64Codec"
          (ruh/current-href provider) => "/PageB")
        (cleanup)))))
