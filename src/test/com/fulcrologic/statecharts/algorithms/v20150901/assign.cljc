(ns com.fulcrologic.statecharts.algorithms.v20150901.assign
  (:require
    [com.fulcrologic.statecharts.elements
     :refer [state
             initial
             parallel
             final
             transition
             raise
             on-entry
             on-exit
             data-model
             assign
             script]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.testing :as testing]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [fulcro-spec.core :refer [specification assertions =>]]))

;; Not sure if this test makes sense?
#_(specification "assign_invalid"
  (let [chart (chart/statechart {}
                (data-model
                  {:id :o1})
                (state {:id :uber}
                  (transition {:event :error.execution :target :pass})
                  (transition {:event :* :target :fail})

                  (state {:id :s1}
                    (on-entry {}
                      (assign {:location :o1 :expr (fn [_ _] (throw (ex-info "Failing" {})))}))))

                (final {:id :pass})
                (final {:id :fail}))
        env   (testing/new-testing-env {:statechart chart} {})]

    (testing/start! env)

    (assertions
      (testing/in? env :pass) => true)))

(specification "assign_map_literal" :focus
  (let [chart (chart/statechart {}
                (data-model
                  {:id :o1})
                (state {:id :uber}
                  (transition {:event :* :target :fail})

                  (state {:id :s1}
                    (transition {:event :pass :target :pass})
                    (on-entry {}
                      (assign {:location :o1 :expr {:p1 :v1 :p2 :v2}}))))

                (final {:id :pass})
                (final {:id :fail}))
        env   (testing/new-testing-env {:statechart chart} {})]

    (testing/start! env)

    (assertions
      (testing/in? env :s1) => true)

    (testing/run-events! env :pass)

    (assertions
      (testing/in? env :pass) => true)))