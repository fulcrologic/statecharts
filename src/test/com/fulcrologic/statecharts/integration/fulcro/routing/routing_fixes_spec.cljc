(ns com.fulcrologic.statecharts.integration.fulcro.routing.routing-fixes-spec
  "Regression specs for routing fixes: per-state URL params, `replace-route-params!`, entering a routing module for
   a pending route, the busy guard of a route inside a module, `send-to-self!` after a route ended, and `istate` on a
   keyword chart."
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state]]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as sroute]
    [com.fulcrologic.statecharts.integration.fulcro.routing-options :as sfro]
    [com.fulcrologic.statecharts.integration.fulcro.routing.simulated-history :as rsh]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-codec :as ruc]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-codec-transit :as ruct]
    [fulcro-spec.core :refer [=> =throws=> assertions specification]]))

(defsc Root [_ _]
  {:query         [:ui/current-route]
   :ident         (fn [] [:component/id ::Root])
   :initial-state {:ui/current-route {}}})

(defsc PageA [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :a}})

;; A nested route whose two states declare the same param, with idents from their params
(defsc Child [_ _]
  {:query         [:child/id]
   :ident         :child/id
   :initial-state (fn [{:keys [id]}] {:child/id id})})

(defsc Parent [_ _]
  {:query         [:parent/id :ui/current-route]
   :ident         :parent/id
   :initial-state (fn [{:keys [id]}] {:parent/id id})})

;; Module M routes to the istate N (its initial route) or M2; N's chart routes to N1 (its initial route) or N2.
(defsc N1 [_ _] {:query [:page/id] :ident :page/id :initial-state {:page/id :n1}})
(defsc N2 [_ _] {:query [:page/id] :ident :page/id :initial-state {:page/id :n2}})
(defsc M2 [_ _] {:query [:page/id] :ident :page/id :initial-state {:page/id :m2}})

(defsc N [_ _]
  {:query          [:ui/current-route]
   :ident          (fn [] [:component/id ::N])
   :initial-state  {}
   sfro/statechart (chart/statechart {:initial :state/route-root}
                     (sroute/routing-regions
                       (sroute/routes {:id ::n-routes :routing/root `N}
                         (sroute/rstate {:route/target `N1})
                         (sroute/rstate {:route/target `N2}))))})

(defsc M [_ _]
  {:query          [:module/busy? :ui/current-route]
   :ident          (fn [] [:component/id ::M])
   :initial-state  {:module/busy? false}
   sfro/busy?      (fn [_ props] (boolean (:module/busy? props)))
   sfro/statechart (chart/statechart {:initial :state/route-root}
                     (sroute/routing-regions
                       (sroute/routes {:id ::m-routes :routing/root `M}
                         (sroute/istate {:route/target `N})
                         (sroute/rstate {:route/target `M2}))))})

;; An istate target whose chart doesn't route
(defsc S [_ _]
  {:query          [:s/id]
   :ident          (fn [] [:component/id ::S])
   :initial-state  {:s/id 1}
   sfro/statechart (chart/statechart {:initial :s/idle} (state {:id :s/idle}))})

