(ns com.fulcrologic.statecharts.irp.test-189
  "IRP test 189 — `<send target=\"#_internal\">` must place the event on the
   internal queue, so it is processed before any pending external events.

   onentry sends `event2` (external) first, then `event1` with target
   `#_internal`. Because the internal queue is drained before the external
   queue, `event1` must be the first one observed.

   Source: https://www.w3.org/Voice/2013/scxml-irp/189/test189.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-189
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (Send {:event :event2})
          (Send {:event :event1 :target :_internal}))
        (transition {:event :event1 :target :pass})
        (transition {:event :event2 :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 189 — send target=#_internal queues internally (event1 before event2)"
  (assertions
    "reaches pass"
    (runner/passes? chart-189 []) => true))
