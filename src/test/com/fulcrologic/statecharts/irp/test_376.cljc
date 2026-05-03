(ns com.fulcrologic.statecharts.irp.test-376
  "IRP test 376 — each `<onentry>` handler is a separate executable block.
   An illegal `<send>` in the first handler causes an error, but the second
   handler (which increments Var1 from 1 to 2) still runs. The machine reaches
   pass when Var1 == 2.

   Source: https://www.w3.org/Voice/2013/scxml-irp/376/test376.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition on-entry Send script data-model]]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-376
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 1}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        ;; First onentry: illegal Send target causes error (separate block)
        (on-entry {} (Send {:event :event1 :type "x-illegal-target" :target "#_nonexistent"}))
        ;; Second onentry: increment Var1 (runs independently)
        (on-entry {} (script {:expr (fn [_ d] (ops/set-map-ops {:Var1 (inc (:Var1 d 0))}))}))
        (transition {:cond (fn [_ d] (= 2 (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 376 — each onentry is a separate executable block"
  (assertions
    "reaches pass"
    (runner/passes? chart-376 []) => true))
