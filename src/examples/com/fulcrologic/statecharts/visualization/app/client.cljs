(ns com.fulcrologic.statecharts.visualization.app.client
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.networking.http-remote :as http]
    [com.fulcrologic.statecharts.visualization.app.ui :as ui]))

(defonce app
  (app/fulcro-app
    {:remotes {:remote (http/fulcro-http-remote
                         {:url "/api"})}}))

(defn load-initial-data!
  "Load the initial chart list when the app starts."
  []
  (df/load! app :com.fulcrologic.statecharts/all-charts ui/ChartListItem
    {:target [:component/id :chart-selector :ui/all-charts]}))

(def Root ui/Root)
