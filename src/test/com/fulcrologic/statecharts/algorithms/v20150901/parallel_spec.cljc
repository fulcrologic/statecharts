(ns com.fulcrologic.statecharts.algorithms.v20150901.parallel-spec
  (:require [com.fulcrologic.statecharts.elements :refer
             [state initial parallel final transition raise on-entry on-exit
              data-model assign script history log]]
            [com.fulcrologic.statecharts :as sc]
            [com.fulcrologic.statecharts.chart :as chart]
            [com.fulcrologic.statecharts.testing :as testing]
            [com.fulcrologic.statecharts.data-model.operations :as ops]
            [fulcro-spec.core :refer [specification assertions =>]]))

(specification
  "test0"
  (let [chart (chart/statechart
                {}
                (parallel {:id :p} (state {:id :a}) (state {:id :b})))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true (testing/in? env :b) => true)))

(specification
  "test1"
  (let [chart (chart/statechart
                {}
                (parallel
                  {:id :p}
                  (state {:id :a}
                    (initial {} (transition {:target :a1}))
                    (state {:id :a1} (transition {:event :t, :target :a2}))
                    (state {:id :a2}))
                  (state {:id :b}
                    (initial {} (transition {:target :b1}))
                    (state {:id :b1} (transition {:event :t, :target :b2}))
                    (state {:id :b2}))))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a1) => true (testing/in? env :b1) => true)
    (testing/run-events! env :t)
    (assertions (testing/in? env :a2) => true (testing/in? env :b2) => true)))

(specification
  "test2"
  (let [chart
            (chart/statechart
              {}
              (parallel
                {:id :p1}
                (state {:id :s1, :initial :p2}
                  (parallel {:id :p2}
                    (state {:id :s3})
                    (state {:id :s4})
                    (transition {:target :p3, :event :t}))
                  (parallel {:id :p3} (state {:id :s5}) (state {:id :s6})))
                (state
                  {:id :s2, :initial :p4}
                  (parallel {:id :p4}
                    (state {:id :s7})
                    (state {:id :s8})
                    (transition {:target :p5, :event :t}))
                  (parallel {:id :p5} (state {:id :s9}) (state {:id :s10})))))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :s3) => true
      (testing/in? env :s4) => true
      (testing/in? env :s7) => true
      (testing/in? env :s8) => true)

    (testing/run-events! env :t)

    (assertions
      (testing/in? env :s5) => true
      (testing/in? env :s6) => true
      (testing/in? env :s9) => true
      (testing/in? env :s10) => true)))

(specification
  "test3"
  (let [chart
            (chart/statechart
              {:initial :p1}
              (parallel
                {:id :p1}
                (state
                  {:id :s1, :initial :p2}
                  (parallel {:id :p2}
                    (state {:id :s3, :initial :s3.1}
                      (state {:id :s3.1}
                        (transition {:target :s3.2, :event :t}))
                      (state {:id :s3.2}))
                    (state {:id :s4}))
                  (parallel {:id :p3} (state {:id :s5}) (state {:id :s6})))
                (state
                  {:id :s2, :initial :p4}
                  (parallel {:id :p4}
                    (state {:id :s7})
                    (state {:id :s8})
                    (transition {:target :p5, :event :t}))
                  (parallel {:id :p5} (state {:id :s9}) (state {:id :s10})))))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :s3.1) => true
      (testing/in? env :s4) => true
      (testing/in? env :s7) => true
      (testing/in? env :s8) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :s3.2) => true
      (testing/in? env :s4) => true
      (testing/in? env :s9) => true
      (testing/in? env :s10) => true)))