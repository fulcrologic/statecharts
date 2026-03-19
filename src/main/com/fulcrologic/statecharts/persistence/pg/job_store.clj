(ns com.fulcrologic.statecharts.persistence.pg.job-store
  "PostgreSQL-backed durable job store for statechart invocations.

   Jobs are created by DurableJobInvocationProcessor when entering an invoke state,
   and claimed by the worker for execution. Designed for restart safety with
   lease-based ownership and idempotent operations."
  (:require
   [com.fulcrologic.statecharts.persistence.pg.core :as core]
   [pg.core :as pg]
   [pg.pool :as pool]
   [taoensso.timbre :as log])
  (:import
   [java.time OffsetDateTime Duration]))

;; -----------------------------------------------------------------------------
;; Invokeid Serialization
;; -----------------------------------------------------------------------------

(defn invokeid->str
  "Serialize an invokeid keyword to a string, preserving namespace.
   Simple keywords: :content-generation → \"content-generation\"
   Qualified keywords: :my-ns/gen → \"my-ns/gen\""
  [invokeid]
  (subs (str invokeid) 1))

(defn str->invokeid
  "Deserialize a string back to an invokeid keyword.
   \"content-generation\" → :content-generation
   \"my-ns/gen\" → :my-ns/gen"
  [s]
  (keyword s))

;; -----------------------------------------------------------------------------
;; Internal Helpers
;; -----------------------------------------------------------------------------

(defn- with-conn
  "Execute f with a connection from pool-or-conn."
  [pool-or-conn f]
  (if (pool/pool? pool-or-conn)
    (pg/with-connection [c pool-or-conn]
      (f c))
    (f pool-or-conn)))

(defn- backoff-seconds
  "Exponential backoff: 2^attempt seconds, capped at 60s."
  [attempt]
  (min 60 (long (Math/pow 2 attempt))))

(defn- guarded-where-clause
  "Build a status/lease-guarded WHERE clause for terminal updates.
   If owner-id is present, only the current lease owner may update."
  [param-count owner-id]
  (if owner-id
    {:sql (str " WHERE id = $" (inc param-count)
               " AND status = 'running'"
               " AND lease_owner = $" (+ 2 param-count))
     :extra-params [owner-id]}
    {:sql (str " WHERE id = $" (inc param-count)
               " AND status IN ('pending', 'running')")
     :extra-params []}))

(defn- hydrate-job-row
  "Decode persisted fields and restore session-id shape for runtime use."
  [row]
  (-> row
      (update :session-id core/str->session-id)
      (update :invokeid str->invokeid)
      (update :payload core/thaw)
      (cond->
        (:result row) (update :result core/thaw)
        (:error row) (update :error core/thaw)
        (:terminal-event-data row) (update :terminal-event-data core/thaw))))

;; -----------------------------------------------------------------------------
;; Job CRUD
;; -----------------------------------------------------------------------------

(defn create-job!
  "Create a new job, returning the job-id (UUID).

   Idempotent (I1): if an active job already exists for this session+invokeid,
   returns the existing job-id instead of creating a duplicate.
   Uses partial unique index on (session_id, invokeid) WHERE status IN ('pending','running')."
  [pool {:keys [id session-id invokeid job-type payload max-attempts]
         :or {max-attempts 3}}]
  (with-conn pool
    (fn [conn]
      (let [result (pg/execute conn
                     (str "INSERT INTO statechart_jobs (id, session_id, invokeid, job_type, payload, max_attempts)"
                          " VALUES ($1, $2, $3, $4, $5, $6)"
                          " ON CONFLICT (session_id, invokeid) WHERE status IN ('pending', 'running')"
                          " DO NOTHING"
                          " RETURNING id")
                     {:params [id
                               (core/session-id->str session-id)
                               (invokeid->str invokeid)
                               job-type
                               (core/freeze payload)
                               max-attempts]
                      :kebab-keys? true})]
        (if-let [inserted-id (some-> (when (sequential? result) (first result)) :id)]
          ;; INSERT succeeded — RETURNING row with id
          inserted-id
          ;; Some pg2 variants return summary maps for mutations.
          ;; If we affected a row, the inserted id is the one we requested.
          (if (pos? (core/affected-row-count result))
            id
            ;; Conflict — active job already exists, fetch its id
            (let [existing (pg/execute conn
                             (str "SELECT id FROM statechart_jobs"
                                  " WHERE session_id = $1 AND invokeid = $2"
                                  " AND status IN ('pending', 'running')")
                             {:params [(core/session-id->str session-id)
                                       (invokeid->str invokeid)]
                              :kebab-keys? true})]
              (:id (first existing)))))))))

