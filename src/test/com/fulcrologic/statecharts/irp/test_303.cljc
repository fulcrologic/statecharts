(ns com.fulcrologic.statecharts.irp.test-303
  "IRP test 303 — verifies that `<script>` runs as part of executable content,
   changing a variable at the right point. Var1 is initialised to 0; an
   `<assign>` sets it to 2; the subsequent `<script>` sets it to 1. If the
   transition thereafter finds Var1 == 1 the chart reaches pass (script ran,
   in order, after the assign).

   Source: https://www.w3.org/Voice/2013/scxml-irp/303/test303.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry assign script
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-303
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 0}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (assign {:location [:Var1] :expr (fn [_ _] 2)})
          (script {:expr (fn [_ _] (ops/set-map-ops {:Var1 1}))}))
        (transition {:cond (fn [_ d] (= 1 (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 303 — script runs as executable content in order"
  (assertions
    "reaches pass"
    (runner/passes? chart-303 []) => true))
