(ns com.fulcrologic.statecharts.persistence.pg.chaos
  "Chaos testing harness for the PostgreSQL persistence layer.

   Provides:
   - Fault injection wrappers (connection death, handler failure, slow queries)
   - Concurrent operation helpers (barriers, latches, thread pools)
   - Invariant checking for events, sessions, and jobs
   - Test data generators
   - Deterministic seed-based reproducibility

   Modeled after kosmos chaos testing patterns."
  (:require
   [clojure.test :refer [is]]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.persistence.pg.core :as core]
   [com.fulcrologic.statecharts.persistence.pg.event-queue :as pg-eq]
   [com.fulcrologic.statecharts.persistence.pg.job-store :as job-store]
   [com.fulcrologic.statecharts.persistence.pg.schema :as schema]
   [com.fulcrologic.statecharts.persistence.pg.working-memory-store :as pg-wms]
   [com.fulcrologic.statecharts.protocols :as sp]
   [pg.core :as pg]
   [pg.pool :as pool]
   [taoensso.timbre :as log])
  (:import
   [java.util.concurrent CyclicBarrier CountDownLatch TimeUnit
    ExecutorService Executors Future]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def default-chaos-config
  {:pool-min-size 2
   :pool-max-size 4
   :thread-count 6
   :iterations-per-thread 50
   :batch-size 5
   :event-handler-failure-rate 0.0 ;; 0.0 = never, 1.0 = always
   :handler-delay-ms 0
   :worker-count 3
   :lease-duration-seconds 2
   :stale-claim-timeout-seconds 2})

;; =============================================================================
;; Test Data Generators
;; =============================================================================

(defn make-session-id
  "Generate a deterministic session ID."
  [prefix n]
  (keyword (str prefix "-" n)))

(defn make-working-memory
  "Create a minimal working memory map for testing."
  [session-id chart-src]
  {::sc/session-id session-id
   ::sc/statechart-src chart-src
   ::sc/configuration #{:s1 :uber}
   ::sc/initialized-states #{:s1 :uber}
   ::sc/history-value {}
   ::sc/running? true
   ::sc/data-model {:counter 0}})

(defn make-event
  "Create an event send request."
  ([target event-name]
   (make-event target event-name {}))
  ([target event-name data]
   {:event event-name
    :target target
    :source-session-id target
    :data data}))

(defn make-job
  "Create a job request map."
  [session-id invokeid]
  {:id (random-uuid)
   :session-id session-id
   :invokeid invokeid
   :job-type "chaos-test"
   :payload {:test true}
   :max-attempts 3})

;; =============================================================================
;; Fault Injection
;; =============================================================================

(defn failing-handler
  "Create an event handler that fails at a configurable rate.
   failure-rate: 0.0 = never fails, 1.0 = always fails.
   Records all invocations in the tracker atom."
  [tracker failure-rate]
  (fn [_env event]
    (let [event-name (:name event)
          target (:target event)
          should-fail? (< (rand) failure-rate)]
      (swap! tracker conj {:event-name event-name
                           :target target
                           :failed? should-fail?
                           :thread (.getName (Thread/currentThread))
                           :timestamp (System/nanoTime)})
      (when should-fail?
        (throw (ex-info "Chaos: injected handler failure"
                        {:event-name event-name
                         :target target}))))))

(defn slow-handler
  "Create a handler that sleeps for delay-ms before calling inner-handler."
  [inner-handler delay-ms]
  (fn [env event]
    (Thread/sleep (long delay-ms))
    (inner-handler env event)))

(defn counting-handler
  "Create a handler that counts invocations per event target+name.
   Returns [handler, counts-atom] where counts is {[target name] count}."
  []
  (let [counts (atom {})]
    [(fn [_env event]
       (swap! counts update [(:target event) (:name event)]
              (fnil inc 0)))
     counts]))

(defn poison-event?
  "Returns true if the event has the poison marker."
  [event]
  (get-in event [:data :poison?]))

(defn poison-aware-handler
  "Handler that throws on poison events, counts all others."
  [tracker]
  (fn [_env event]
    (swap! tracker conj {:event-name (:name event)
                         :target (:target event)
                         :poison? (poison-event? event)
                         :thread (.getName (Thread/currentThread))
                         :timestamp (System/nanoTime)})
    (when (poison-event? event)
      (throw (ex-info "Chaos: poison event"
                      {:event-name (:name event)})))))