(defn claim-jobs!
  "Claim up to `limit` claimable jobs for this worker.

   A job is claimable if:
   - status is 'pending' and next_run_at <= now, OR
   - status is 'running' and lease has expired (stale worker recovery)

   Uses SELECT FOR UPDATE SKIP LOCKED to prevent concurrent claims.
   Sets status='running', increments attempt, sets lease.

   Returns claimed job rows with thawed payload."
  [pool {:keys [owner-id lease-duration-seconds limit]
         :or {lease-duration-seconds 60 limit 5}}]
  (with-conn pool
    (fn [conn]
      (pg/with-tx [conn]
        (let [limit (long limit) ;; ensure numeric
              now (OffsetDateTime/now)
              lease-until (.plus now (Duration/ofSeconds lease-duration-seconds))
              rows (pg/execute conn
                     (str "UPDATE statechart_jobs"
                          " SET status = 'running',"
                          "     attempt = attempt + 1,"
                          "     lease_owner = $1,"
                          "     lease_expires_at = $2,"
                          "     updated_at = now()"
                          " WHERE id IN ("
                          "   SELECT id FROM statechart_jobs"
                          "   WHERE (status = 'pending' AND next_run_at <= now())"
                          "      OR (status = 'running' AND lease_expires_at < now())"
                          "   ORDER BY next_run_at"
                          "   LIMIT " limit
                          "   FOR UPDATE SKIP LOCKED"
                          " )"
                          " RETURNING *")
                     {:params [owner-id lease-until]
                      :kebab-keys? true})]
          (->> rows
               ;; UPDATE ... RETURNING does not guarantee row order in all plans.
               ;; Re-apply deterministic ordering for predictable claims/tests.
               (sort-by (juxt :next-run-at :id))
               (mapv hydrate-job-row)))))))

(defn heartbeat!
  "Extend the lease for a running job owned by this worker.

   Returns true if lease was extended (we still own it).
   Returns false if lease was taken over by another worker (I8) or job is
   no longer running. On false, the worker must abandon execution immediately."
  [pool job-id owner-id lease-duration-seconds]
  (with-conn pool
    (fn [conn]
      (let [lease-until (.plus (OffsetDateTime/now) (Duration/ofSeconds lease-duration-seconds))
            result (pg/execute conn
                     (str "UPDATE statechart_jobs"
                          " SET lease_expires_at = $1, updated_at = now()"
                          " WHERE id = $2 AND lease_owner = $3 AND status = 'running'"
                          " RETURNING id")
                     {:params [lease-until job-id owner-id]
                      :kebab-keys? true})]
        (pos? (core/affected-row-count result))))))

(defn complete!
  "Mark a job as succeeded and store the result.
   Also stores the terminal event name and data for reconciliation.
   Returns true when the row was updated, false otherwise."
  ([pool job-id result terminal-event-name terminal-event-data]
   (complete! pool job-id nil result terminal-event-name terminal-event-data))
  ([pool job-id owner-id result terminal-event-name terminal-event-data]
   (with-conn pool
     (fn [conn]
       (let [{:keys [sql extra-params]} (guarded-where-clause 3 owner-id)
             rows (pg/execute conn
                    (str "UPDATE statechart_jobs"
                         " SET status = 'succeeded',"
                         "     result = $1,"
                         "     terminal_event_name = $2,"
                         "     terminal_event_data = $3,"
                         "     lease_owner = NULL,"
                         "     lease_expires_at = NULL,"
                         "     updated_at = now()"
                         sql
                         " RETURNING id")
                    {:params (into [(core/freeze result)
                                    terminal-event-name
                                    (core/freeze terminal-event-data)
                                    job-id]
                                   extra-params)
                     :kebab-keys? true})]
         (pos? (core/affected-row-count rows)))))))

(defn fail!
  "Handle job failure. If attempts remain, re-enqueue with backoff.
   If exhausted, mark failed and store the terminal event for dispatch.
   Returns one of:
   - :retry-scheduled
   - :failed
   - :ignored (job no longer active/owned)."
  ([pool job-id attempt max-attempts error terminal-event-name terminal-event-data]
   (fail! pool job-id nil attempt max-attempts error terminal-event-name terminal-event-data))
  ([pool job-id owner-id attempt max-attempts error terminal-event-name terminal-event-data]
   (with-conn pool
     (fn [conn]
       (if (< attempt max-attempts)
         ;; Retryable — re-enqueue with backoff
         (let [delay-secs (backoff-seconds attempt)
               next-run (.plus (OffsetDateTime/now) (Duration/ofSeconds delay-secs))
               {:keys [sql extra-params]} (guarded-where-clause 2 owner-id)
               rows (pg/execute conn
                      (str "UPDATE statechart_jobs"
                           " SET status = 'pending',"
                           "     next_run_at = $1,"
                           "     lease_owner = NULL,"
                           "     lease_expires_at = NULL,"
                           "     error = $2,"
                           "     updated_at = now()"
                           sql
                           " RETURNING id")
                      {:params (into [next-run (core/freeze error) job-id]
                                     extra-params)
                       :kebab-keys? true})]
           (if (pos? (core/affected-row-count rows))
             (do
               (log/info "Job failed, scheduling retry"
                         {:job-id job-id :attempt attempt :max-attempts max-attempts
                          :next-run-in-seconds delay-secs})
               :retry-scheduled)
             :ignored))
         ;; Exhausted — terminal failure
         (let [{:keys [sql extra-params]} (guarded-where-clause 3 owner-id)
               rows (pg/execute conn
                      (str "UPDATE statechart_jobs"
                           " SET status = 'failed',"
                           "     error = $1,"
                           "     terminal_event_name = $2,"
                           "     terminal_event_data = $3,"
                           "     lease_owner = NULL,"
                           "     lease_expires_at = NULL,"
                           "     updated_at = now()"
                           sql
                           " RETURNING id")
                      {:params (into [(core/freeze error)
                                      terminal-event-name
                                      (core/freeze terminal-event-data)
                                      job-id]
                                     extra-params)
                       :kebab-keys? true})]
           (if (pos? (core/affected-row-count rows))
             (do
               (log/warn "Job failed permanently"
                         {:job-id job-id :attempt attempt :max-attempts max-attempts})
               :failed)
             :ignored)))))))

