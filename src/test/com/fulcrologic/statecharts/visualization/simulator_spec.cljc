(ns com.fulcrologic.statecharts.visualization.simulator-spec
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final state transition]]
    [com.fulcrologic.statecharts.visualization.simulator :as sim]
    [fulcro-spec.core :refer [=> assertions specification component]]))

;; =============================================================================
;; Test Charts
;; =============================================================================

(defn allowed? [_ _] true)
(defn blocked? [_ _] false)
(defn ready?   [_ _] true)

(def simple-chart
  "A → B → C linear chart."
  (chart/statechart {:initial :a}
    (state {:id :a}
      (transition {:event :go :target :b}))
    (state {:id :b}
      (transition {:event :finish :target :c}))
    (final {:id :c})))

(def guarded-chart
  "A chart with two guarded transitions from :start."
  (chart/statechart {:initial :start}
    (state {:id :start}
      (transition {:event  :try-left
                   :cond   allowed?
                   :target :left
                   :diagram/condition "allowed?"})
      (transition {:event  :try-right
                   :cond   blocked?
                   :target :right
                   :diagram/condition "blocked?"}))
    (state {:id :left}
      (transition {:event :back :target :start}))
    (state {:id :right}
      (transition {:event :back :target :start}))
    (final {:id :done})))

(def multi-guard-chart
  "Chart with multiple guards for integration testing."
  (chart/statechart {:initial :idle}
    (state {:id :idle}
      (transition {:event  :attempt
                   :cond   ready?
                   :target :processing
                   :diagram/condition "ready?"})
      (transition {:event :skip :target :done}))
    (state {:id :processing}
      (transition {:event  :approve
                   :cond   allowed?
                   :target :approved
                   :diagram/condition "allowed?"})
      (transition {:event :reject :target :idle}))
    (state {:id :approved}
      (transition {:event :reset :target :idle}))
    (final {:id :done})))

;; =============================================================================
;; Tier 1: Unit Tests
;; =============================================================================

(specification "SimulatorExecutionModel: toggled guard returns correct boolean"
  (let [guard-atom (atom {allowed? true, blocked? false})
        exec       (sim/new-simulator-execution-model guard-atom)
        guard-env  {::sc/raw-result? true}]
    (assertions
      "Returns true for a guard set to true"
      (com.fulcrologic.statecharts.protocols/run-expression! exec guard-env allowed?) => true
      "Returns false for a guard set to false"
      (com.fulcrologic.statecharts.protocols/run-expression! exec guard-env blocked?) => false)))

(specification "SimulatorExecutionModel: unknown guards return true (permissive default)"
  (let [guard-atom (atom {})
        exec       (sim/new-simulator-execution-model guard-atom)
        guard-env  {::sc/raw-result? true}
        unknown-fn (fn [_ _] nil)]
    (assertions
      "Unknown guard defaults to true"
      (com.fulcrologic.statecharts.protocols/run-expression! exec guard-env unknown-fn) => true)))

(specification "SimulatorExecutionModel: scripts return nil (noop)"
  (let [guard-atom (atom {})
        exec       (sim/new-simulator-execution-model guard-atom)
        script-env {}
        some-fn    (fn [_ _] :should-not-see)]
    (assertions
      "Script execution returns nil"
      (com.fulcrologic.statecharts.protocols/run-expression! exec script-env some-fn) => nil)))

(specification "start-simulation!: produces valid initial configuration"
  (let [sim-state (sim/start-simulation! simple-chart)
        config    (sim/current-configuration sim-state)]
    (assertions
      "Initial configuration contains the initial state"
      (contains? config :a) => true
      "Simulator state contains expected keys"
      (contains? sim-state :env) => true
      (contains? sim-state :session-id) => true
      (contains? sim-state :guard-values) => true)))

(specification "send-event!: transitions to correct state when guard is true"
  (let [sim-state (sim/start-simulation! guarded-chart)]
    ;; allowed? defaults to true since extract-guards sets :default true
    (assertions
      "Transitions when guard is true"
      (contains? (sim/send-event! sim-state :try-left) :left) => true)))

(specification "send-event!: does NOT transition when guard is toggled to false"
  (let [sim-state (sim/start-simulation! guarded-chart)]
    ;; Toggle allowed? to false
    (sim/toggle-guard! sim-state allowed? false)
    (assertions
      "Stays in :start when guard is false"
      (contains? (sim/send-event! sim-state :try-left) :start) => true)))