;; =============================================================================
;; Concurrent Execution Helpers
;; =============================================================================

(defn execute-concurrent!
  "Execute functions concurrently with a barrier for synchronized start.
   Returns a vector of {:result _ :error _ :thread-id _} maps."
  [fns & {:keys [timeout-ms] :or {timeout-ms 30000}}]
  (let [n (count fns)
        barrier (CyclicBarrier. n)
        done-latch (CountDownLatch. n)
        results (atom (vec (repeat n nil)))]
    (doseq [[i f] (map-indexed vector fns)]
      (.start
       (Thread.
        (fn []
          (try
            (.await barrier 10 TimeUnit/SECONDS)
            (let [result (try
                           {:result (f) :error nil :thread-id i}
                           (catch Exception e
                             {:result nil :error e :thread-id i}))]
              (swap! results assoc i result))
            (catch Exception e
              (swap! results assoc i {:result nil :error e :thread-id i}))
            (finally
              (.countDown done-latch))))
        (str "chaos-worker-" i))))
    (.await done-latch timeout-ms TimeUnit/MILLISECONDS)
    @results))

(defn run-workers!
  "Run n worker functions concurrently, each doing iterations-per-worker work.
   worker-fn receives (worker-id iteration).
   Returns {:results [...] :errors [...] :duration-ms long}."
  [n iterations-per-worker worker-fn & {:keys [timeout-ms] :or {timeout-ms 60000}}]
  (let [barrier (CyclicBarrier. n)
        done-latch (CountDownLatch. n)
        all-results (atom [])
        all-errors (atom [])
        start-time (System/nanoTime)]
    (doseq [worker-id (range n)]
      (.start
       (Thread.
        (fn []
          (try
            (.await barrier 10 TimeUnit/SECONDS)
            (dotimes [iter iterations-per-worker]
              (try
                (let [result (worker-fn worker-id iter)]
                  (swap! all-results conj result))
                (catch Exception e
                  (swap! all-errors conj
                         {:worker-id worker-id
                          :iteration iter
                          :error-class (type e)
                          :message (.getMessage e)}))))
            (catch Exception e
              (swap! all-errors conj
                     {:worker-id worker-id
                      :iteration -1
                      :error-class (type e)
                      :message (.getMessage e)}))
            (finally
              (.countDown done-latch))))
        (str "chaos-worker-" worker-id))))
    (.await done-latch timeout-ms TimeUnit/MILLISECONDS)
    {:results @all-results
     :errors @all-errors
     :duration-ms (/ (- (System/nanoTime) start-time) 1e6)}))

;; =============================================================================
;; Database Helpers
;; =============================================================================

(defn count-events
  "Count events matching criteria."
  [pool & {:keys [processed? claimed? session-id]}]
  (let [where-clauses (cond-> [:and]
                        (some? processed?) (conj (if processed?
                                                   [:is-not :processed-at nil]
                                                   [:is :processed-at nil]))
                        (some? claimed?) (conj (if claimed?
                                                 [:is-not :claimed-at nil]
                                                 [:is :claimed-at nil]))
                        session-id (conj [:= :target-session-id
                                          (core/session-id->str session-id)]))
        sql {:select [[[:count :*] :count]]
             :from [:statechart-events]
             :where (if (= 1 (count where-clauses))
                      true
                      where-clauses)}]
    (:count (core/execute-one! pool sql))))

(defn count-jobs
  "Count jobs matching criteria."
  [pool & {:keys [status session-id]}]
  (let [where-clauses (cond-> [:and]
                        status (conj [:= :status status])
                        session-id (conj [:= :session-id
                                          (core/session-id->str session-id)]))
        sql {:select [[[:count :*] :count]]
             :from [:statechart-jobs]
             :where (if (= 1 (count where-clauses))
                      true
                      where-clauses)}]
    (:count (core/execute-one! pool sql))))

(defn get-all-events
  "Fetch all events from the queue."
  [pool]
  (core/execute! pool
    {:select [:*]
     :from [:statechart-events]
     :order-by [:id]}))

(defn get-all-sessions
  "Fetch all sessions."
  [pool]
  (core/execute! pool
    {:select [:session-id :version :statechart-src]
     :from [:statechart-sessions]
     :order-by [:session-id]}))

