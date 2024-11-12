(ns com.fulcrologic.statecharts.integration.fulcro.ui-routes-options )

(def initialize
  "Component option. One of :once, :always, or :never. Indicates when the ui routing entry
   handler will initialize the state of the target. The default is :once"
  :com.fulcrologic.statecharts.integration.fulcro.ui-routes/initialize)

(def initial-props
  "Component option. A (fn [env data] tree) that returns the desired initial state of the
   route's target. Defaults to a function that pulls the initial-state of
   the component."
  :com.fulcrologic.statecharts.integration.fulcro.ui-routes/initial-props)

(def busy?
  "Component option. A (fn [env data] boolean?) that indicates if a route is busy and should not
   be interrupted. All active route states will be asked this, so it is best to just put it on leaf
   routes (and not parents)."
  :com.fulcrologic.statecharts.integration.fulcro.ui-routes/busy?
  )
