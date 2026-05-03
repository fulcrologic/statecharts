(ns com.fulcrologic.statecharts.irp.test-409
  "IRP test 409 — states are removed from the active configuration as they
   are exited. When s01's on-exit fires, s011 is already gone from the
   configuration, so In(s011) is false and event1 is NOT raised.
   The delayed timeout then fires to indicate success.

   Source: https://www.w3.org/Voice/2013/scxml-irp/409/test409.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry on-exit If raise Send In]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-409
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0 :initial :s01}
        (on-entry {}
          (Send {:event :timeout :delay 500}))
        (transition {:event :timeout :target :pass})
        (transition {:event :event1 :target :fail})
        (state {:id :s01 :initial :s011}
          (on-exit {}
            (If {:cond (In :s011)}
              (raise {:event :event1})))
          (state {:id :s011}
            (transition {:target :s02})))
        (state {:id :s02}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 409 — states removed from config as exited; In() is false in onexit"
  (assertions
    "reaches pass"
    (runner/passes-with-delays? chart-409 events) => true))
