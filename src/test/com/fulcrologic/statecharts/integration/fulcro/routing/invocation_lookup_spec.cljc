(ns com.fulcrologic.statecharts.integration.fulcro.routing.invocation-lookup-spec
  "How routing finds the invoked sessions under a route: `send-to-self!`/`current-invocation-configuration` (the
   nearest running invocation of a component or its ancestors) and the busy guard (every child invocation of an
   active state)."
  (:require
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [invoke script state transition]]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]
    [com.fulcrologic.statecharts.integration.fulcro.routing-options :as sfro]
    [com.fulcrologic.statecharts.integration.fulcro.routing.headless-app :as h]
    [fulcro-spec.core :refer [=> assertions specification]]))

(defn- pinged-chart
  "A chart that records `:pinged` when it gets `:event/ping`."
  []
  (chart/statechart {:initial :state/idle}
    (state {:id :state/idle}
      (transition {:event :event/ping}
        (script {:expr (fn [& _] [(ops/assign :pinged true)])})))))

(defsc Root [_ _]
  {:query         [:ui/current-route]
   :ident         (fn [] [:component/id ::Root])
   :initial-state {:ui/current-route {}}})

(defsc Landing [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :landing}})

(defsc Panel [_ _]
  {:query          [:panel/id]
   :ident          :panel/id
   :initial-state  {:panel/id 1}
   sfro/statechart (pinged-chart)})

(defsc Host [_ _]
  {:query          [:host/id {:host/panel (comp/get-query Panel)}]
   :ident          (fn [] [:component/id ::Host])
   :initial-state  {:host/id 1}
   sfro/statechart (pinged-chart)})

(def routing-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/routes :routing/root `Root}
        (scr/rstate {:route/target `Landing})
        (scr/istate {:route/target `Panel})
        (scr/istate {:route/target `Host})))))

