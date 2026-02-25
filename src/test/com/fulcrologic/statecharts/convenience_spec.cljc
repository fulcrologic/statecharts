(ns com.fulcrologic.statecharts.convenience-spec
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :refer [statechart]]
    [com.fulcrologic.statecharts.convenience :as c]
    [com.fulcrologic.statecharts.convenience-macros :as cm]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [Send assign cancel on-entry on-exit script state transition]]
    [com.fulcrologic.statecharts.testing :as testing]
    [fulcro-spec.core :refer [=> =fn=> =throws=> assertions behavior component specification]]))

;; Test helper functions
(defn pred1 [env data] (= 1 (:value data)))
(defn pred2 [env data] (= 2 (:value data)))
(defn script-fn [env data] [:assign :result :executed])

(specification "on"
  (behavior "creates a transition with event and target"
    (let [result (c/on :my-event :target-state)]
      (assertions
        "returns a transition element"
        (:node-type result) => :transition
        "has the correct event"
        (:event result) => :my-event
        "has the correct target"
        (:target result) => [:target-state])))

  (behavior "accepts additional action children"
    (let [script-elem (script {:expr script-fn})
          result      (c/on :my-event :target-state script-elem)]
      (assertions
        "includes children"
        (count (:children result)) => 1
        "child is preserved"
        (first (:children result)) => script-elem))))

(specification "handle function"
  (behavior "creates a targetless transition with a script"
    (let [result (c/handle :my-event script-fn)]
      (assertions
        "returns a transition element"
        (:node-type result) => :transition
        "has the correct event"
        (:event result) => :my-event
        "has no target"
        (:target result) => nil
        "contains a script child"
        (count (:children result)) => 1
        "script has the expression"
        (-> result :children first :expr) => script-fn))))

(specification "handle macro"
  (behavior "creates a targetless transition with expression and diagram hint"
    (let [result (cm/handle :my-event script-fn)]
      (assertions
        "returns a transition element"
        (:node-type result) => :transition
        "has the correct event"
        (:event result) => :my-event
        "contains a script child"
        (count (:children result)) => 1
        "script has the expression"
        (-> result :children first :expr) => script-fn
        "includes diagram expression hint"
        (-> result :children first :diagram/expression) => "script-fn"
        "includes diagram label (same value as expression)"
        (-> result :children first :diagram/label) => "script-fn"))))

(specification "assign-on function"
  (behavior "creates a targetless transition with assign"
    (let [result (c/assign-on :update-event :location script-fn)]
      (assertions
        "returns a transition element"
        (:node-type result) => :transition
        "has the correct event"
        (:event result) => :update-event
        "has no target"
        (:target result) => nil
        "contains an assign child"
        (count (:children result)) => 1
        "assign has the location"
        (-> result :children first :location) => :location
        "assign has the expression"
        (-> result :children first :expr) => script-fn))))

(specification "assign-on macro"
  (behavior "creates a targetless transition with assign and diagram hint"
    (let [result (cm/assign-on :update-event :location script-fn)]
      (assertions
        "returns a transition element"
        (:node-type result) => :transition
        "has the correct event"
        (:event result) => :update-event
        "contains an assign child"
        (count (:children result)) => 1
        "assign has the location"
        (-> result :children first :location) => :location
        "assign has the expression"
        (-> result :children first :expr) => script-fn
        "includes diagram expression hint"
        (-> result :children first :diagram/expression) => "script-fn"
        "includes diagram label (same value as expression)"
        (-> result :children first :diagram/label) => "script-fn"))))

(specification "choice function"
  (component "structural output"
    (behavior "creates a state with choice prototype"
      (let [result (c/choice {:id :my-choice}
                     pred1 :state1
                     pred2 :state2)]
        (assertions
          "returns a state element"
          (:node-type result) => :state
          "has choice prototype"
          (:diagram/prototype result) => :choice
          "has the correct id"
          (:id result) => :my-choice)))

    (behavior "creates conditional transitions for predicates"
      (let [result (c/choice {:id :my-choice}
                     pred1 :state1
                     pred2 :state2)]
        (assertions
          "has two transitions"
          (count (:children result)) => 2
          "first transition has pred1"
          (-> result :children first :cond) => pred1
          "first transition targets state1"
          (-> result :children first :target) => [:state1]
          "second transition has pred2"
          (-> result :children second :cond) => pred2
          "second transition targets state2"
          (-> result :children second :target) => [:state2])))

    (behavior "creates final unconditional transition for :else clause"
      (let [result (c/choice {:id :my-choice}
                     pred1 :state1
                     :else :default-state)]
        (assertions
          "has two transitions"
          (count (:children result)) => 2
          "first transition is conditional"
          (-> result :children first :cond) => pred1
          "last transition has no condition"
          (-> result :children second :cond) => nil
          "last transition targets default"
          (-> result :children second :target) => [:default-state])))

    (behavior "works without :else clause"
      (let [result (c/choice {:id :my-choice}
                     pred1 :state1)]
        (assertions
          "has one transition"
          (count (:children result)) => 1
          "transition is conditional"
          (-> result :children first :cond) => pred1)))))



