(ns com.fulcrologic.statecharts.irp.test-150
  "IRP test 150 — verifies that `<foreach>` can write to a pre-declared `item`
   variable (Var1/Var2) and also to an undeclared variable (Var4). After the
   second foreach, Var4 must be bound (non-nil) — the array is [1,2,3] so it
   ends up as 3.

   Source: https://www.w3.org/Voice/2013/scxml-irp/150/test150.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry raise foreach
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-150
  (chart/statechart {:initial :_root}
    ;; Var1/Var2/Var3 are declared; Var4/Var5 are undeclared
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
          ;; Var4/Var5 are undeclared — foreach must still write to them
          (foreach {:array (fn [_ d] (:Var3 d)) :item [:Var4] :index [:Var5]})
          (raise {:event :bar}))
        (transition {:event :error :target :fail})
        (transition {:event :* :target :s2}))
      (state {:id :s2}
        ;; Var4 should be bound (set to last item, 3) after the second foreach
        (transition {:cond (fn [_ d] (some? (:Var4 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 150 — foreach writes to declared and undeclared item variables"
  (assertions
    "reaches pass"
    (runner/passes? chart-150 []) => true))
