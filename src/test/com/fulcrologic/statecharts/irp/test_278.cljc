(ns com.fulcrologic.statecharts.irp.test-278
  "IRP test 278 — verifies that a data variable declared in state s1 is
   accessible from state s0 (outside its lexical scope). This library uses a
   flat working-memory data model, so the test is naturally satisfied —
   `:Var1` is global once initialized.

   Source: https://www.w3.org/Voice/2013/scxml-irp/278/test278.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-278
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (transition {:cond (fn [_ d] (= 1 (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (state {:id :s1}
        (data-model {:expr {:Var1 1}}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 278 — data variable accessible outside lexical scope"
  (assertions
    "reaches pass"
    (runner/passes? chart-278 []) => true))
