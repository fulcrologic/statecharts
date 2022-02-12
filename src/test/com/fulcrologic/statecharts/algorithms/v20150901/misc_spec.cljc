(ns com.fulcrologic.statecharts.algorithms.v20150901.misc-spec
  (:require [com.fulcrologic.statecharts.elements :refer
             [state initial parallel final transition raise on-entry on-exit
              data-model assign script history log]]
            [com.fulcrologic.statecharts :as sc]
            [com.fulcrologic.statecharts.chart :as chart]
            [com.fulcrologic.statecharts.testing :as testing]
            [com.fulcrologic.statecharts.data-model.operations :as ops]
            [fulcro-spec.core :refer [specification assertions =>]]))

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