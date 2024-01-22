(ns com.fulcrologic.statecharts.algorithms.v20150901.basic-spec
  (:require [com.fulcrologic.statecharts.chart :as chart]
            [com.fulcrologic.statecharts.elements :refer
             [state transition]]
            [com.fulcrologic.statecharts.testing :as testing]
            [fulcro-spec.core :refer [=> assertions specification]]))

(specification "basic0"
  (let [chart (chart/statechart {:initial :a} (state {:id :a}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true)))

(specification "basic1"
  (let [chart (chart/statechart {} (state {:id :a} (transition {:target :b, :event :t})) (state {:id :b}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t)
    (assertions (testing/in? env :b) => true)))

(specification "basic2"
  (let [chart (chart/statechart {}
                (state {:id :a} (transition {:target :b, :event :t}))
                (state {:id :b} (transition {:target :c, :event :t2}))
                (state {:id :c}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t)
    (assertions (testing/in? env :b) => true)
    (testing/run-events! env :t2)
    (assertions (testing/in? env :c) => true)))
