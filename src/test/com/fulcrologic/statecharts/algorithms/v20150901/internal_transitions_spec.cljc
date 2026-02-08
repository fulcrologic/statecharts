(ns com.fulcrologic.statecharts.algorithms.v20150901.internal-transitions-spec
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer
     [assign data-model data-model on-entry on-exit
      parallel state transition]]
    [com.fulcrologic.statecharts.events :as evt]
    [com.fulcrologic.statecharts.testing :as testing]
    [fulcro-spec.core :refer [=> assertions specification]]))

(defn inc-x-expr [_ {:keys [x]}]
  (inc x))

(defn make-x-eq-expr [v]
  (fn [_ {:keys [x]}] (= x v)))

(specification "test0"
  (let [chart (chart/statechart {}
                (data-model
                  {:expr {:x 0}})
                (state
                  {:id :a}
                  (on-entry {}
                    (assign {:location :x, :expr inc-x-expr}))
                  (on-exit {}
                    (assign {:location :x, :expr inc-x-expr}))
                  (state {:id :a1})
                  (state {:id :a2}
                    (transition {:target :b, :event :t2, :cond (make-x-eq-expr 1)}))
                  (transition
                    {:target :a2, :event :t1, :type :internal, :cond (make-x-eq-expr 1)}))
                (state {:id :b}
                  (transition {:target :c, :event :t3, :cond (make-x-eq-expr 2)}))
                (state {:id :c}))
        env   (testing/new-testing-env {:statechart      chart
                                        :mocking-options {:run-unmocked? true}} {})]
    (testing/start! env)
    (assertions (testing/in? env :a1) => true)
    (testing/run-events! env :t1)
    (assertions (testing/in? env :a2) => true)
    (testing/run-events! env :t2)
    (assertions (testing/in? env :b) => true)
    (testing/run-events! env :t3)
    (assertions (testing/in? env :c) => true)))

(specification
  "test1"
  (let [chart
            (chart/statechart
              {}
              (data-model
                {:expr {:x 0}})
              (parallel
                {:id :p}
                (on-entry {} (assign {:location :x, :expr inc-x-expr}))
                (on-exit {} (assign {:location :x, :expr inc-x-expr}))
                (state
                  {:id :a}
                  (on-entry {} (assign {:location :x, :expr inc-x-expr}))
                  (on-exit {} (assign {:location :x, :expr inc-x-expr}))
                  (state {:id :a1}
                    (on-entry {} (assign {:location :x, :expr inc-x-expr}))
                    (on-exit {} (assign {:location :x, :expr inc-x-expr})))
                  (state {:id :a2}
                    (on-entry {} (assign {:location :x, :expr inc-x-expr}))
                    (on-exit {} (assign {:location :x, :expr inc-x-expr}))
                    (transition {:target :c, :event :t2, :cond (make-x-eq-expr 5)}))
                  (transition
                    {:target :a2, :event :t1, :type :internal, :cond (make-x-eq-expr 3)}))
                (state {:id :b} (state {:id :b1}) (state {:id :b2})))
              (state {:id :c}
                (transition {:target :d, :event :t3, :cond (make-x-eq-expr 8)}))
              (state {:id :d}))
        env (testing/new-testing-env {:statechart      chart
                                      :mocking-options {:run-unmocked? true}} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :a1) => true
      (testing/in? env :b1) => true)
    (testing/run-events! env :t1)
    (assertions
      (testing/in? env :a2) => true
      (testing/in? env :b1) => true)
    (testing/run-events! env :t2)
    (assertions
      (testing/in? env :c) => true)
    (testing/run-events! env :t3)
    (assertions
      (testing/in? env :d) => true)))

(specification "Events visible in transitions" :focus
  (let [events-seen (volatile! [])
        expr-seen   (volatile! [])
        chart       (chart/statechart {}
                      (state {:id :root}
                        (data-model {:expr {:y 1}})
                        (state {:id :a}
                          (transition {:cond (fn [_ args]
                                               (vswap! events-seen conj args)
                                               false)})
                          (transition {:event  :ping
                                       :target :b}))
                        (state {:id :b}
                          (transition {:cond (fn [_ args]
                                               (vswap! events-seen conj args)
                                               false)})
                          (transition {:event  :pong
                                       :target :a}))))
        env         (testing/new-testing-env {:statechart      chart
                                              :mocking-options {:run-unmocked? true}} {})]
    (testing/start! env)
    (testing/run-events! env (evt/new-event :ping {:x 1}))
    (testing/run-events! env (evt/new-event :pong {:x 2}))

    (assertions
      "Transitions that had no event get just the data model (no :_event)"
      (dissoc (first @events-seen) :_sessionid :_name) => {:y 1}
      "Transitions that had an event, get the event with data"
      (mapv #(dissoc % :_sessionid :_name) (rest @events-seen)) => [
                              {:y 1
                               :_event
                               {:type                                   :external,
                                :name                                   :ping,
                                :data                                   {:x 1},
                                :com.fulcrologic.statecharts/event-name :ping}}
                              {:y 1
                               :_event
                               {:type                                   :external,
                                :name                                   :pong,
                                :data                                   {:x 2},
                                :com.fulcrologic.statecharts/event-name :pong}}])
    ))
