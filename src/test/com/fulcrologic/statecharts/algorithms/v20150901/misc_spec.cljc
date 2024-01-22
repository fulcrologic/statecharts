(ns com.fulcrologic.statecharts.algorithms.v20150901.misc-spec
  (:require [com.fulcrologic.statecharts.chart :as chart]
            [com.fulcrologic.statecharts.elements :refer
             [on-entry state transition]]
            [com.fulcrologic.statecharts.testing :as testing]
            [fulcro-spec.core :refer [=> assertions specification]]))

(specification
  "deep-initial"
  (let [chart (chart/statechart
                {:initial :s2}
                (state {:id :uber}
                  (state {:id :s1}
                    (on-entry {})
                    (transition {:event :ev1, :target :s2}))
                  (state {:id :s2})))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :s2) => true)))
