(ns com.fulcrologic.statecharts.irp.test-495
  "IRP test 495 — SCXML I/O processor puts events in correct queues.
   `<send>` default target is the external queue; `target=\"#_internal\"`
   routes to the internal queue. Internal events are processed before
   external ones, so event2 (internal) must arrive before event1 (external).

   Source: https://www.w3.org/Voice/2013/scxml-irp/495/test495.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-495
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          ;; default target = external queue
          (Send {:event :event1 :type "http://www.w3.org/TR/scxml/#SCXMLEventProcessor"})
          ;; explicit internal-queue target
          (Send {:event :event2 :target :_internal
                 :type "http://www.w3.org/TR/scxml/#SCXMLEventProcessor"}))
        ;; internal event2 must arrive first; if event1 (external) wins, fail
        (transition {:event :event1 :target :fail})
        (transition {:event :event2 :target :s1}))
      (state {:id :s1}
        (transition {:event :event1 :target :pass})
        (transition {:event :*      :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 495 — #_internal target routes to internal queue (internal events first)"
  (assertions
    "reaches pass"
    (runner/passes? chart-495 []) => true))
