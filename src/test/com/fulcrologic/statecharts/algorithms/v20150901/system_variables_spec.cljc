(ns com.fulcrologic.statecharts.algorithms.v20150901.system-variables-spec
  "Tests for SCXML system variables as per W3C SCXML specification Section 5.7 and 5.9.
   These tests verify that _sessionid, In() predicate, and optionally _name are properly
   accessible from expression contexts."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [In assign data-model final on-entry parallel script state transition]]
    [com.fulcrologic.statecharts.testing :as testing]
    [fulcro-spec.core :refer [=> assertions behavior component specification]]))

;; =============================================================================
;; Test: _sessionid system variable
;; =============================================================================

(specification "_sessionid system variable"
  (component "after initialization"
    (let [captured-session-id (volatile! nil)
          capture-fn          (fn [env data]
                                (vreset! captured-session-id (:_sessionid data)))
          chart               (chart/statechart {}
                                (state {:id :start}
                                  (on-entry {}
                                    (script {:expr capture-fn}))
                                  (transition {:event :done :target :end}))
                                (final {:id :end}))
          env                 (testing/new-testing-env
                                {:statechart      chart
                                 :session-id      ::test-session
                                 :mocking-options {:run-unmocked? true}}
                                {})]

      (testing/start! env)

      (behavior "is accessible from expression lambdas via data model"
        (assertions
          "captured session-id matches the initialized session"
          @captured-session-id => ::test-session))

      (behavior "is present in data model after initialization"
        (assertions
          "data model contains _sessionid key"
          (:_sessionid (testing/data env)) => ::test-session))))

  (component "with assign operation"
    (let [chart (chart/statechart {}
                  (state {:id :start}
                    (on-entry {}
                      (assign {:location :stored-session
                               :expr     (fn [_ data] (:_sessionid data))}))
                    (transition {:event :check :target :done}))
                  (final {:id :done}))
          env   (testing/new-testing-env
                  {:statechart      chart
                   :session-id      ::another-session
                   :mocking-options {:run-unmocked? true}}
                  {})]

      (testing/start! env)

      (behavior "can be read and assigned to other data model locations"
        (assertions
          "_sessionid is readable and assignable"
          (:stored-session (testing/data env)) => ::another-session
          "_sessionid remains in data model"
          (:_sessionid (testing/data env)) => ::another-session))))

  (component "across state transitions"
    (let [session-ids       (volatile! [])
          capture-fn        (fn [env data]
                              (vswap! session-ids conj (:_sessionid data)))
          chart             (chart/statechart {}
                              (state {:id :first}
                                (on-entry {}
                                  (script {:expr capture-fn}))
                                (transition {:event :next :target :second}))
                              (state {:id :second}
                                (on-entry {}
                                  (script {:expr capture-fn}))
                                (transition {:event :done :target :end}))
                              (final {:id :end}))
          env               (testing/new-testing-env
                              {:statechart      chart
                               :session-id      ::persistent-session
                               :mocking-options {:run-unmocked? true}}
                              {})]

      (testing/start! env)
      (testing/run-events! env :next)

      (behavior "remains consistent across state transitions"
        (assertions
          "session-id captured in first state"
          (first @session-ids) => ::persistent-session
          "session-id captured in second state"
          (second @session-ids) => ::persistent-session
          "both captures are identical"
          (= (first @session-ids) (second @session-ids)) => true)))))

;; =============================================================================
;; Test: In() predicate
;; =============================================================================

