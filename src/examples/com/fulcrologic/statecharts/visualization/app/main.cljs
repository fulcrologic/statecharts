(ns com.fulcrologic.statecharts.visualization.app.main
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.statecharts.visualization.app.client :as client]))

(defn ^:export init
  "Application entry point. Called on page load."
  []
  (app/mount! client/app client/Root "app")
  (client/load-initial-data!)
  (js/console.log "Statechart Visualizer initialized"))

(defn ^:export refresh
  "Called by shadow-cljs on hot reload."
  []
  (app/force-root-render! client/app)
  (js/console.log "Hot reload"))
