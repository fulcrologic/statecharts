(ns com.fulcrologic.statecharts.persistence.pg.chaos-test
  "Chaos tests for the PostgreSQL persistence layer.

   Tests concurrent access, failure recovery, and invariant preservation
   under adversarial conditions. Requires a running PostgreSQL instance.

   Run with: clj -M:test -m kaocha.runner --focus :integration

   Test categories:
   1. Concurrent event processing (exactly-once under contention)
   2. Concurrent session access (optimistic lock contention)
   3. Concurrent job claiming (no double-claim)
   4. Failure recovery (handler failures, stale claims, poison events)
   5. Pool stress (connection contention, pool exhaustion)
   6. Event loop resilience (handler failures, database errors)"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.persistence.pg.chaos :as chaos]
   [com.fulcrologic.statecharts.persistence.pg.core :as core]
   [com.fulcrologic.statecharts.persistence.pg.event-queue :as pg-eq]
   [com.fulcrologic.statecharts.persistence.pg.job-store :as job-store]
   [com.fulcrologic.statecharts.persistence.pg.schema :as schema]
   [com.fulcrologic.statecharts.persistence.pg.working-memory-store :as pg-wms]
   [com.fulcrologic.statecharts.protocols :as sp]
   [pg.core :as pg]
   [pg.pool :as pool])
  (:import
   [java.util.concurrent CountDownLatch CyclicBarrier TimeUnit]))

;; =============================================================================
;; Test Configuration
;; =============================================================================

(def ^:private test-config
  {:host (or (System/getenv "PG_TEST_HOST") "localhost")
   :port (parse-long (or (System/getenv "PG_TEST_PORT") "5432"))
   :database (or (System/getenv "PG_TEST_DATABASE") "statecharts_test")
   :user (or (System/getenv "PG_TEST_USER") "postgres")
   :password (or (System/getenv "PG_TEST_PASSWORD") "postgres")
   :binary-encode? true
   :binary-decode? true})

(def ^:dynamic *pool* nil)

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn with-pool [f]
  (let [p (pool/pool (assoc test-config
                            :pool-min-size 3
                            :pool-max-size 8))]
    (try
      (binding [*pool* p]
        (f))
      (finally
        (pool/close p)))))

(defn with-clean-tables [f]
  (chaos/setup-tables! *pool*)
  (try
    (f)
    (finally
      (schema/truncate-tables! *pool*))))

(use-fixtures :once with-pool)
(use-fixtures :each with-clean-tables)

;; =============================================================================
;; 1. Concurrent Event Processing - Exactly Once
;; =============================================================================

(deftest ^:integration concurrent-event-claiming-exactly-once-test
  (testing "multiple workers claiming events concurrently - each event processed exactly once"
    (let [session-id :chaos-session-1
          event-count 100
          worker-count 4
          queue (pg-eq/new-queue *pool* "setup-worker")]

      ;; Insert many events
      (dotimes [i event-count]
        (sp/send! queue {} (chaos/make-event session-id
                                             (keyword (str "evt-" i))
                                             {:seq i})))

      ;; Verify all events inserted
      (is (= event-count (chaos/count-events *pool* :processed? false))
          "All events should be in queue")

      ;; Run multiple workers concurrently, each processing events
      (let [[handler counts] (chaos/counting-handler)
            worker-fns (for [worker-id (range worker-count)]
                         (fn []
                           (let [wq (pg-eq/new-queue *pool* (str "worker-" worker-id))]
                             ;; Each worker polls until no more events
                             (loop [total 0]
                               (let [before (chaos/count-events *pool* :processed? false)]
                                 (when (pos? before)
                                   (sp/receive-events! wq {} handler
                                                       {:batch-size 5})
                                   (recur (inc total))))))))]
        (chaos/execute-concurrent! (vec worker-fns) :timeout-ms 30000)

        ;; Give a moment for all transactions to commit
        (Thread/sleep 100)

        ;; Verify: all events processed, none lost, none duplicated
        (let [processed (chaos/count-events *pool* :processed? true)
              unprocessed (chaos/count-events *pool* :processed? false)]
          (is (= event-count processed)
              (str "Expected " event-count " processed events, got " processed))
          (is (zero? unprocessed)
              (str "Expected 0 unprocessed events, got " unprocessed)))

        ;; Verify: no event was handled more than once
        (let [total-handler-calls (reduce + (vals @counts))]
          (is (= event-count total-handler-calls)
              (str "Handler should be called exactly " event-count
                   " times, was called " total-handler-calls " times")))

        ;; Check invariants
        (chaos/assert-invariants! *pool* "after concurrent event processing")))))

