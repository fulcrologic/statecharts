(ns com.fulcrologic.statecharts.algorithms.v20150901.history-spec
  (:require [com.fulcrologic.statecharts.elements :refer
             [state initial parallel final transition raise on-entry on-exit
              data-model assign script history log]]
            [com.fulcrologic.statecharts :as sc]
            [com.fulcrologic.statecharts.chart :as chart]
            [com.fulcrologic.statecharts.testing :as testing]
            [com.fulcrologic.statecharts.data-model.operations :as ops]
            [fulcro-spec.core :refer [specification assertions =>]]))

(specification
  "history0"
  (let [chart (chart/statechart
                {:initial :a}
                (state {:id :a} (transition {:target :h, :event :t1}))
                (state {:id :b, :initial :b1}
                  (history {:id :h} (transition {:target :b2}))
                  (state {:id :b1})
                  (state {:id :b2} (transition {:event :t2, :target :b3}))
                  (state {:id :b3} (transition {:event :t3, :target :a}))))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t1)
    (assertions (testing/in? env :b2) => true)
    (testing/run-events! env :t2)
    (assertions (testing/in? env :b3) => true)
    (testing/run-events! env :t3)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t1)
    (assertions (testing/in? env :b3) => true)))

(specification
  "history1"
  (let [chart (chart/statechart
                {:initial :a}
                (state {:id :a} (transition {:target :h, :event :t1}))
                (state
                  {:id :b, :initial :b1}
                  (history {:id :h, :type :deep} (transition {:target :b1.2}))
                  (state
                    {:id :b1, :initial :b1.1}
                    (state {:id :b1.1})
                    (state {:id :b1.2} (transition {:event :t2, :target :b1.3}))
                    (state {:id :b1.3} (transition {:event :t3, :target :a})))))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t1)
    (assertions (testing/in? env :b1.2) => true)
    (testing/run-events! env :t2)
    (assertions (testing/in? env :b1.3) => true)
    (testing/run-events! env :t3)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t1)
    (assertions (testing/in? env :b1.3) => true)))

(specification
  "history2"
  (let [chart
            (chart/statechart
              {:initial :a}
              (state {:id :a} (transition {:target :h, :event :t1}))
              (state
                {:id :b, :initial :b1}
                (history {:id :h, :type :shallow} (transition {:target :b1.2}))
                (state
                  {:id :b1, :initial :b1.1}
                  (state {:id :b1.1})
                  (state {:id :b1.2} (transition {:event :t2, :target :b1.3}))
                  (state {:id :b1.3} (transition {:event :t3, :target :a})))))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t1)
    (assertions (testing/in? env :b1.2) => true)
    (testing/run-events! env :t2)
    (assertions (testing/in? env :b1.3) => true)
    (testing/run-events! env :t3)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t1)
    (assertions (testing/in? env :b1.1) => true)))

(specification
  "history3"
  (let [chart
            (chart/statechart
              {:initial :a}
              (state {:id :a}
                (transition {:target :p, :event :t1})
                (transition {:target :h, :event :t4}))
              (parallel
                {:id :p}
                (history {:id :h, :type :deep} (transition {:target :b}))
                (state {:id :b, :initial :b1}
                  (state {:id :b1} (transition {:target :b2, :event :t2}))
                  (state {:id :b2}))
                (state {:id :c, :initial :c1}
                  (state {:id :c1} (transition {:target :c2, :event :t2}))
                  (state {:id :c2}))
                (transition {:target :a, :event :t3})))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t1)
    (assertions (testing/in? env :b1) => true (testing/in? env :c1) => true)
    (testing/run-events! env :t2)
    (assertions (testing/in? env :b2) => true (testing/in? env :c2) => true)
    (testing/run-events! env :t3)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t4)
    (assertions (testing/in? env :b2) => true (testing/in? env :c2) => true)))