(defn get-all-jobs
  "Fetch all jobs."
  [pool]
  (core/execute! pool
    {:select [:*]
     :from [:statechart-jobs]
     :order-by [:created-at]}))

;; =============================================================================
;; Invariant Checking
;; =============================================================================

(defn check-event-invariants
  "Check invariants on the event queue state.

   Returns {:valid? bool :violations [...]}"
  [pool]
  (let [events (get-all-events pool)
        violations (atom [])]

    ;; I1: No event should be both claimed and processed
    ;; (processed events may retain their claimed_by for audit,
    ;;  but the semantics should be: once processed, not re-claimable)
    ;; Actually, processed events retain claimed_at/claimed_by. This is fine.

    ;; I2: Every processed event must have been claimed at some point
    ;; (processed_at implies claimed_at was set during the processing transaction)
    (doseq [e events]
      (when (and (:processed-at e) (nil? (:claimed-at e)))
        (swap! violations conj
               {:invariant :processed-implies-claimed
                :event-id (:id e)
                :message "Event is processed but was never claimed"})))

    ;; I3: No two unprocessed events should be claimed by the same worker
    ;; for the same session at the same time (prevents duplicate processing)
    (let [active-claims (->> events
                             (filter #(and (:claimed-at %) (nil? (:processed-at %))))
                             (group-by (juxt :claimed-by :target-session-id)))]
      (doseq [[[worker session] claimed] active-claims]
        (when (> (count claimed) 1)
          ;; This is actually allowed - a worker can claim multiple events
          ;; for the same session. The invariant is about SKIP LOCKED preventing
          ;; concurrent claims, not about batch sizes.
          nil)))

    ;; I4: deliver_at must be set for all events
    (doseq [e events]
      (when (nil? (:deliver-at e))
        (swap! violations conj
               {:invariant :deliver-at-required
                :event-id (:id e)
                :message "Event missing deliver_at timestamp"})))

    {:valid? (empty? @violations)
     :violations @violations}))

(defn check-session-invariants
  "Check invariants on session state.

   Returns {:valid? bool :violations [...]}"
  [pool]
  (let [sessions (get-all-sessions pool)
        violations (atom [])]

    ;; I1: Version must be positive
    (doseq [s sessions]
      (when-not (pos? (:version s))
        (swap! violations conj
               {:invariant :version-positive
                :session-id (:session-id s)
                :version (:version s)
                :message "Session version must be positive"})))

    ;; I2: No duplicate session IDs (enforced by PK, but verify)
    (let [ids (map :session-id sessions)]
      (when-not (= (count ids) (count (set ids)))
        (swap! violations conj
               {:invariant :unique-session-ids
                :message "Duplicate session IDs found"
                :ids (frequencies ids)})))

    ;; I3: statechart_src must be set
    (doseq [s sessions]
      (when (nil? (:statechart-src s))
        (swap! violations conj
               {:invariant :statechart-src-required
                :session-id (:session-id s)
                :message "Session missing statechart_src"})))

    {:valid? (empty? @violations)
     :violations @violations}))

(defn check-job-invariants
  "Check invariants on job state.

   Returns {:valid? bool :violations [...]}"
  [pool]
  (let [jobs (get-all-jobs pool)
        violations (atom [])]

    ;; I1: Valid status values
    (let [valid-statuses #{"pending" "running" "succeeded" "failed" "cancelled"}]
      (doseq [j jobs]
        (when-not (valid-statuses (:status j))
          (swap! violations conj
                 {:invariant :valid-status
                  :job-id (:id j)
                  :status (:status j)
                  :message "Invalid job status"}))))

    ;; I2: Running jobs must have lease_owner and lease_expires_at
    (doseq [j jobs]
      (when (= "running" (:status j))
        (when (nil? (:lease-owner j))
          (swap! violations conj
                 {:invariant :running-has-lease-owner
                  :job-id (:id j)
                  :message "Running job missing lease_owner"}))
        (when (nil? (:lease-expires-at j))
          (swap! violations conj
                 {:invariant :running-has-lease-expiry
                  :job-id (:id j)
                  :message "Running job missing lease_expires_at"}))))

    ;; I3: Terminal jobs (succeeded/failed) must NOT have lease
    (doseq [j jobs]
      (when (#{"succeeded" "failed"} (:status j))
        (when (:lease-owner j)
          (swap! violations conj
                 {:invariant :terminal-no-lease
                  :job-id (:id j)
                  :status (:status j)
                  :message "Terminal job should not have lease_owner"}))))

    ;; I4: Attempt count must be <= max_attempts
    (doseq [j jobs]
      (when (> (:attempt j) (:max-attempts j))
        (swap! violations conj
               {:invariant :attempt-within-bounds
                :job-id (:id j)
                :attempt (:attempt j)
                :max-attempts (:max-attempts j)
                :message "Attempt count exceeds max_attempts"})))

    ;; I5: At most one active (pending/running) job per session+invokeid
    (let [active-jobs (->> jobs
                           (filter #(#{"pending" "running"} (:status %)))
                           (group-by (juxt :session-id :invokeid)))]
      (doseq [[[sid iid] active] active-jobs]
        (when (> (count active) 1)
          (swap! violations conj
                 {:invariant :unique-active-job
                  :session-id sid
                  :invokeid iid
                  :count (count active)
                  :message "Multiple active jobs for same session+invokeid"}))))

    {:valid? (empty? @violations)
     :violations @violations}))

(defn check-all-invariants
  "Run all invariant checks. Returns {:valid? bool :violations [...]}."
  [pool]
  (let [event-check (check-event-invariants pool)
        session-check (check-session-invariants pool)
        job-check (check-job-invariants pool)]
    {:valid? (and (:valid? event-check)
                  (:valid? session-check)
                  (:valid? job-check))
     :violations (concat (:violations event-check)
                         (:violations session-check)
                         (:violations job-check))}))

(defn assert-invariants!
  "Assert all invariants hold, failing the test if not."
  [pool context-msg]
  (let [{:keys [valid? violations]} (check-all-invariants pool)]
    (when-not valid?
      (log/error "Invariant violations" {:context context-msg
                                          :violations violations}))
    (is valid? (str context-msg ": " (count violations) " invariant violations: "
                    (pr-str (take 3 violations))))))

;; =============================================================================
;; Exactly-Once Verification
;; =============================================================================

(defn verify-exactly-once-events
  "Verify that each event was processed exactly once.
   Takes the pool and the number of events expected.

   Returns {:valid? bool :total-events long :processed long :unprocessed long
            :double-processed long}."
  [pool expected-count]
  (let [events (get-all-events pool)
        total (count events)
        processed (count (filter :processed-at events))
        unprocessed (count (filter #(nil? (:processed-at %)) events))
        ;; Check for any that were released but not re-claimed
        ;; (shouldn't happen if we waited long enough)
        stale-claimed (count (filter #(and (:claimed-at %)
                                           (nil? (:processed-at %)))
                                     events))]
    {:valid? (and (= expected-count total)
                  (= expected-count processed)
                  (zero? unprocessed))
     :total-events total
     :expected expected-count
     :processed processed
     :unprocessed unprocessed
     :stale-claimed stale-claimed}))

;; =============================================================================
;; Session Contention Helpers
;; =============================================================================

(defn contended-save!
  "Perform a read-modify-write cycle on a session.
   Used to create optimistic lock contention."
  [store session-id modifier-fn]
  (pg-wms/with-optimistic-retry
    {:max-retries 5 :backoff-ms 10}
    (fn []
      (let [wmem (sp/get-working-memory store {} session-id)
            modified (modifier-fn wmem)]
        (sp/save-working-memory! store {} session-id modified)
        modified))))

;; =============================================================================
;; Pool Lifecycle
;; =============================================================================

(defn make-pool
  "Create a test pool with chaos-appropriate settings."
  [base-config & {:keys [min-size max-size]
                  :or {min-size 2 max-size 4}}]
  (pool/pool (assoc base-config
                    :pool-min-size min-size
                    :pool-max-size max-size)))

(defmacro with-chaos-pool
  "Create a pool, bind it, execute body, close pool."
  [binding base-config opts & body]
  `(let [~binding (make-pool ~base-config ~@(mapcat identity opts))]
     (try
       ~@body
       (finally
         (pool/close ~binding)))))

(defn setup-tables!
  "Create and truncate tables for a clean test run."
  [pool]
  (schema/create-tables! pool)
  (schema/truncate-tables! pool))
