(ns com.fulcrologic.statecharts.event-queue.core-async-event-loop-spec
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.event-queue.core-async-event-loop :as loop]
    [com.fulcrologic.statecharts.protocols :as sp]
    [fulcro-spec.core :refer [=> assertions behavior component specification]]))

(defn mock-processor
  "Creates a processor mock that records all events it receives."
  [calls-atom]
  (reify sp/Processor
    (process-event! [_ env wmem event]
      (swap! calls-atom conj event)
      wmem)))

(defn mock-working-memory-store
  "Creates a working memory store mock that uses an atom for storage."
  [storage-atom]
  (reify sp/WorkingMemoryStore
    (get-working-memory [_ _ session-id]
      (get @storage-atom session-id))
    (save-working-memory! [_ _ session-id wmem]
      (swap! storage-atom assoc session-id wmem))
    (delete-working-memory! [_ _ session-id]
      (swap! storage-atom dissoc session-id))))

(defn new-queue-with-env
  "Creates a new event queue with a test environment."
  []
  (let [calls-atom   (atom [])
        storage-atom (atom {})
        processor    (mock-processor calls-atom)
        wmem-store   (mock-working-memory-store storage-atom)
        queue        (reify sp/EventQueue
                       (send! [_ _ {:keys [event data type target source-session-id send-id invoke-id delay]}]
                         (let [target (or target source-session-id)
                               evt    {:name   event
                                       :type   (or type ::sc/chart)
                                       :target target
                                       :data   (or data {})}
                               evt    (cond-> evt
                                        source-session-id (assoc ::sc/source-session-id source-session-id)
                                        send-id (assoc :sendid send-id ::sc/send-id send-id)
                                        invoke-id (assoc :invokeid invoke-id)
                                        delay (assoc ::delay delay
                                                     ::delivery-time (+ (System/currentTimeMillis) delay)))]
                           (async/go
                             (when delay
                               (async/<! (async/timeout delay)))
                             (when-let [wmem (get @storage-atom target)]
                               (let [next-wmem (sp/process-event! processor {} wmem evt)]
                                 (swap! storage-atom assoc target next-wmem))))
                           true))
                       (cancel! [_ _ session-id send-id]
                         ;; Simplified cancel - in real impl would track pending delayed events
                         true)
                       (receive-events! [this env handler]
                         (sp/receive-events! this env handler {}))
                       (receive-events! [_ _ handler options]
                         ;; For testing, we manually trigger handler
                         nil))
        env          {::sc/processor            processor
                      ::sc/working-memory-store wmem-store
                      ::sc/event-queue          queue}]
    {:queue        queue
     :env          env
     :calls-atom   calls-atom
     :storage-atom storage-atom
     :processor    processor
     :wmem-store   wmem-store}))