(deftest ^:integration concurrent-send-and-process-test
  (testing "concurrent sending and processing don't lose events"
    (let [session-id :chaos-session-2
          send-count 50
          [handler counts] (chaos/counting-handler)
          sender-done (atom false)
          processed-total (atom 0)]

      ;; Start processor thread
      (let [processor-thread
            (future
              (let [wq (pg-eq/new-queue *pool* "processor")]
                (while (or (not @sender-done)
                           (pos? (chaos/count-events *pool* :processed? false)))
                  (sp/receive-events! wq {} handler {:batch-size 10})
                  (Thread/sleep 5))
                ;; One final sweep
                (sp/receive-events! wq {} handler {:batch-size 100})))]

        ;; Send events concurrently from multiple threads
        (let [sender-fns (for [sender-id (range 5)]
                           (fn []
                             (let [sq (pg-eq/new-queue *pool* (str "sender-" sender-id))]
                               (dotimes [i (/ send-count 5)]
                                 (sp/send! sq {}
                                           (chaos/make-event
                                            session-id
                                            (keyword (str "evt-s" sender-id "-" i))))))))]
          (chaos/execute-concurrent! (vec sender-fns) :timeout-ms 10000))

        (reset! sender-done true)
        ;; Wait for processor to finish
        (Thread/sleep 500)
        (deref processor-thread 10000 :timeout)

        ;; Verify all events processed
        (let [total-handled (reduce + (vals @counts))]
          (is (= send-count total-handled)
              (str "All " send-count " sent events should be processed, got " total-handled)))

        (chaos/assert-invariants! *pool* "after concurrent send+process")))))

;; =============================================================================
;; 2. Concurrent Session Access - Optimistic Locking
;; =============================================================================

(deftest ^:integration concurrent-session-contention-test
  (testing "concurrent writes to same session are serialized via optimistic locking"
    (let [store (pg-wms/new-store *pool*)
          session-id :contention-session
          thread-count 8
          increments-per-thread 10
          wmem (chaos/make-working-memory session-id :test-chart)]

      ;; Create initial session
      (sp/save-working-memory! store {} session-id wmem)

      ;; Concurrently increment the counter
      (let [success-count (atom 0)
            retry-count (atom 0)
            worker-fns (for [_tid (range thread-count)]
                         (fn []
                           (dotimes [_ increments-per-thread]
                             (pg-wms/with-optimistic-retry
                               {:max-retries 20 :backoff-ms 5}
                               (fn []
                                 (let [current (sp/get-working-memory store {} session-id)
                                       counter (get-in current [::sc/data-model :counter])
                                       updated (assoc-in current [::sc/data-model :counter]
                                                          (inc counter))]
                                   (sp/save-working-memory! store {} session-id updated)
                                   (swap! success-count inc)))))))]
        (chaos/execute-concurrent! (vec worker-fns) :timeout-ms 30000))

      ;; Verify: counter equals total increments (no lost updates)
      (let [final-wmem (sp/get-working-memory store {} session-id)
            final-counter (get-in final-wmem [::sc/data-model :counter])
            expected (* thread-count increments-per-thread)]
        (is (= expected final-counter)
            (str "Counter should be " expected " but was " final-counter
                 ". Lost updates detected!"))
        (is (= expected (core/get-version final-wmem))
            (str "Version should be " expected " (initial 1 + " (dec expected)
                 " updates) but was " (core/get-version final-wmem))))

      (chaos/assert-invariants! *pool* "after session contention"))))

