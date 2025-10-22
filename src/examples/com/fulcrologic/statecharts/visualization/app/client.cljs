(ns com.fulcrologic.statecharts.visualization.app.client
  (:require
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.networking.http-remote :as http]
    [com.fulcrologic.rad.application :as rad.app]
    [com.fulcrologic.statecharts.visualization.app.ui :as ui]))

(defonce app
  (rad.app/fulcro-rad-app
    {:remotes {:remote (http/fulcro-http-remote
                         {:url "/api"})}}))

(defn load-initial-data!
  "Load the initial chart list when the app starts."
  []
  (df/load! app :com.fulcrologic.statecharts/all-charts ui/ChartListItem
    {:target [:component/id :chart-selector :ui/all-charts]}))

(def Root ui/Root)
