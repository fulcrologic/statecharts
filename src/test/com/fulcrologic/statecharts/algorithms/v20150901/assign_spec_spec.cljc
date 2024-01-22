(ns com.fulcrologic.statecharts.algorithms.v20150901.assign-spec-spec
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements
     :refer [assign
             data-model
             final
             on-entry
             script
             state
             transition]]
    [com.fulcrologic.statecharts.testing :as testing]
    [fulcro-spec.core :refer [=> assertions specification]]))

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

(def final-target (volatile! nil))

(defn pass [& _]
  (vreset! final-target :pass))

(defn fail [& _]
  (vreset! final-target :fail))

(specification "assign_map_literal"
  (let [chart (chart/statechart {}
                (data-model {:id :o1})

                (state {:id :uber}
                  (transition {:event :* :target :fail})

                  (state {:id :s1}
                    (transition {:event :pass :target :pass})
                    (on-entry {}
                      (assign {:location :o1 :expr {:p1 :v1 :p2 :v2}}))))

                (final {:id :pass}
                  (on-entry {}
                    (script {:expr pass})))
                (final {:id :fail}
                  (on-entry {}
                    (script {:expr fail}))))
        env   (testing/new-testing-env {:statechart chart} {pass pass
                                                            fail fail})]

    (testing/start! env)

    (assertions
      "Starts in the correct initial state"
      (testing/in? env :s1) => true)

    (testing/run-events! env :pass)

    (assertions
      @final-target => :pass)))
