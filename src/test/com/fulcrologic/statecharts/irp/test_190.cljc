(ns com.fulcrologic.statecharts.irp.test-190
  "IRP test 190 — `<send>` with target=#_scxml_<sessionid> routes through the
   external queue. Internal-queued events (raise) must be processed before
   external-queued events. This port simplifies the `targetExpr=#_scxml_<id>`
   to a plain `Send` to self (which already uses the external queue).

   Source: https://www.w3.org/Voice/2013/scxml-irp/190/test190.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry raise Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-190
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          ;; goes to external queue (target = self via #_scxml_<sessionid>)
          (Send {:event :event2})
          ;; goes to internal queue
          (raise {:event :event1})
          ;; safety net so the test doesn't hang
          (Send {:event :timeout}))
        (transition {:event :event1 :target :s1})
        (transition {:event :*      :target :fail}))
      (state {:id :s1}
        (transition {:event :event2 :target :pass})
        (transition {:event :*      :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 190 — send to self routes via external queue; internal first"
  (assertions
    "reaches pass"
    (runner/passes? chart-190 []) => true))
