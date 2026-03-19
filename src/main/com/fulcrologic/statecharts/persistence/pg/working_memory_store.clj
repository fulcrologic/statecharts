(ns com.fulcrologic.statecharts.persistence.pg.working-memory-store
  "A PostgreSQL-backed working memory store with optimistic locking.

   Working memory is stored as BYTEA (via nippy) with a version column for
   concurrent write detection."
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.persistence.pg.core :as core]
   [com.fulcrologic.statecharts.protocols :as sp]
   [pg.core :as pg]
   [pg.pool :as pool]
   [taoensso.timbre :as log]))

;; -----------------------------------------------------------------------------
;; Optimistic Lock Exception
;; -----------------------------------------------------------------------------

(defn optimistic-lock-failure
  "Create an exception for optimistic lock failure."
  [session-id expected-version]
  (ex-info "Optimistic lock failure: session was modified by another process"
           {:type ::optimistic-lock-failure
            :session-id session-id
            :expected-version expected-version}))

(defn optimistic-lock-failure?
  "Check if an exception is an optimistic lock failure."
  [e]
  (and (instance? clojure.lang.ExceptionInfo e)
       (= ::optimistic-lock-failure (:type (ex-data e)))))

;; -----------------------------------------------------------------------------
;; Internal Helpers
;; -----------------------------------------------------------------------------

(defn- fetch-session
  "Fetch a session by ID, returning working memory with version metadata."
  [conn session-id]
  (when-let [row (core/execute-one! conn
                                    {:select [:working-memory :version :statechart-src]
                                     :from [:statechart-sessions]
                                     :where [:= :session-id (core/session-id->str session-id)]})]
    (let [src (clojure.edn/read-string (:statechart-src row))]
      (-> (:working-memory row)
          core/thaw
          (assoc ::sc/statechart-src src)
          (core/attach-version (:version row))))))

(defn- insert-session!
  "Insert a new session. Returns true on success."
  [conn session-id wmem]
  (let [src (get wmem ::sc/statechart-src)]
    (core/execute-one! conn
                       {:insert-into :statechart-sessions
                        :values [{:session-id (core/session-id->str session-id)
                                  :statechart-src (pr-str src)
                                  :working-memory (core/freeze wmem)
                                  :version 1}]})
    (log/debug "Session created"
               {:session-id session-id
                :statechart-src src})
    true))

(defn- update-session!
  "Update an existing session with optimistic locking.
   Throws on version mismatch."
  [conn session-id wmem expected-version]
  (let [new-version (inc expected-version)
        result (core/execute! conn
                              {:update :statechart-sessions
                               :set {:working-memory (core/freeze wmem)
                                     :version new-version
                                     :updated-at [:now]}
                               :where [:and
                                       [:= :session-id (core/session-id->str session-id)]
                                       [:= :version expected-version]]})]
    (when (zero? (core/affected-row-count result))
      (log/warn "Optimistic lock failure"
                {:session-id session-id
                 :expected-version expected-version})
      (throw (optimistic-lock-failure session-id expected-version)))
    (log/trace "Session updated"
               {:session-id session-id
                :version new-version})
    true))

(defn- upsert-session!
  "Insert or update a session with proper version handling."
  [conn session-id wmem]
  (let [expected-version (core/get-version wmem)]
    (if expected-version
      ;; Has version = existing session, do optimistic lock update
      (update-session! conn session-id wmem expected-version)
      ;; No version = new session, insert
      ;; Use ON CONFLICT to handle race condition where session was created
      ;; between our check and insert
      (let [src (get wmem ::sc/statechart-src)]
        (core/execute! conn
                       {:insert-into :statechart-sessions
                        :values [{:session-id (core/session-id->str session-id)
                                  :statechart-src (pr-str src)
                                  :working-memory (core/freeze wmem)
                                  :version 1}]
                        :on-conflict [:session-id]
                        :do-update-set {:working-memory :excluded.working-memory
                                        :version [:+ :statechart-sessions.version 1]
                                        :updated-at [:now]}})
        true))))

(defn- delete-session!
  "Delete a session by ID."
  [conn session-id]
  (let [result (core/execute! conn
                              {:delete-from :statechart-sessions
                               :where [:= :session-id (core/session-id->str session-id)]})]
    (when (pos? (core/affected-row-count result))
      (log/debug "Session deleted"
                 {:session-id session-id}))
    true))

;; -----------------------------------------------------------------------------
;; WorkingMemoryStore Implementation
;; -----------------------------------------------------------------------------

(defrecord PostgresWorkingMemoryStore [pool]
  sp/WorkingMemoryStore
  (get-working-memory [_ _env session-id]
    (if (pool/pool? pool)
      (pg/with-connection [c pool]
        (fetch-session c session-id))
      (fetch-session pool session-id)))

  (save-working-memory! [_ _env session-id wmem]
    (if (pool/pool? pool)
      (pg/with-connection [c pool]
        (upsert-session! c session-id wmem))
      (upsert-session! pool session-id wmem)))

  (delete-working-memory! [_ _env session-id]
    (if (pool/pool? pool)
      (pg/with-connection [c pool]
        (delete-session! c session-id))
      (delete-session! pool session-id))))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn new-store
  "Create a new PostgreSQL-backed working memory store.

   pool - pg2 connection pool"
  [pool]
  (->PostgresWorkingMemoryStore pool))

;; -----------------------------------------------------------------------------
;; Retry Helper
;; -----------------------------------------------------------------------------

(defn with-optimistic-retry
  "Execute f with automatic retry on optimistic lock failure.

   Options:
   - :max-retries - Maximum number of retries (default 3)
   - :backoff-ms - Initial backoff in ms (default 50, doubles each retry)"
  ([f] (with-optimistic-retry {} f))
  ([{:keys [max-retries backoff-ms]
     :or {max-retries 3
          backoff-ms 50}} f]
   (loop [attempt 1]
     (let [result (try
                    {:ok (f)}
                    (catch clojure.lang.ExceptionInfo e
                      (if (and (optimistic-lock-failure? e)
                               (< attempt max-retries))
                        (let [backoff (* backoff-ms (long (Math/pow 2 (dec attempt))))]
                          (log/debug "Retrying after optimistic lock failure"
                                     {:attempt attempt
                                      :max-retries max-retries
                                      :backoff-ms backoff
                                      :session-id (:session-id (ex-data e))})
                          {:retry true :backoff backoff})
                        (throw e))))]
       (if (:retry result)
         (do
           (Thread/sleep (:backoff result))
           (recur (inc attempt)))
         (:ok result))))))