(specification "choice macro"
  (behavior "creates conditional transitions with diagram hints"
    (let [result (cm/choice {:id :my-choice}
                   pred1 :state1
                   pred2 :state2)]
      (assertions
        "returns a state element"
        (:node-type result) => :state
        "has choice prototype"
        (:diagram/prototype result) => :choice
        "has two transitions"
        (count (:children result)) => 2
        "first transition includes diagram condition hint"
        (-> result :children first :diagram/condition) => "pred1"
        "second transition includes diagram condition hint"
        (-> result :children second :diagram/condition) => "pred2"))))

(specification "send-after"
  (component "structural output"
    (behavior "creates on-entry and on-exit pair"
      (let [result (c/send-after {:id :my-send :event :timeout :delay 1000})]
        (assertions
          "returns a vector with two elements"
          (vector? result) => true
          (count result) => 2
          "first element is on-entry"
          (-> result first :node-type) => :on-entry
          "second element is on-exit"
          (-> result second :node-type) => :on-exit)))

    (behavior "on-entry contains Send with all properties"
      (let [result (c/send-after {:id :my-send :event :timeout :delay 1000})]
        (assertions
          "on-entry has one child"
          (-> result first :children count) => 1
          "child is a Send element"
          (-> result first :children first :node-type) => :send
          "Send has the id"
          (-> result first :children first :id) => :my-send
          "Send has the event"
          (-> result first :children first :event) => :timeout
          "Send has the delay"
          (-> result first :children first :delay) => 1000)))

    (behavior "on-exit contains cancel with sendid"
      (let [result (c/send-after {:id :my-send :event :timeout :delay 1000})]
        (assertions
          "on-exit has one child"
          (-> result second :children count) => 1
          "child is a cancel element"
          (-> result second :children first :node-type) => :cancel
          "cancel has the sendid matching the send id"
          (-> result second :children first :sendid) => :my-send)))

    (behavior "throws when :id is missing"
      (assertions
        "requires :id in props"
        (c/send-after {:event :timeout :delay 1000}) =throws=> #"MUST include an :id"))))



(specification "send-after with delayexpr"
  (behavior "supports delayexpr instead of delay"
    (let [delay-fn (fn [env data] 3000)
          result   (c/send-after {:id :my-send :event :timeout :delayexpr delay-fn})]
      (assertions
        "Send has delayexpr"
        (-> result first :children first :delayexpr) => delay-fn
        "Send does not have delay"
        (-> result first :children first :delay) => nil))))

;; =============================================================================
;; Behavioral Tests - Verify convenience functions work in running statecharts
;; =============================================================================

(specification "on - behavioral"
  (behavior "transitions to target state when event is triggered"
    (let [chart (statechart {:initial :idle}
                  (state {:id :idle}
                    (c/on :start :working))
                  (state {:id :working}))
          env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]
      (testing/start! env)
      (assertions
        "starts in idle"
        (testing/in? env :idle) => true)
      (testing/run-events! env :start)
      (assertions
        "transitions to working"
        (testing/in? env :working) => true)))

  (behavior "executes actions during transition"
    (let [action-fn (fn [env data] [(ops/assign :executed true)])
          chart     (statechart {:initial :idle}
                      (state {:id :idle}
                        (c/on :start :working
                          (script {:expr action-fn})))
                      (state {:id :working}))
          env       (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]
      (testing/start! env)
      (testing/run-events! env :start)
      (assertions
        "reaches target state"
        (testing/in? env :working) => true
        "executes action"
        (:executed (testing/data env)) => true))))

(specification "handle - behavioral"
  (behavior "executes expression without changing state"
    (let [handler-fn (fn [env data] [(ops/assign :count (inc (or (:count data) 0)))])
          chart      (statechart {:initial :active}
                       (state {:id :active}
                         (c/handle :increment handler-fn)))
          env        (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]
      (testing/start! env)
      (assertions
        "starts in active"
        (testing/in? env :active) => true
        "count is nil initially"
        (:count (testing/data env)) => nil)
      (testing/run-events! env :increment)
      (assertions
        "remains in active"
        (testing/in? env :active) => true
        "count is incremented"
        (:count (testing/data env)) => 1)
      (testing/run-events! env :increment)
      (assertions
        "still in active"
        (testing/in? env :active) => true
        "count is incremented again"
        (:count (testing/data env)) => 2))))

(specification "assign-on - behavioral"
  (behavior "assigns value without changing state"
    (let [value-expr (fn [env data] "new-value")
          chart      (statechart {:initial :active}
                       (state {:id :active}
                         (c/assign-on :update :my-field value-expr)))
          env        (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]
      (testing/start! env)
      (assertions
        "starts in active"
        (testing/in? env :active) => true)
      (testing/run-events! env :update)
      (assertions
        "remains in active"
        (testing/in? env :active) => true
        "field is assigned"
        (:my-field (testing/data env)) => "new-value"))))

(specification "choice function - behavioral"
  (behavior "routes to first matching predicate"
    (let [pred-one (fn [env data] (= 1 (:value data)))
          pred-two (fn [env data] (= 2 (:value data)))
          chart    (statechart {:initial :start}
                     (state {:id :start}
                       (c/on :decide :decision))
                     (c/choice {:id :decision}
                       pred-one :result-one
                       pred-two :result-two
                       :else :result-default)
                     (state {:id :result-one})
                     (state {:id :result-two})
                     (state {:id :result-default}))
          env      (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]
    (testing/start! env)
    (testing/goto-configuration! env [(ops/assign :value 1)] #{:start})
    (testing/run-events! env :decide)
    (assertions
      "routes to result-one when value is 1"
      (testing/in? env :result-one) => true)))

  (behavior "routes to else clause when no predicates match"
    (let [pred-one (fn [env data] (= 1 (:value data)))
          pred-two (fn [env data] (= 2 (:value data)))
          chart    (statechart {:initial :start}
                     (state {:id :start}
                       (c/on :decide :decision))
                     (c/choice {:id :decision}
                       pred-one :result-one
                       pred-two :result-two
                       :else :result-default)
                     (state {:id :result-one})
                     (state {:id :result-two})
                     (state {:id :result-default}))
          env      (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]
    (testing/start! env)
    (testing/goto-configuration! env [(ops/assign :value 99)] #{:start})
    (testing/run-events! env :decide)
    (assertions
      "routes to default when value doesn't match"
      (testing/in? env :result-default) => true)))

  (behavior "evaluates predicates in order"
    (let [pred-always-true (fn [env data] true)
          pred-never-called (fn [env data] (throw (ex-info "Should not be called" {})))
          chart             (statechart {:initial :start}
                              (state {:id :start}
                                (c/on :decide :decision))
                              (c/choice {:id :decision}
                                pred-always-true :first-match
                                pred-never-called :second-match)
                              (state {:id :first-match})
                              (state {:id :second-match}))
          env               (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]
      (testing/start! env)
      (testing/run-events! env :decide)
      (assertions
        "routes to first matching predicate"
        (testing/in? env :first-match) => true))))

(specification "choice macro - behavioral"
  (behavior "works the same as choice function but with diagram hints"
    (let [pred-one (fn [env data] (= 1 (:value data)))
          chart    (statechart {:initial :start}
                     (state {:id :start}
                       (c/on :decide :decision))
                     (cm/choice {:id :decision}
                       pred-one :result-one
                       :else :result-default)
                     (state {:id :result-one})
                     (state {:id :result-default}))
          env      (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]
      (testing/start! env)
      (testing/goto-configuration! env [(ops/assign :value 1)] #{:start})
      (testing/run-events! env :decide)
      (assertions
        "routes correctly with macro"
        (testing/in? env :result-one) => true))))

(specification "send-after - behavioral"
  (component "delayed event sending"
    (behavior "schedules event on entry"
      (let [chart (statechart {:initial :waiting}
                    (state {:id :waiting}
                      (c/send-after {:id :timeout-send :event :timeout :delay 5000})
                      (c/on :timeout :timed-out))
                    (state {:id :timed-out}))
            env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]
        (testing/start! env)
        (assertions
          "starts in waiting"
          (testing/in? env :waiting) => true
          "sends delayed event"
          (testing/sent? env {:event  :timeout
                              :delay  5000
                              :send-id :timeout-send}) => true))))

  (component "cancellation on exit"
    (behavior "cancels event when exiting state"
      (let [chart (statechart {:initial :waiting}
                    (state {:id :waiting}
                      (c/send-after {:id :timeout-send :event :timeout :delay 5000})
                      (c/on :cancel :cancelled)
                      (c/on :timeout :timed-out))
                    (state {:id :cancelled})
                    (state {:id :timed-out}))
            env   (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]
        (testing/start! env)
        (testing/run-events! env :cancel)
        (assertions
          "transitions to cancelled state"
          (testing/in? env :cancelled) => true
          "cancels the send"
          (testing/cancelled? env :test :timeout-send) => true))))

  (component "with delayexpr"
    (behavior "creates send with delayexpr when entering state"
      (let [delay-fn (fn [env data] 3000)
            chart    (statechart {:initial :idle}
                       (state {:id :idle}
                         (c/on :start :waiting))
                       (state {:id :waiting}
                         (c/send-after {:id :timeout-send :event :timeout :delayexpr delay-fn})
                         (c/on :timeout :timed-out))
                       (state {:id :timed-out}))
            env      (testing/new-testing-env {:statechart chart :mocking-options {:run-unmocked? true}} {})]
        (testing/start! env)
        (testing/run-events! env :start)
        (assertions
          "sends event when entering waiting state"
          (testing/sent? env {:event :timeout
                              :send-id :timeout-send}) => true)))))
