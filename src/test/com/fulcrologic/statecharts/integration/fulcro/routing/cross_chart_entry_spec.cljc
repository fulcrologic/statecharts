(ns com.fulcrologic.statecharts.integration.fulcro.routing.cross-chart-entry-spec
  "Entering a routing module (an istate's child chart) for a pending route: the module enters the pending route
   directly, and the route event's data reaches routes any number of istates deep."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :as ele :refer [on-entry raise script transition]]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]
    [com.fulcrologic.statecharts.integration.fulcro.routing-options :as sfro]
    [com.fulcrologic.statecharts.integration.fulcro.routing.headless-app :as h]
    [com.fulcrologic.statecharts.testing :as testing]
    [fulcro-spec.core :refer [=> =throws=> assertions component specification]]))

(def entries
  "Route state id -> the data of the route events it was entered with, in order."
  (atom {}))

(defn- counting-entry
  "An on-entry that records the entering event's data under `k`."
  [k]
  (on-entry {}
    (script {:expr (fn [_env data & _]
                     (swap! entries update k (fnil conj []) (get-in data [:_event :data]))
                     nil)})))

(defn- entered [k] (get @entries k []))

(defsc Root [_ _]
  {:query         [:ui/current-route]
   :ident         (fn [] [:component/id ::Root])
   :initial-state {:ui/current-route {}}})

(defsc Landing [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :landing}})

(defsc First [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :first}})

(defsc Second [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :second}})

