(ns com.fulcrologic.statecharts.irp.test-185
  "IRP test 185 — `<send>` respects the `delay` attribute. event2 is sent with
   a 1s delay, event1 is sent immediately; event1 must arrive first.

   Source: https://www.w3.org/Voice/2013/scxml-irp/185/test185.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-185
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (Send {:event :event2 :delay 1000})
          (Send {:event :event1}))
        (transition {:event :event1 :target :s1})
        (transition {:event :*      :target :fail}))
      (state {:id :s1}
        (transition {:event :event2 :target :pass})
        (transition {:event :*      :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 185 — send respects delay (event1 before event2)"
  (assertions
    "reaches pass"
    (runner/passes-with-delays? chart-185 []) => true))
