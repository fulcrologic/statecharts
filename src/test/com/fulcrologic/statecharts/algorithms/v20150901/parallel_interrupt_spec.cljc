(ns com.fulcrologic.statecharts.algorithms.v20150901.parallel-interrupt-spec
  (:require [com.fulcrologic.statecharts.chart :as chart]
            [com.fulcrologic.statecharts.elements :refer
             [parallel state transition]]
            [com.fulcrologic.statecharts.testing :as testing]
            [fulcro-spec.core :refer [=> assertions specification]]))

(specification
  "test0"
  (let [chart (chart/statechart
                {:initial :b}
                (parallel {:id :b}
                  (state {:id :c} (transition {:event :t, :target :a1}))
                  (state {:id :d}
                    (transition {:event :t, :target :a2})))
                (state {:id :a1})
                (state {:id :a2}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c) => true
      (testing/in? env :d) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a1) => true)))

(specification
  "test1"
  (let [chart
            (chart/statechart
              {:initial :b}
              (parallel
                {:id :b}
                (state {:id :c, :initial :c1}
                  (state {:id :c1} (transition {:event :t, :target :c2}))
                  (state {:id :c2}))
                (state {:id :d, :initial :d1}
                  (state {:id :d1} (transition {:event :t, :target :a1}))))
              (state {:id :a1}))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c1) => true
      (testing/in? env :d1) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :c2) => true
      (testing/in? env :d1) => true)))

(specification
  "test10"
  (let [chart (chart/statechart
                {:initial :a}
                (state {:id :a, :initial :b}
                  (transition {:event :t, :target :c})
                  (parallel {:id :b} (state {:id :b1}) (state {:id :b2}))
                  (parallel {:id :c} (state {:id :c1}) (state {:id :c2}))))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :b1) => true
      (testing/in? env :b2) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :c1) => true
      (testing/in? env :c2) => true)))

(specification
  "test11"
  (let [chart (chart/statechart
                {:initial :a}
                (state {:id :a, :initial :b}
                  (parallel
                    {:id :b}
                    (state {:id :b1} (transition {:event :t, :target :d}))
                    (state {:id :b2} (transition {:event :t, :target :c})))
                  (parallel {:id :c} (state {:id :c1}) (state {:id :c2})))
                (state {:id :d}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :b1) => true
      (testing/in? env :b2) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :d) => true)))

(specification
  "test12"
  (let [chart (chart/statechart
                {:initial :a}
                (state {:id :a, :initial :b}
                  (parallel
                    {:id :b}
                    (state {:id :b1} (transition {:event :t, :target :c}))
                    (state {:id :b2} (transition {:event :t, :target :d})))
                  (parallel {:id :c} (state {:id :c1}) (state {:id :c2})))
                (state {:id :d}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :b1) => true
      (testing/in? env :b2) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :c1) => true
      (testing/in? env :c2) => true)))

(specification
  "test13"
  (let [chart (chart/statechart
                {:initial :a}
                (state {:id :a, :initial :b}
                  (parallel {:id :b}
                    (state {:id :b1}
                      (transition {:event :t, :target :c}))
                    (state {:id :b2})
                    (transition {:event :t, :target :d}))
                  (parallel {:id :c} (state {:id :c1}) (state {:id :c2})))
                (state {:id :d}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :b1) => true
      (testing/in? env :b2) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :c1) => true
      (testing/in? env :c2) => true)))

(specification
  "test14"
  (let [chart
            (chart/statechart
              {:initial :a}
              (parallel
                {:id :a}
                (parallel
                  {:id :b}
                  (parallel
                    {:id :c}
                    (parallel {:id :d}
                      (parallel {:id :e}
                        (state {:id :i, :initial :i1}
                          (state {:id :i1}
                            (transition {:target :l,
                                         :event  :t}))
                          (state {:id :i2}))
                        (state {:id :j}))
                      (state {:id :h}))
                    (state {:id :g}))
                  (state {:id :f, :initial :f1}
                    (state {:id :f1} (transition {:target :f2, :event :t}))
                    (state {:id :f2})))
                (state {:id :k}))
              (state {:id :l}))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :i1) => true
      (testing/in? env :j) => true
      (testing/in? env :h) => true
      (testing/in? env :g) => true
      (testing/in? env :f1) => true
      (testing/in? env :k) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :l) => true)))

