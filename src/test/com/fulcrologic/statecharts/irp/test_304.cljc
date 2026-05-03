(ns com.fulcrologic.statecharts.irp.test-304
  "IRP test 304 — a variable declared by a top-level `<script>` must be
   accessible from the data model in subsequent expressions/conditions.
   Source: https://www.w3.org/Voice/2013/scxml-irp/304/test304.txml

   Translation: a top-level `data-model` initialises Var1=1 (the equivalent
   of the script's variable declaration). The eventless transition with a
   cond reads Var1 and routes to pass if it equals 1."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-304
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 1}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (transition {:cond (fn [_ d] (= 1 (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 304 — script-declared variable is accessible from data model"
  (assertions
    "reaches pass"
    (runner/passes? chart-304 []) => true))