(def module-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/module-routes :routing/root `Module}
        (transition {:event :event/reset :target :region/module-routes})
        (scr/rstate {:route/target `First :route/params #{:p}} (counting-entry ::First))
        (scr/rstate {:route/target `Second :route/params #{:p}} (counting-entry ::Second))))))

(defsc Module [_ _]
  {:query          [:ui/current-route]
   :ident          (fn [] [:component/id ::Module])
   :initial-state  {}
   sfro/statechart module-chart})

;; Two hops: Root -> Org (istate) -> Team (istate) -> Deep
(defsc Deep [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :deep}})

(defsc TeamHome [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :team-home}})

(def team-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/team-routes :routing/root `Team}
        (scr/rstate {:route/target `TeamHome} (counting-entry ::TeamHome))
        (scr/rstate {:route/target `Deep :route/params #{:id}} (counting-entry ::Deep))))))

(defsc Team [_ _]
  {:query          [:ui/current-route]
   :ident          (fn [] [:component/id ::Team])
   :initial-state  {}
   sfro/statechart team-chart})

(defsc OrgHome [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :org-home}})

(def org-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/org-routes :routing/root `Org}
        (transition {:event :event/reset :target :region/org-routes})
        (scr/istate {:route/target `Team :route/params #{:team} :route/reachable #{::Deep ::TeamHome}})
        (scr/rstate {:route/target `OrgHome} (counting-entry ::OrgHome))))))

(defsc Org [_ _]
  {:query          [:ui/current-route]
   :ident          (fn [] [:component/id ::Org])
   :initial-state  {}
   sfro/statechart org-chart})

(def routing-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/routes :routing/root `Root}
        ;; Routes to the module and then to one of its routes in one macrostep
        (transition {:event :event/module-then-second}
          (raise {:event (scr/route-to-event-name ::Module)})
          (raise {:event (scr/route-to-event-name ::Second) :data {:p 22}}))
        (scr/rstate {:route/target `Landing})
        (scr/istate {:route/target `Module :route/reachable #{::First ::Second}})
        (scr/istate {:route/target `Org :route/params #{:org} :route/reachable #{::Deep ::TeamHome ::OrgHome}})))))

(defn- in? [a session-id k] (contains? (scf/current-configuration a session-id) k))
(defn- routing-params [a session-id k] (get-in (h/local-data a session-id) [:routing/parameters k]))
(defn- org-sid [a] (h/child-session-id a ::Org))
(defn- team-sid [a] (h/child-session-id a (org-sid a) ::Team))

(specification "Entering a routing module for a pending route"
  (component "that isn't the module's initial route"
    (reset! entries {})
    (let [{a :app} (h/routed-app Root routing-chart "/" {:url-sync? false})]
      (scr/route-to! a `Second {:p 2})
      (assertions
        "enters the pending route"
        (in? a (h/child-session-id a ::Module) ::Second) => true
        "with the route event's data"
        (entered ::Second) => [{:p 2}]
        (routing-params a (h/child-session-id a ::Module) ::Second) => {:p 2}
        "without entering the module's initial route first"
        (entered ::First) => [])))
  (component "that is the module's initial route"
    (reset! entries {})
    (let [{a :app} (h/routed-app Root routing-chart "/" {:url-sync? false})]
      (scr/route-to! a `First {:p 1})
      (assertions
        "enters it once, with the route event's data"
        (entered ::First) => [{:p 1}]
        (routing-params a (h/child-session-id a ::Module) ::First) => {:p 1})))
  (component "followed by a re-entry of the module's routes"
    (reset! entries {})
    (let [{a :app} (h/routed-app Root routing-chart "/" {:url-sync? false})]
      (scr/route-to! a `Second {:p 2})
      (scf/send! a (h/child-session-id a ::Module) :event/reset {})
      (assertions
        "the re-entry enters the initial route"
        (in? a (h/child-session-id a ::Module) ::First) => true)))
  (component "routed to in the macrostep that enters the module"
    (reset! entries {})
    (let [{a :app} (h/routed-app Root routing-chart "/" {:url-sync? false})]
      (scf/send! a scr/session-id :event/module-then-second {})
      (assertions
        "becomes the module's pending route"
        (in? a (h/child-session-id a ::Module) ::Second) => true
        (entered ::Second) => [{:p 22}]
        (entered ::First) => [])))
  (component "with no pending route"
    (reset! entries {})
    (let [{a :app} (h/routed-app Root routing-chart "/" {:url-sync? false})]
      (scr/route-to! a `Module {})
      (assertions
        "enters the module's initial route, once"
        (in? a (h/child-session-id a ::Module) ::First) => true
        (count (entered ::First)) => 1))))

(specification "A route two istates deep"
  (component "routed to from outside both modules"
    (reset! entries {})
    (let [{a :app} (h/routed-app Root routing-chart "/" {:url-sync? false})]
      (scr/route-to! a `Deep {:id 1 :org "o" :team "t"})
      (assertions
        "is entered in the inner module"
        (in? a (team-sid a) ::Deep) => true
        "with the route event's data"
        (entered ::Deep) => [{:id 1 :org "o" :team "t"}]
        "and each state on the way records its own route params"
        (routing-params a scr/session-id ::Org) => {:org "o"}
        (routing-params a (org-sid a) ::Team) => {:team "t"}
        (routing-params a (team-sid a) ::Deep) => {:id 1}
        "without entering either module's initial route"
        (entered ::OrgHome) => []
        (entered ::TeamHome) => [])
      (scf/send! a (org-sid a) :event/reset {})
      (assertions
        "and a later re-entry of the outer module's routes enters its initial route"
        (in? a (org-sid a) ::Team) => true)))
  (component "routed to again from inside the outer module"
    (reset! entries {})
    (let [{a :app} (h/routed-app Root routing-chart "/" {:url-sync? false})]
      (scr/route-to! a `OrgHome {})
      (scr/route-to! a `Deep {:id 2 :team "t2"})
      (assertions
        "reaches the inner module's route with its data"
        (in? a (team-sid a) ::Deep) => true
        (entered ::Deep) => [{:id 2 :team "t2"}]
        (routing-params a (org-sid a) ::Team) => {:team "t2"})))
  (component "restored from a bookmark"
    (reset! entries {})
    (let [{a :app :as env} (h/routed-app Root routing-chart)]
      (scr/route-to! a `Deep {:id 3 :org "o3" :team "t3"})
      (let [bookmark (h/current-url env)
            {b :app} (h/routed-app Root routing-chart bookmark)]
        (assertions
          "(the URL has the whole path)"
          (first (clojure.string/split bookmark #"\?")) => "/Org/Team/Deep"
          "enters the route with every state's params"
          (in? b (team-sid b) ::Deep) => true
          (routing-params b scr/session-id ::Org) => {:org "o3"}
          (routing-params b (org-sid b) ::Team) => {:team "t3"}
          (routing-params b (team-sid b) ::Deep) => {:id 3})))))

;; ===== The routes node's generated initial states, in the testing env =====

(defn- module-like-chart
  "A routing chart whose root data model already holds a pending child route to `pending-target` (none when nil)."
  [pending-target]
  (chart/statechart {:initial :state/route-root}
    (ele/data-model {:expr (if pending-target
                             {::scr/pending-child-route {:event (scr/route-to-event-name pending-target) :data {}}}
                             {})})
    (scr/routing-regions
      (scr/routes {:id :region/t-routes :routing/root ::TRoot}
        (scr/rstate {:route/target ::TFirst})
        (scr/rstate {:route/target ::TSecond})))))

(defn- started-configuration [chart mocks mocking-options]
  (let [env (testing/new-testing-env {:statechart chart :mocking-options mocking-options} mocks)]
    (testing/start! env)
    (testing/current-configuration env)))

(specification "A routes node's initial state, in the testing env"
  (assertions
    "with unmocked (false) conds, is the initial route, as before"
    (contains? (started-configuration (module-like-chart nil) {} {}) ::TFirst) => true
    "with a pending route, is that route"
    (contains? (started-configuration (module-like-chart ::TSecond) {} {:run-unmocked? true :explode-event? true})
      ::TSecond) => true
    "with a pending route it has no route for, is the initial route"
    (contains? (started-configuration (module-like-chart ::Elsewhere) {} {:run-unmocked? true :explode-event? true})
      ::TFirst) => true)
  (let [cfg (started-configuration (module-like-chart ::TSecond) {scr/busy? (constantly true)}
              {:run-unmocked? true :explode-event? true})]
    (assertions
      "with a pending route the busy guard denies, is the initial route (and the denial shows)"
      (contains? cfg ::TFirst) => true
      (contains? cfg :routing-info/open) => true)))

(defn- look-alike-routes-chart
  "Three routes nodes, in parallel, whose ids differ only in where the namespace ends (`:a.b/c`, `:a/b.c`) or only
   in their namespace (`:a.b/c`, `:x/c`)."
  []
  (chart/statechart {:initial :state/both}
    (ele/parallel {:id :state/both}
      (scr/routes {:id :a.b/c :routing/root ::TRoot}
        (scr/rstate {:route/target ::TFirst}))
      (scr/routes {:id :a/b.c :routing/root ::TRoot}
        (scr/rstate {:route/target ::TSecond}))
      (scr/routes {:id :x/c :routing/root ::TRoot}
        (scr/rstate {:route/target ::TThird})))))

(specification "The generated states of routes nodes whose ids look alike"
  (let [chart (look-alike-routes-chart)
        ids   (into #{} (filter #(some-> (namespace %) (str/includes? "routes-initial")))
                (keys (:com.fulcrologic.statecharts/elements-by-id chart)))]
    (assertions
      "are distinct states"
      (count ids) => 3
      "so each node starts on its initial route"
      (let [cfg (started-configuration chart {} {})]
        [(contains? cfg ::TFirst) (contains? cfg ::TSecond) (contains? cfg ::TThird)]) => [true true true])))

;; ===== A routes node with an explicit initial element =====

(defsc ExFirst [_ _] {:query [:page/id] :ident :page/id :initial-state {:page/id :ex-first}})
(defsc ExSecond [_ _] {:query [:page/id] :ident :page/id :initial-state {:page/id :ex-second}})

(def explicit-initial-module-chart
  "A module whose routes node has an explicit `initial` element, to its second route."
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/explicit-routes :routing/root `ExplicitModule}
        (ele/initial {} (transition {:target ::ExSecond}))
        (scr/rstate {:route/target `ExFirst} (counting-entry ::ExFirst))
        (scr/rstate {:route/target `ExSecond} (counting-entry ::ExSecond))))))

(defsc ExplicitModule [_ _]
  {:query          [:ui/current-route]
   :ident          (fn [] [:component/id ::ExplicitModule])
   :initial-state  {}
   sfro/statechart explicit-initial-module-chart})

(def explicit-initial-routing-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/routes :routing/root `Root}
        (scr/rstate {:route/target `Landing})
        (scr/istate {:route/target `ExplicitModule})))))

(specification "Entering a routing module whose routes node has an explicit initial element"
  (assertions
    "(the routes node keeps its element and gets no generated states)"
    (filterv #(some-> (namespace %) (str/includes? "routing.routes-"))
      (keys (:com.fulcrologic.statecharts/elements-by-id explicit-initial-module-chart))) => [])
  (reset! entries {})
  (let [{a :app} (h/routed-app Root explicit-initial-routing-chart "/" {:url-sync? false})]
    (scr/route-to! a `ExplicitModule {})
    (assertions
      "with no pending route, enters the element's target"
      (in? a (h/child-session-id a ::ExplicitModule) ::ExSecond) => true
      (count (entered ::ExFirst)) => 0))
  (reset! entries {})
  (let [{a :app} (h/routed-app Root explicit-initial-routing-chart "/" {:url-sync? false})]
    (scr/route-to! a `ExFirst {})
    (assertions
      "for a pending route, keeps the old order: enters the element's target, then the pending route"
      (in? a (h/child-session-id a ::ExplicitModule) ::ExFirst) => true
      (count (entered ::ExSecond)) => 1
      (count (entered ::ExFirst)) => 1)))

;; ===== istate on a component whose chart is a keyword =====

(defsc KwHome [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :kw-home}})

(def kw-module-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/kw-routes :routing/root `KwModule}
        (scr/rstate {:route/target `KwHome})))))

(defsc KwModule [_ _]
  {:query             [:ui/current-route]
   :ident             (fn [] [:component/id ::KwModule])
   :initial-state     {}
   sfro/statechart-id ::kw-module-chart})

(defsc NoChart [_ _]
  {:query         [:ui/current-route]
   :ident         (fn [] [:component/id ::NoChart])
   :initial-state {}})

(specification "istate on a target with a keyword chart and no :route/reachable"
  (let [node (scr/istate {:route/target `KwModule})]
    (assertions
      "builds, with no reachable routes"
      (:route/reachable node) => nil
      "as does one whose chart is the :statechart-id option"
      (:id (scr/istate {:route/target `NoChart :statechart-id ::kw-module-chart})) => ::NoChart
      "while a target with no chart at all still throws"
      (scr/istate {:route/target `NoChart}) =throws=> #"no sfro/statechart"))
  (let [chart    (chart/statechart {:initial :state/route-root}
                   (scr/routing-regions
                     (scr/routes {:id :region/routes :routing/root `Root}
                       (scr/rstate {:route/target `Landing})
                       (scr/istate {:route/target `KwModule}))))
        {a :app} (h/routed-app Root chart "/" {:url-sync? false})]
    (scf/register-statechart! a ::kw-module-chart kw-module-chart)
    (scr/route-to! a `KwModule {})
    (assertions
      "routes into the registered chart"
      (in? a (h/child-session-id a ::KwModule) ::KwHome) => true)))

