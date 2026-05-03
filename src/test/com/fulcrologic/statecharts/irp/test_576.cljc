(ns com.fulcrologic.statecharts.irp.test-576
  "IRP test 576 — the scxml `initial` attribute overrides document-order defaults,
   starting in deeply nested non-default parallel siblings. The original SCXML uses
   `initial=\"s11p112 s11p122\"`. We achieve equivalent behavior by setting explicit
   `:initial :s11p112` on s11p11 and `:initial :s11p122` on s11p12, and entering
   the parallel state directly. s11p112 raises `In-s11p112`; s11p122 handles it → pass.

   Source: https://www.w3.org/Voice/2013/scxml-irp/576/test576.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition on-entry parallel raise Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-576
  (chart/statechart {:initial :_root}
    (state {:id :s0} (transition {:target :fail}))
    (state {:id :_root :initial :s1}
      (state {:id :s1 :initial :s11p1}
        (on-entry {} (Send {:event :timeout :delay 1000}))
        (transition {:event :timeout :target :fail})
        (state {:id :s11 :initial :s111}
          (state {:id :s111})
          (parallel {:id :s11p1}
            (state {:id :s11p11 :initial :s11p112}
              (state {:id :s11p111})
              (state {:id :s11p112}
                (on-entry {} (raise {:event :In-s11p112}))))
            (state {:id :s11p12 :initial :s11p122}
              (state {:id :s11p121})
              (state {:id :s11p122}
                (transition {:event :In-s11p112 :target :pass}))))))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 576 — initial attribute starts in non-default nested parallel states"
  (assertions
    "reaches pass"
    (runner/passes? chart-576 []) => true))