(specification
  "history4"
  (let [chart
            (chart/statechart
              {:initial :a}
              (state {:id :a}
                (transition {:target :p, :event :t1})
                (transition {:target :p, :event :t6})
                (transition {:target :hp, :event :t9}))
              (parallel
                {:id :p}
                (history {:id :hp, :type :deep} (transition {:target :b}))
                (state
                  {:id :b, :initial :hb}
                  (history {:id :hb, :type :deep} (transition {:target :b1}))
                  (state
                    {:id :b1, :initial :b1.1}
                    (state {:id :b1.1} (transition {:target :b1.2, :event :t2}))
                    (state {:id :b1.2} (transition {:target :b2, :event :t3})))
                  (state {:id :b2, :initial :b2.1}
                    (state {:id :b2.1}
                      (transition {:target :b2.2, :event :t4}))
                    (state {:id :b2.2}
                      (transition {:target :a, :event :t5})
                      (transition {:target :a, :event :t8}))))
                (state
                  {:id :c, :initial :hc}
                  (history {:id :hc, :type :shallow} (transition {:target :c1}))
                  (state
                    {:id :c1, :initial :c1.1}
                    (state {:id :c1.1} (transition {:target :c1.2, :event :t2}))
                    (state {:id :c1.2} (transition {:target :c2, :event :t3})))
                  (state {:id :c2, :initial :c2.1}
                    (state {:id :c2.1}
                      (transition {:target :c2.2, :event :t4})
                      (transition {:target :c2.2, :event :t7}))
                    (state {:id :c2.2})))))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t1)
    (assertions (testing/in? env :b1.1) => true (testing/in? env :c1.1) => true)
    (testing/run-events! env :t2)
    (assertions (testing/in? env :b1.2) => true (testing/in? env :c1.2) => true)
    (testing/run-events! env :t3)
    (assertions (testing/in? env :b2.1) => true (testing/in? env :c2.1) => true)
    (testing/run-events! env :t4)
    (assertions (testing/in? env :b2.2) => true (testing/in? env :c2.2) => true)
    (testing/run-events! env :t5)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t6)
    (assertions (testing/in? env :b2.2) => true (testing/in? env :c2.1) => true)
    (testing/run-events! env :t7)
    (assertions (testing/in? env :b2.2) => true (testing/in? env :c2.2) => true)
    (testing/run-events! env :t8)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t9)
    (assertions (testing/in? env :b2.2)
      =>
      true
      (testing/in? env :c2.2)
      =>
      true)))

(specification
  "history4b"
  (let [chart
            (chart/statechart
              {:initial :a}
              (state {:id :a}
                (transition {:target :p, :event :t1})
                (transition {:target [:hb :hc], :event :t6})
                (transition {:target :hp, :event :t9}))
              (parallel
                {:id :p}
                (history {:id :hp, :type :deep} (transition {:target :b}))
                (state
                  {:id :b, :initial :hb}
                  (history {:id :hb, :type :deep} (transition {:target :b1}))
                  (state
                    {:id :b1, :initial :b1.1}
                    (state {:id :b1.1} (transition {:target :b1.2, :event :t2}))
                    (state {:id :b1.2} (transition {:target :b2, :event :t3})))
                  (state {:id :b2, :initial :b2.1}
                    (state {:id :b2.1}
                      (transition {:target :b2.2, :event :t4}))
                    (state {:id :b2.2}
                      (transition {:target :a, :event :t5})
                      (transition {:target :a, :event :t8}))))
                (state
                  {:id :c, :initial :hc}
                  (history {:id :hc, :type :shallow} (transition {:target :c1}))
                  (state
                    {:id :c1, :initial :c1.1}
                    (state {:id :c1.1} (transition {:target :c1.2, :event :t2}))
                    (state {:id :c1.2} (transition {:target :c2, :event :t3})))
                  (state {:id :c2, :initial :c2.1}
                    (state {:id :c2.1}
                      (transition {:target :c2.2, :event :t4})
                      (transition {:target :c2.2, :event :t7}))
                    (state {:id :c2.2})))))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t1)
    (assertions (testing/in? env :b1.1) => true (testing/in? env :c1.1) => true)
    (testing/run-events! env :t2)
    (assertions (testing/in? env :b1.2) => true (testing/in? env :c1.2) => true)
    (testing/run-events! env :t3)
    (assertions (testing/in? env :b2.1) => true (testing/in? env :c2.1) => true)
    (testing/run-events! env :t4)
    (assertions (testing/in? env :b2.2) => true (testing/in? env :c2.2) => true)
    (testing/run-events! env :t5)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t6)
    (assertions (testing/in? env :b2.2) => true (testing/in? env :c2.1) => true)
    (testing/run-events! env :t7)
    (assertions (testing/in? env :b2.2) => true (testing/in? env :c2.2) => true)
    (testing/run-events! env :t8)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t9)
    (assertions (testing/in? env :b2.2)
      =>
      true
      (testing/in? env :c2.2)
      =>
      true)))

