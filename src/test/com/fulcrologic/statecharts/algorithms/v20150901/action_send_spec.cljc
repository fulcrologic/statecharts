(ns com.fulcrologic.statecharts.algorithms.v20150901.action-send-spec
  (:require
    [com.fulcrologic.guardrails.config :as grc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements
     :refer [initial
             on-entry
             on-exit
             raise
             state
             transition]]
    [com.fulcrologic.statecharts.testing :as testing]
    [fulcro-spec.core :refer [=> assertions specification]]))

(grc/clear-exclusions!)

(specification "send1"
  (let [chart (chart/statechart {}
                (state {:id :a}
                  (transition {:target :b :event :t}
                    (raise {:event :s})))
                (state {:id :b}
                  (transition {:target :c :event :s}))
                (state {:id :c}))
        env   (testing/new-testing-env {:statechart chart} {})]

    (testing/start! env)

    (assertions
      (testing/in? env :a) => true)

    (testing/run-events! env :t)

    (assertions
      (testing/in? env :c) => true)))

(specification "send2"
  (let [chart (chart/statechart {}
                (state {:id :a}
                  (on-exit {}
                    (raise {:event :s}))
                  (transition {:target :b :event :t}))
                (state {:id :b}
                  (transition {:target :c :event :s}))
                (state {:id :c}))
        env   (testing/new-testing-env {:statechart chart} {})]

    (testing/start! env)

    (assertions
      (testing/in? env :a) => true)

    (testing/run-events! env :t)

    (assertions
      (testing/in? env :c) => true)))


(specification "send3"
  (let [chart (chart/statechart {}
                (state {:id :a}
                  (transition {:target :b :event :t}))
                (state {:id :b}
                  (on-entry {}
                    (raise {:event :s}))
                  (transition {:target :c :event :s}))
                (state {:id :c}))
        env   (testing/new-testing-env {:statechart chart} {})]

    (testing/start! env)

    (assertions
      (testing/in? env :a) => true)

    (testing/run-events! env :t)

    (assertions
      (testing/in? env :c) => true)))

(specification "send4"
  (let [chart (chart/statechart {}
                (state {:id :a}
                  (transition {:target :b :event :t}))
                (state {:id :b}
                  (on-entry {}
                    (raise {:event :s}))
                  (transition {:target :c :event :s})
                  (transition {:target :f1}))
                (state {:id :c}
                  (transition {:target :f2 :event :s})
                  (transition {:target :d}))
                (state {:id :f1})
                (state {:id :d})
                (state {:id :f2}))
        env   (testing/new-testing-env {:statechart chart} {})]

    (testing/start! env)

    (assertions
      (testing/in? env :a) => true)

    (testing/run-events! env :t)

    (assertions
      (testing/in? env :f1) => true)))

(specification "send4b"
  (let [chart (chart/statechart {}
                (state {:id :a}
                  (transition {:target :b :event :t}))
                (state {:id :b}
                  (on-entry {}
                    (raise {:event :s}))
                  (transition {:target :c :event :s}))
                (state {:id :c}))
        env   (testing/new-testing-env {:statechart chart} {})]

    (testing/start! env)

    (assertions
      (testing/in? env :a) => true)

    (testing/run-events! env :t)

    (assertions
      (testing/in? env :c) => true)))

(specification "send7"
  (let [chart (chart/statechart {}
                (state {:id :a}
                  (transition {:target :b :event :t}
                    (raise {:event :s})))
                (state {:id :b :initial :b1}
                  (state {:id :b1}
                    (transition {:event :s :target :b2})
                    (transition {:target :b3}))
                  (state {:id :b2})
                  (state {:id :b3})))
        env   (testing/new-testing-env {:statechart chart} {})]

    (testing/start! env)

    (assertions
      (testing/in? env :a) => true)

    (testing/run-events! env :t)

    (assertions
      (testing/in? env :b3) => true)))

(specification "send7b"
  (let [chart (chart/statechart {}
                (state {:id :a}
                  (transition {:target :b :event :t}
                    (raise {:event :s})))
                (state {:id :b :initial :b1}
                  (state {:id :b1}
                    (transition {:event :s :target :b2}))
                  (state {:id :b2})
                  (state {:id :b3})))
        env   (testing/new-testing-env {:statechart chart} {})]

    (testing/start! env)

    (assertions
      (testing/in? env :a) => true)

    (testing/run-events! env :t)

    (assertions
      (testing/in? env :b2) => true)))


(specification "send8"
  (let [chart (chart/statechart {}
                (state {:id :a}
                  (transition {:target :b1 :event :t}
                    (raise {:event :s})))
                (state {:id :b :initial :b1}
                  (state {:id :b1}
                    (transition {:event :s :target :b2})
                    (transition {:target :b3}))
                  (state {:id :b2})
                  (state {:id :b3})))
        env   (testing/new-testing-env {:statechart chart} {})]

    (testing/start! env)

    (assertions
      (testing/in? env :a) => true)

    (testing/run-events! env :t)

    (assertions
      (testing/in? env :b3) => true)))

(specification "send8b"
  (let [chart (chart/statechart {}
                (state {:id :a}
                  (transition {:target :b1 :event :t}
                    (raise {:event :s})))
                (state {:id :b :initial :b1}
                  (state {:id :b1}
                    (transition {:event :s :target :b2}))
                  (state {:id :b2})
                  (state {:id :b3})))
        env   (testing/new-testing-env {:statechart chart} {})]

    (testing/start! env)

    (assertions
      (testing/in? env :a) => true)

    (testing/run-events! env :t)

    (assertions
      (testing/in? env :b2) => true)))

(specification "send9"
  (let [chart (chart/statechart {}
                (state {:id :a}
                  (transition {:target :b :event :t}
                    (raise {:event :s})))
                (state {:id :b}
                  (initial {}
                    (transition {:target :b1}))
                  (state {:id :b1}
                    (transition {:event :s :target :b2})
                    (transition {:target :b3}))
                  (state {:id :b2})
                  (state {:id :b3})))
        env   (testing/new-testing-env {:statechart chart} {})]

    (testing/start! env)

    (assertions
      (testing/in? env :a) => true)

    (testing/run-events! env :t)

    (assertions
      (testing/in? env :b3) => true)))