(defn cancel!
  "Cancel a job for a specific session+invokeid.
   Status-conditional (I7): only cancels pending/running jobs.
   Returns the number of rows affected."
  [pool session-id invokeid]
  (with-conn pool
    (fn [conn]
      (let [result (pg/execute conn
                     (str "UPDATE statechart_jobs"
                          " SET status = 'cancelled', updated_at = now()"
                          " WHERE session_id = $1 AND invokeid = $2"
                          " AND status IN ('pending', 'running')"
                          " RETURNING id")
                     {:params [(core/session-id->str session-id)
                               (invokeid->str invokeid)]
                      :kebab-keys? true})]
        (core/affected-row-count result)))))

(defn cancel-by-session!
  "Cancel all active jobs for a session (I6).
   Used when session is being deleted or reset."
  [pool session-id]
  (with-conn pool
    (fn [conn]
      (let [result (pg/execute conn
                     (str "UPDATE statechart_jobs"
                          " SET status = 'cancelled', updated_at = now()"
                          " WHERE session_id = $1"
                          " AND status IN ('pending', 'running')"
                          " RETURNING id")
                     {:params [(core/session-id->str session-id)]
                      :kebab-keys? true})]
        (core/affected-row-count result)))))

(defn get-active-job
  "Get the active (pending/running) job for a session+invokeid, or nil."
  [pool session-id invokeid]
  (with-conn pool
    (fn [conn]
      (let [rows (pg/execute conn
                   (str "SELECT * FROM statechart_jobs"
                        " WHERE session_id = $1 AND invokeid = $2"
                        " AND status IN ('pending', 'running')")
                   {:params [(core/session-id->str session-id)
                             (invokeid->str invokeid)]
                    :kebab-keys? true})]
        (when-let [row (first rows)]
          (hydrate-job-row row))))))

(defn job-cancelled?
  "Check if a job has been cancelled. Used by worker to poll during execution (I6)."
  [pool job-id]
  (with-conn pool
    (fn [conn]
      (let [rows (pg/execute conn
                   "SELECT status FROM statechart_jobs WHERE id = $1"
                   {:params [job-id]
                    :kebab-keys? true})]
        (= "cancelled" (:status (first rows)))))))

(defn get-undispatched-terminal-jobs
  "Get jobs that completed/failed but whose terminal event hasn't been dispatched yet.
   Used by the reconciliation loop."
  [pool limit]
  (with-conn pool
    (fn [conn]
      (let [rows (pg/execute conn
                   (str "SELECT * FROM statechart_jobs"
                        " WHERE status IN ('succeeded', 'failed')"
                        " AND terminal_event_dispatched_at IS NULL"
                        " AND terminal_event_name IS NOT NULL"
                        " ORDER BY updated_at"
                        " LIMIT " limit)
                   {:kebab-keys? true})]
        (mapv hydrate-job-row rows)))))

(defn mark-terminal-event-dispatched!
  "Mark a job's terminal event as dispatched (reconciliation complete)."
  [pool job-id]
  (with-conn pool
    (fn [conn]
      (core/execute! conn
        {:update :statechart-jobs
         :set {:terminal-event-dispatched-at [:now]}
         :where [:= :id job-id]}))))

(defn store-partial-result!
  "Store intermediate result for idempotent retry (I9).
   Non-streaming job handlers call this after entity creation but before completion,
   so retries can skip entity creation."
  [pool job-id result]
  (with-conn pool
    (fn [conn]
      (core/execute! conn
        {:update :statechart-jobs
         :set {:result (core/freeze result)
               :updated-at [:now]}
         :where [:= :id job-id]}))))
