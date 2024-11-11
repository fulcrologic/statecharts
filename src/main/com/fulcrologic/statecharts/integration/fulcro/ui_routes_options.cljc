(ns com.fulcrologic.statecharts.integration.fulcro.ui-routes-options )

(def initialize
  "One of :once, :always, or :never. Indicates when the ui routing entry
   handler will initialize the state of the target. The default is :once"
  :com.fulcrologic.statecharts.integration.fulcro.ui-routes/initialize)

(def initial-props
  "A (fn [env data] tree) that returns the desired initial state of the
   route's target. Defaults to a function that pulls the initial-state of
   the component."
  :com.fulcrologic.statecharts.integration.fulcro.ui-routes/initial-props)