(specification
  "test15"
  (let [chart
            (chart/statechart
              {:initial :a}
              (parallel
                {:id :a}
                (parallel
                  {:id :b}
                  (parallel
                    {:id :c}
                    (parallel {:id :d}
                      (parallel {:id :e}
                        (state {:id :i, :initial :i1}
                          (state {:id :i1}
                            (transition {:target :i2,
                                         :event  :t}))
                          (state {:id :i2}))
                        (state {:id :j}))
                      (state {:id :h}))
                    (state {:id :g}))
                  (state {:id :f, :initial :f1}
                    (state {:id :f1} (transition {:target :l, :event :t}))
                    (state {:id :f2})))
                (state {:id :k}))
              (state {:id :l}))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :i1) => true
      (testing/in? env :j) => true
      (testing/in? env :h) => true
      (testing/in? env :g) => true
      (testing/in? env :f1) => true
      (testing/in? env :k) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :i2) => true
      (testing/in? env :j) => true
      (testing/in? env :h) => true
      (testing/in? env :g) => true
      (testing/in? env :f1) => true
      (testing/in? env :k) => true)))

(specification
  "test16"
  (let [chart
            (chart/statechart
              {:initial :b}
              (parallel {:id :b}
                (state {:id :c} (transition {:event :t, :target :a}))
                (state {:id :d} (transition {:event :t, :target :a2})))
              (state {:id :a, :initial :a1} (state {:id :a1}) (state {:id :a2})))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c) => true
      (testing/in? env :d) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a1) => true)))

(specification
  "test17"
  (let [chart
            (chart/statechart
              {:initial :b}
              (parallel {:id :b}
                (state {:id :c} (transition {:event :t, :target :a2}))
                (state {:id :d} (transition {:event :t, :target :a})))
              (state {:id :a, :initial :a1} (state {:id :a1}) (state {:id :a2})))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c) => true
      (testing/in? env :d) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a2) => true)))

(specification
  "test18"
  (let [chart (chart/statechart
                {:initial :b}
                (parallel {:id :b}
                  (state {:id :c})
                  (state {:id :d} (transition {:event :t, :target :a2}))
                  (transition {:event :t, :target :a1}))
                (state {:id :a1})
                (state {:id :a2}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c) => true
      (testing/in? env :d) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a2) => true)))

(specification
  "test19"
  (let [chart (chart/statechart
                {:initial :b}
                (parallel
                  {:id :b}
                  (state {:id :c, :initial :c1}
                    (state {:id :c1} (transition {:event :t, :target :c2}))
                    (state {:id :c2}))
                  (state {:id :d, :initial :d1}
                    (state {:id :d1} (transition {:event :t, :target :d2}))
                    (state {:id :d2}))
                  (transition {:event :t, :target :a1}))
                (state {:id :a1}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c1) => true
      (testing/in? env :d1) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :c2) => true
      (testing/in? env :d2) => true)))

(specification
  "test2"
  (let [chart (chart/statechart
                {:initial :b}
                (parallel
                  {:id :b}
                  (state {:id :c, :initial :c1}
                    (state {:id :c1} (transition {:event :t, :target :a1}))
                    (state {:id :c2}))
                  (state {:id :d, :initial :d1}
                    (state {:id :d1} (transition {:event :t, :target :d2}))
                    (state {:id :d2})))
                (state {:id :a1}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c1) => true
      (testing/in? env :d1) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a1) => true)))

