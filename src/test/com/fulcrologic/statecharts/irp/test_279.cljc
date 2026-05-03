(ns com.fulcrologic.statecharts.irp.test-279
  "IRP test 279 — verifies early-binding semantics: a `<data>` declared inside
   state s1 is initialized at chart start (before s1 is visited), so it is
   already readable from s0.

   This library uses a flat working-memory data model and initializes all
   `data-model` declarations at chart start (early binding), which matches
   the W3C requirement.

   Source: https://www.w3.org/Voice/2013/scxml-irp/279/test279.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-279
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (transition {:cond (fn [_ d] (= 1 (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (state {:id :s1}
        (data-model {:expr {:Var1 1}}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 279 — early binding initializes data before state is visited"
  (assertions
    "reaches pass"
    (runner/passes? chart-279 []) => true))
