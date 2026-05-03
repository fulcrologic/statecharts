(ns com.fulcrologic.statecharts.irp.test-364
  "IRP test 364 — default initial states: initial attribute, initial element, first child.

   Tests three ways to specify initial entry:
   1. `initial` attribute with multiple states (parallel initial)
   2. `<initial><transition>` element
   3. Default: first child in document order

   Source: https://www.w3.org/Voice/2013/scxml-irp/364/test364.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state parallel final transition
                                                   initial on-entry raise Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-364
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s1}
      (state {:id :s1 :initial [:s11p112 :s11p122]}
        (on-entry {} (Send {:event :timeout :delay 1000}))
        (transition {:event :timeout :target :fail})
        (state {:id :s11 :initial :s111}
          (state {:id :s111})
          (parallel {:id :s11p1}
            (state {:id :s11p11 :initial :s11p111}
              (state {:id :s11p111})
              (state {:id :s11p112}
                (on-entry {} (raise {:event :In-s11p112}))))
            (state {:id :s11p12 :initial :s11p121}
              (state {:id :s11p121})
              (state {:id :s11p122}
                (transition {:event :In-s11p112 :target :s2}))))))
      (state {:id :s2}
        (initial {}
          (transition {:target [:s21p112 :s21p122]}))
        (transition {:event :timeout :target :fail})
        (state {:id :s21 :initial :s211}
          (state {:id :s211})
          (parallel {:id :s21p1}
            (state {:id :s21p11 :initial :s21p111}
              (state {:id :s21p111})
              (state {:id :s21p112}
                (on-entry {} (raise {:event :In-s21p112}))))
            (state {:id :s21p12 :initial :s21p121}
              (state {:id :s21p121})
              (state {:id :s21p122}
                (transition {:event :In-s21p112 :target :s3}))))))
      ;; s3 uses default first-child initial: s31 → s311 → s3111
      (state {:id :s3}
        (transition {:target :fail})
        (state {:id :s31}
          (state {:id :s311}
            (state {:id :s3111}
              (transition {:target :pass}))
            (state {:id :s3112}))
          (state {:id :s312}))
        (state {:id :s32}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 364 — initial attribute, initial element, and first-child default"
  (assertions
    "reaches pass"
    (runner/passes? chart-364 events) => true))