(specification
  "test20"
  (let [chart (chart/statechart
                {:initial :b}
                (parallel {:id :b}
                  (state {:id :c, :initial :c1}
                    (state {:id :c1}
                      (transition {:event :t, :target :c2}))
                    (state {:id :c2}))
                  (state {:id :d} (transition {:event :t, :target :a1}))
                  (transition {:event :t, :target :a2}))
                (state {:id :a1})
                (state {:id :a2}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c1) => true
      (testing/in? env :d) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :c2) => true
      (testing/in? env :d) => true)))

(specification
  "test21"
  (let [chart (chart/statechart
                {:initial :b}
                (parallel {:id :b}
                  (state {:id :c} (transition {:event :t, :target :a1}))
                  (state {:id :d, :initial :d1}
                    (state {:id :d1}
                      (transition {:event :t, :target :d2}))
                    (state {:id :d2}))
                  (transition {:event :t, :target :a2}))
                (state {:id :a1})
                (state {:id :a2}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c) => true
      (testing/in? env :d1) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a1) => true)))

(specification
  "test21b"
  (let [chart (chart/statechart
                {:initial :b}
                (parallel {:id :b}
                  (transition {:event :t, :target :a2})
                  (state {:id :c} (transition {:event :t, :target :a1}))
                  (state {:id :d, :initial :d1}
                    (state {:id :d1}
                      (transition {:event :t, :target :d2}))
                    (state {:id :d2})))
                (state {:id :a1})
                (state {:id :a2}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c) => true
      (testing/in? env :d1) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a1) => true)))

(specification
  "test21c"
  (let [chart (chart/statechart
                {:initial :b}
                (parallel
                  {:id :b}
                  (transition {:event :t, :target :a2})
                  (state {:id :d, :initial :d1}
                    (state {:id :d1} (transition {:event :t, :target :d2}))
                    (state {:id :d2}))
                  (state {:id :c} (transition {:event :t, :target :a1})))
                (state {:id :a1})
                (state {:id :a2}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c) => true
      (testing/in? env :d1) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :c) => true
      (testing/in? env :d2) => true)))

(specification
  "test22"
  (let [chart (chart/statechart
                {:initial :b}
                (parallel
                  {:id :b}
                  (state {:id :c, :initial :c1}
                    (state {:id :c1} (transition {:event :t, :target :c2}))
                    (state {:id :c2}))
                  (state {:id :d, :initial :d1}
                    (state {:id :d1} (transition {:event :t, :target :d2}))
                    (state {:id :d2}))
                  (transition {:event :t, :target :a1}))
                (state {:id :a1}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c1) => true
      (testing/in? env :d1) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :c2) => true
      (testing/in? env :d2) => true)))

(specification
  "test23"
  (let [chart (chart/statechart
                {:initial :b}
                (parallel {:id :b}
                  (state {:id :c})
                  (state {:id :d} (transition {:event :t, :target :a2}))
                  (transition {:event :t, :target :a1}))
                (state {:id :a1})
                (state {:id :a2}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c) => true
      (testing/in? env :d) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a2) => true)))

(specification
  "test24"
  (let [chart (chart/statechart
                {:initial :b}
                (parallel
                  {:id :b}
                  (state {:id :c, :initial :c1}
                    (state {:id :c1} (transition {:event :t, :target :c2}))
                    (state {:id :c2}))
                  (state {:id :d, :initial :d1}
                    (state {:id :d1} (transition {:event :t, :target :d2}))
                    (state {:id :d2}))
                  (transition {:event :t, :target :a1}))
                (state {:id :a1}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c1) => true
      (testing/in? env :d1) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :c2) => true
      (testing/in? env :d2) => true)))

(specification
  "test25"
  (let [chart (chart/statechart
                {:initial :b}
                (parallel {:id :b}
                  (state {:id :c, :initial :c1}
                    (state {:id :c1}
                      (transition {:event :t, :target :c2}))
                    (state {:id :c2}))
                  (state {:id :d} (transition {:event :t, :target :a1}))
                  (transition {:event :t, :target :a2}))
                (state {:id :a1})
                (state {:id :a2}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c1) => true
      (testing/in? env :d) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :c2) => true
      (testing/in? env :d) => true)))

