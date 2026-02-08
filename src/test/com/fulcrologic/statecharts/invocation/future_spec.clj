(ns com.fulcrologic.statecharts.invocation.future-spec
  "Tests for future invocation processor (CLJ only).

   CRITICAL: These tests verify backward compatibility for async future invocations.
   Tests document CURRENT behavior - do not modify production code without approval."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.invocation.future :as sut]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.simple :as simple]
    [fulcro-spec.core :refer [=> assertions behavior component specification]]))

;; =============================================================================
;; Test Functions
;; =============================================================================

(defn success-fn
  "Function that completes successfully"
  [params]
  {:result :success :input params})

(defn slow-fn
  "Function that takes time"
  [_params]
  (Thread/sleep 200)
  {:result :completed})

(defn non-map-fn
  "Returns non-map value"
  [_params]
  42)

;; =============================================================================
;; Slice 1: Basic Protocol Implementation
;; =============================================================================

(specification "FutureInvocationProcessor - Protocol Methods"
  (component "supports-invocation-type?"
    (let [processor (sut/new-future-processor)]
      (behavior "returns true for :future type"
        (assertions
          (sp/supports-invocation-type? processor :future) => true))

      (behavior "returns false for other types"
        (assertions
          (sp/supports-invocation-type? processor :statechart) => false
          (sp/supports-invocation-type? processor ::sc/chart) => false))))

  (component "start-invocation! launches future"
    (let [processor (sut/new-future-processor)
          env (simple/simple-env)
          queue (::sc/event-queue env)
          parent-env (assoc env ::sc/vwmem (volatile! {::sc/session-id :parent-123}))
          active-futures (:active-futures processor)]

      ;; Start future
      (sp/start-invocation! processor parent-env
        {:invokeid :my-future
         :src      success-fn
         :params   {:foo :bar}})

      (behavior "future is tracked in active-futures"
        (let [future-key :parent-123.my-future
              f (get @active-futures future-key)]
          (assertions
            "future exists"
            (some? f) => true
            "future is a Future"
            (future? f) => true)))

      ;; Wait for completion
      (Thread/sleep 100)

      (behavior "done event sent to parent on completion"
        (let [sends @(:session-queues queue)
              parent-events (get sends :parent-123)
              done-event (first (filter #(= :done.invoke.my-future (:event %)) parent-events))]
          (assertions
            "done event exists"
            (some? done-event) => true
            "event includes result data"
            (:data done-event) => {:result :success :input {:foo :bar}}
            "event has correct source"
            (:source-session-id done-event) => :parent-123.my-future)))

      (behavior "future removed from tracking after completion"
        ;; Give finally block time to execute
        (Thread/sleep 100)
        (let [future-key :parent-123.my-future]
          (assertions
            (contains? @active-futures future-key) => false)))))

  (component "start-invocation! with non-function"
    (let [processor (sut/new-future-processor)
          env (simple/simple-env)
          queue (::sc/event-queue env)
          parent-env (assoc env ::sc/vwmem (volatile! {::sc/session-id :parent-456}))
          active-futures (:active-futures processor)]

      ;; Try to invoke non-function
      (sp/start-invocation! processor parent-env
        {:invokeid :bad-future
         :src      "not-a-function"
         :params   {}})

      (behavior "error.platform event sent immediately"
        (let [sends @(:session-queues queue)
              parent-events (get sends :parent-456)
              error-event (first (filter #(= :error.platform (:event %)) parent-events))]
          (assertions
            "error event exists"
            (some? error-event) => true
            "error includes message"
            (contains? (:data error-event) :message) => true)))

      (behavior "no future created"
        (assertions
          (empty? @active-futures) => true))

      (behavior "returns true even on error (known issue)"
        (let [result (sp/start-invocation! processor parent-env
                       {:invokeid :another-bad
                        :src      123
                        :params   {}})]
          (assertions
            result => true)))))

  (component "Non-map return value handling"
    (let [processor (sut/new-future-processor)
          env (simple/simple-env)
          queue (::sc/event-queue env)
          parent-env (assoc env ::sc/vwmem (volatile! {::sc/session-id :parent-789}))]

      ;; Start future that returns non-map
      (sp/start-invocation! processor parent-env
        {:invokeid :non-map-future
         :src      non-map-fn
         :params   {}})

      ;; Wait for completion
      (Thread/sleep 100)

      (behavior "done event sent with empty map"
        (let [sends @(:session-queues queue)
              parent-events (get sends :parent-789)
              done-event (first (filter #(= :done.invoke.non-map-future (:event %)) parent-events))]
          (assertions
            "done event exists"
            (some? done-event) => true
            "data is empty map"
            (:data done-event) => {})))))

  (component "stop-invocation! cancels future"
    (let [processor (sut/new-future-processor)
          env (simple/simple-env)
          parent-env (assoc env ::sc/vwmem (volatile! {::sc/session-id :parent-999}))
          active-futures (:active-futures processor)]

      ;; Start slow future
      (sp/start-invocation! processor parent-env
        {:invokeid :slow-future
         :src      slow-fn
         :params   {}})

      (behavior "future exists before stop"
        (let [f (get @active-futures :parent-999.slow-future)]
          (assertions
            "future created"
            (some? f) => true
            "not done yet"
            (future-done? f) => false)))

      ;; Stop it
      (sp/stop-invocation! processor parent-env {:invokeid :slow-future})

      (behavior "future is cancelled"
        (let [f (get @active-futures :parent-999.slow-future)]
          (assertions
            "future was cancelled"
            (when f (future-cancelled? f)) => true)))

      (behavior "stop-invocation! returns true"
        (assertions
          (sp/stop-invocation! processor parent-env {:invokeid :nonexistent}) => true))))

  (component "forward-event! does nothing"
    (let [processor (sut/new-future-processor)
          env (simple/simple-env)]

      (behavior "returns nil (no-op)"
        (let [result (sp/forward-event! processor env
                       {:invokeid :test
                        :event    {:name :test/event}})]
          (assertions
            result => nil))))))

;; =============================================================================
;; Slice 2: Session ID Format
;; =============================================================================

(specification "FutureInvocationProcessor - Session IDs"
  (component "Child session ID format"
    (let [processor (sut/new-future-processor)
          env (simple/simple-env)
          queue (::sc/event-queue env)
          parent-env (assoc env ::sc/vwmem (volatile! {::sc/session-id :parent-abc}))]

      ;; Start future
      (sp/start-invocation! processor parent-env
        {:invokeid :my-future
         :src      success-fn
         :params   {}})

      ;; Wait for completion
      (Thread/sleep 100)

      (behavior "child session-id is parent.invokeid"
        (let [sends @(:session-queues queue)
              parent-events (get sends :parent-abc)
              done-event (first (filter #(= :done.invoke.my-future (:event %)) parent-events))]
          (assertions
            "sendid follows format"
            (:sendid done-event) => :parent-abc.my-future
            "source-session-id follows format"
            (:source-session-id done-event) => :parent-abc.my-future))))))

;; =============================================================================
;; Slice 3: Edge Cases
;; =============================================================================

(specification "FutureInvocationProcessor - Edge Cases"
  (component "Constructor"
    (behavior "new-future-processor creates processor"
      (let [proc (sut/new-future-processor)]
        (assertions
          (some? proc) => true
          (satisfies? sp/InvocationProcessor proc) => true))))

  (component "Concurrent futures"
    (let [processor (sut/new-future-processor)
          env (simple/simple-env)
          parent-env (assoc env ::sc/vwmem (volatile! {::sc/session-id :parent-concurrent}))
          active-futures (:active-futures processor)]

      ;; Start multiple futures
      (sp/start-invocation! processor parent-env
        {:invokeid :future1 :src success-fn :params {}})
      (sp/start-invocation! processor parent-env
        {:invokeid :future2 :src success-fn :params {}})
      (sp/start-invocation! processor parent-env
        {:invokeid :future3 :src success-fn :params {}})

      (behavior "all futures tracked independently"
        (assertions
          "future1 exists"
          (contains? @active-futures :parent-concurrent.future1) => true
          "future2 exists"
          (contains? @active-futures :parent-concurrent.future2) => true
          "future3 exists"
          (contains? @active-futures :parent-concurrent.future3) => true))

      ;; Wait for all to complete
      (Thread/sleep 150)

      (behavior "all futures removed after completion"
        (assertions
          (contains? @active-futures :parent-concurrent.future1) => false
          (contains? @active-futures :parent-concurrent.future2) => false
          (contains? @active-futures :parent-concurrent.future3) => false)))))
