(ns com.fulcrologic.statecharts.irp.test-411
  "IRP test 411 — states are added to the active configuration before their
   on-entry handlers execute. When s0's on-entry fires, s01 is not yet in
   the configuration (In(s01) = false). When s01's on-entry fires, s01 IS
   in the configuration (In(s01) = true).

   Source: https://www.w3.org/Voice/2013/scxml-irp/411/test411.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry If raise Send In]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-411
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0 :initial :s01}
        (on-entry {}
          (Send {:event :timeout :delay 500})
          ;; In(s01) is false during s0's on-entry
          (If {:cond (In :s01)}
            (raise {:event :event1})))
        (transition {:event :timeout :target :fail})
        (transition {:event :event1 :target :fail})
        (transition {:event :event2 :target :pass})
        (state {:id :s01}
          (on-entry {}
            ;; In(s01) is true during s01's own on-entry
            (If {:cond (In :s01)}
              (raise {:event :event2})))))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 411 — states added to config before on-entry fires"
  (assertions
    "reaches pass"
    (runner/passes? chart-411 events) => true))
