(ns com.fulcrologic.statecharts.irp.test-378
  "IRP test 378 — each `<onexit>` handler is a separate executable block.
   An illegal `<send>` in the first handler causes an error, but the second
   handler (which increments Var1 from 1 to 2) still runs independently.
   The machine reaches pass when Var1 == 2.

   Source: https://www.w3.org/Voice/2013/scxml-irp/378/test378.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition on-exit Send script data-model]]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-378
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 1}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-exit {} (Send {:event :event1 :type "x-illegal-target" :target "#_nonexistent"}))
        (on-exit {} (script {:expr (fn [_ d] (ops/set-map-ops {:Var1 (inc (:Var1 d 0))}))}))
        (transition {:target :s1}))
      (state {:id :s1}
        (transition {:cond (fn [_ d] (= 2 (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 378 — each onexit is a separate executable block"
  (assertions
    "reaches pass"
    (runner/passes? chart-378 []) => true))
