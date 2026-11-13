(ns com.fulcrologic.statecharts.algorithms.v20150901.misc-spec
  (:require [com.fulcrologic.statecharts :as sc]
            [com.fulcrologic.statecharts.chart :as chart]
            [com.fulcrologic.statecharts.elements :refer
             [finalize invoke on-entry script state transition]]
            [com.fulcrologic.statecharts.testing :as testing]
            [fulcro-spec.core :refer [=> assertions specification]]))

(specification
  "deep-initial"
  (let [chart (chart/statechart
                {:initial :s2}
                (state {:id :uber}
                  (state {:id :s1}
                    (on-entry {})
                    (transition {:event :ev1, :target :s2}))
                  (state {:id :s2})))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :s2) => true)))

(defn- record-finalize [& _] nil)

(specification "An invoke without an :id or :idlocation"
  (let [chart       (chart/statechart {:initial :idle}
                      (state {:id :idle}
                        (transition {:event :open :target :open}))
                      (state {:id :open}
                        (invoke {:type :statechart :src :child :autoforward true}
                          (finalize {} (script {:expr record-finalize})))
                        (transition {:event :close :target :idle})))
        env         (testing/new-testing-env {:statechart chart} {})
        invocations (.-invocations (first (::sc/invocation-processors (:env env))))]
    (testing/start! env)
    (testing/run-events! env :open)
    (let [invokeid (first (keys @invocations))]
      (testing/run-events! env :ping {:name :reply :invokeid invokeid} :close)
      (assertions
        "forwards its external events to its child by the generated id"
        (set (map :name (get-in @invocations [invokeid :forward-events]))) => #{:ping :reply :close}
        "runs its finalize for events from that id"
        (testing/ran? env record-finalize) => true
        "stops its child by that id when its state is exited"
        (get-in @invocations [invokeid :finished?]) => true))))
