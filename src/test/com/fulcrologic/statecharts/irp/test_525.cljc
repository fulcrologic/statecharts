(ns com.fulcrologic.statecharts.irp.test-525
  "IRP test 525 — verifies that `<foreach>` does a shallow copy, so that modifying
   the array during iteration does NOT change the iteration count.

   Var1 starts as [1 2 3]. Inside the foreach body, each iteration appends to Var1
   (extending the array) and increments Var2. If foreach iterated over the *live*
   array, Var2 would grow without bound; the W3C spec requires the array be
   shallow-copied at the start, so Var2 must equal 3 after the loop completes.

   Source: https://www.w3.org/Voice/2013/scxml-irp/525/test525.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry foreach script
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-525
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 [1 2 3] :Var2 0 :Var3 nil}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (foreach {:array (fn [_ d] (:Var1 d)) :item [:Var3] :index nil}
            ;; conf:extendArray — append a new element to Var1
            (script {:expr (fn [_ d] (ops/set-map-ops {:Var1 (conj (vec (:Var1 d)) 99)}))})
            ;; conf:incrementID — increment Var2 (iteration counter)
            (script {:expr (fn [_ d] (ops/set-map-ops {:Var2 (inc (:Var2 d 0))}))})))
        (transition {:cond (fn [_ d] (= 3 (:Var2 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 525 — foreach shallow-copies the array, iteration count is fixed"
  (assertions
    "reaches pass"
    (runner/passes? chart-525 []) => true))