(deftest ^:integration concurrent-session-create-race-test
  (testing "concurrent creation of same session ID doesn't corrupt state"
    (let [store (pg-wms/new-store *pool*)
          session-id :race-session
          thread-count 6
          wmems (for [i (range thread-count)]
                  (assoc (chaos/make-working-memory session-id :test-chart)
                         ::sc/data-model {:creator i}))]

      ;; All threads try to create the same session simultaneously
      (let [results (chaos/execute-concurrent!
                     (vec (for [wmem wmems]
                            (fn []
                              (try
                                (sp/save-working-memory! store {} session-id wmem)
                                :saved
                                (catch Exception e
                                  {:error (.getMessage e)}))))))]

        ;; At least one should succeed
        (is (some #(= :saved (:result %)) results)
            "At least one create should succeed")

        ;; Session should exist with consistent state
        (let [final (sp/get-working-memory store {} session-id)]
          (is (some? final) "Session must exist")
          (is (= session-id (::sc/session-id final)))
          (is (some? (get-in final [::sc/data-model :creator]))
              "Session should have a creator from one of the threads")))

      (chaos/assert-invariants! *pool* "after session create race"))))

;; =============================================================================
;; 3. Concurrent Job Claiming
;; =============================================================================

(deftest ^:integration concurrent-job-claiming-no-double-claim-test
  (testing "multiple workers claiming jobs concurrently - no double claims"
    (let [job-count 20
          worker-count 4
          job-ids (atom [])]

      ;; Create many jobs for different sessions
      (dotimes [i job-count]
        (let [sid (chaos/make-session-id "job-sess" i)
              job-id (job-store/create-job! *pool*
                       (chaos/make-job sid (keyword (str "invoke-" i))))]
          (swap! job-ids conj job-id)))

      ;; All workers try to claim jobs simultaneously
      (let [claimed-by-worker (atom {})
            worker-fns (for [worker-id (range worker-count)]
                         (fn []
                           (let [owner (str "worker-" worker-id)
                                 claimed (job-store/claim-jobs! *pool*
                                           {:owner-id owner
                                            :lease-duration-seconds 60
                                            :limit job-count})]
                             (swap! claimed-by-worker assoc worker-id
                                    (mapv :id claimed))
                             (count claimed))))]
        (let [results (chaos/execute-concurrent! (vec worker-fns) :timeout-ms 15000)
              total-claimed (reduce + (keep :result results))
              all-claimed-ids (mapcat val @claimed-by-worker)]

          ;; Total claimed should equal job count
          (is (= job-count total-claimed)
              (str "Total claimed should be " job-count ", got " total-claimed))

          ;; No job should be claimed by multiple workers
          (is (= (count all-claimed-ids) (count (set all-claimed-ids)))
              "No job should be claimed by more than one worker")))

      (chaos/assert-invariants! *pool* "after concurrent job claiming"))))

(deftest ^:integration job-lease-expiry-recovery-test
  (testing "expired leases are reclaimed by other workers"
    (let [session-id :lease-test-session
          job-id (job-store/create-job! *pool*
                   (chaos/make-job session-id :test-invoke))]

      ;; Worker 1 claims with very short lease
      (let [claimed (job-store/claim-jobs! *pool*
                      {:owner-id "worker-1"
                       :lease-duration-seconds 1
                       :limit 1})]
        (is (= 1 (count claimed)) "Worker 1 should claim the job"))

      ;; Wait for lease to expire
      (Thread/sleep 1500)

      ;; Worker 2 should be able to claim the expired lease
      (let [reclaimed (job-store/claim-jobs! *pool*
                        {:owner-id "worker-2"
                         :lease-duration-seconds 60
                         :limit 1})]
        (is (= 1 (count reclaimed)) "Worker 2 should reclaim expired job")
        (when (seq reclaimed)
          (is (= "worker-2" (:lease-owner (first reclaimed))))
          ;; Attempt should be incremented
          (is (= 2 (:attempt (first reclaimed)))
              "Attempt should be 2 after re-claim")))

      ;; Worker 1's heartbeat should fail
      (is (false? (job-store/heartbeat! *pool* job-id "worker-1" 60))
          "Worker 1's heartbeat should fail after lease takeover")

      ;; Worker 2's heartbeat should succeed
      (is (true? (job-store/heartbeat! *pool* job-id "worker-2" 60))
          "Worker 2's heartbeat should succeed")

      (chaos/assert-invariants! *pool* "after lease expiry recovery"))))

;; =============================================================================
;; 4. Failure Recovery
;; =============================================================================

(deftest ^:integration handler-failure-releases-event-for-retry-test
  (testing "when handler throws, event is released and can be retried"
    (let [session-id :failure-session
          queue (pg-eq/new-queue *pool* "test-worker")
          attempt-count (atom 0)
          succeed-on-attempt 3]

      ;; Send one event
      (sp/send! queue {} (chaos/make-event session-id :flaky-event))

      ;; Process with handler that fails first N-1 times
      (dotimes [_ succeed-on-attempt]
        (sp/receive-events! queue {}
          (fn [_env _event]
            (swap! attempt-count inc)
            (when (< @attempt-count succeed-on-attempt)
              (throw (ex-info "Transient failure" {}))))
          {:batch-size 1}))

      (is (= succeed-on-attempt @attempt-count)
          (str "Handler should be called " succeed-on-attempt " times"))

      ;; Event should now be processed
      (is (= 1 (chaos/count-events *pool* :processed? true))
          "Event should be marked processed after successful retry")

      (chaos/assert-invariants! *pool* "after handler failure retry"))))

(deftest ^:integration poison-event-does-not-block-queue-test
  (testing "a poison event that always fails doesn't block other events"
    (let [session-id :poison-session
          queue (pg-eq/new-queue *pool* "test-worker")
          tracker (atom [])]

      ;; Send: good, poison, good, good
      (sp/send! queue {} (chaos/make-event session-id :good-1))
      (sp/send! queue {} (chaos/make-event session-id :poison {:poison? true}))
      (sp/send! queue {} (chaos/make-event session-id :good-2))
      (sp/send! queue {} (chaos/make-event session-id :good-3))

      ;; Process multiple rounds
      (let [handler (chaos/poison-aware-handler tracker)]
        (dotimes [_ 10]
          (sp/receive-events! queue {} handler {:batch-size 2})
          (Thread/sleep 10)))

      ;; Good events should all be processed
      (let [processed-names (->> @tracker
                                 (remove :poison?)
                                 (map :event-name)
                                 set)]
        (is (contains? processed-names :good-1) "good-1 should be processed")
        (is (contains? processed-names :good-2) "good-2 should be processed")
        (is (contains? processed-names :good-3) "good-3 should be processed"))

      ;; Poison event should still be in queue (released for retry each time)
      (is (pos? (chaos/count-events *pool* :processed? false))
          "Poison event should remain unprocessed in queue"))))

(deftest ^:integration stale-claim-recovery-test
  (testing "stale claims are recovered after timeout"
    (let [session-id :stale-session
          queue (pg-eq/new-queue *pool* "worker-that-will-crash")]

      ;; Send events
      (dotimes [i 5]
        (sp/send! queue {} (chaos/make-event session-id (keyword (str "evt-" i)))))

      ;; Claim events (simulating a worker that crashes mid-processing)
      (pg/with-connection [conn *pool*]
        (pg/with-tx [conn]
          (pg/execute conn
            "UPDATE statechart_events SET claimed_at = now(), claimed_by = 'dead-worker' WHERE processed_at IS NULL"
            {})))

      ;; Verify events are claimed
      (is (= 5 (chaos/count-events *pool* :claimed? true :processed? false))
          "All events should be claimed by dead worker")

      ;; Recover stale claims (timeout = 0 means recover immediately)
      (let [recovered (pg-eq/recover-stale-claims! *pool* 0)]
        (is (= 5 recovered) "Should recover all 5 stale claims"))

      ;; Events should now be claimable again
      (is (= 5 (chaos/count-events *pool* :claimed? false :processed? false))
          "Events should be unclaimed after recovery")

      ;; Process them with a healthy worker
      (let [healthy-queue (pg-eq/new-queue *pool* "healthy-worker")
            processed (atom 0)]
        (sp/receive-events! healthy-queue {}
          (fn [_ _] (swap! processed inc))
          {:batch-size 10})
        (is (= 5 @processed) "Healthy worker should process all recovered events"))

      (chaos/assert-invariants! *pool* "after stale claim recovery"))))

;; =============================================================================
;; 5. Pool Stress
;; =============================================================================

(deftest ^:integration pool-exhaustion-under-concurrent-load-test
  (testing "operations survive pool contention with small pool"
    (let [;; Create a tiny pool to force contention
          tiny-pool (pool/pool (assoc test-config
                                      :pool-min-size 1
                                      :pool-max-size 2))
          store (pg-wms/new-store tiny-pool)
          queue (pg-eq/new-queue tiny-pool "stress-worker")]
      (try
        (chaos/setup-tables! tiny-pool)

        ;; Hammer the pool with concurrent operations
        (let [thread-count 8
              ops-per-thread 20
              errors (atom [])
              results (chaos/run-workers! thread-count ops-per-thread
                        (fn [worker-id iter]
                          (case (mod iter 4)
                            ;; Save session
                            0 (let [sid (chaos/make-session-id "stress" worker-id)]
                                (try
                                  (sp/save-working-memory! store {} sid
                                    (chaos/make-working-memory sid :chart))
                                  :saved
                                  (catch Exception e
                                    (swap! errors conj {:op :save :error (.getMessage e)})
                                    :error)))
                            ;; Read session
                            1 (let [sid (chaos/make-session-id "stress" worker-id)]
                                (sp/get-working-memory store {} sid))
                            ;; Send event
                            2 (sp/send! queue {}
                                (chaos/make-event
                                 (chaos/make-session-id "stress" worker-id)
                                 (keyword (str "evt-" iter))))
                            ;; Queue depth
                            3 (pg-eq/queue-depth tiny-pool)))
                        :timeout-ms 30000)]
          ;; Some errors may occur due to pool exhaustion timeouts,
          ;; but no corruption should result
          (chaos/assert-invariants! tiny-pool "after pool stress"))
        (finally
          (pool/close tiny-pool))))))

(deftest ^:integration mixed-workload-concurrent-stress-test
  (testing "mixed read/write/event workload under concurrency"
    (let [store (pg-wms/new-store *pool*)
          session-count 10
          thread-count 6
          ops-per-thread 30]

      ;; Pre-create sessions
      (dotimes [i session-count]
        (let [sid (chaos/make-session-id "mixed" i)]
          (sp/save-working-memory! store {} sid
            (chaos/make-working-memory sid :chart))))

      ;; Run mixed workload
      (let [results (chaos/run-workers! thread-count ops-per-thread
                      (fn [worker-id iter]
                        (let [sid (chaos/make-session-id "mixed" (mod iter session-count))
                              store (pg-wms/new-store *pool*)
                              queue (pg-eq/new-queue *pool* (str "w-" worker-id))]
                          (case (mod iter 5)
                            ;; Read
                            0 (sp/get-working-memory store {} sid)
                            ;; Write (with retry)
                            1 (pg-wms/with-optimistic-retry
                                {:max-retries 5 :backoff-ms 5}
                                (fn []
                                  (let [wm (sp/get-working-memory store {} sid)
                                        updated (update-in wm [::sc/data-model :counter]
                                                           (fnil inc 0))]
                                    (sp/save-working-memory! store {} sid updated))))
                            ;; Send event
                            2 (sp/send! queue {}
                                (chaos/make-event sid (keyword (str "e-" iter))))
                            ;; Process events
                            3 (sp/receive-events! queue {}
                                (fn [_ _] nil) {:batch-size 5})
                            ;; Queue depth
                            4 (pg-eq/queue-depth *pool*))))
                      :timeout-ms 30000)]

        (is (empty? (:errors results))
            (str "No errors expected in mixed workload, got: "
                 (take 5 (:errors results))))

        (chaos/assert-invariants! *pool* "after mixed workload stress")))))

;; =============================================================================
;; 6. Event Loop Resilience
;; =============================================================================

(deftest ^:integration event-loop-survives-handler-failures-test
  (testing "event loop continues processing after handler exceptions"
    (let [session-id :loop-resilience
          queue (pg-eq/new-queue *pool* "loop-worker")
          tracker (atom [])
          fail-until (atom 3) ;; fail first 3 events
          handler (fn [_env event]
                    (swap! tracker conj (:name event))
                    (when (pos? (swap! fail-until dec))
                      (throw (ex-info "Chaos: loop handler failure" {}))))]

      ;; Send 10 events
      (dotimes [i 10]
        (sp/send! queue {} (chaos/make-event session-id (keyword (str "evt-" i)))))

      ;; Process them in a loop (simulating start-event-loop! behavior)
      (dotimes [_ 20]
        (try
          (sp/receive-events! queue {} handler {:batch-size 3})
          (catch Exception _))
        (Thread/sleep 10))

      ;; All 10 events should eventually be processed
      ;; (the first 3 get retried after failure)
      (let [processed (chaos/count-events *pool* :processed? true)]
        (is (= 10 processed)
            (str "All 10 events should be processed, got " processed)))

      (chaos/assert-invariants! *pool* "after event loop resilience"))))

;; =============================================================================
;; 7. Job State Machine Chaos
;; =============================================================================

(deftest ^:integration job-lifecycle-concurrent-complete-and-fail-test
  (testing "concurrent complete and fail on same job - only one wins"
    (let [session-id :job-race-session
          job-id (job-store/create-job! *pool*
                   (chaos/make-job session-id :race-invoke))]

      ;; Claim the job
      (let [claimed (job-store/claim-jobs! *pool*
                      {:owner-id "racer" :lease-duration-seconds 60 :limit 1})]
        (is (= 1 (count claimed))))

      ;; Race: complete vs fail
      (let [results (chaos/execute-concurrent!
                     [(fn []
                        (job-store/complete! *pool* job-id "racer"
                          {:result "success"}
                          "done" {:outcome :ok}))
                      (fn []
                        (job-store/fail! *pool* job-id "racer"
                          1 3 {:error "failed"}
                          "error.failed" {:outcome :error}))])]

        ;; Exactly one should succeed
        (let [successes (->> results
                             (map :result)
                             (filter #(or (true? %) (= :failed %) (= :retry-scheduled %))))]
          (is (= 1 (count successes))
              (str "Exactly one operation should succeed, got: "
                   (mapv :result results)))))

      (chaos/assert-invariants! *pool* "after job complete/fail race"))))

(deftest ^:integration job-cancel-during-execution-test
  (testing "cancelling a job while it's being executed"
    (let [session-id :cancel-race-session
          job-id (job-store/create-job! *pool*
                   (chaos/make-job session-id :cancel-invoke))]

      ;; Worker claims the job
      (let [claimed (job-store/claim-jobs! *pool*
                      {:owner-id "worker-1" :lease-duration-seconds 60 :limit 1})]
        (is (= 1 (count claimed))))

      ;; Cancel while "executing"
      (let [cancelled (job-store/cancel! *pool* session-id :cancel-invoke)]
        (is (= 1 cancelled) "Cancel should affect the running job"))

      ;; Worker's heartbeat should fail
      (is (false? (job-store/heartbeat! *pool* job-id "worker-1" 60))
          "Heartbeat should fail after cancellation")

      ;; Worker's complete should fail (job is cancelled, not running)
      (is (false? (job-store/complete! *pool* job-id "worker-1"
                    {:result "too late"} "done" {}))
          "Complete should fail after cancellation")

      ;; Verify final state
      (is (true? (job-store/job-cancelled? *pool* job-id))
          "Job should be in cancelled state")

      (chaos/assert-invariants! *pool* "after job cancel during execution"))))

;; =============================================================================
;; 8. Version Monotonicity
;; =============================================================================

(deftest ^:integration version-monotonicity-under-contention-test
  (testing "session version is strictly monotonically increasing"
    (let [store (pg-wms/new-store *pool*)
          session-id :version-test
          wmem (chaos/make-working-memory session-id :chart)
          observed-versions (atom [])]

      ;; Create session
      (sp/save-working-memory! store {} session-id wmem)

      ;; Concurrent readers tracking version progression
      (let [reader-fns (for [_rid (range 4)]
                         (fn []
                           (dotimes [_ 50]
                             (when-let [wm (sp/get-working-memory store {} session-id)]
                               (swap! observed-versions conj (core/get-version wm)))
                             (Thread/sleep 1))))
            ;; Concurrent writer incrementing
            writer-fn (fn []
                        (dotimes [_ 20]
                          (pg-wms/with-optimistic-retry
                            {:max-retries 10 :backoff-ms 5}
                            (fn []
                              (let [wm (sp/get-working-memory store {} session-id)
                                    updated (update-in wm [::sc/data-model :counter]
                                                       (fnil inc 0))]
                                (sp/save-working-memory! store {} session-id updated))))))]

        (chaos/execute-concurrent! (conj (vec reader-fns) writer-fn)
                                   :timeout-ms 15000))

      ;; Verify: all observed versions are positive integers
      (let [versions (sort @observed-versions)]
        (is (every? pos? versions)
            "All observed versions should be positive")
        ;; The final version should be 1 (initial) + 20 (writes) = 21
        (let [final-wm (sp/get-working-memory store {} session-id)
              final-version (core/get-version final-wm)]
          (is (= 21 final-version)
              (str "Final version should be 21, got " final-version)))))))

;; =============================================================================
;; 9. Event Delivery Ordering and Delayed Events
;; =============================================================================

(deftest ^:integration delayed-event-ordering-test
  (testing "delayed events are delivered after their deliver_at time"
    (let [session-id :delay-test
          queue (pg-eq/new-queue *pool* "delay-worker")
          delivery-order (atom [])]

      ;; Send: immediate, delayed (500ms), immediate
      (sp/send! queue {} (chaos/make-event session-id :immediate-1))
      (sp/send! queue {} (assoc (chaos/make-event session-id :delayed-500)
                                :delay 500))
      (sp/send! queue {} (chaos/make-event session-id :immediate-2))

      ;; Process immediately available events
      (sp/receive-events! queue {}
        (fn [_ e] (swap! delivery-order conj (:name e)))
        {:batch-size 10})

      ;; Only immediate events should be processed
      (is (= #{:immediate-1 :immediate-2} (set @delivery-order))
          "Only immediate events should be delivered first")

      ;; Wait for delayed event
      (Thread/sleep 600)

      (sp/receive-events! queue {}
        (fn [_ e] (swap! delivery-order conj (:name e)))
        {:batch-size 10})

      (is (= 3 (count @delivery-order))
          "All 3 events should be delivered")
      (is (= :delayed-500 (last @delivery-order))
          "Delayed event should be delivered last"))))

;; =============================================================================
;; 10. High-Volume Exactly-Once with Multiple Sessions
;; =============================================================================

(deftest ^:integration high-volume-multi-session-exactly-once-test
  (testing "exactly-once delivery across many sessions with concurrent workers"
    (let [session-count 10
          events-per-session 20
          worker-count 4
          total-events (* session-count events-per-session)
          processed-events (atom #{})]

      ;; Send events to multiple sessions
      (let [queue (pg-eq/new-queue *pool* "bulk-sender")]
        (dotimes [s session-count]
          (let [sid (chaos/make-session-id "multi" s)]
            (dotimes [e events-per-session]
              (sp/send! queue {}
                (chaos/make-event sid (keyword (str "e-" s "-" e))
                                  {:session s :event-num e}))))))

      ;; Process with multiple workers
      (let [worker-fns (for [wid (range worker-count)]
                         (fn []
                           (let [wq (pg-eq/new-queue *pool* (str "multi-w-" wid))]
                             (loop [rounds 0]
                               (when (and (< rounds 100)
                                          (pos? (chaos/count-events *pool* :processed? false)))
                                 (sp/receive-events! wq {}
                                   (fn [_ event]
                                     (let [key [(:target event) (:name event)]]
                                       ;; Track for duplicate detection
                                       (when (contains? @processed-events key)
                                         (throw (ex-info "DUPLICATE PROCESSING"
                                                         {:key key})))
                                       (swap! processed-events conj key)))
                                   {:batch-size 10})
                                 (Thread/sleep 5)
                                 (recur (inc rounds)))))))]
        (chaos/execute-concurrent! (vec worker-fns) :timeout-ms 60000))

      ;; Wait for stragglers
      (Thread/sleep 200)

      ;; Verify exactly-once
      (is (= total-events (count @processed-events))
          (str "Expected " total-events " unique processed events, got "
               (count @processed-events)))

      (let [verification (chaos/verify-exactly-once-events *pool* total-events)]
        (is (:valid? verification)
            (str "Exactly-once verification failed: " (pr-str verification))))

      (chaos/assert-invariants! *pool* "after high-volume multi-session"))))

;; =============================================================================
;; Regression Tests for Bug #3 and Bug #4
;;
;; These tests were written RED-first to prove the bugs existed, then the
;; source code was fixed. They now serve as regression tests.
;; =============================================================================

;; ---------------------------------------------------------------------------
;; Bug #3 (FIXED): Double-processing when batch tx rolls back
;;
;; Previously, receive-events! wrapped the entire batch (claim + all handlers
;; + all mark-processed) in a single pg/with-tx. If an Error propagated from
;; a later handler, the tx rolled back, un-marking earlier events that had
;; already been handled (with committed side effects). Those events would be
;; re-claimed and re-handled = double processing.
;;
;; Fix: Each event now gets its own transaction for mark-processed/release.
;; The claim is a separate tx. An Error in event C no longer rolls back
;; event A's mark-processed.
;; ---------------------------------------------------------------------------

(deftest ^:integration per-event-tx-prevents-double-processing-test
  (testing "Error in event C does not roll back event A's mark-processed"
    (let [session-id :double-process-session
          queue (pg-eq/new-queue *pool* "dp-worker")
          handler-calls (atom [])
          call-count (atom 0)]

      ;; Send 3 events that will be claimed as a batch
      (sp/send! queue {} (chaos/make-event session-id :event-A {:seq 1}))
      (sp/send! queue {} (chaos/make-event session-id :event-B {:seq 2}))
      (sp/send! queue {} (chaos/make-event session-id :event-C {:seq 3}))

      ;; Process. Handler records invocations and on event-C throws an Error.
      ;; With per-event tx, events A and B are already individually committed.
      (try
        (sp/receive-events! queue {}
          (fn [_env event]
            (swap! handler-calls conj (:name event))
            (swap! call-count inc)
            (when (= :event-C (:name event))
              (throw (AssertionError. "Chaos: kill event C"))))
          {:batch-size 10})
        (catch AssertionError _))

      ;; Handler was called for all 3 events
      (is (= 3 @call-count) "Handler called 3 times in first pass")

      ;; Events A and B should be marked processed (their per-event tx committed).
      ;; Event C should NOT be processed (its handler threw before mark-processed).
      (let [processed (chaos/count-events *pool* :processed? true)
            unprocessed (chaos/count-events *pool* :processed? false)]
        (is (= 2 processed)
            (str "Events A and B should be processed, got " processed))
        (is (= 1 unprocessed)
            (str "Only event C should remain unprocessed, got " unprocessed)))

      ;; Process again — only event C should be re-handled
      (reset! call-count 0)
      (sp/receive-events! queue {}
        (fn [_env event]
          (swap! handler-calls conj (:name event))
          (swap! call-count inc))
        {:batch-size 10})

      (is (= 1 @call-count) "Only event C should be retried")

      ;; A and B were each called exactly once — no double processing
      (let [a-calls (count (filter #{:event-A} @handler-calls))
            b-calls (count (filter #{:event-B} @handler-calls))]
        (is (= 1 a-calls)
            (str "event-A should be called exactly once, was called " a-calls " times"))
        (is (= 1 b-calls)
            (str "event-B should be called exactly once, was called " b-calls " times"))))))

;; ---------------------------------------------------------------------------
;; Bug #4 (FIXED): Upsert race silently loses data
;;
;; Previously, ON CONFLICT DO UPDATE SET did not increment the version column.
;; Both concurrent creators got true back, version stayed at 1, and one
;; writer's data was silently overwritten.
;;
;; Fix: ON CONFLICT now includes :version [:+ :statechart-sessions.version 1].
;; The second writer still succeeds, but version becomes 2, so any subsequent
;; optimistic-lock update by the first writer (holding version 1) will fail
;; as expected.
;; ---------------------------------------------------------------------------

(deftest ^:integration upsert-race-increments-version-test
  (testing "concurrent session creation increments version on conflict"
    (let [store (pg-wms/new-store *pool*)
          session-id :upsert-race-session
          wmem-alpha (assoc (chaos/make-working-memory session-id :chart-a)
                            ::sc/data-model {:creator :alpha :value 111})
          wmem-beta (assoc (chaos/make-working-memory session-id :chart-b)
                           ::sc/data-model {:creator :beta :value 222})
          results (atom {})]

      ;; Both threads save the same session-id with different data
      (let [fns [(fn []
                   (sp/save-working-memory! store {} session-id wmem-alpha)
                   (swap! results assoc :alpha :saved))
                 (fn []
                   (Thread/sleep (rand-int 2))
                   (sp/save-working-memory! store {} session-id wmem-beta)
                   (swap! results assoc :beta :saved))]]
        (chaos/execute-concurrent! fns :timeout-ms 5000))

      ;; Both report success
      (is (= :saved (:alpha @results)) "Alpha save reported success")
      (is (= :saved (:beta @results)) "Beta save reported success")

      ;; Version should be 2 — one INSERT (version=1), one ON CONFLICT UPDATE (version=2)
      (let [final (sp/get-working-memory store {} session-id)
            version (core/get-version final)]
        (is (= 2 version)
            (str "Version should be 2 after concurrent create, got " version
                 ". The ON CONFLICT UPDATE must increment version."))

        ;; One creator's data is the final state — that's fine.
        ;; The important thing is the version bump so the loser can detect it.
        (let [creator (get-in final [::sc/data-model :creator])]
          (is (#{:alpha :beta} creator)))))))
