(ns com.fulcrologic.statecharts.algorithms.v20150901-async.regression-spec
  "Comprehensive regression test suite for the async statechart processor.
   Uses testing-async with synchronous expressions to prove behavioral equivalence
   with the sync processor across all major SCXML algorithm paths."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [assign data-model final history initial
                                                   on-entry on-exit parallel script state transition]]
    [com.fulcrologic.statecharts.events :as evt]
    [com.fulcrologic.statecharts.testing-async :as testing]
    [fulcro-spec.core :refer [=> assertions specification]]))

;;; ============================================================================
;;; Basic State Machine Behavior
;;; ============================================================================

(specification "Basic: Initial state"
  (let [chart (chart/statechart {:initial :a} (state {:id :a}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      "Enters the initial state on start"
      (testing/in? env :a) => true)))

(specification "Basic: Simple transition"
  (let [chart (chart/statechart {}
                (state {:id :a} (transition {:target :b, :event :t}))
                (state {:id :b}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      "Starts in first state"
      (testing/in? env :a) => true)
    (testing/run-events! env :t)
    (assertions
      "Transitions to target state on event"
      (testing/in? env :b) => true)))

(specification "Basic: Multi-step transitions"
  (let [chart (chart/statechart {}
                (state {:id :a} (transition {:target :b, :event :t1}))
                (state {:id :b} (transition {:target :c, :event :t2}))
                (state {:id :c}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t1)
    (assertions (testing/in? env :b) => true)
    (testing/run-events! env :t2)
    (assertions (testing/in? env :c) => true)))

;;; ============================================================================
;;; Hierarchical States
;;; ============================================================================

(specification "Hierarchy: Nested states"
  (let [chart (chart/statechart {}
                (state {:id :a}
                  (state {:id :a1} (transition {:target :a2, :event :t}))
                  (state {:id :a2})))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      "Enters first child state by default"
      (testing/in? env :a1) => true
      "Parent state is also active"
      (testing/in? env :a) => true)
    (testing/run-events! env :t)
    (assertions
      "Transitions between siblings"
      (testing/in? env :a2) => true
      "Parent remains active"
      (testing/in? env :a) => true)))

(specification "Hierarchy: Document order determines transition priority"
  (let [chart (chart/statechart {}
                (state {:id :a}
                  (state {:id :a1} (transition {:target :a2, :event :t}))
                  (state {:id :a2})
                  (transition {:target :b, :event :t}))
                (state {:id :b}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a1) => true)
    (testing/run-events! env :t)
    (assertions
      "Child transition takes priority (document order)"
      (testing/in? env :a2) => true)))

(specification "Hierarchy: Parent transition from deep child"
  (let [chart (chart/statechart {}
                (state {:id :a}
                  (state {:id :a1} (transition {:target :b, :event :t}))
                  (state {:id :a2})
                  (transition {:target :a2, :event :t}))
                (state {:id :b}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a1) => true)
    (testing/run-events! env :t)
    (assertions
      "Child transition exits parent entirely"
      (testing/in? env :b) => true)))

;;; ============================================================================
;;; Parallel States
;;; ============================================================================

(specification "Parallel: All regions active simultaneously"
  (let [chart (chart/statechart {}
                (parallel {:id :p} (state {:id :a}) (state {:id :b})))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      "Both parallel regions are active"
      (testing/in? env :a) => true
      (testing/in? env :b) => true)))

(specification "Parallel: Independent transitions in regions"
  (let [chart (chart/statechart {}
                (parallel {:id :p}
                  (state {:id :a}
                    (initial {} (transition {:target :a1}))
                    (state {:id :a1} (transition {:event :t, :target :a2}))
                    (state {:id :a2}))
                  (state {:id :b}
                    (initial {} (transition {:target :b1}))
                    (state {:id :b1} (transition {:event :t, :target :b2}))
                    (state {:id :b2}))))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      (testing/in? env :a1) => true
      (testing/in? env :b1) => true)
    (testing/run-events! env :t)
    (assertions
      "Both regions transition on same event"
      (testing/in? env :a2) => true
      (testing/in? env :b2) => true)))

(specification "Parallel: Nested parallel states"
  (let [chart (chart/statechart {}
                (parallel {:id :p1}
                  (state {:id :s1, :initial :p2}
                    (parallel {:id :p2}
                      (state {:id :s3})
                      (state {:id :s4})
                      (transition {:target :p3, :event :t}))
                    (parallel {:id :p3} (state {:id :s5}) (state {:id :s6})))
                  (state {:id :s2, :initial :p4}
                    (parallel {:id :p4}
                      (state {:id :s7})
                      (state {:id :s8})
                      (transition {:target :p5, :event :t}))
                    (parallel {:id :p5} (state {:id :s9}) (state {:id :s10})))))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      "All nested parallel regions are active"
      (testing/in? env :s3) => true
      (testing/in? env :s4) => true
      (testing/in? env :s7) => true
      (testing/in? env :s8) => true)
    (testing/run-events! env :t)
    (assertions
      "All nested parallel regions transition simultaneously"
      (testing/in? env :s5) => true
      (testing/in? env :s6) => true
      (testing/in? env :s9) => true
      (testing/in? env :s10) => true)))

;;; ============================================================================
;;; History States
;;; ============================================================================

(specification "History: Shallow history with default"
  (let [chart (chart/statechart {:initial :a}
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
    (assertions
      "First entry uses history default"
      (testing/in? env :b2) => true)
    (testing/run-events! env :t2)
    (assertions (testing/in? env :b3) => true)
    (testing/run-events! env :t3)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t1)
    (assertions
      "History restores last active state"
      (testing/in? env :b3) => true)))

(specification "History: Deep history"
  (let [chart (chart/statechart {:initial :a}
                (state {:id :a} (transition {:target :h, :event :t1}))
                (state {:id :b, :initial :b1}
                  (history {:id :h, :type :deep} (transition {:target :b1.2}))
                  (state {:id :b1, :initial :b1.1}
                    (state {:id :b1.1})
                    (state {:id :b1.2} (transition {:event :t2, :target :b1.3}))
                    (state {:id :b1.3} (transition {:event :t3, :target :a})))))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t1)
    (assertions
      "First entry uses deep history default"
      (testing/in? env :b1.2) => true)
    (testing/run-events! env :t2)
    (assertions (testing/in? env :b1.3) => true)
    (testing/run-events! env :t3)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t1)
    (assertions
      "Deep history restores nested state"
      (testing/in? env :b1.3) => true)))

(specification "History: Deep history in parallel states"
  (let [chart (chart/statechart {:initial :a}
                (state {:id :a}
                  (transition {:target :p, :event :t1})
                  (transition {:target :h, :event :t4}))
                (parallel {:id :p}
                  (history {:id :h, :type :deep} (transition {:target :b}))
                  (state {:id :b, :initial :b1}
                    (state {:id :b1} (transition {:target :b2, :event :t2}))
                    (state {:id :b2}))
                  (state {:id :c, :initial :c1}
                    (state {:id :c1} (transition {:target :c2, :event :t2}))
                    (state {:id :c2}))
                  (transition {:target :a, :event :t3})))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t1)
    (assertions
      (testing/in? env :b1) => true
      (testing/in? env :c1) => true)
    (testing/run-events! env :t2)
    (assertions
      (testing/in? env :b2) => true
      (testing/in? env :c2) => true)
    (testing/run-events! env :t3)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t4)
    (assertions
      "Deep history restores all parallel regions"
      (testing/in? env :b2) => true
      (testing/in? env :c2) => true)))

;;; ============================================================================
;;; Conditional Transitions
;;; ============================================================================

(specification "Conditions: Guarded transitions"
  (let [chart (chart/statechart {}
                (data-model {:expr {:x 1}})
                (state {:id :a}
                  (transition {:event  :t1
                               :target :b
                               :cond   (fn [_ {:keys [x]}] (= x 1))})
                  (transition {:event  :t1
                               :target :c}))
                (state {:id :b})
                (state {:id :c}))
        env   (testing/new-testing-env {:statechart      chart
                                        :mocking-options {:run-unmocked? true}} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t1)
    (assertions
      "First transition with true condition is taken"
      (testing/in? env :b) => true)))

(specification "Conditions: Multiple guards"
  (let [chart (chart/statechart {}
                (data-model {:expr {:x 2}})
                (state {:id :a}
                  (transition {:event  :t
                               :target :b
                               :cond   (fn [_ {:keys [x]}] (= x 1))})
                  (transition {:event  :t
                               :target :c
                               :cond   (fn [_ {:keys [x]}] (= x 2))})
                  (transition {:event  :t
                               :target :d}))
                (state {:id :b})
                (state {:id :c})
                (state {:id :d}))
        env   (testing/new-testing-env {:statechart      chart
                                        :mocking-options {:run-unmocked? true}} {})]
    (testing/start! env)
    (testing/run-events! env :t)
    (assertions
      "Second transition guard passes"
      (testing/in? env :c) => true)))

;;; ============================================================================
;;; Entry and Exit Handlers
;;; ============================================================================

(defn inc-x [_ {:keys [x]}] (inc x))
(defn make-x-eq [v] (fn [_ {:keys [x]}] (= x v)))

(specification "Entry/Exit: Execution order"
  (let [order (volatile! [])
        a-fn  (fn [_ _] (vswap! order conj :a))
        b-fn  (fn [_ _] (vswap! order conj :b))
        c-fn  (fn [_ _] (vswap! order conj :c))
        d-fn  (fn [_ _] (vswap! order conj :d))
        chart (chart/statechart {}
                (state {:id :s1}
                  (on-entry {} (script {:expr a-fn}) (script {:expr b-fn}))
                  (on-exit {} (script {:expr c-fn}) (script {:expr d-fn}))
                  (transition {:event :t :target :s2}))
                (state {:id :s2}))
        env   (testing/new-testing-env {:statechart      chart
                                        :mocking-options {:run-unmocked? true}} {})]
    (testing/start! env)
    (assertions
      "Entry handlers run in document order"
      @order => [:a :b])
    (vreset! order [])
    (testing/run-events! env :t)
    (assertions
      "Exit handlers run in document order"
      @order => [:c :d])))

(specification "Entry/Exit: Hierarchical order"
  (let [order (volatile! [])
        a-fn  (fn [_ _] (vswap! order conj :a))
        b-fn  (fn [_ _] (vswap! order conj :b))
        c-fn  (fn [_ _] (vswap! order conj :c))
        chart (chart/statechart {}
                (state {:id :parent}
                  (on-entry {} (script {:expr a-fn}))
                  (on-exit {} (script {:expr c-fn}))
                  (state {:id :child}
                    (on-entry {} (script {:expr b-fn}))
                    (transition {:event :t :target :other})))
                (state {:id :other}))
        env   (testing/new-testing-env {:statechart      chart
                                        :mocking-options {:run-unmocked? true}} {})]
    (testing/start! env)
    (assertions
      "Parent entry runs before child entry"
      @order => [:a :b])
    (vreset! order [])
    (testing/run-events! env :t)
    (assertions
      "Child exit runs before parent exit (reverse order)"
      (first @order) => :c)))

(specification "Entry/Exit: Data model updates"
  (let [chart (chart/statechart {}
                (data-model {:expr {:x 0}})
                (state {:id :a}
                  (on-entry {} (assign {:location :x, :expr inc-x}))
                  (transition {:event  :t
                               :target :b
                               :cond   (make-x-eq 1)}))
                (state {:id :b}))
        env   (testing/new-testing-env {:statechart      chart
                                        :mocking-options {:run-unmocked? true}} {})]
    (testing/start! env)
    (assertions
      "Entry handler updates data model"
      (testing/in? env :a) => true)
    (testing/run-events! env :t)
    (assertions
      "Transition uses updated data model"
      (testing/in? env :b) => true)))

;;; ============================================================================
;;; Internal Transitions
;;; ============================================================================

(specification "Internal: Type internal does not exit/enter parent"
  (let [chart (chart/statechart {}
                (data-model {:expr {:x 0}})
                (state {:id :a}
                  (on-entry {} (assign {:location :x, :expr inc-x}))
                  (on-exit {} (assign {:location :x, :expr inc-x}))
                  (state {:id :a1})
                  (state {:id :a2}
                    (transition {:target :b, :event :t2, :cond (make-x-eq 1)}))
                  (transition {:target :a2, :event :t1, :type :internal, :cond (make-x-eq 1)}))
                (state {:id :b}
                  (transition {:target :c, :event :t3, :cond (make-x-eq 2)}))
                (state {:id :c}))
        env   (testing/new-testing-env {:statechart      chart
                                        :mocking-options {:run-unmocked? true}} {})]
    (testing/start! env)
    (assertions
      "Enters a1, x is now 1"
      (testing/in? env :a1) => true)
    (testing/run-events! env :t1)
    (assertions
      "Internal transition to a2, x still 1 (no exit/entry)"
      (testing/in? env :a2) => true)
    (testing/run-events! env :t2)
    (assertions
      "External transition to b, x now 2 (exit incremented x)"
      (testing/in? env :b) => true)
    (testing/run-events! env :t3)
    (assertions
      "Condition with x=2 allows transition to c"
      (testing/in? env :c) => true)))

;;; ============================================================================
;;; Final States
;;; ============================================================================

(specification "Final: Reaching final state"
  (let [chart (chart/statechart {}
                (state {:id :a}
                  (transition {:event :t :target :done}))
                (final {:id :done}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t)
    (assertions
      "Interpreter exits after reaching top-level final state"
      (testing/running? env) => false)))

(specification "Final: Done event from compound state"
  (let [chart (chart/statechart {}
                (state {:id :compound :initial :a}
                  (state {:id :a}
                    (transition {:event :t :target :done}))
                  (final {:id :done})
                  (transition {:event :done.state.compound :target :finished}))
                (state {:id :finished}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions (testing/in? env :a) => true)
    (testing/run-events! env :t)
    (assertions
      "Compound state generates done event when final child is reached"
      (testing/in? env :finished) => true)))

;;; ============================================================================
;;; Event Data Access
;;; ============================================================================

(specification "Events: Event data visible in conditions"
  (let [events-seen (volatile! [])
        chart       (chart/statechart {}
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
                                     :target :a})))
        env         (testing/new-testing-env {:statechart      chart
                                              :mocking-options {:run-unmocked? true}} {})]
    (testing/start! env)
    (testing/run-events! env (evt/new-event :ping {:x 1}))
    (testing/run-events! env (evt/new-event :pong {:x 2}))
    (assertions
      "Eventless transitions get just the data model"
      (first @events-seen) => {:y 1}
      "Event transitions get the event in :_event"
      (count @events-seen) => 3
      (contains? (second @events-seen) :_event) => true
      (get-in (second @events-seen) [:_event :name]) => :ping
      (get-in (second @events-seen) [:_event :data :x]) => 1)))

;;; ============================================================================
;;; Initial State Configuration
;;; ============================================================================

(specification "Initial: Explicit initial attribute"
  (let [chart (chart/statechart {:initial :b}
                (state {:id :a})
                (state {:id :b}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      "Uses explicit initial state"
      (testing/in? env :b) => true)))

(specification "Initial: Default to first child"
  (let [chart (chart/statechart {}
                (state {:id :a})
                (state {:id :b}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      "Defaults to first child in document order"
      (testing/in? env :a) => true)))

(specification "Initial: Initial element with transition"
  (let [chart (chart/statechart {}
                (initial {} (transition {:target :b}))
                (state {:id :a})
                (state {:id :b}))
        env   (testing/new-testing-env {:statechart chart} {})]
    (testing/start! env)
    (assertions
      "Initial element transition targets correct state"
      (testing/in? env :b) => true)))
