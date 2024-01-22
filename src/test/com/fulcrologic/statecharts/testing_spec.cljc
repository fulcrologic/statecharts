(ns com.fulcrologic.statecharts.testing-spec
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state transition]]
    [com.fulcrologic.statecharts.testing :as testing]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(defn is-tuesday? [env data] false)

(def some-statechart
  (chart/statechart {}
    (state {:id :state/start}
      (transition {:cond   is-tuesday?
                   :event  :event/trigger
                   :target :state/next}))
    (state {:id :state/next})))

(defn config [] {:statechart some-statechart})

(specification "My Machine"
  (component "When it is tuesday"
    (let [env (testing/new-testing-env (config) {is-tuesday? true})]

      (testing/start! env)
      (testing/run-events! env :event/trigger)

      (assertions
        "Checks to see that it is tuesday"
        (testing/ran? env is-tuesday?) => true
        "Goes to the next state"
        (testing/in? env :state/next) => true)

      (testing/goto-configuration! env [] #{:state/start})

      (assertions
        "Can be forced into a state"
        (testing/in? env :state/start) => true))))
