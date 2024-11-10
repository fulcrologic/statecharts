(ns com.fulcrologic.statecharts.integration.fulcro.ui-routes-options
  "ALPHA. This namespace's API is subject to change.")

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
  :com.fulcrologic.statecharts.integration.fulcro.ui-routes/busy?)

(def statechart
  "Component option. A co-located statechart (definition). Will auto-register the chart using the registry
   key of the containing component.
   "
  :com.fulcrologic.statecharts.integration.fulcro.ui-routes/statechart)

(def statechart-id
  "Component option. The ID of a pre-registered statechart that will be run when the component is routed to.
   "
  :com.fulcrologic.statecharts.integration.fulcro.ui-routes/statechart-id)

(def actors
  "Component option. A (fn [] actors-map) that returns a map of additional actors to be used with a co-located statechart. "
  :com.fulcrologic.statecharts.integration.fulcro.ui-routes/actors)
