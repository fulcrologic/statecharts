(ns com.fulcrologic.statecharts.algorithms.v20150901.more-parallel-spec
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer
     [assign data-model on-entry on-exit
      parallel state transition]]
    [com.fulcrologic.statecharts.testing :as testing]
    [fulcro-spec.core :refer [=> assertions specification]]
    [taoensso.timbre :as log]))

(defn inc-x-expr [_ {:keys [x]}]
  (inc x))

(defn make-x-eq-expr [v]
  (fn [_ {:keys [x]}] (= x v)))

(specification
  "test0"
  (let [chart (chart/statechart
                {}
                (parallel {:id :p}
                  (state {:id :a} (transition {:target :a, :event :t}))
                  (state {:id :b})))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true (testing/in? env :b) => true)
    (testing/run-events! env :t)
    (assertions (testing/in? env :a) => true (testing/in? env :b) => true)))

(specification
  "test1"
  (let [chart (chart/statechart
                {}
                (parallel {:id :p}
                  (state {:id :a}
                    (transition {:event :t, :target :a})
                    (state {:id :a1})
                    (state {:id :a2}))
                  (state {:id :b} (state {:id :b1}) (state {:id :b2}))))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a1) => true (testing/in? env :b1) => true)
    (testing/run-events! env :t)
    (assertions (testing/in? env :a1) => true (testing/in? env :b1) => true)))

(specification "test10" :focus
  (let [chart (chart/statechart {}
                (data-model {:expr {:x 0}})
                (parallel {:id :p}
                  (on-entry {} (assign {:location :x, :expr inc-x-expr}))
                  (on-exit {} (assign {:location :x, :expr inc-x-expr}))
                  (state {:id :a}
                    (on-entry {} (assign {:location :x, :expr inc-x-expr}))
                    (on-exit {} (assign {:location :x, :expr inc-x-expr}))
                    (transition {:target :a, :event :t1, :cond (make-x-eq-expr 2)}))
                  (state {:id :b})
                  (transition {:target :c, :event :t2, :cond (make-x-eq-expr 6)}))
                (state {:id :c}
                  (transition {:target :d, :event :t3, :cond (make-x-eq-expr 8)}))
                (state {:id :d}))
        env   (testing/new-testing-env {:statechart chart
                                        :mocking-options {:run-unmocked? true}} {})]
    (testing/start! env)

    (assertions
      "initial configuration"
      (testing/in? env :a) => true
      (testing/in? env :b) => true)

    (testing/run-events! env :t1)
    (assertions
      "after t1"
      (testing/in? env :a) => true
      (testing/in? env :b) => true)

    (testing/run-events! env :t2)
    (assertions
      "after t2"
      (testing/in? env :c) => true)

    (testing/run-events! env :t3)
    (assertions
      "after t3"
      (testing/in? env :d) => true)))

(specification "test2"
  (let [chart (chart/statechart {}
                (parallel
                  {:id :p}
                  (state {:id :a}
                    (transition {:event :t, :target :a})
                    (state {:id :a1})
                    (state {:id :a2}))
                  (state {:id :b}
                    (state {:id :b1}
                      (transition {:event :t, :target :b2}))
                    (state {:id :b2}))))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)

    (assertions
      (testing/in? env :a1) => true
      (testing/in? env :b1) => true)

    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a1) => true
      (testing/in? env :b1) => true)))

(specification
  "test2b"
  (let [chart (chart/statechart {}
                (parallel
                  {:id :p}
                  (state {:id :b}
                    (state {:id :b1}
                      (transition {:event :t, :target :b2}))
                    (state {:id :b2}))
                  (state {:id :a}
                    (transition {:event :t, :target :a})
                    (state {:id :a1})
                    (state {:id :a2}))))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)

    (assertions
      (testing/in? env :a1) => true
      (testing/in? env :b1) => true)

    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a1) => true
      (testing/in? env :b2) => true)))

(specification "test3"
  (let [chart (chart/statechart {}
                (parallel {:id :p}
                  (state {:id :a}
                    (transition {:event :t :target :a2})
                    (state {:id :a1})
                    (state {:id :a2}))
                  (state {:id :b}
                    (transition {:event :t :target :b2})
                    (state {:id :b1})
                    (state {:id :b2}))))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :a1) => true
      (testing/in? env :b1) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a2) => true
      (testing/in? env :b1) => true)))

(specification
  "test3b"
  (let [chart (chart/statechart
                {}
                (parallel
                  {:id :p}
                  (state {:id :b}
                    (state {:id :b1} (transition {:event :t, :target :b2}))
                    (state {:id :b2}))
                  (state {:id :a}
                    (transition {:event :t, :target :a2})
                    (state {:id :a1})
                    (state {:id :a2}))))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :a1) => true
      (testing/in? env :b1) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a1) => true
      (testing/in? env :b2) => true)))

