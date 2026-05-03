(ns com.fulcrologic.statecharts.irp.test-506
  "IRP test 506 — an internal transition whose target is not a proper
   descendant of its source state behaves like an external transition.

   Source: https://www.w3.org/Voice/2013/scxml-irp/506/test506.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry on-exit assign raise
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(defn- inc-var [k]
  (assign {:location [k] :expr (fn [_ d] (inc (get d k 0)))}))

(def chart-506
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 0 :Var2 0 :Var3 0}})
    (state {:id :_root :initial :s1}
      (state {:id :s1}
        (on-entry {}
          (raise {:event :foo})
          (raise {:event :bar}))
        (transition {:target :s2}))
      (state {:id :s2 :initial :s21}
        (on-exit {} (inc-var :Var1))
        ;; internal to s2 (not proper descendant) → behaves like external
        (transition {:event :foo :type :internal :target :s2}
          (inc-var :Var3))
        (transition {:event :bar :cond (fn [_ d] (= 1 (:Var3 d))) :target :s3})
        (transition {:event :bar :target :fail})
        (state {:id :s21}
          (on-exit {} (inc-var :Var2))))
      (state {:id :s3}
        ;; s2 exited twice
        (transition {:cond (fn [_ d] (= 2 (:Var1 d))) :target :s4})
        (transition {:target :fail}))
      (state {:id :s4}
        ;; s21 exited twice
        (transition {:cond (fn [_ d] (= 2 (:Var2 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 506 — internal transition to non-descendant is external"
  (assertions
    "reaches pass"
    (runner/passes? chart-506 events) => true))