(specification "toggle-guard!: flips value, subsequent send-event! respects new value"
  (let [sim-state (sim/start-simulation! guarded-chart)]
    ;; Initially allowed? is true (default)
    (assertions
      "Can transition with default guard value"
      (contains? (sim/send-event! sim-state :try-left) :left) => true)
    ;; Go back to start
    (sim/send-event! sim-state :back)
    ;; Toggle allowed? off
    (sim/toggle-guard! sim-state allowed? false)
    (assertions
      "Cannot transition after toggling guard off"
      (contains? (sim/send-event! sim-state :try-left) :start) => true)
    ;; Toggle back on
    (sim/toggle-guard! sim-state allowed? true)
    (assertions
      "Can transition again after toggling guard back on"
      (contains? (sim/send-event! sim-state :try-left) :left) => true)))

(specification "extract-guards: finds all unique guard fns with labels"
  (let [guards (sim/extract-guards guarded-chart)]
    (assertions
      "Finds both guard functions"
      (count guards) => 2
      "Contains allowed? guard"
      (contains? guards allowed?) => true
      "Contains blocked? guard"
      (contains? guards blocked?) => true
      "Labels come from :diagram/condition"
      (:label (get guards allowed?)) => "allowed?"
      (:label (get guards blocked?)) => "blocked?")))

(specification "available-events: returns correct event set for a given configuration"
  (let [sim-state (sim/start-simulation! guarded-chart)
        config    (sim/current-configuration sim-state)
        events    (sim/available-events guarded-chart config)]
    (assertions
      "Returns events available from :start state"
      (contains? events :try-left) => true
      (contains? events :try-right) => true
      "Does not include events from other states"
      (contains? events :back) => false)))

(specification "reset-simulation!: returns to initial configuration"
  (let [sim-state (sim/start-simulation! guarded-chart)]
    ;; Move to :left
    (sim/send-event! sim-state :try-left)
    (assertions
      "Currently in :left"
      (contains? (sim/current-configuration sim-state) :left) => true)
    ;; Reset
    (let [config (sim/reset-simulation! sim-state)]
      (assertions
        "Back to initial state :start after reset"
        (contains? config :start) => true
        "No longer in :left"
        (contains? config :left) => false))))

;; =============================================================================
;; Tier 2: Integration test
;; =============================================================================

(specification "Integration: full walk-through with guard toggling"
  (let [sim-state (sim/start-simulation! multi-guard-chart)]
    (component "Start in :idle"
      (assertions
        (contains? (sim/current-configuration sim-state) :idle) => true))

    (component "ready? is true by default, so :attempt transitions to :processing"
      (sim/send-event! sim-state :attempt)
      (assertions
        (contains? (sim/current-configuration sim-state) :processing) => true))

    (component "allowed? is true by default, so :approve transitions to :approved"
      (sim/send-event! sim-state :approve)
      (assertions
        (contains? (sim/current-configuration sim-state) :approved) => true))

    (component "Reset and toggle ready? off"
      (sim/reset-simulation! sim-state)
      (sim/toggle-guard! sim-state ready? false)
      ;; :attempt should NOT move us out of :idle now
      (sim/send-event! sim-state :attempt)
      (assertions
        "Stays in :idle because ready? is false"
        (contains? (sim/current-configuration sim-state) :idle) => true))

    (component "Toggle ready? back on, but toggle allowed? off"
      (sim/toggle-guard! sim-state ready? true)
      (sim/toggle-guard! sim-state allowed? false)
      (sim/send-event! sim-state :attempt)
      (assertions
        "Transitions to :processing because ready? is now true"
        (contains? (sim/current-configuration sim-state) :processing) => true)
      ;; :approve should fail because allowed? is false
      (sim/send-event! sim-state :approve)
      (assertions
        "Stays in :processing because allowed? is false"
        (contains? (sim/current-configuration sim-state) :processing) => true)
      ;; Use :reject instead
      (sim/send-event! sim-state :reject)
      (assertions
        "Reject goes back to :idle"
        (contains? (sim/current-configuration sim-state) :idle) => true))

    (component "available-events reflects current state"
      (let [events (sim/available-events multi-guard-chart
                     (sim/current-configuration sim-state))]
        (assertions
          "Shows :attempt and :skip from :idle"
          (contains? events :attempt) => true
          (contains? events :skip) => true
          "Does not show :approve from :processing"
          (contains? events :approve) => false)))))
