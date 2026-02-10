(ns com.fulcrologic.statecharts.flow-control-spec
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state transition on-entry raise assign
                                                   script sc-if sc-else-if sc-else sc-foreach]]
    [com.fulcrologic.statecharts.testing :as testing]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [fulcro-spec.core :refer [=> assertions component specification behavior]]))

(specification "Flow Control - sc-if"
  (behavior "executes then-branch when condition is true"
    (let [chart (chart/statechart {}
                  (state {:id :start}
                    (on-entry {}
                      (sc-if {:cond (fn [_ _] true)}
                        (assign {:location [:result] :expr :then-executed})))
                    (transition {:event :done :target :end}))
                  (state {:id :end}))
          env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

      (testing/start! env)

      (assertions
        "assigns result from then-branch"
        (get-in (testing/data env) [:result]) => :then-executed)))

  (behavior "skips then-branch when condition is false"
    (let [chart (chart/statechart {}
                  (state {:id :start}
                    (on-entry {}
                      (assign {:location [:result] :expr :initial})
                      (sc-if {:cond (fn [_ _] false)}
                        (assign {:location [:result] :expr :should-not-execute})))
                    (transition {:event :done :target :end}))
                  (state {:id :end}))
          env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

      (testing/start! env)

      (assertions
        "does not execute then-branch"
        (get-in (testing/data env) [:result]) => :initial))))

(specification "Flow Control - sc-if with sc-else"
  (behavior "executes then-branch when condition is true"
    (let [chart (chart/statechart {}
                  (state {:id :start}
                    (on-entry {}
                      (sc-if {:cond (fn [_ _] true)}
                        (assign {:location [:result] :expr :then})
                        (sc-else {}
                          (assign {:location [:result] :expr :else}))))
                    (transition {:event :done :target :end}))
                  (state {:id :end}))
          env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

      (testing/start! env)

      (assertions
        "executes then-branch"
        (get-in (testing/data env) [:result]) => :then)))

  (behavior "executes else-branch when condition is false"
    (let [chart (chart/statechart {}
                  (state {:id :start}
                    (on-entry {}
                      (sc-if {:cond (fn [_ _] false)}
                        (assign {:location [:result] :expr :then})
                        (sc-else {}
                          (assign {:location [:result] :expr :else}))))
                    (transition {:event :done :target :end}))
                  (state {:id :end}))
          env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

      (testing/start! env)

      (assertions
        "executes else-branch"
        (get-in (testing/data env) [:result]) => :else))))

(specification "Flow Control - sc-if with sc-else-if"
  (behavior "executes first matching branch"
    (let [chart (chart/statechart {}
                  (state {:id :start}
                    (on-entry {}
                      (assign {:location [:x] :expr 2})
                      (sc-if {:cond (fn [_ data] (= (get data :x) 1))}
                        (assign {:location [:result] :expr :first})
                        (sc-else-if {:cond (fn [_ data] (= (get data :x) 2))}
                          (assign {:location [:result] :expr :second}))
                        (sc-else-if {:cond (fn [_ data] (= (get data :x) 3))}
                          (assign {:location [:result] :expr :third}))
                        (sc-else {}
                          (assign {:location [:result] :expr :else}))))
                    (transition {:event :done :target :end}))
                  (state {:id :end}))
          env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

      (testing/start! env)

      (assertions
        "executes second branch (first else-if)"
        (get-in (testing/data env) [:result]) => :second)))

  (behavior "executes else when no conditions match"
    (let [chart (chart/statechart {}
                  (state {:id :start}
                    (on-entry {}
                      (assign {:location [:x] :expr 99})
                      (sc-if {:cond (fn [_ data] (= (get data :x) 1))}
                        (assign {:location [:result] :expr :first})
                        (sc-else-if {:cond (fn [_ data] (= (get data :x) 2))}
                          (assign {:location [:result] :expr :second}))
                        (sc-else {}
                          (assign {:location [:result] :expr :else}))))
                    (transition {:event :done :target :end}))
                  (state {:id :end}))
          env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

      (testing/start! env)

      (assertions
        "executes else branch"
        (get-in (testing/data env) [:result]) => :else))))

(specification "Flow Control - sc-foreach"
  (behavior "iterates over collection and executes children for each item"
    (let [chart (chart/statechart {}
                  (state {:id :start}
                    (on-entry {}
                      (assign {:location [:sum] :expr 0})
                      (assign {:location [:items] :expr [1 2 3 4 5]})
                      (sc-foreach {:array (fn [_ data] (get data :items))
                                   :item  [:current]
                                   :index [:idx]}
                        (assign {:location [:sum]
                                 :expr     (fn [_ data]
                                             (+ (get data :sum) (get data :current)))})))
                    (transition {:event :done :target :end}))
                  (state {:id :end}))
          env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

      (testing/start! env)

      (assertions
        "sums all items"
        (get-in (testing/data env) [:sum]) => 15)))

  (behavior "binds item and index correctly"
    (let [chart (chart/statechart {}
                  (state {:id :start}
                    (on-entry {}
                      (assign {:location [:result] :expr []})
                      (sc-foreach {:array (fn [_ _] [:a :b :c])
                                   :item  [:item]
                                   :index [:i]}
                        (assign {:location [:result]
                                 :expr     (fn [_ data]
                                             (conj (get data :result)
                                               {:item (get data :item)
                                                :idx  (get data :i)}))})))
                    (transition {:event :done :target :end}))
                  (state {:id :end}))
          env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

      (testing/start! env)

      (assertions
        "builds correct result with items and indices"
        (get-in (testing/data env) [:result]) => [{:item :a :idx 0}
                                             {:item :b :idx 1}
                                             {:item :c :idx 2}])))

  (behavior "works with nested executable content"
    (let [chart (chart/statechart {}
                  (state {:id :start}
                    (on-entry {}
                      (assign {:location [:collected] :expr []})
                      (sc-foreach {:array (fn [_ _] ["a" "b" "c"])
                                   :item  [:current]}
                        (assign {:location [:collected]
                                 :expr     (fn [_ data]
                                             (conj (get data :collected) (get data :current)))})))
                    (transition {:event :done :target :end}))
                  (state {:id :end}))
          env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

      (testing/start! env)

      (assertions
        "collects items via nested assign"
        (get-in (testing/data env) [:collected]) => ["a" "b" "c"]))))

(specification "Flow Control - nested conditionals"
  (behavior "supports nested sc-if statements"
    (let [chart (chart/statechart {}
                  (state {:id :start}
                    (on-entry {}
                      (assign {:location [:x] :expr 5})
                      (assign {:location [:y] :expr 10})
                      (sc-if {:cond (fn [_ data] (> (get data :x) 0))}
                        (sc-if {:cond (fn [_ data] (> (get data :y) 5))}
                          (assign {:location [:result] :expr :both-positive}))
                        (sc-else {}
                          (assign {:location [:result] :expr :x-not-positive}))))
                    (transition {:event :done :target :end}))
                  (state {:id :end}))
          env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

      (testing/start! env)

      (assertions
        "executes nested conditional correctly"
        (get-in (testing/data env) [:result]) => :both-positive)))

  (behavior "supports sc-if within sc-foreach"
    (let [chart (chart/statechart {}
                  (state {:id :start}
                    (on-entry {}
                      (assign {:location [:evens] :expr []})
                      (sc-foreach {:array (fn [_ _] [1 2 3 4 5 6])
                                   :item  [:n]}
                        (sc-if {:cond (fn [_ data] (even? (get data :n)))}
                          (assign {:location [:evens]
                                   :expr     (fn [_ data]
                                               (conj (get data :evens) (get data :n)))}))))
                    (transition {:event :done :target :end}))
                  (state {:id :end}))
          env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

      (testing/start! env)

      (assertions
        "filters even numbers correctly"
        (get-in (testing/data env) [:evens]) => [2 4 6]))))

(specification "Flow Control - edge cases"
  (behavior "sc-foreach handles empty collection"
    (let [chart (chart/statechart {}
                  (state {:id :start}
                    (on-entry {}
                      (assign {:location [:count] :expr 0})
                      (sc-foreach {:array (fn [_ _] [])
                                   :item  [:x]}
                        (assign {:location [:count]
                                 :expr     (fn [_ data] (inc (get data :count)))})))
                    (transition {:event :done :target :end}))
                  (state {:id :end}))
          env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

      (testing/start! env)

      (assertions
        "does not execute body"
        (get-in (testing/data env) [:count]) => 0)))

  (behavior "sc-if with no else handles false condition gracefully"
    (let [chart (chart/statechart {}
                  (state {:id :start}
                    (on-entry {}
                      (assign {:location [:executed] :expr false})
                      (sc-if {:cond (fn [_ _] false)}
                        (assign {:location [:executed] :expr true})))
                    (transition {:event :done :target :end}))
                  (state {:id :end}))
          env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

      (testing/start! env)

      (assertions
        "leaves data unchanged"
        (get-in (testing/data env) [:executed]) => false))))