#?(:clj
   (defn- instance
     "A CLJ instance of `Class` on its props at `ident`, rendered under the instance `parent` (or none)."
     [a Class ident parent]
     (binding [comp/*app* a comp/*parent* parent]
       ((comp/factory Class) (fns/ui->props (app/current-state a) Class ident)))))

#?(:clj
   (specification "send-to-self! from a component whose own route ended"
     (let [{a :app} (h/routed-app Root routing-chart "/" {:url-sync? false})]
       (scr/route-to! a `Panel {})
       (let [stale (h/child-session-id a ::Panel)]
         (scr/route-to! a `Host {})
         (let [host  (instance a Host [:component/id ::Host] nil)
               panel (instance a Panel [:panel/id 1] host)]
           (scr/send-to-self! panel :event/ping)
           (assertions
             "(the routing session still has the ended route's invocation id)"
             (h/child-session-id a ::Panel) => stale
             (scf/current-configuration a stale) => #{}
             "reaches the running invocation of an ancestor (the host)"
             (:pinged (h/local-data a (h/child-session-id a ::Host))) => true
             "and current-invocation-configuration reads that session"
             (scr/current-invocation-configuration panel) => #{:state/idle}))))))

#?(:clj
   (specification "send-to-self! from a component whose own route is active"
     (let [{a :app} (h/routed-app Root routing-chart "/" {:url-sync? false})]
       (scr/route-to! a `Panel {})
       (scr/send-to-self! (instance a Panel [:panel/id 1] nil) :event/ping)
       (assertions
         "reaches its own invocation"
         (:pinged (h/local-data a (h/child-session-id a ::Panel))) => true))))

;; ===== The busy guard and non-routed child invocations =====

(defsc Editor [_ _]
  {:query     [:editor/id :editor/busy?]
   :ident     :editor/id
   sfro/busy? (fn [_app props] (boolean (:editor/busy? props)))})

(defsc Item [_ _]
  {:query       [:item/id :item/name fs/form-config-join]
   :ident       :item/id
   :form-fields #{:item/name}})

(def editor-chart
  "A non-routed chart that edits its actors (it has no route states)."
  (chart/statechart {:initial :state/editing}
    (state {:id :state/editing})))

(def host-chart
  "A host's chart: its state invokes `editor-chart` for an Editor and an Item (no id location, generated id)."
  (chart/statechart {:initial :state/open}
    (state {:id :state/open}
      (invoke {:type   :statechart
               :src    ::editor-chart
               :params {:fulcro/actors (fn [& _] {:actor/editor (scf/actor Editor [:editor/id 1])
                                                  :actor/item   (scf/actor Item [:item/id 1])})}}))))

(defsc EditHost [_ _]
  {:query          [:host/id]
   :ident          (fn [] [:component/id ::EditHost])
   :initial-state  {:host/id 2}
   sfro/statechart host-chart})

(def busy-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/routes :routing/root `Root}
        (scr/rstate {:route/target `Landing})
        (scr/istate {:route/target `EditHost})))))

(defn- edit-app
  "A routed app on `busy-chart`, routed to EditHost, with Editor 1 and a pristine Item 1 in app state."
  []
  (let [{a :app :as env} (h/routed-app Root busy-chart "/" {:url-sync? false})]
    (scf/register-statechart! a ::editor-chart editor-chart)
    (swap! (::app/state-atom a) (fn [s] (-> s
                                          (assoc-in [:editor/id 1] {:editor/id 1 :editor/busy? false})
                                          (assoc-in [:item/id 1] {:item/id 1 :item/name "a"})
                                          (fs/add-form-config* Item [:item/id 1]))))
    (scr/route-to! a `EditHost {})
    env))

(defn- on-host? [a] (contains? (scf/current-configuration a scr/session-id) ::EditHost))

(specification "The busy guard and a route's non-routed child invocations"
  (let [{a :app} (edit-app)]
    (assertions
      "(the host's chart invoked the editor chart)"
      (count (filter #(= (h/child-session-id a ::EditHost) (:com.fulcrologic.statecharts/parent-session-id %))
               (vals (:com.fulcrologic.statecharts/session-id (app/current-state a))))) => 1)
    (scr/route-to! a `Landing {})
    (assertions
      "allow route-away when none of their actors is busy"
      (on-host? a) => false))
  (let [{a :app} (edit-app)]
    (swap! (::app/state-atom a) assoc-in [:editor/id 1 :editor/busy?] true)
    (scr/route-to! a `Landing {})
    (assertions
      "deny it when an actor's sfro/busy? is true"
      (on-host? a) => true
      (scr/route-denied? a) => true))
  (let [{a :app} (edit-app)]
    (swap! (::app/state-atom a) assoc-in [:item/id 1 :item/name] "changed")
    (scr/route-to! a `Landing {})
    (assertions
      "deny it when an actor is a dirty form"
      (on-host? a) => true)))

(defsc ModPageA [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :mod-a}})

(defsc ModPageB [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :mod-b}})

(def busy-module-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/busy-module-routes :routing/root `BusyModule}
        (scr/rstate {:route/target `ModPageA})
        (scr/rstate {:route/target `ModPageB})))))

(defsc BusyModule [_ _]
  {:query          [:module/busy?]
   :ident          (fn [] [:component/id ::BusyModule])
   :initial-state  {:module/busy? true}
   sfro/busy?      (fn [_app props] (boolean (:module/busy? props)))
   sfro/statechart busy-module-chart})

(def module-routing-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/routes :routing/root `Root}
        (scr/rstate {:route/target `Landing})
        (scr/istate {:route/target `BusyModule})))))

(specification "A route change inside a module whose own component is busy"
  (let [{a :app} (h/routed-app Root module-routing-chart "/" {:url-sync? false})
        _   (scr/route-to! a `BusyModule {})
        sid (h/child-session-id a ::BusyModule)]
    (scf/send! a sid (scr/route-to-event-name ::ModPageB) {})
    (assertions
      "is allowed: the module's session doesn't check its own actors (they stay)"
      (contains? (scf/current-configuration a sid) ::ModPageB) => true)
    (scr/route-to! a `Landing {})
    (assertions
      "while leaving the module is denied (its component is a busy route target)"
      (contains? (scf/current-configuration a scr/session-id) ::BusyModule) => true)))


(specification "A route change inside a module whose own component is busy, routed from the root"
  (let [{a :app} (h/routed-app Root module-routing-chart "/" {:url-sync? false})
        _   (scr/route-to! a `BusyModule {})
        sid (h/child-session-id a ::BusyModule)]
    (scr/route-to! a `ModPageB {})
    (assertions
      "is allowed: the module's state stays active, so its component isn't checked"
      (contains? (scf/current-configuration a sid) ::ModPageB) => true
      (scr/route-denied? a) => false)))

;; ===== Entering an istate from a busy route; busy? as a non-route event's cond =====

(defsc DirtyPage [_ _]
  {:query         [:page/id :page/dirty?]
   :ident         :page/id
   :initial-state {:page/id :dirty-page :page/dirty? true}
   sfro/busy?     (fn [_app props] (boolean (:page/dirty? props)))})

(defsc CalmA [_ _] {:query [:page/id] :ident :page/id :initial-state {:page/id :calm-a}})
(defsc CalmB [_ _] {:query [:page/id] :ident :page/id :initial-state {:page/id :calm-b}})

(def calm-module-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/calm-module-routes :routing/root `CalmModule}
        (scr/rstate {:route/target `CalmA})
        (scr/rstate {:route/target `CalmB})))))

(defsc CalmModule [_ _]
  {:query          [:ui/current-route]
   :ident          (fn [] [:component/id ::CalmModule])
   :initial-state  {}
   sfro/statechart calm-module-chart})

(def dirty-page-chart
  "Routes to a busy `DirtyPage` and to an istate `CalmModule` (not busy). `:event/check` (not a route event) is taken
   only when `scr/busy?` is true."
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/dirty-routes :routing/root `Root}
        (transition {:event :event/check :cond scr/busy?}
          (script {:expr (fn [& _] [(ops/assign :busy-seen? true)])}))
        (scr/rstate {:route/target `Landing})
        (scr/rstate {:route/target `DirtyPage})
        (scr/istate {:route/target `CalmModule :route/reachable #{::CalmA ::CalmB}})))))

(specification "A route from a busy route to a reachable target of an istate that isn't active"
  (let [{a :app} (h/routed-app Root dirty-page-chart "/" {:url-sync? false})]
    (scr/route-to! a `DirtyPage {})
    (scr/route-to! a `CalmB {})
    (assertions
      "is denied: entering the istate exits the busy route"
      (contains? (scf/current-configuration a scr/session-id) ::DirtyPage) => true
      (contains? (scf/current-configuration a scr/session-id) ::CalmModule) => false
      (scr/route-denied? a) => true)))

(specification "busy? as the cond of a transition on an event that isn't a route event"
  (let [{a :app} (h/routed-app Root dirty-page-chart "/" {:url-sync? false})
        busy-seen? (fn [] (:busy-seen? (h/local-data a)))]
    (scf/send! a scr/session-id :event/check {})
    (assertions
      "is false while nothing active is busy"
      (busy-seen?) => nil)
    (scr/route-to! a `DirtyPage {})
    (scf/send! a scr/session-id :event/check {})
    (assertions
      "and true once an active route is (it checks everything active, not what a route change would exit)"
      (busy-seen?) => true)))

;; ===== The busy guard checks only what a route change exits =====

(defsc TabA [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :tab-a}})

(defsc TabB [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :tab-b}})

(defsc TabC [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :tab-c}})

(defn- editor-of
  "An invoke of `editor-chart` whose actor is the Item `item-id`."
  [item-id]
  (invoke {:type   :statechart
           :src    ::editor-chart
           :params {:fulcro/actors (fn [& _] {:actor/item (scf/actor Item [:item/id item-id])})}}))

(def tab-host-chart
  "A host whose top state invokes an editor of Item 1 beside its routes (it stays while the tabs change), and whose
   TabB state invokes an editor of Item 2."
  (chart/statechart {:initial :state/tab-host}
    (state {:id :state/tab-host}
      (editor-of 1)
      (scr/routing-regions
        (scr/routes {:id :region/tab-routes :routing/root `TabHost}
          (scr/rstate {:route/target `TabA})
          (scr/rstate {:route/target `TabB} (editor-of 2))
          (scr/rstate {:route/target `TabC}))))))

(defsc TabHost [_ _]
  {:query          [:ui/current-route]
   :ident          (fn [] [:component/id ::TabHost])
   :initial-state  {}
   sfro/statechart tab-host-chart})

(defsc NodeTabA [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :node-tab-a}})

(defsc NodeTabB [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :node-tab-b}})

(def routes-node-host-chart
  "A host whose routes node itself invokes an editor of Item 1 (route changes re-enter the routes node, so they restart
   it)."
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/node-host-routes :routing/root `RoutesNodeHost}
        (editor-of 1)
        (scr/rstate {:route/target `NodeTabA})
        (scr/rstate {:route/target `NodeTabB})))))

(defsc RoutesNodeHost [_ _]
  {:query          [:ui/current-route]
   :ident          (fn [] [:component/id ::RoutesNodeHost])
   :initial-state  {}
   sfro/statechart routes-node-host-chart})

(def tab-routing-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/routes :routing/root `Root}
        (scr/rstate {:route/target `Landing})
        (scr/istate {:route/target `TabHost})
        (scr/istate {:route/target `RoutesNodeHost})))))

(def beside-routes-chart
  "A routing chart whose top state invokes, beside its routes, an editor of Item 3 (generated id) and one of Item 2
   (explicit id)."
  (chart/statechart {:initial :state/app}
    (state {:id :state/app}
      (editor-of 3)
      (invoke {:id     :editor/explicit
               :type   :statechart
               :src    ::editor-chart
               :params {:fulcro/actors (fn [& _] {:actor/item (scf/actor Item [:item/id 2])})}})
      (scr/routing-regions
        (scr/routes {:id :region/routes :routing/root `Root}
          (scr/rstate {:route/target `Landing})
          (scr/rstate {:route/target `TabA})
          (scr/istate {:route/target `RoutesNodeHost}))))))

(defn- items-app
  "Returns `{:app a}`: an app routing with `chart` (no URL sync), with the editor chart registered and Items 1-3 as
   clean forms before it starts."
  [chart]
  (let [a (h/new-app Root)]
    (scf/register-statechart! a ::editor-chart editor-chart)
    (swap! (::app/state-atom a)
      (fn [s] (reduce (fn [s id] (-> s (assoc-in [:item/id id] {:item/id id :item/name "a"}) (fs/add-form-config* Item [:item/id id])))
                s [1 2 3])))
    (scr/start! a chart)
    {:app a}))

(defn- edit-item! [a id] (swap! (::app/state-atom a) assoc-in [:item/id id :item/name] "edited"))

(defn- in? [a session-id state-id] (contains? (scf/current-configuration a session-id) state-id))

(specification "The busy guard and a dirty editor beside a host's routes"
  (let [{a :app} (items-app tab-routing-chart)]
    (scr/route-to! a `TabA {})
    (edit-item! a 1)
    (scr/route-to! a `TabC {})
    (assertions
      "allows a route change between the host's tabs: the state that invoked the editor stays active"
      (in? a (h/child-session-id a ::TabHost) ::TabC) => true
      (scr/route-denied? a) => false)
    (scr/route-to! a `Landing {})
    (assertions
      "denies leaving the host, which exits the editor"
      (in? a scr/session-id ::TabHost) => true
      (scr/route-denied? a) => true)))

(specification "The busy guard and a dirty editor that a host's tab invokes"
  (let [{a :app} (items-app tab-routing-chart)]
    (scr/route-to! a `TabB {})
    (edit-item! a 2)
    (scr/route-to! a `TabA {})
    (assertions
      "denies leaving the tab, which exits the editor"
      (in? a (h/child-session-id a ::TabHost) ::TabB) => true
      "in the routing session, which shows the denial"
      (scr/route-denied? a) => true)))

(specification "The busy guard and a dirty editor that a host's routes node invokes"
  (let [{a :app} (items-app tab-routing-chart)]
    (scr/route-to! a `NodeTabA {})
    (edit-item! a 1)
    (scr/route-to! a `NodeTabB {})
    (assertions
      "denies a route change between its tabs: a route transition re-enters its routes node, which restarts the editor"
      (in? a (h/child-session-id a ::RoutesNodeHost) ::NodeTabA) => true
      (scr/route-denied? a) => true)))

(specification "The busy guard and a dirty editor that the routing chart invokes beside its routes"
  (let [{a :app} (items-app beside-routes-chart)]
    (edit-item! a 3)
    (assertions
      "(the routing chart invoked the editors)"
      (count (filter #(= scr/session-id (:com.fulcrologic.statecharts/parent-session-id %))
               (vals (:com.fulcrologic.statecharts/session-id (app/current-state a))))) => 2)
    (scr/route-to! a `TabA {})
    (assertions
      "allows route changes: the state that invoked the editor stays active"
      (in? a scr/session-id ::TabA) => true
      (scr/route-denied? a) => false)
    (scr/route-to! a `NodeTabB {})
    (assertions
      "also into a module that isn't active (entered for the route)"
      (in? a (h/child-session-id a ::RoutesNodeHost) ::NodeTabB) => true
      (scr/route-denied? a) => false))
  (let [{a :app} (items-app beside-routes-chart)]
    (edit-item! a 2)
    (scr/route-to! a `TabA {})
    (assertions
      "and for an editor invoked with an explicit id"
      (in? a scr/session-id ::TabA) => true)))

;; ===== The busy guard's cost =====

(defsc Plain [_ _]
  {:query [:plain/id :plain/v]
   :ident :plain/id})

(def plain-actors-chart
  "Invokes a session whose actors are ten Plain components (no busy check: not forms, no `sfro/busy?`) and the Editor."
  (chart/statechart {:initial :state/open}
    (state {:id :state/open}
      (invoke {:id     :plain/actors
               :type   :statechart
               :src    ::editor-chart
               :params {:fulcro/actors (fn [& _] (assoc (into {} (map (fn [i] [(keyword "actor" (str "plain-" i)) (scf/actor Plain [:plain/id i])]))
                                                          (range 10))
                                                   :actor/editor (scf/actor Editor [:editor/id 1])))}}))))

(defsc PlainHost [_ _]
  {:query          [:host/id]
   :ident          (fn [] [:component/id ::PlainHost])
   :initial-state  {:host/id 3}
   sfro/statechart plain-actors-chart})

(def plain-routing-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/routes :routing/root `Root}
        (scr/rstate {:route/target `Landing})
        (scr/istate {:route/target `PlainHost})))))

(specification "The busy guard's check of an invoked session's actors"
  (let [{a :app} (items-app plain-routing-chart)
        props-of (atom [])
        ui->props fns/ui->props]
    (swap! (::app/state-atom a) (fn [s] (reduce (fn [s i] (assoc-in s [:plain/id i] {:plain/id i :plain/v (vec (range 50))})) s (range 10))))
    (scr/route-to! a `PlainHost {})
    (with-redefs [fns/ui->props (fn [state-map Class ident] (swap! props-of conj Class) (ui->props state-map Class ident))]
      (scr/route-to! a `Landing {}))
    (assertions
      "(routes away: nothing is busy)"
      (in? a scr/session-id ::Landing) => true
      "builds the props only of components that have a busy check"
      (set @props-of) => #{Editor})))