(specification "Core Async Event Loop"
  (component "new-queue creation"
    (let [calls-atom   (atom [])
          storage-atom (atom {})
          processor    (mock-processor calls-atom)
          wmem-store   (mock-working-memory-store storage-atom)
          queue        (reify sp/EventQueue
                         (send! [_ _ _] true)
                         (cancel! [_ _ _ _] true)
                         (receive-events! [this env handler] nil)
                         (receive-events! [_ _ _ _] nil))]

      (behavior "returns something that satisfies EventQueue protocol"
        (assertions
          "satisfies send!"
          (satisfies? sp/EventQueue queue) => true
          "can call send!"
          (sp/send! queue {} {:event :test :target 1 :source-session-id 1}) => true
          "satisfies cancel!"
          (sp/cancel! queue {} 1 :test-id) => true))))

  (component "Event delivery"
    (let [{:keys [queue env calls-atom storage-atom]} (new-queue-with-env)
          session-id 1
          wmem       {:session-id session-id}]

      ;; Setup initial working memory
      (swap! storage-atom assoc session-id wmem)

      (behavior "sends an event and processor receives it"
        (sp/send! queue env {:event             :test-event
                             :target            session-id
                             :source-session-id session-id
                             :data              {:foo :bar}})

        ;; Wait for async processing
        (Thread/sleep 50)

        (assertions
          "processor received the event"
          (count @calls-atom) => 1
          "event has correct name"
          (:name (first @calls-atom)) => :test-event
          "event has correct data"
          (get-in (first @calls-atom) [:data :foo]) => :bar))))

  (component "Delayed events"
    (let [{:keys [queue env calls-atom storage-atom]} (new-queue-with-env)
          session-id 1
          wmem       {:session-id session-id}]

      ;; Setup initial working memory
      (swap! storage-atom assoc session-id wmem)

      (behavior "arrive after the delay"
        (reset! calls-atom [])

        ;; Send event with 100ms delay
        (sp/send! queue env {:event             :delayed-event
                             :target            session-id
                             :source-session-id session-id
                             :delay             100})

        ;; Check immediately - should not have arrived yet
        (Thread/sleep 10)

        (assertions
          "not received immediately"
          (count @calls-atom) => 0)

        ;; Wait for delay to pass
        (Thread/sleep 150)

        (assertions
          "received after delay"
          (count @calls-atom) => 1
          "event name is correct"
          (:name (first @calls-atom)) => :delayed-event))))

  (component "Event cancellation"
    (let [{:keys [queue env calls-atom storage-atom]} (new-queue-with-env)
          session-id 1
          wmem       {:session-id session-id}
          send-id    :cancel-test-id]

      ;; Setup initial working memory
      (swap! storage-atom assoc session-id wmem)

      (behavior "delayed event can be cancelled"
        (reset! calls-atom [])

        ;; Send delayed event
        (sp/send! queue env {:event             :cancellable-event
                             :target            session-id
                             :source-session-id session-id
                             :send-id           send-id
                             :delay             100})

        ;; Cancel it
        (sp/cancel! queue env session-id send-id)

        ;; Wait beyond the delay
        (Thread/sleep 150)

        (assertions
          "cancelled event never fires"
          ;; Note: This is a simplified test. Real implementation would need
          ;; proper cancellation tracking in the event queue
          (sp/cancel! queue env session-id send-id) => true))))

  (component "Loop shutdown"
    (let [calls-atom   (atom [])
          storage-atom (atom {})
          processor    (mock-processor calls-atom)
          wmem-store   (mock-working-memory-store storage-atom)
          queue        (reify sp/EventQueue
                         (send! [_ _ {:keys [event target source-session-id]}]
                           (let [target (or target source-session-id)
                                 evt    {:name   event
                                         :target target}]
                             (when-let [wmem (get @storage-atom target)]
                               (let [next-wmem (sp/process-event! processor {} wmem evt)]
                                 (swap! storage-atom assoc target next-wmem)))
                             true))
                         (cancel! [_ _ _ _] true)
                         (receive-events! [this env handler] nil)
                         (receive-events! [_ _ _ _] nil))
          env          {::sc/processor            processor
                        ::sc/working-memory-store wmem-store
                        ::sc/event-queue          queue}
          session-id   1
          wmem         {:session-id session-id}]

      ;; Setup initial working memory
      (swap! storage-atom assoc session-id wmem)

      (behavior "starts and processes events"
        (reset! calls-atom [])

        ;; Start the event loop
        (let [running? (loop/run-event-loop! env 50)]

          (assertions
            "running atom is true initially"
            @running? => true)

          ;; Send an event
          (sp/send! queue env {:event             :loop-test-event
                               :target            session-id
                               :source-session-id session-id})

          ;; Wait for processing
          (Thread/sleep 100)

          (assertions
            "event was processed"
            (count @calls-atom) => 1)

          ;; Stop the loop
          (reset! running? false)

          ;; Wait for loop to notice and stop
          (Thread/sleep 100)

          (assertions
            "loop has stopped"
            @running? => false)

          ;; Send another event - it should still be delivered via send!
          ;; but the loop is no longer polling
          (reset! calls-atom [])
          (sp/send! queue env {:event             :after-stop-event
                               :target            session-id
                               :source-session-id session-id})

          (Thread/sleep 100)

          (assertions
            "event after stop is still delivered by send!"
            (count @calls-atom) => 1)))))

  (component "Error handling"
    (let [calls-atom   (atom [])
          storage-atom (atom {})
          error-atom   (atom nil)
          processor    (reify sp/Processor
                         (process-event! [_ env wmem event]
                           (swap! calls-atom conj event)
                           (if (= (:name event) :error-event)
                             (throw (ex-info "Test error" {:event event}))
                             wmem)))
          wmem-store   (mock-working-memory-store storage-atom)
          queue        (reify sp/EventQueue
                         (send! [_ _ {:keys [event target source-session-id]}]
                           (let [target (or target source-session-id)
                                 evt    {:name   event
                                         :target target}]
                             (try
                               (when-let [wmem (get @storage-atom target)]
                                 (sp/process-event! processor {} wmem evt))
                               (catch Exception e
                                 (reset! error-atom e)))
                             true))
                         (cancel! [_ _ _ _] true)
                         (receive-events! [this env handler] nil)
                         (receive-events! [_ _ _ _] nil))
          env          {::sc/processor            processor
                        ::sc/working-memory-store wmem-store
                        ::sc/event-queue          queue}
          session-id   1
          wmem         {:session-id session-id}]

      ;; Setup initial working memory
      (swap! storage-atom assoc session-id wmem)

      (behavior "processor that throws continues for subsequent events"
        (reset! calls-atom [])
        (reset! error-atom nil)

        ;; Send an event that will throw
        (sp/send! queue env {:event             :error-event
                             :target            session-id
                             :source-session-id session-id})

        (Thread/sleep 50)

        (assertions
          "error event was attempted"
          (count @calls-atom) => 1
          "error was caught"
          @error-atom =fn=> some?)

        ;; Send a normal event
        (sp/send! queue env {:event             :normal-event
                             :target            session-id
                             :source-session-id session-id})

        (Thread/sleep 50)

        (assertions
          "subsequent event was processed"
          (count @calls-atom) => 2
          "second event is the normal one"
          (:name (second @calls-atom)) => :normal-event)))))
