(ns com.fulcrologic.statecharts.algorithms.v20150901.internal-transitions-spec
  (:require [com.fulcrologic.statecharts.elements :refer
             [state initial parallel final transition raise on-entry on-exit
              data-model assign script history log data-model]]
            [com.fulcrologic.statecharts :as sc]
            [com.fulcrologic.statecharts.chart :as chart]
            [com.fulcrologic.statecharts.testing :as testing]
            [com.fulcrologic.statecharts.data-model.operations :as ops]
            [fulcro-spec.core :refer [specification assertions =>]]))

(defn inc-x-expr [_ {:keys [x]}]
  (inc x))

(defn make-x-eq-expr [v]
  (fn [_ {:keys [x]}] (= x v)))

(specification "test0"
  (let [chart (chart/statechart {}
              (data-model
                {:expr {:x 0}})
              (state
                {:id :a}
                (on-entry {}
                  (assign {:location :x, :expr inc-x-expr}))
                (on-exit {}
                  (assign {:location :x, :expr inc-x-expr}))
                (state {:id :a1})
                (state {:id :a2}
                  (transition {:target :b, :event :t2, :cond (make-x-eq-expr 1) }))
                (transition
                  {:target :a2, :event :t1, :type :internal, :cond (make-x-eq-expr 1)}))
              (state {:id :b}
                (transition {:target :c, :event :t3, :cond (make-x-eq-expr 2)}))
              (state {:id :c}))
        env (testing/new-testing-env {:statechart chart
                                      :mocking-options {:run-unmocked? true}} {})]
    (testing/start! env)
    (assertions (testing/in? env :a1) => true)
    (testing/run-events! env :t1)
    (assertions (testing/in? env :a2) => true)
    (testing/run-events! env :t2)
    (assertions (testing/in? env :b) => true)
    (testing/run-events! env :t3)
    (assertions (testing/in? env :c) => true)))

(specification
  "test1"
  (let [chart
            (chart/statechart
              {}
              (data-model
                {:expr {:x 0}})
              (parallel
                {:id :p}
                (on-entry {} (assign {:location :x, :expr inc-x-expr}))
                (on-exit {} (assign {:location :x, :expr inc-x-expr}))
                (state
                  {:id :a}
                  (on-entry {} (assign {:location :x, :expr inc-x-expr}))
                  (on-exit {} (assign {:location :x, :expr inc-x-expr}))
                  (state {:id :a1}
                    (on-entry {} (assign {:location :x, :expr inc-x-expr}))
                    (on-exit {} (assign {:location :x, :expr inc-x-expr})))
                  (state {:id :a2}
                    (on-entry {} (assign {:location :x, :expr inc-x-expr}))
                    (on-exit {} (assign {:location :x, :expr inc-x-expr}))
                    (transition {:target :c, :event :t2, :cond (make-x-eq-expr 5)}))
                  (transition
                    {:target :a2, :event :t1, :type :internal, :cond (make-x-eq-expr 3)}))
                (state {:id :b} (state {:id :b1}) (state {:id :b2})))
              (state {:id :c}
                (transition {:target :d, :event :t3, :cond (make-x-eq-expr 8)}))
              (state {:id :d}))
        env (testing/new-testing-env {:statechart chart
                                      :mocking-options {:run-unmocked? true}} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :a1) => true
      (testing/in? env :b1) => true)
    (testing/run-events! env :t1)
    (assertions
      (testing/in? env :a2) => true
      (testing/in? env :b1) => true)
    (testing/run-events! env :t2)
    (assertions
      (testing/in? env :c) => true)
    (testing/run-events! env :t3)
    (assertions
      (testing/in? env :d) => true)))