(specification "In() predicate"
  (component "basic state testing"
    (let [in-result         (volatile! nil)
          test-fn           (fn [env data]
                              (vreset! in-result ((In :start) env data)))
          chart             (chart/statechart {}
                              (state {:id :start}
                                (on-entry {}
                                  (script {:expr test-fn}))
                                (transition {:event :go :target :other}))
                              (state {:id :other})
                              (final {:id :end}))
          env               (testing/new-testing-env
                              {:statechart      chart
                               :mocking-options {:run-unmocked? true}}
                              {})]

      (testing/start! env)

      (behavior "returns true when machine is in the specified state"
        (assertions
          "In(:start) returns true while in :start"
          @in-result => true))

      (vreset! in-result nil)
      (let [test-not-in-start (fn [env data]
                                (vreset! in-result ((In :start) env data)))
            chart2            (chart/statechart {}
                                (state {:id :start}
                                  (transition {:event :go :target :other}))
                                (state {:id :other}
                                  (on-entry {}
                                    (script {:expr test-not-in-start})))
                                (final {:id :end}))
            env2              (testing/new-testing-env
                                {:statechart      chart2
                                 :mocking-options {:run-unmocked? true}}
                                {})]

        (testing/start! env2)
        (testing/run-events! env2 :go)

        (behavior "returns false when machine is not in the specified state"
          (assertions
            "In(:start) returns false when in :other"
            @in-result => false)))))

  (component "as transition condition"
    (let [chart (chart/statechart {}
                  (state {:id :a}
                    (transition {:event  :try-from-a
                                 :target :success
                                 :cond   (In :a)})
                    (transition {:event  :try-from-b
                                 :target :fail
                                 :cond   (In :b)}))
                  (state {:id :success})
                  (state {:id :fail}))
          env   (testing/new-testing-env
                  {:statechart      chart
                   :mocking-options {:run-unmocked? true}}
                  {})]

      (testing/start! env)

      (behavior "enables transition when condition is true"
        (testing/run-events! env :try-from-a)
        (assertions
          "transitions to success when In(:a) is true"
          (testing/in? env :success) => true)))

    (let [chart2 (chart/statechart {}
                   (state {:id :a}
                     (transition {:event  :try-from-a
                                  :target :success
                                  :cond   (In :a)})
                     (transition {:event  :try-from-b
                                  :target :fail
                                  :cond   (In :b)}))
                   (state {:id :success})
                   (state {:id :fail}))
          env2   (testing/new-testing-env
                   {:statechart      chart2
                    :mocking-options {:run-unmocked? true}}
                   {})]

      (testing/start! env2)

      (behavior "disables transition when condition is false"
        (testing/run-events! env2 :try-from-b)
        (assertions
          "does not transition when In(:b) is false"
          (testing/in? env2 :a) => true
          "does not reach fail state"
          (testing/in? env2 :fail) => false))))

  (component "with nested states"
    (let [results           (volatile! {})
          test-parent-fn    (fn [env data]
                              (vswap! results assoc :in-parent ((In :parent) env data)))
          test-child-fn     (fn [env data]
                              (vswap! results assoc :in-child ((In :child) env data)))
          chart             (chart/statechart {}
                              (state {:id :parent}
                                (state {:id :child}
                                  (on-entry {}
                                    (script {:expr test-parent-fn})
                                    (script {:expr test-child-fn})))
                                (transition {:event :done :target :end}))
                              (final {:id :end}))
          env               (testing/new-testing-env
                              {:statechart      chart
                               :mocking-options {:run-unmocked? true}}
                              {})]

      (testing/start! env)

      (behavior "returns true for both parent and child when in nested state"
        (assertions
          "In(:parent) returns true"
          (:in-parent @results) => true
          "In(:child) returns true"
          (:in-child @results) => true))))

  (component "with parallel states"
    (let [results             (volatile! {})
          test-region-a-fn    (fn [env data]
                                (vswap! results assoc :in-a ((In :region-a) env data)))
          test-region-b-fn    (fn [env data]
                                (vswap! results assoc :in-b ((In :region-b) env data)))
          chart               (chart/statechart {}
                                (parallel {:id :parallel}
                                  (state {:id :region-a}
                                    (on-entry {}
                                      (script {:expr test-region-a-fn})))
                                  (state {:id :region-b}
                                    (on-entry {}
                                      (script {:expr test-region-b-fn})))
                                  (transition {:event :done :target :end}))
                                (final {:id :end}))
          env                 (testing/new-testing-env
                                {:statechart      chart
                                 :mocking-options {:run-unmocked? true}}
                                {})]

      (testing/start! env)

      (behavior "returns true for all active parallel regions"
        (assertions
          "In(:region-a) returns true"
          (:in-a @results) => true
          "In(:region-b) returns true"
          (:in-b @results) => true))))

  (component "with non-existent state"
    (let [result            (volatile! nil)
          test-fn           (fn [env data]
                              (vreset! result ((In :non-existent) env data)))
          chart             (chart/statechart {}
                              (state {:id :start}
                                (on-entry {}
                                  (script {:expr test-fn}))))
          env               (testing/new-testing-env
                              {:statechart      chart
                               :mocking-options {:run-unmocked? true}}
                              {})]

      (testing/start! env)

      (behavior "returns false for non-existent states"
        (assertions
          "In(:non-existent) returns false"
          @result => false)))))

;; =============================================================================
;; Test: _name system variable (optional)
;; =============================================================================

(specification "_name system variable"
  (component "when statechart has a name"
    (let [captured-name (volatile! nil)
          capture-fn    (fn [env data]
                          (vreset! captured-name (:_name data)))
          chart         (chart/statechart {:name "TestChart"}
                          (state {:id :start}
                            (on-entry {}
                              (script {:expr capture-fn}))
                            (transition {:event :done :target :end}))
                          (final {:id :end}))
          env           (testing/new-testing-env
                          {:statechart      chart
                           :mocking-options {:run-unmocked? true}}
                          {})]

      (testing/start! env)

      (behavior "is accessible from expression lambdas"
        (assertions
          "captured name matches the chart name"
          @captured-name => "TestChart"
          "data model contains _name"
          (:_name (testing/data env)) => "TestChart"))))

  (component "when statechart has no name"
    (let [captured-name (volatile! nil)
          capture-fn    (fn [env data]
                          (vreset! captured-name (:_name data)))
          chart         (chart/statechart {}
                          (state {:id :start}
                            (on-entry {}
                              (script {:expr capture-fn}))
                            (transition {:event :done :target :end}))
                          (final {:id :end}))
          env           (testing/new-testing-env
                          {:statechart      chart
                           :mocking-options {:run-unmocked? true}}
                          {})]

      (testing/start! env)

      (behavior "is nil or absent when chart has no name"
        (assertions
          "captured name is nil"
          (nil? @captured-name) => true)))))

;; =============================================================================
;; Test: Combined system variables
;; =============================================================================

(specification "Combined system variable usage"
  (let [results     (volatile! {})
        collect-fn  (fn [env data]
                      (vreset! results {:session (:_sessionid data)
                                        :name    (:_name data)
                                        :in-a    ((In :a) env data)
                                        :in-b    ((In :b) env data)}))
        chart       (chart/statechart {:name "CombinedTest"}
                      (state {:id :a}
                        (on-entry {}
                          (script {:expr collect-fn}))
                        (transition {:event :go :target :b}))
                      (state {:id :b}))
        env         (testing/new-testing-env
                      {:statechart      chart
                       :session-id      ::combined-session
                       :mocking-options {:run-unmocked? true}}
                      {})]

    (testing/start! env)

    (behavior "all system variables work together correctly"
      (assertions
        "_sessionid is present"
        (:session @results) => ::combined-session
        "_name is present"
        (:name @results) => "CombinedTest"
        "In() correctly identifies current state"
        (:in-a @results) => true
        "In() correctly identifies non-current state"
        (:in-b @results) => false))))
