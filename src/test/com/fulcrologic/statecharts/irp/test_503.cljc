(ns com.fulcrologic.statecharts.irp.test-503
  "IRP test 503 — a targetless transition does not exit and re-enter its
   source state.

   Source: https://www.w3.org/Voice/2013/scxml-irp/503/test503.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry on-exit assign raise
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(defn- inc-var [k]
  (assign {:location [k] :expr (fn [_ d] (inc (get d k 0)))}))

(def chart-503
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 0 :Var2 0}})
    (state {:id :_root :initial :s1}
      (state {:id :s1}
        (on-entry {}
          (raise {:event :foo})
          (raise {:event :bar}))
        (transition {:target :s2}))
      (state {:id :s2}
        (on-exit {} (inc-var :Var1))
        ;; targetless — does NOT exit s2
        (transition {:event :foo}
          (inc-var :Var2))
        (transition {:event :bar :cond (fn [_ d] (= 1 (:Var2 d))) :target :s3})
        (transition {:event :bar :target :fail}))
      (state {:id :s3}
        ;; s2 exited exactly once (when bar transition fired)
        (transition {:cond (fn [_ d] (= 1 (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 503 — targetless transition does not exit source state"
  (assertions
    "reaches pass"
    (runner/passes? chart-503 events) => true))
