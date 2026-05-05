(ns com.fulcrologic.statecharts.irp.test-402
  "IRP test 402 — errors are pulled off the internal queue in order, and prefix
   matching works on them (event prefix `error` matches `error.execution`).

   Sequence: onentry of s01 raises :event1, then an illegal assign raises
   error.execution. Transition on :event1 to s02 raises :event2. In s02 the
   prefix `error` should match the queued error.execution → s03. In s03
   :event2 → :pass.

   Source: https://www.w3.org/Voice/2013/scxml-irp/402/test402.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry raise assign Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-402
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0 :initial :s01}
        (on-entry {}
          ;; failure-case timeout
          (Send {:event :timeout :delay 1000}))
        (transition {:event :timeout :target :fail})

        (state {:id :s01}
          (on-entry {}
            (raise {:event :event1})
            ;; illegal expr — raises error.execution (matches test312 pattern)
            (assign {:location [:Var1]
                     :expr     (fn [_ _] (throw (ex-info "illegal" {})))}))
          (transition {:event :event1 :target :s02}
            (raise {:event :event2}))
          (transition {:event :* :target :fail}))

        (state {:id :s02}
          (transition {:event :error :target :s03})
          (transition {:event :* :target :fail}))

        (state {:id :s03}
          (transition {:event :event2 :target :pass})
          (transition {:event :* :target :fail})))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 402 — errors queued in order with prefix matching"
  (assertions
    "reaches pass"
    (runner/passes? chart-402 []) => true))
