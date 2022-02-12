(ns com.fulcrologic.statecharts.algorithms.v20150901.atom-3-basic-tests-spec
  (:require [com.fulcrologic.statecharts.elements :refer
             [state initial parallel final transition raise on-entry on-exit data-model assign script log]]
            [com.fulcrologic.statecharts :as sc]
            [com.fulcrologic.statecharts.chart :as chart]
            [com.fulcrologic.statecharts.testing :as testing]
            [com.fulcrologic.statecharts.data-model.operations :as ops]
            [fulcro-spec.core :refer [specification assertions =>]]))

(specification "m0"
  (let [chart (chart/statechart {}
                (state {:id :A}
                  (on-entry {} (log {:expr "entering A"}))
                  (on-exit {} (log {:expr "exiting A"}))
                  (transition {:target :B, :event :e1}
                    (log {:expr "doing A->B transition"})))
                (state {:id :B} (transition {:target :A, :event :e2})))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :A) => true)
    (testing/run-events! env :e1)
    (assertions (testing/in? env :B) => true)
    (testing/run-events! env :e2)
    (assertions (testing/in? env :A) => true)))

(specification
  "m1"
  (let [chart (chart/statechart
                {}
                (state {:id :A}
                  (on-entry {} (log {:expr "entering state A"}))
                  (on-exit {} (log {:expr "exiting state A"}))
                  (transition {:target :B, :event :e1} (log {:expr "triggered by e1"})))
                (state {:id :B} (transition {:target :A, :event :e2} (log {:expr "triggered by e2"}))))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :A) => true)
    (testing/run-events! env :e1)
    (assertions (testing/in? env :B) => true)
    (testing/run-events! env :e2)
    (assertions (testing/in? env :A) => true)))

(specification
  "m2"
  (let [chart
            (chart/statechart
              {}
              (state {:id :AB}
                (initial {} (transition {:target :A}))
                (state {:id :A}
                  (on-entry {} (log {:expr "entering state A"}))
                  (on-exit {} (log {:expr "exiting state A"}))
                  (transition {:target :B, :event :e1} (log {:expr "triggered by e1"})))
                (state {:id :B}
                  (transition {:target :A, :event :e2} (log {:expr "triggered by e2"})))))
        env (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :A) => true)
    (testing/run-events! env :e1)
    (assertions (testing/in? env :B) => true)
    (testing/run-events! env :e2)
    (assertions (testing/in? env :A) => true)))

(specification
  "m3"
  (let [chart (chart/statechart
                {}
                (state {:id :AB}
                  (initial {} (transition {:target :A}))
                  (state {:id :A}
                    (on-entry {} (log {:expr "entering state A"}))
                    (on-exit {} (log {:expr "exiting state A"}))
                    (transition {:target :B, :event :e1} (log {:expr "triggered by e1"})))
                  (state {:id :B}
                    (transition {:target :A, :event :e2} (log {:expr "triggered by e2"})))
                  (transition {:target :C, :event :e1}))
                (state {:id :C}
                  (on-entry {} (log {:expr "entering state C"}))
                  (on-exit {} (log {:expr "exiting state C"}))))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :A) => true)
    (testing/run-events! env :e1)
    (assertions (testing/in? env :B) => true)
    (testing/run-events! env :e2)
    (assertions (testing/in? env :A) => true)
    (testing/run-events! env :e1)
    (assertions (testing/in? env :B) => true)
    (testing/run-events! env :e1)
    (assertions (testing/in? env :C) => true)))