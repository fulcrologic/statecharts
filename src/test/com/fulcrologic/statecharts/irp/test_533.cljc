(ns com.fulcrologic.statecharts.irp.test-533
  "IRP test 533 — an internal transition from a parallel (non-compound) state
   DOES exit its source state (internal only exempts compound states).

   Source: https://www.w3.org/Voice/2013/scxml-irp/533/test533.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state parallel final transition
                                                   on-entry on-exit assign raise
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(defn- inc-var [k]
  (assign {:location [k] :expr (fn [_ d] (inc (get d k 0)))}))

(def chart-533
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 0 :Var2 0 :Var3 0 :Var4 0}})
    (state {:id :_root :initial :s1}
      (state {:id :s1}
        (on-entry {}
          (raise {:event :foo})
          (raise {:event :bar}))
        (transition {:target :p}))
      (parallel {:id :p}
        (on-exit {} (inc-var :Var1))
        (transition {:event :foo :type :internal :target :ps1}
          (inc-var :Var4))
        (transition {:event :bar :cond (fn [_ d] (= 1 (:Var4 d))) :target :s2})
        (transition {:event :bar :target :fail})
        (state {:id :ps1}
          (on-exit {} (inc-var :Var2)))
        (state {:id :ps2}
          (on-exit {} (inc-var :Var3))))
      (state {:id :s2}
        (transition {:cond (fn [_ d] (= 2 (:Var1 d))) :target :s3})
        (transition {:target :fail}))
      (state {:id :s3}
        (transition {:cond (fn [_ d] (= 2 (:Var2 d))) :target :s4})
        (transition {:target :fail}))
      (state {:id :s4}
        (transition {:cond (fn [_ d] (= 2 (:Var3 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 533 — internal transition on parallel state exits the parallel"
  (assertions
    "reaches pass"
    (runner/passes? chart-533 events) => true))
