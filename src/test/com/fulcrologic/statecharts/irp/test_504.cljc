(ns com.fulcrologic.statecharts.irp.test-504
  "IRP test 504 — an external transition exits all states up to the LCCA.

   Source: https://www.w3.org/Voice/2013/scxml-irp/504/test504.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state parallel final transition
                                                   on-entry on-exit assign raise
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(defn- inc-var [k]
  (assign {:location [k] :expr (fn [_ d] (inc (get d k 0)))}))

(def chart-504
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 0 :Var2 0 :Var3 0 :Var4 0 :Var5 0}})
    (state {:id :_root :initial :s1}
      (state {:id :s1}
        (on-entry {}
          (raise {:event :foo})
          (raise {:event :bar}))
        (transition {:target :p}))
      (state {:id :s2}
        (on-exit {} (inc-var :Var5))
        (parallel {:id :p}
          (on-exit {} (inc-var :Var1))
          (transition {:event :foo :target :ps1}
            (inc-var :Var4))
          (transition {:event :bar :cond (fn [_ d] (= 1 (:Var4 d))) :target :s3})
          (transition {:event :bar :target :fail})
          (state {:id :ps1}
            (on-exit {} (inc-var :Var2)))
          (state {:id :ps2}
            (on-exit {} (inc-var :Var3)))))
      (state {:id :s3}
        (transition {:cond (fn [_ d] (= 2 (:Var1 d))) :target :s4})
        (transition {:target :fail}))
      (state {:id :s4}
        (transition {:cond (fn [_ d] (= 2 (:Var2 d))) :target :s5})
        (transition {:target :fail}))
      (state {:id :s5}
        (transition {:cond (fn [_ d] (= 2 (:Var3 d))) :target :s6})
        (transition {:target :fail}))
      (state {:id :s6}
        (transition {:cond (fn [_ d] (= 1 (:Var5 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 504 — external transition exits all states to LCCA"
  (assertions
    "reaches pass"
    (runner/passes? chart-504 events) => true))
