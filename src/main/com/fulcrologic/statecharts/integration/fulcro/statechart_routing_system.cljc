(ns com.fulcrologic.statecharts.integration.fulcro.statechart-routing-system
  "A Fulcro Routing System that should be installed on your Fulcro application if you want a generalized
   routing solution that leverages statecharts, and is compatible with things like RAD.

   This system does NO URL integration, and is useful for things like React Native"
  (:require
    [com.fulcrologic.fulcro.routing.system :as sys :refer [notify!]]
    [com.fulcrologic.fulcro.routing.system-protocol :as sp]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.ui-routes :as uir]))

(defn push-route [history route] (take 10 (cons route history)))
(defn pop-route [history] (drop 1 history))

(deftype StatechartRoutingSystem [app vhistory vlisteners]
  sp/RoutingSystem
  (-route-to! [this {:keys [target route params force?]
                     :as   options}]
    (let [routing-statechart (uir/routing-statechart app)
          Target             (or target (uir/target-for-path routing-statechart route))]
      ;; TASK: Add callback support to route-to!
      (uir/route-to! app Target
        (assoc params
          ::uir/on-success (fn []
                             (notify! (vals @vlisteners) options)
                             (vswap! vhistory push-route options))
          ::uir/force? (boolean force?)))))
  (-replace-route! [this {:keys [target route params] :as new-route}]
    (let [routing-statechart (uir/routing-statechart app)
          target             (or target (uir/target-for-path routing-statechart route))]
      (vswap! vhistory (fn [old] (cons (assoc new-route :target target) (rest old))))))
  (-current-route [this] (first @vhistory))
  (-current-route-busy? [this] (uir/busy? (scf/statechart-env app)))
  (-back! [this force?]
    (if (and (not force?) (sp/-current-route-busy? this))
      (notify! (vals @vlisteners) {:desired-route (second @vhistory)
                                   :direction     :back
                                   :denied?       true})
      (let [routing-statechart (uir/routing-statechart app)]
        (vswap! vhistory pop-route)
        (let [{:keys [route params] :as rte} (first @vhistory)
              Target (uir/target-for-path routing-statechart route)]
          (uir/route-to! app Target (assoc params ::uir/force? (boolean force?)))
          (notify! (vals @vlisteners) rte)))))
  (-current-route-params [this] (:params (first @vhistory)))
  (-set-route-params! [this params]
    (vswap! vhistory (fn [[rt & others :as old-history]] (cons (assoc rt :params params) others))))
  (-add-route-listener! [this k listener] (vswap! vlisteners assoc k listener) nil)
  (-remove-route-listener! [this k] (vswap! vlisteners dissoc k) nil))

(defn install-statechart-routing-system!
  "Install the statechart router system (no URL integration)"
  [app]
  (let [vhistory   (volatile! (list))
        vlisteners (volatile! {})
        sys        (->StatechartRoutingSystem app vhistory vlisteners)]
    (sys/install-routing-system! app sys))
  app)
