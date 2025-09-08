(ns com.fulcrologic.statecharts.integration.fulcro.statechart-browser-routing-system
  "An implementation of the RoutingSystem abstraction for when you are using statechart routing as your UI routing,
   and you would like to integrate it with an HTML Browser which has history and forward/back buttons.

   In order for this to work properly you will use the ui-routes support from statecharts
   for the *construction* of the UI, but you should use the function in the
   com.fulcrologic.fulcro.routing.system namespace for doing any route-related logic (e.g. changing routes,
   looking at the current route, listening for external route changes, manipulating the URL parameters, etc.).

   The URL itself will be built from the route segments, and any routing params will be transit-encoded into the
   query string of the URL. This ensures that browser nav (back/forward) can properly restore your route by passing you
   the same routing parameters you received previously.

   Event data that is sent in a statechart event that results in routing will be stored in the URL as transit-encoded
   data. Updating the URL parameters (route params) will mean that loading that routed state from a bookmark will
   receive those updated params in the event data.

   The routing system method `current-route` will return the parameters as their original types, as
   derived from the URL query string (a transit encoded version of the parameters passed to the route).

   A target can manipulate and use the (type safe) URL parameters using sys/current-route,
   sys/merge-route-params! and sys/set-route-params!, but
   be careful not to overwrite parameters that are also used in your routes! Getting those out of sync can cause
   strange behavior.

   = System Startup

   When you enter your application the user MAY have pasted a particular bookmark. The ui routes statechart
   will of course try to move to that state, and your construction of the chart can handle all of the necessary steps for
   denying/restoring that route with authentication.
   "
  ;; TASK: Make sure the startup of the statechart when using this system will read the URL and try to move to the right state...
  (:require
    [com.fulcrologic.fulcro.routing.browser-history-utils :as bhu]
    [com.fulcrologic.fulcro.routing.system :as sys :refer [notify!]]
    [com.fulcrologic.fulcro.routing.system-protocol :as sp]
    [com.fulcrologic.statecharts.integration.fulcro.ui-routes :as uir]
    [taoensso.timbre :as log]))

(deftype StatechartBrowserRoutingSystem [app vnumber vlisteners route->url url->route]
  sp/RoutingSystem
  (-route-to! [this {::keys [external?]
                     :keys  [target route params force?]
                     :as    options}]
    (let [routing-statechart (uir/routing-statechart app)
          Target             (or target (uir/target-for-path routing-statechart route))]
      ;; TASK: Add callback support to route-to!
      ;; TASK: statecharts use the active states in the URL to track the actual route, not a path. A `target` CAN be ambiguous.
      (uir/route-to! app Target
        (assoc params
          ::uir/on-success (fn []
                             (when-not external?
                               (let [rte {:target Target
                                          :params params}]
                                 (vswap! vnumber inc)
                                 (bhu/push-state! @vnumber (route->url rte))
                                 (notify! (vals @vlisteners) rte))))
          ::uir/force? (boolean force?)))))
  (-replace-route! [this {:keys [route target params] :as new-route}]
    (let [routing-statechart (uir/routing-statechart app)
          path               (or route (uir/path-for-target routing-statechart target))]
      (bhu/replace-state! @vnumber (route->url (assoc new-route :route path)))))
  (-current-route [this]
    (let [routes  (dr/active-routes app)
          nroutes (count routes)
          {:keys [path target-component] :as preferred-route} (first routes)]
      (if (> nroutes 1)
        (do
          (log/debug "Current route was ambiguous in code (sibling routers). Returning URL route instead")
          (bhu/current-url->route))
        (when path
          {:route  path
           :target target-component}))))
  (-current-route-busy? [this] (not (dr/can-change-route? app)))
  (-back! [this force?]
    (when force?
      (some-> (dr/target-denying-route-changes app) (dr/set-force-route-flag!)))
    (bhu/browser-back!))
  (-current-route-params [this] (:params (bhu/current-url->route)))
  (-set-route-params! [this params]
    (bhu/replace-state! @vnumber (route->url (assoc (bhu/current-url->route)
                                               :params params))))
  (-add-route-listener! [this k listener] (vswap! vlisteners assoc k listener) nil)
  (-remove-route-listener! [this k] (vswap! vlisteners dissoc k) nil))

(defn install-dynamic-routing-browser-system!
  "Install the dynamic router system with support for browser history/URL"
  ([app]
   (install-dynamic-routing-browser-system! app nil))
  ([app {:keys [prefix hash?]}]
   (let [vnumber            (volatile! 0)
         vlisteners         (volatile! {})
         sys                (->StatechartBrowserRoutingSystem app vnumber vlisteners
                              (fn [{:keys [route params]}]
                                (cond->> (bhu/route->url route params hash?)
                                  (seq prefix) (str prefix "/")))
                              (fn [] (bhu/current-url->route hash? prefix)))
         pop-state-listener (bhu/build-popstate-listener app vnumber vlisteners)]
     (sys/install-routing-system! app sys)
     (bhu/add-popstate-listener! pop-state-listener)
     app)))