(specification
  "test27"
  (let [chart (chart/statechart
                {:initial :b}
                (parallel
                  {:id :b}
                  (state {:id :c, :initial :c1}
                    (state {:id :c1} (transition {:event :t, :target :c2}))
                    (state {:id :c2}))
                  (state {:id :d, :initial :d1}
                    (state {:id :d1} (transition {:event :t, :target :d2}))
                    (state {:id :d2}))
                  (transition {:event :t, :target :a1}))
                (state {:id :a1}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c1) => true
      (testing/in? env :d1) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :c2) => true
      (testing/in? env :d2) => true)))

(specification
  "test28"
  (let [chart
            (chart/statechart
              {:initial :b}
              (parallel {:id :b}
                (state {:id :c})
                (state {:id :d} (transition {:event :t, :target :a2}))
                (transition {:event :t, :target :a}))
              (state {:id :a, :initial :a1} (state {:id :a1}) (state {:id :a2})))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c) => true
      (testing/in? env :d) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a2) => true)))

(specification
  "test29"
  (let [chart
            (chart/statechart
              {:initial :b}
              (parallel {:id :b}
                (state {:id :c})
                (state {:id :d} (transition {:event :t, :target :a}))
                (transition {:event :t, :target :a2}))
              (state {:id :a, :initial :a1} (state {:id :a1}) (state {:id :a2})))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c) => true
      (testing/in? env :d) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a1) => true)))

(specification
  "test3"
  (let [chart (chart/statechart
                {:initial :b}
                (parallel
                  {:id :b}
                  (parallel
                    {:id :c}
                    (state {:id :e} (transition {:event :t, :target :a1}))
                    (state {:id :f} (transition {:event :t, :target :a2}))
                    (transition {:event :t, :target :a3}))
                  (state {:id :d} (transition {:event :t, :target :a4})))
                (state {:id :a1})
                (state {:id :a2})
                (state {:id :a3})
                (state {:id :a4}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :e) => true
      (testing/in? env :f) => true
      (testing/in? env :d) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a1) => true)))

(specification
  "test30"
  (let [chart
            (chart/statechart
              {:initial :b}
              (parallel {:id :b}
                (state {:id :c, :initial :c1}
                  (state {:id :c1}
                    (transition {:event :t, :target :c2}))
                  (state {:id :c2}))
                (state {:id :d} (transition {:event :t, :target :a}))
                (transition {:event :t, :target :a2}))
              (state {:id :a, :initial :a1} (state {:id :a1}) (state {:id :a2})))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c1) => true
      (testing/in? env :d) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :c2) => true
      (testing/in? env :d) => true)))

(specification
  "test31"
  (let [chart
            (chart/statechart
              {:initial :b}
              (parallel
                {:id :b}
                (state {:id :c, :initial :c1}
                  (state {:id :c1} (transition {:event :t, :target :a})))
                (state {:id :d, :initial :d1}
                  (state {:id :d1} (transition {:event :t, :target :d2}))
                  (state {:id :d2}))
                (transition {:event :t, :target :a2}))
              (state {:id :a, :initial :a1} (state {:id :a1}) (state {:id :a2})))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c1) => true
      (testing/in? env :d1) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a1) => true)))

(specification
  "test4"
  (let [chart
            (chart/statechart
              {:initial :b}
              (parallel
                {:id :b}
                (parallel {:id :p}
                  (state {:id :e} (transition {:event :t, :target :a1}))
                  (state {:id :f} (transition {:event :t, :target :a2}))
                  (transition {:event :t, :target :a3}))
                (state {:id :d, :initial :g}
                  (state {:id :g} (transition {:event :t, :target :h}))
                  (state {:id :h})))
              (state {:id :a1})
              (state {:id :a2})
              (state {:id :a3})
              (state {:id :a4}))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :e) => true
      (testing/in? env :f) => true
      (testing/in? env :g) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a1) => true)))

