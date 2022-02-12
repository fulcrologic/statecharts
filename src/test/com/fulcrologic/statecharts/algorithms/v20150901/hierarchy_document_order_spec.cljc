(ns com.fulcrologic.statecharts.algorithms.v20150901.hierarchy-document-order-spec
  (:require [com.fulcrologic.statecharts.elements :refer
             [state initial parallel final transition raise on-entry on-exit
              data-model assign script]]
            [com.fulcrologic.statecharts :as sc]
            [com.fulcrologic.statecharts.chart :as chart]
            [com.fulcrologic.statecharts.testing :as testing]
            [com.fulcrologic.statecharts.data-model.operations :as ops]
            [fulcro-spec.core :refer [specification assertions =>]]))

(specification "test0"
  (let [chart (chart/statechart
                {}
                (state {:id :a}
                  (state {:id :a1}
                    (transition {:target :a2, :event :t})
                    (transition {:target :c, :event :t}))
                  (state {:id :a2})
                  (transition {:target :b, :event :t}))
                (state {:id :b})
                (state {:id :c}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a1) => true)
    (testing/run-events! env :t)
    (assertions (testing/in? env :a2) => true)))

(specification "test1"
  (let [chart (chart/statechart
                {}
                (state {:id :a}
                  (state {:id :a1}
                    (transition {:target :b, :event :t})
                    (transition {:target :c, :event :t}))
                  (state {:id :a2})
                  (transition {:target :a2, :event :t}))
                (state {:id :b})
                (state {:id :c}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a1) => true)
    (testing/run-events! env :t)
    (assertions (testing/in? env :b) => true)))