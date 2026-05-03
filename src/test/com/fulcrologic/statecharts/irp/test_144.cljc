(ns com.fulcrologic.statecharts.irp.test-144
  "IRP test 144 — verifies events are inserted into the internal queue in raise
   order. Two `raise`s in onentry: foo then bar. The chart must see foo before
   bar; otherwise it transitions to fail.
   Source: https://www.w3.org/Voice/2013/scxml-irp/144/test144.txml

   Note: pass/fail are wrapped inside a parent state so the interpreter does
   not shut down (and clear configuration) on entry to a top-level final."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition on-entry raise]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-144
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (raise {:event :foo})
          (raise {:event :bar}))
        (transition {:event :foo :target :s1})
        (transition {:event :*    :target :fail}))
      (state {:id :s1}
        (transition {:event :bar :target :pass})
        (transition {:event :*   :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 144 — raised events are queued in raise order (foo before bar)"
  (assertions
    "reaches pass"
    (runner/passes? chart-144 []) => true))