(def routing-chart
  (chart/statechart {:initial :state/route-root}
    (sroute/routing-regions
      (sroute/routes {:id :region/routes :routing/root `Root}
        (sroute/rstate {:route/target `PageA})
        (sroute/rstate {:route/target `Parent :route/params #{:id}}
          (sroute/rstate {:route/target `Child :route/params #{:id}}))
        (sroute/istate {:route/target `S})
        (sroute/istate {:route/target `M})))))

(defn- new-app
  "A headless app running `routing-chart`, with `url-sync-on-save` on every save, and URL sync on a simulated history
   at `url` when one is given."
  ([] (new-app nil))
  ([url]
   (let [a (app/fulcro-app)]
     (app/set-root! a Root {:initialize-state? true})
     (scf/install-fulcro-statecharts! a {:event-loop? false
                                         :on-save     (fn [sid wmem] (sroute/url-sync-on-save sid wmem a))})
     (sroute/start! a routing-chart)
     (when url (sroute/install-url-sync! a {:provider (rsh/simulated-url-history url)}))
     a)))

(defn- settle! [a] (dotimes [_ 10] (scf/process-events! a)) a)
(defn- route! [a target] (sroute/route-to! a target {}) (settle! a))
(defn- local-data [a sid] (get-in (rapp/current-state a) [::sc/local-data sid]))
(defn- child-sid [a sid target] (get-in (local-data a sid) [:invocation/id (comp/class->registry-key target)]))
(defn- leaves [a] (sroute/active-leaf-routes a))
(defn- k [c] (comp/class->registry-key c))

(specification "A URL restore of a route whose states declare the same param"
  (let [codec (ruct/transit-base64-codec)
        url   (ruc/encode-url codec {:segments       [(k Parent) (k Child)]
                                     :params         {(k Parent) {:id 1} (k Child) {:id 2}}
                                     :route-elements (::sc/elements-by-id routing-chart)})
        a     (settle! (new-app url))
        data  (local-data a sroute/session-id)]
    (assertions
      "stores each state's own params"
      (get-in data [:routing/parameters (k Parent)]) => {:id 1}
      (get-in data [:routing/parameters (k Child)]) => {:id 2}
      "initializes each route from its own params"
      (get-in data [:route/idents (k Parent)]) => [:parent/id 1]
      (get-in data [:route/idents (k Child)]) => [:child/id 2])))

(specification "A save after replace-route-params!"
  (let [a        (route! (settle! (new-app "/")) `PageA)
        provider (sroute/url-sync-provider a)]
    (sroute/replace-route-params! a `PageA {:page 2})
    (sroute/abandon-route-change! a)
    (settle! a)
    (assertions
      "keeps the one history entry that replace-route-params! replaced"
      (count (rsh/history-stack provider)) => 1
      (rsh/history-stack provider) => [(sroute/route-current-url a)])))

#?(:clj
   (specification "Entering a routing module for a pending route"
     (let [a (settle! (new-app))]
       (route! a `M2)
       (assertions
         "reaches the pending route"
         (leaves a) => #{(k M2)}
         "never enters the module's initial route (the istate N)"
         (get-in (rapp/current-state a) [:component/id ::N]) => nil))
     (let [a (settle! (new-app))]
       (route! a `N2)
       (assertions
         "reaches a pending route two istates deep"
         (leaves a) => #{(k N2)}))))

#?(:clj
   (specification "The busy guard of a route inside a module whose own component is busy"
     (let [a (settle! (new-app))]
       (route! a `M2)
       (swap! (::app/state-atom a) assoc-in [:component/id ::M :module/busy?] true)
       (route! a `N1)
       (assertions
         "allows the route change (the module stays)"
         (leaves a) => #{(k N1)}
         (sroute/route-denied? a) => false)
       (route! a `PageA)
       (assertions
         "denies a route that leaves the module"
         (leaves a) => #{(k N1)}
         (sroute/route-denied? a) => true))))

#?(:clj
   (defn- instance
     "A CLJ instance of `Class` on its props at `ident`, rendered under the instance `parent` (or none)."
     [a Class ident parent]
     (binding [comp/*app* a comp/*parent* parent]
       ((comp/factory Class) (fns/ui->props (app/current-state a) Class ident)))))

#?(:clj
   (specification "send-to-self! from a component whose own route ended"
     (let [a (settle! (new-app))]
       (route! a `S)
       (let [ended (child-sid a sroute/session-id S)]
         (route! a `M2)
         (sroute/send-to-self! (instance a S [:component/id ::S] (instance a M [:component/id ::M] nil))
           (sroute/route-to-event-name `N1))
         (settle! a)
         (assertions
           "(the routing session still has the ended route's invocation id)"
           (child-sid a sroute/session-id S) => ended
           (scf/current-configuration a ended) => #{}
           "reaches the running invocation of an ancestor"
           (leaves a) => #{(k N1)})))))

(defsc KeywordChart [_ _] {:query [:x] :ident (fn [] [:x 1]) sfro/statechart-id ::some-chart})
(defsc NoChart [_ _] {:query [:x] :ident (fn [] [:x 2])})

(specification "istate on a target with a keyword chart and no :route/reachable"
  (assertions
    "builds the state for a component's sfro/statechart-id"
    (:route/target (sroute/istate {:route/target `KeywordChart})) => (k KeywordChart)
    "builds the state for the istate's statechart-id"
    (:route/target (sroute/istate {:route/target `NoChart :statechart-id ::some-chart})) => (k NoChart)
    "throws for a target with no chart"
    (sroute/istate {:route/target `NoChart}) =throws=> #"has no sfro/statechart"))
