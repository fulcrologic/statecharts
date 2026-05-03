(ns com.fulcrologic.statecharts.irp.test-413
  "IRP test 413 — the state machine starts in the configuration specified by
   the initial attribute rather than document-order defaults.

   The original SCXML uses `initial=\"s2p112 s2p122\"`. We achieve the same
   by setting `:initial :s2p112` on s2p11 and `:initial :s2p122` on s2p12
   so each parallel region begins in the non-default child state.

   Source: https://www.w3.org/Voice/2013/scxml-irp/413/test413.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state parallel final transition In]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-413
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s2}
      (state {:id :s2 :initial :s2p1}
        (parallel {:id :s2p1}
          (state {:id :s2p11 :initial :s2p112}
            (state {:id :s2p111}
              (transition {:target :fail}))
            (state {:id :s2p112}
              (transition {:cond (In :s2p122) :target :pass})))
          (state {:id :s2p12 :initial :s2p122}
            (state {:id :s2p121}
              (transition {:target :fail}))
            (state {:id :s2p122}
              (transition {:cond (In :s2p112) :target :pass})))))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 413 — initial attribute overrides document-order defaults"
  (assertions
    "reaches pass"
    (runner/passes? chart-413 events) => true))
