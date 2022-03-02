(ns com.fulcrologic.statecharts.algorithms.v20150901.assign-current-small-step-spec
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

(specification "test0"
  (let [chart (chart/statechart {}
                (data-model
                  {:expr {:x nil}})
                (state {:id :a}
                  (on-entry {}
                    (assign {:location :x :expr -1})
                    (assign {:location :x :expr 99}))
                  (transition {:event  :t
                               :target :b
                               :cond   (fn [_ {:keys [x]}] (= x 99))}
                    (assign {:location :x :expr (fn [_ {:keys [x]}] (+ x 1))})))
                (state {:id :b}
                  (on-entry {}
                    (script {:expr (fn script* [env {:keys [x]}]
                                     [(ops/assign [:ROOT :x] (* 2 x))])}))
                  (transition {:target :c
                               :cond   (fn [_ {:keys [x]}] (= 200 x))})
                  (transition {:target :f}))
                (state {:id :c})
                (state {:id :f}))
        env   (testing/new-testing-env {:statechart chart} {})]

    (testing/start! env)

    (assertions
      (testing/in? env :a) => true)

    (testing/run-events! env :t)

    (assertions
      (testing/in? env :c) => true)))

(specification "test1"
  (let [chart (chart/statechart {}
                (data-model
                  {:id :i})
                (state {:id :a}
                  (transition {:target :b :event :t}
                    (assign {:location :i :expr 0})))
                (state {:id :b}
                  (transition {:target :b
                               :cond   (fn [_ {:keys [i]}] (< i 100))}
                    (assign {:location :i :expr (fn [_ {:keys [i]}] (inc i))}))
                  (transition {:target :c :cond (fn [_ {:keys [i]}] (= i 100))}))
                (state {:id :c}))
        env   (testing/new-testing-env {:statechart chart} {})]

    (testing/start! env)

    (assertions
      (testing/in? env :a) => true)

    (testing/run-events! env :t)

    (assertions
      (testing/in? env :c) => true)))

(specification "test2"
  (let [chart (chart/statechart {}
                (data-model
                  {:id :i})
                (state {:id :a}
                  (transition {:target :b :event :t}
                    (assign {:location :i :expr 0})))
                (state {:id :A}
                  (state {:id :b}
                    (transition {:target :c
                                 :cond   (fn [_ {:keys [i]}] (< i 100))}
                      (assign {:location :i :expr (fn [_ {:keys [i]}] (inc i))})))
                  (state {:id :c}
                    (transition {:target :b
                                 :cond   (fn [_ {:keys [i]}] (< i 100))}
                      (assign {:location :i :expr (fn [_ {:keys [i]}] (inc i))})))
                  (transition {:target :d :cond (fn [_ {:keys [i]}] (= i 100))}
                    (assign {:location :i :expr (fn [_ {:keys [i]}] (* 2 i))})))
                (state {:id :d}
                  (transition {:target :e :cond (fn [_ {:keys [i]}] (= i 200))})
                  (transition {:target :f}))
                (state {:id :e})
                (state {:id :f}))
        env   (testing/new-testing-env {:statechart chart} {})]

    (testing/start! env)

    (assertions
      (testing/in? env :a) => true)

    (testing/run-events! env :t)

    (assertions
      (testing/in? env :e) => true)))

(specification "test3"
  (let [chart (chart/statechart {}
                (data-model
                  {:id :i})
                (state {:id :a}
                  (transition {:target :p :event :t1}
                    (assign {:location :i :expr 0})))
                (parallel {:id :p}
                  (state {:id :b :initial :b1}
                    (state {:id :b1}
                      (transition {:event :t2 :target :b2}
                        (assign {:location :i :expr (fn [_ {:keys [i]}] (inc i))})))
                    (state {:id :b2}))
                  (state {:id :c :initial :c1}
                    (state {:id :c1}
                      (transition {:event :t2 :target :c2}
                        (assign {:location :i :expr (fn [_ {:keys [i]}] (dec i))})))
                    (state {:id :c2}))

                  (transition {:event :t3 :target :d :cond (fn [_ {:keys [i]}] (= i 0))})
                  (transition {:event :t3 :target :f}))
                (state {:id :d})
                (state {:id :f}))
        env   (testing/new-testing-env {:statechart chart} {})]

    (testing/start! env)

    (assertions
      (testing/in? env :a) => true)

    (testing/run-events! env :t1)

    (assertions
      (testing/in? env :b1) => true
      (testing/in? env :c1) => true)

    (testing/run-events! env :t2)

    (assertions
      (testing/in? env :b2) => true
      (testing/in? env :c2) => true)

    (testing/run-events! env :t3)

    (assertions
      (testing/in? env :d) => true)))

(specification "test4"
  (let [chart (chart/statechart {}
                (data-model
                  {:id :x})
                (state {:id :a}
                  (on-entry {}
                    (assign {:location :x :expr 2}))
                  (transition {:event :t :target :b1}))

                (state {:id :b}
                  (on-entry {}
                    (assign {:location :x :expr (fn [_ {:keys [x]}] (* x 3))}))
                  (state {:id :b1}
                    (on-entry {}
                      (assign {:location :x :expr (fn [_ {:keys [x]}] (* x 5))})))
                  (state {:id :b2}
                    (on-entry {}
                      (assign {:location :x :expr (fn [_ {:keys [x]}] (* x 7))})))

                  (transition {:target :c :cond (fn [_ {:keys [x]}] (= x 30))})
                  (transition {:target :f}))

                (state {:id :c})
                (state {:id :f}))
        env   (testing/new-testing-env {:statechart chart} {})]

    (testing/start! env)

    (assertions
      (testing/in? env :a) => true)

    (testing/run-events! env :t)

    (assertions
      (testing/in? env :c) => true)))
