(ns com.fulcrologic.statecharts.visualization.app.main
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.rad.application :as rad.app]
    [com.fulcrologic.rad.rendering.semantic-ui.semantic-ui-controls :as sui]
    [com.fulcrologic.statecharts.visualization.app.client :as client]
    [fulcro.inspect.tool :as it]))

(defn ^:export init
  "Application entry point. Called on page load."
  []
  (rad.app/install-ui-controls! client/app sui/all-controls)
  (it/add-fulcro-inspect! client/app)
  (app/mount! client/app client/Root "app")
  (client/load-initial-data!)
  (js/console.log "Statechart Visualizer initialized"))

(defn ^:export refresh
  "Called by shadow-cljs on hot reload."
  []
  (app/force-root-render! client/app)
  (js/console.log "Hot reload"))
