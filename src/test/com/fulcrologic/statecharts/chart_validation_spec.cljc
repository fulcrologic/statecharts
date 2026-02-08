(ns com.fulcrologic.statecharts.chart-validation-spec
  "Tests for chart validation functions, specifically history element validation.

   Note: The `history` element constructor already validates most invalid cases via assertions,
   so we focus on testing that valid history nodes are correctly identified as valid."
  (:require
    [com.fulcrologic.guardrails.config :as grc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [history state transition]]
    [fulcro-spec.core :refer [=> assertions behavior component specification]]))

(grc/clear-exclusions!)

(specification "invalid-history-elements"
  (component "valid shallow history"
    (behavior "produces no errors for properly configured shallow history with one target"
      (let [test-chart (chart/statechart
                         {:initial :a}
                         (state {:id :a} (transition {:target :h :event :go}))
                         (state {:id :b :initial :b1}
                           (history {:id :h :type :shallow}
                             (transition {:target :b1}))
                           (state {:id :b1})
                           (state {:id :b2})))
            invalid    (chart/invalid-history-elements test-chart)]
        (assertions
          "returns empty collection"
          (count invalid) => 0
          "no error messages"
          (seq invalid) => nil))))

  (component "valid deep history"
    (behavior "produces no errors for properly configured deep history"
      (let [test-chart (chart/statechart
                         {:initial :a}
                         (state {:id :a} (transition {:target :h :event :go}))
                         (state {:id :b :initial :b1}
                           (history {:id :h :type :deep}
                             (transition {:target :b1}))
                           (state {:id :b1 :initial :b1a}
                             (state {:id :b1a})
                             (state {:id :b1b}))))
            invalid    (chart/invalid-history-elements test-chart)]
        (assertions
          "returns empty collection"
          (count invalid) => 0
          "no error messages"
          (seq invalid) => nil))))

  (component "multiple shallow history in same parent"
    (behavior "each valid shallow history produces no errors"
      (let [test-chart (chart/statechart
                         {:initial :a}
                         (state {:id :a}
                           (transition {:target :h1 :event :go1})
                           (transition {:target :h2 :event :go2}))
                         (state {:id :b :initial :b1}
                           (history {:id :h1 :type :shallow}
                             (transition {:target :b1}))
                           (state {:id :b1})
                           (state {:id :b2}))
                         (state {:id :c :initial :c1}
                           (history {:id :h2 :type :shallow}
                             (transition {:target :c1}))
                           (state {:id :c1})
                           (state {:id :c2})))
            invalid    (chart/invalid-history-elements test-chart)]
        (assertions
          "returns empty collection for all histories"
          (count invalid) => 0)))))