(specification
  "test5"
  (let [chart
            (chart/statechart
              {:initial :b}
              (parallel
                {:id :b}
                (state {:id :d, :initial :g}
                  (state {:id :g} (transition {:event :t, :target :h}))
                  (state {:id :h}))
                (parallel {:id :p}
                  (state {:id :e} (transition {:event :t, :target :a1}))
                  (state {:id :f} (transition {:event :t, :target :a2}))
                  (transition {:event :t, :target :a3})))
              (state {:id :a1})
              (state {:id :a2})
              (state {:id :a3})
              (state {:id :a4}))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :e) => true
      (testing/in? env :f) => true
      (testing/in? env :g) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :e) => true
      (testing/in? env :f) => true
      (testing/in? env :h) => true)))

(specification
  "test6"
  (let [chart
            (chart/statechart
              {:initial :b}
              (parallel
                {:id :b}
                (state {:id :c, :initial :g}
                  (state {:id :g} (transition {:event :t, :target :h}))
                  (state {:id :h}))
                (parallel
                  {:id :d}
                  (state {:id :e, :initial :e1}
                    (state {:id :e1} (transition {:event :t, :target :e2}))
                    (state {:id :e2}))
                  (state {:id :f, :initial :f1}
                    (state {:id :f1} (transition {:event :t, :target :f2}))
                    (state {:id :f2})))))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :g) => true
      (testing/in? env :e1) => true
      (testing/in? env :f1) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :h) => true
      (testing/in? env :e2) => true
      (testing/in? env :f2) => true)))

(specification
  "test7"
  (let [chart
            (chart/statechart
              {:initial :b}
              (parallel
                {:id :b}
                (state {:id :c} (transition {:event :t, :target :a1}))
                (parallel
                  {:id :d}
                  (state {:id :e, :initial :e1}
                    (state {:id :e1} (transition {:event :t, :target :e2}))
                    (state {:id :e2}))
                  (state {:id :f, :initial :f1}
                    (state {:id :f1} (transition {:event :t, :target :f2}))
                    (state {:id :f2}))))
              (state {:id :a1}))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c) => true
      (testing/in? env :e1) => true
      (testing/in? env :f1) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a1) => true)))

(specification
  "test7b"
  (let [chart
            (chart/statechart
              {:initial :b}
              (parallel
                {:id :b}
                (parallel
                  {:id :d}
                  (state {:id :e, :initial :e1}
                    (state {:id :e1} (transition {:event :t, :target :e2}))
                    (state {:id :e2}))
                  (state {:id :f, :initial :f1}
                    (state {:id :f1} (transition {:event :t, :target :f2}))
                    (state {:id :f2})))
                (state {:id :c} (transition {:event :t, :target :a1})))
              (state {:id :a1}))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :c) => true
      (testing/in? env :e1) => true
      (testing/in? env :f1) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :c) => true
      (testing/in? env :e2) => true
      (testing/in? env :f2) => true)))

(specification
  "test8"
  (let [chart (chart/statechart
                {:initial :a}
                (state {:id :a, :initial :b}
                  (parallel {:id :b}
                    (state {:id :b1})
                    (state {:id :b2})
                    (transition {:event :t, :target :c}))
                  (parallel {:id :c} (state {:id :c1}) (state {:id :c2}))))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :b1) => true
      (testing/in? env :b2) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :c1) => true
      (testing/in? env :c2) => true)))

(specification
  "test9"
  (let [chart (chart/statechart
                {:initial :a}
                (state {:id :a, :initial :b}
                  (parallel {:id :b}
                    (state {:id :b1}
                      (transition {:event :t, :target :c}))
                    (state {:id :b2}))
                  (parallel {:id :c} (state {:id :c1}) (state {:id :c2}))))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :b1) => true
      (testing/in? env :b2) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :c1) => true
      (testing/in? env :c2) => true)))