(specification
  "history5"
  (let [chart
            (chart/statechart
              {:initial :a}
              (parallel
                {:id :a}
                (history {:id :ha, :type :deep} (transition {:target :b}))
                (parallel
                  {:id :b}
                  (parallel
                    {:id :c}
                    (parallel
                      {:id :d}
                      (parallel
                        {:id :e}
                        (state
                          {:id :i, :initial :i1}
                          (state {:id :i1} (transition {:target :i2, :event :t1}))
                          (state {:id :i2} (transition {:target :l, :event :t2})))
                        (state {:id :j}))
                      (state {:id :h}))
                    (state {:id :g}))
                  (state {:id :f, :initial :f1}
                    (state {:id :f1} (transition {:target :f2, :event :t1}))
                    (state {:id :f2})))
                (state {:id :k}))
              (state {:id :l} (transition {:target :ha, :event :t3})))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :i1) => true
      (testing/in? env :j) => true
      (testing/in? env :h) => true
      (testing/in? env :g) => true
      (testing/in? env :f1) => true
      (testing/in? env :k) => true)
    (testing/run-events! env :t1)
    (assertions
      (testing/in? env :i2) => true
      (testing/in? env :j) => true
      (testing/in? env :h) => true
      (testing/in? env :g) => true
      (testing/in? env :f2) => true
      (testing/in? env :k) => true)
    (testing/run-events! env :t2)
    (assertions (testing/in? env :l) => true)
    (testing/run-events! env :t3)
    (assertions
      (testing/in? env :i2) => true
      (testing/in? env :j) => true
      (testing/in? env :h) => true
      (testing/in? env :g) => true
      (testing/in? env :f2) => true
      (testing/in? env :k) => true)))

(specification
  "history6"
  (let [chart (chart/statechart
                {:initial :a}
                (data-model
                  {:expr {:x 2}})
                (state {:id :a}
                  (transition {:target :h, :event :t1}))
                (state {:id :b, :initial :b1}
                  (on-entry {}
                    (assign {:location :x, :expr (fn [_ {:keys [x] :as env}] (* x 3))}))
                  (history {:id :h} (transition {:target :b2}))
                  (state {:id :b1})
                  (state {:id :b2}
                    (on-entry {}
                      (assign {:location :x, :expr (fn [_ {:keys [x]}] (* x 5))}))
                    (transition {:event :t2, :target :b3}))
                  (state {:id :b3}
                    (on-entry {}
                      (assign {:location :x, :expr (fn [_ {:keys [x]}] (* x 7))}))
                    (transition {:event :t3, :target :a}))
                  (transition
                    {:event :t4, :target :success, :cond (fn [_ {:keys [x]}] (= x 4410))})
                  (transition
                    {:event :t4, :target :really-fail, :cond (fn [_ {:keys [x]}] (= x 1470))})
                  (transition {:event :t4, :target :fail}))
                (state {:id :success})
                (state {:id :fail})
                (state {:id :really-fail}))
        env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}}
                {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t1)
    (assertions (testing/in? env :b2) => true)
    (testing/run-events! env :t2)
    (assertions (testing/in? env :b3) => true)
    (testing/run-events! env :t3)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t1)
    (assertions (testing/in? env :b3) => true)
    (testing/run-events! env :t4)
    (assertions (testing/in? env :success) => true)))
