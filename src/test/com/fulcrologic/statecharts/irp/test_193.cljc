(ns com.fulcrologic.statecharts.irp.test-193
  "IRP test 193 — `<send>` without a `target` (or with explicit
   type=SCXMLEventProcessor) places the event on the external queue. Both
   `internal` and `event1` are queued externally and dequeued in send order, so
   `internal` (sent first) is delivered first.

   Source: https://www.w3.org/Voice/2013/scxml-irp/193/test193.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-193
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (Send {:event :internal})
          (Send {:event :event1})
          (Send {:event :timeout :delay 1000}))
        (transition {:event :event1   :target :fail})
        (transition {:event :internal :target :s1}))
      (state {:id :s1}
        (transition {:event :event1  :target :pass})
        (transition {:event :timeout :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 193 — send without target uses external queue"
  (assertions
    "reaches pass"
    (runner/passes-with-delays? chart-193 []) => true))
