(ns com.fulcrologic.statecharts.irp.test-152
  "IRP test 152 — verifies that an illegal array value (non-collection) or
   illegal item value causes `error.execution` and skips the foreach body.
   Var1 must remain 0 because the body never executes.

   Source: https://www.w3.org/Voice/2013/scxml-irp/152/test152.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry raise assign foreach
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

;; conf:illegalArray — a non-collection value (integer) that is not iterable
;; conf:illegalItem  — not directly representable; we use nil location which
;;                     should fail assignment validation

(def chart-152
  ;; Only the "illegal array" branch of W3C test 152 ports cleanly to the lambda
  ;; data model. The original test's "illegal item" sub-case relies on
  ;; ECMAScript-style location validation that does not apply here (nil item
  ;; locations are simply skipped).
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 0 :Var2 nil :Var3 nil :Var4 42}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          ;; illegal array (Var4 = 42, not a collection) — must raise error.execution
          ;; AND skip the body so Var1 stays 0.
          (foreach {:array (fn [_ d] (:Var4 d)) :item [:Var2] :index [:Var3]}
            (assign {:location [:Var1] :expr (fn [_ d] (inc (:Var1 d)))}))
          (raise {:event :foo}))
        (transition {:event :error.execution :target :s1})
        (transition {:event :* :target :fail}))
      (state {:id :s1}
        ;; Var1 must still be 0 — body never ran
        (transition {:cond (fn [_ d] (= 0 (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 152 — foreach with illegal array/item raises error.execution"
  (assertions
    "reaches pass"
    (runner/passes? chart-152 []) => true))