(specification
  "test4"
  (let [chart (chart/statechart
                {}
                (parallel {:id :p}
                  (state {:id :a}
                    (transition {:event :t, :target :a})
                    (state {:id :a1})
                    (state {:id :a2}))
                  (state {:id :b}
                    (transition {:event :t, :target :b})
                    (state {:id :b1})
                    (state {:id :b2}))))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :a1) => true
      (testing/in? env :b1) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a1) => true
      (testing/in? env :b1) => true)))

(specification "test5"
  (let [chart (chart/statechart
                {}
                (parallel {:id :p}
                  (state {:id :a}
                    (transition {:event :t, :target :a2})
                    (state {:id :a1})
                    (state {:id :a2}))
                  (state {:id :b}
                    (transition {:event :t, :target :b2})
                    (state {:id :b1})
                    (state {:id :b2}))))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :a1) => true
      (testing/in? env :b1) => true)
    (testing/run-events! env :t)
    (assertions
      (testing/in? env :a2) => true
      (testing/in? env :b1) => true)))

(specification "test6"
  (let [chart
            (chart/statechart
              {}
              (parallel
                {:id :p}
                (state {:id :a}
                  (transition {:event :t, :target :a22})
                  (state {:id :a1} (state {:id :a11}) (state {:id :a12}))
                  (state {:id :a2} (state {:id :a21}) (state {:id :a22})))
                (state {:id :b}
                  (state {:id :b1}
                    (state {:id :b11}
                      (transition {:event :t, :target :b12}))
                    (state {:id :b12}))
                  (state {:id :b2} (state {:id :b21}) (state {:id :b22})))))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a11) => true (testing/in? env :b11) => true)
    (testing/run-events! env :t)
    (assertions (testing/in? env :a22) => true (testing/in? env :b11) => true)))

(specification
  "test6b"
  (let [chart
            (chart/statechart
              {}
              (parallel
                {:id :p}
                (state {:id :b}
                  (state {:id :b1}
                    (state {:id :b11}
                      (transition {:event :t, :target :b12}))
                    (state {:id :b12}))
                  (state {:id :b2} (state {:id :b21}) (state {:id :b22})))
                (state {:id :a}
                  (transition {:event :t, :target :a22})
                  (state {:id :a1} (state {:id :a11}) (state {:id :a12}))
                  (state {:id :a2} (state {:id :a21}) (state {:id :a22})))))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a11) => true (testing/in? env :b11) => true)
    (testing/run-events! env :t)
    (assertions (testing/in? env :a11) => true (testing/in? env :b12) => true)))

(specification
  "test7"
  (let [chart
            (chart/statechart
              {}
              (parallel
                {:id :p}
                (state {:id :a}
                  (transition {:event :t, :target :a22})
                  (state {:id :a1} (state {:id :a11}) (state {:id :a12}))
                  (state {:id :a2} (state {:id :a21}) (state {:id :a22})))
                (state {:id :b}
                  (transition {:event :t, :target :b22})
                  (state {:id :b1} (state {:id :b11}) (state {:id :b12}))
                  (state {:id :b2} (state {:id :b21}) (state {:id :b22})))))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a11) => true (testing/in? env :b11) => true)
    (testing/run-events! env :t)
    (assertions (testing/in? env :a22) => true (testing/in? env :b11) => true)))

(specification
  "test8"
  (let [chart
            (chart/statechart
              {}
              (state {:id :x} (transition {:event :t, :target :a22}))
              (parallel
                {:id :p}
                (state {:id :a}
                  (state {:id :a1} (state {:id :a11}) (state {:id :a12}))
                  (state {:id :a2} (state {:id :a21}) (state {:id :a22})))
                (state {:id :b}
                  (state {:id :b1} (state {:id :b11}) (state {:id :b12}))
                  (state {:id :b2} (state {:id :b21}) (state {:id :b22})))))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :x) => true)
    (testing/run-events! env :t)
    (assertions (testing/in? env :a22) => true (testing/in? env :b11) => true)))

(specification
  "test9"
  (let [chart
            (chart/statechart
              {}
              (state {:id :x} (transition {:event :t, :target [:a22 :b22]}))
              (parallel
                {:id :p}
                (state {:id :a}
                  (state {:id :a1} (state {:id :a11}) (state {:id :a12}))
                  (state {:id :a2} (state {:id :a21}) (state {:id :a22})))
                (state {:id :b}
                  (state {:id :b1} (state {:id :b11}) (state {:id :b12}))
                  (state {:id :b2} (state {:id :b21}) (state {:id :b22})))))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :x) => true)
    (testing/run-events! env :t)
    (assertions (testing/in? env :a22) => true (testing/in? env :b22) => true)))

(log/set-level! :info)
