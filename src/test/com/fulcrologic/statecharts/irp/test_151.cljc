(ns com.fulcrologic.statecharts.irp.test-151
  "IRP test 151 — same as test 150 but checks that the undeclared `index`
   variable (Var5) is bound after the second foreach. Var5 should be the last
   index (2, for a 3-element array).

   Source: https://www.w3.org/Voice/2013/scxml-irp/151/test151.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry raise foreach
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-151
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 nil :Var2 nil :Var3 [1 2 3]}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (foreach {:array (fn [_ d] (:Var3 d)) :item [:Var1] :index [:Var2]})
          (raise {:event :foo}))
        (transition {:event :error :target :fail})
        (transition {:event :* :target :s1}))
      (state {:id :s1}
        (on-entry {}
          (foreach {:array (fn [_ d] (:Var3 d)) :item [:Var4] :index [:Var5]})
          (raise {:event :bar}))
        (transition {:event :error :target :fail})
        (transition {:event :* :target :s2}))
      (state {:id :s2}
        ;; Var5 should be bound (set to last index, 2) after the second foreach
        (transition {:cond (fn [_ d] (some? (:Var5 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 151 — foreach writes to undeclared index variable (Var5 is bound)"
  (assertions
    "reaches pass"
    (runner/passes? chart-151 []) => true))
