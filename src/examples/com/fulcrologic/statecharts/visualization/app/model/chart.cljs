(ns com.fulcrologic.statecharts.visualization.app.model.chart
  (:require
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.attributes :refer [defattr]]))

(defattr id ::sc/id :keyword
  {ao/identity? true})

(defattr label :chart/label :keyword
  {ao/identities #{::sc/id}})
