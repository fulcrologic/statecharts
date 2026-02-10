(ns com.fulcrologic.statecharts.algorithms.v20150901-async.async-spec
  "Tests for async-specific behaviors in the async statechart processor.
   Covers promise handling, async expressions, rejection handling, and mixed sync/async execution."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state transition on-entry on-exit script]]
    [com.fulcrologic.statecharts.testing-async :as testing]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [promesa.core :as p]
    [fulcro-spec.core :refer [=> assertions specification]]))

;; =============================================================================
;; Test 1: Expression returns a resolved promise
;; =============================================================================

(def assign-via-promise
  (fn [env data]
    (p/resolved [(ops/assign :x 42)])))

(specification "Script expression returns resolved promise with data model operations"
  (let [chart (chart/statechart {:initial :start}
                (state {:id :start}
                  (on-entry {}
                    (script {:expr assign-via-promise}))
                  (transition {:event :done :target :end}))
                (state {:id :end}))
        env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

    (testing/start! env)

    (assertions
      "State is entered after async entry completes"
      (testing/in? env :start) => true
      "Data model is updated after promise resolves"
      (get (testing/data env) :x) => 42)

    (testing/run-events! env :done)

    (assertions
      "Transitions work normally after async expressions"
      (testing/in? env :end) => true)))

;; =============================================================================
;; Test 2: Entry handler with async expression
;; =============================================================================

(def async-entry-script
  (fn [env data]
    (p/resolved [(ops/assign :initialized true)])))

(specification "on-entry script returns promise and state is entered correctly"
  (let [chart (chart/statechart {:initial :loading}
                (state {:id :loading}
                  (on-entry {}
                    (script {:expr async-entry-script}))
                  (transition {:event :loaded :target :ready}))
                (state {:id :ready}))
        env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

    (testing/start! env)

    (assertions
      "State is active after async entry handler completes"
      (testing/in? env :loading) => true
      "Entry handler promise resolved and updated data model"
      (get (testing/data env) :initialized) => true)

    (testing/run-events! env :loaded)

    (assertions
      "Can transition after async entry handler"
      (testing/in? env :ready) => true)))

;; =============================================================================
;; Test 3: Mixed sync/async expressions
;; =============================================================================

(def sync-script
  (fn [env data]
    [(ops/assign :sync-ran true)]))

(def async-script
  (fn [env data]
    (p/resolved [(ops/assign :async-ran true)])))

(specification "Chart with mixed sync and async expressions"
  (let [chart (chart/statechart {:initial :state-a}
                (state {:id :state-a}
                  (on-entry {}
                    (script {:expr sync-script}))
                  (transition {:event :next :target :state-b}))
                (state {:id :state-b}
                  (on-entry {}
                    (script {:expr async-script}))
                  (transition {:event :next :target :state-c}))
                (state {:id :state-c}
                  (on-entry {}
                    (script {:expr sync-script}))))
        env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

    (testing/start! env)

    (assertions
      "Sync script runs on entry to state-a"
      (testing/in? env :state-a) => true
      (get (testing/data env) :sync-ran) => true)

    (testing/run-events! env :next)

    (assertions
      "Async script runs on entry to state-b"
      (testing/in? env :state-b) => true
      (get (testing/data env) :async-ran) => true)

    (testing/run-events! env :next)

    (assertions
      "Sync script runs again after async operations"
      (testing/in? env :state-c) => true)))

;; =============================================================================
;; Test 4: Async condition on transition
;; =============================================================================

(def condition-true
  (fn [env data]
    (p/resolved true)))

(def condition-false
  (fn [env data]
    (p/resolved false)))

(specification "Transition with async condition"
  (let [chart (chart/statechart {:initial :check}
                (state {:id :check}
                  (transition {:event :evaluate :cond condition-true :target :success})
                  (transition {:event :evaluate :target :fallback}))
                (state {:id :success})
                (state {:id :fallback}))
        env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

    (testing/start! env)
    (testing/run-events! env :evaluate)

    (assertions
      "Async condition resolving to true enables transition"
      (testing/in? env :success) => true))

  (let [chart (chart/statechart {:initial :check}
                (state {:id :check}
                  (transition {:event :evaluate :cond condition-false :target :success})
                  (transition {:event :evaluate :target :fallback}))
                (state {:id :success})
                (state {:id :fallback}))
        env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

    (testing/start! env)
    (testing/run-events! env :evaluate)

    (assertions
      "Async condition resolving to false prevents transition"
      (testing/in? env :fallback) => true)))

;; =============================================================================
;; Test 5: Multiple sequential async entry handlers
;; =============================================================================

(def first-async-handler
  (fn [env data]
    (p/resolved [(ops/assign :first true)])))

(def second-async-handler
  (fn [env data]
    (p/resolved [(ops/assign :second true)])))

(def third-async-handler
  (fn [env data]
    (p/resolved [(ops/assign :third true)])))

(specification "Multiple sequential on-entry scripts returning promises"
  (let [chart (chart/statechart {:initial :multi-entry}
                (state {:id :multi-entry}
                  (on-entry {}
                    (script {:expr first-async-handler})
                    (script {:expr second-async-handler})
                    (script {:expr third-async-handler}))
                  (transition {:event :done :target :complete}))
                (state {:id :complete}))
        env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

    (testing/start! env)

    (assertions
      "All async entry handlers execute"
      (testing/in? env :multi-entry) => true
      "First handler completes"
      (get (testing/data env) :first) => true
      "Second handler completes"
      (get (testing/data env) :second) => true
      "Third handler completes"
      (get (testing/data env) :third) => true)

    (testing/run-events! env :done)

    (assertions
      "Can transition after multiple async handlers"
      (testing/in? env :complete) => true)))

;; =============================================================================
;; Test 6: Async exit handlers
;; =============================================================================

(def async-exit-script
  (fn [env data]
    (p/resolved [(ops/assign :exited-cleanly true)])))

(specification "on-exit script returns promise and executes before leaving state"
  (let [chart (chart/statechart {:initial :active}
                (state {:id :active}
                  (on-exit {}
                    (script {:expr async-exit-script}))
                  (transition {:event :stop :target :stopped}))
                (state {:id :stopped}))
        env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

    (testing/start! env)

    (assertions
      "Initial state is active"
      (testing/in? env :active) => true
      "Exit handler has not run yet"
      (get (testing/data env) :exited-cleanly) => nil)

    (testing/run-events! env :stop)

    (assertions
      "Transition completes after async exit handler"
      (testing/in? env :stopped) => true
      "Exit handler promise resolved and updated data model"
      (get (testing/data env) :exited-cleanly) => true)))

;; =============================================================================
;; Test 7: Promise rejection handling
;; =============================================================================

(def rejecting-script
  (fn [env data]
    (p/rejected (ex-info "Intentional test error" {:test true}))))

(specification "Script expression that returns rejected promise"
  (let [chart (chart/statechart {:initial :normal}
                (state {:id :normal}
                  (on-entry {}
                    (script {:expr rejecting-script}))
                  (transition {:event :error.execution :target :error-state}))
                (state {:id :error-state}))
        env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

    (testing/start! env)

    (assertions
      "State is entered despite promise rejection"
      (testing/in? env :normal) => true)

    ;; The error.execution event should be sent automatically by the processor
    ;; Note: This may require running the event in the next turn, depending on
    ;; how the async event queue works
    (testing/run-events! env :error.execution)

    (assertions
      "error.execution event triggers transition to error handler state"
      (testing/in? env :error-state) => true)))

;; =============================================================================
;; Test 8: Async script on transition
;; =============================================================================

(def transition-script
  (fn [env data]
    (p/resolved [(ops/assign :transition-executed true)])))

(specification "Transition with async script action"
  (let [chart (chart/statechart {:initial :start}
                (state {:id :start}
                  (transition {:event :go :target :end}
                    (script {:expr transition-script})))
                (state {:id :end}))
        env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

    (testing/start! env)
    (testing/run-events! env :go)

    (assertions
      "Transition completes after async script"
      (testing/in? env :end) => true
      "Async script on transition executes"
      (get (testing/data env) :transition-executed) => true)))

;; =============================================================================
;; Test 9: Chained async operations
;; =============================================================================

(def first-step
  (fn [env data]
    (p/resolved [(ops/assign :step 1)])))

(def second-step
  (fn [env data]
    (let [current-step (get data :step 0)]
      (p/resolved [(ops/assign :step (inc current-step))]))))

(def third-step
  (fn [env data]
    (let [current-step (get data :step 0)]
      (p/resolved [(ops/assign :step (inc current-step))]))))

(specification "Chained async operations in sequence"
  (let [chart (chart/statechart {:initial :chain}
                (state {:id :chain}
                  (on-entry {}
                    (script {:expr first-step})
                    (script {:expr second-step})
                    (script {:expr third-step}))
                  (transition {:event :done :target :complete}))
                (state {:id :complete}))
        env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]

    (testing/start! env)

    (assertions
      "All async operations execute in order"
      (testing/in? env :chain) => true
      "Final step value is correct"
      (get (testing/data env) :step) => 3)))
