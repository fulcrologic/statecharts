(ns com.fulcrologic.statecharts.algorithms.v20150901.hierarchy-spec
  (:require [com.fulcrologic.statecharts.chart :as chart]
            [com.fulcrologic.statecharts.elements :refer
             [state transition]]
            [com.fulcrologic.statecharts.testing :as testing]
            [fulcro-spec.core :refer [=> assertions specification]]))

(specification
  "hier0"
  (let [chart (chart/statechart
                {}
                (state {:id :a}
                  (state {:id :a1} (transition {:target :a2, :event :t}))
                  (state {:id :a2})))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a1) => true)
    (testing/run-events! env :t)
    (assertions (testing/in? env :a2) => true)))

(specification
  "hier1"
  (let [chart (chart/statechart
                {}
                (state {:id :a}
                  (state {:id :a1} (transition {:target :a2, :event :t}))
                  (state {:id :a2})
                  (transition {:target :b, :event :t}))
                (state {:id :b}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a1) => true)
    (testing/run-events! env :t)
    (assertions (testing/in? env :a2) => true)))

(specification
  "hier2"
  (let [chart (chart/statechart
                {}
                (state {:id :a}
                  (state {:id :a1} (transition {:target :b, :event :t}))
                  (state {:id :a2})
                  (transition {:target :a2, :event :t}))
                (state {:id :b}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a1) => true)
    (testing/run-events! env :t)
    (assertions (testing/in? env :b) => true)))
