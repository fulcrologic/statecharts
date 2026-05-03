(ns com.fulcrologic.statecharts.irp.test-505
  "IRP test 505 — an internal transition does not exit its source state.

   Source: https://www.w3.org/Voice/2013/scxml-irp/505/test505.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry on-exit assign raise
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(defn- inc-var [k]
  (assign {:location [k] :expr (fn [_ d] (inc (get d k 0)))}))

(def chart-505
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 0 :Var2 0 :Var3 0}})
    (state {:id :_root :initial :s1}
      (state {:id :s1}
        (on-entry {}
          (raise {:event :foo})
          (raise {:event :bar}))
        (on-exit {} (inc-var :Var1))
        (transition {:event :foo :type :internal :target :s11}
          (inc-var :Var3))
        (transition {:event :bar :cond (fn [_ d] (= 1 (:Var3 d))) :target :s2})
        (transition {:event :bar :target :fail})
        (state {:id :s11}
          (on-exit {} (inc-var :Var2))))
      (state {:id :s2}
        ;; s1 exited once (only when bar transition fires)
        (transition {:cond (fn [_ d] (= 1 (:Var1 d))) :target :s3})
        (transition {:target :fail}))
      (state {:id :s3}
        ;; s11 exited twice (foo transition + bar transition)
        (transition {:cond (fn [_ d] (= 2 (:Var2 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 505 — internal transition does not exit source state"
  (assertions
    "reaches pass"
    (runner/passes? chart-505 events) => true))
