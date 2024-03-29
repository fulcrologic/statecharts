(ns com.fulcrologic.statecharts.algorithms.v20150901.default-initial-state-spec
  (:require [com.fulcrologic.statecharts.chart :as chart]
            [com.fulcrologic.statecharts.elements :refer
             [state transition]]
            [com.fulcrologic.statecharts.testing :as testing]
            [fulcro-spec.core :refer [=> assertions specification]]))

(specification
  "initial1"
  (let [chart (chart/statechart {}
                (state {:id :a}
                  (transition {:target :b, :event :t}))
                (state {:id :b}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t)
    (assertions (testing/in? env :b) => true)))

(specification
  "initial2"
  (let [chart (chart/statechart {:initial :a}
                (state {:id :a}
                  (transition {:target :b, :event :t}))
                (state {:id :b}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t)
    (assertions (testing/in? env :b) => true)))
