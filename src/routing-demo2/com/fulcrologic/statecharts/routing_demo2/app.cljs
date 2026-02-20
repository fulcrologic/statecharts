(ns com.fulcrologic.statecharts.routing-demo2.app
  "Application entry point for routing-demo2.
   Wires Fulcro app with Pathom mock-http-server remote and statechart routing."
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.networking.mock-server-remote :as msr]
    ;; Required for :async? true in install-fulcro-statecharts!
    [com.fulcrologic.statecharts.algorithms.v20150901-async]
    [com.fulcrologic.statecharts.event-queue.async-event-loop]
    [com.fulcrologic.statecharts.execution-model.lambda-async]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.routing.browser-history]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as sroute]
    [com.fulcrologic.statecharts.routing-demo2.admin-chart :as admin-chart]
    [com.fulcrologic.statecharts.routing-demo2.chart :as demo-chart]
    [com.fulcrologic.statecharts.routing-demo2.mock-server :as mock-server]
    [com.fulcrologic.statecharts.routing-demo2.ui :as ui]
    [fulcro.inspect.tool :as it]
    [taoensso.timbre :as log]))

(defonce app-instance
  (app/fulcro-app
    {:remotes {:remote (msr/mock-http-server {:parser (mock-server/make-parser)})}}))

;; Store cleanup fn so hot reload doesn't leak popstate listeners
(defonce url-sync-cleanup (atom nil))

(defn ^:export init
  "Application entry point. Called by shadow-cljs on load."
  []
  (log/info "Initializing routing demo 2")
  (app/set-root! app-instance ui/Root {:initialize-state? true})
  (it/add-fulcro-inspect! app-instance)
  (scf/install-fulcro-statecharts! app-instance
    {:async?      true
     :event-loop? true
     :on-save     (fn [sid wmem]
                    (sroute/url-sync-on-save sid wmem app-instance))})
  ;; Start main routing chart (registers + starts in one call)
  (sroute/start! app-instance demo-chart/routing-chart)
  ;; Install bidirectional URL sync (after start!), storing cleanup for hot reload
  (when-let [old-cleanup @url-sync-cleanup] (old-cleanup))
  (reset! url-sync-cleanup
    (sroute/install-url-sync! app-instance))
  (app/mount! app-instance ui/Root "app")
  (js/console.log "Routing demo 2 initialized."))

(defn ^:export refresh
  "Called by shadow-cljs on hot reload."
  []
  (app/force-root-render! app-instance))
