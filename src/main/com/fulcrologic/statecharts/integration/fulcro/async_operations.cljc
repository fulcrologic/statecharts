(ns com.fulcrologic.statecharts.integration.fulcro.async-operations
  "Async-aware Fulcro operations for use with the async execution engine.

   Provides two layers:

   **Data-map constructors** (primary API) — `load` and `invoke-remote` return composable
   operation maps (`{:op :fulcro/async-load ...}`) that can be mixed into the same vector as
   sync operations like `ops/assign`. These are processed by the async execution engine via
   `run-async-op!`.

   **Promise helpers** (advanced) — `await-load` and `await-mutation` are imperative functions
   that return promesa promises directly. They are used internally by the data-map handlers and
   are available for complex custom expressions.

   Requires promesa on the classpath and the async execution engine (`:async? true` in
   `install-fulcro-statecharts!`).

   Typically aliased as `afop`:

   ```clojure
   (require '[com.fulcrologic.statecharts.integration.fulcro.async-operations :as afop])
   ```"
  (:refer-clojure :exclude [load])
  (:require
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.environment :as senv]
    [com.fulcrologic.statecharts.execution-model.lambda-async :as lambda-async]
    [com.fulcrologic.statecharts.integration.fulcro-impl :as impl]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.statecharts.util :refer [new-uuid now-ms]]
    [edn-query-language.core :as eql]
    [promesa.core :as p]
    [taoensso.timbre :as log])
  #?(:clj (:import [com.fulcrologic.statecharts.execution_model.lambda_async AsyncCLJCExecutionModel])))

(defn- async-engine?
  "Returns true if `env` is using the async execution model."
  [env]
  (instance? #?(:clj  AsyncCLJCExecutionModel
                :cljs lambda-async/AsyncCLJCExecutionModel)
    (::sc/execution-model env)))

(defonce ^:private pending-mutation-callbacks (atom {}))

(defn- take-callback!
  "Atomically retrieves and removes a callback by `callback-id`."
  [callback-id]
  (let [result (volatile! nil)]
    (swap! pending-mutation-callbacks
      (fn [m]
        (vreset! result (get m callback-id))
        (dissoc m callback-id)))
    @result))

(defn cleanup-stale-callbacks!
  "Removes callbacks older than `max-age-ms` (default 5 minutes). Call periodically or on
   chart teardown to prevent memory leaks from mutations that never complete."
  ([] (cleanup-stale-callbacks! 300000))
  ([max-age-ms]
   (let [cutoff (- (now-ms) max-age-ms)]
     (swap! pending-mutation-callbacks
       (fn [m] (into {} (remove (fn [[_ v]] (when-let [t (:created-at v)] (< t cutoff)))) m))))))

(def ^:private await-mutation-delegate (m/->Mutation `await-mutation-delegate))

(let [mtrigger! (fn await-mutation-trigger* [{:keys [result]} callback-id]
                  (when-let [{:keys [env ok-event ok-data resolve]} (take-callback! callback-id)]
                    (let [event-data {:fulcro/mutation-result result}]
                      (when ok-event
                        (senv/raise env ok-event (merge (or ok-data {}) event-data)))
                      (resolve true))))]
  (defmethod m/mutate `await-mutation-delegate [{:keys [state ast] :as _mut-env}]
    (let [{:keys [txn target returning session-id callback-id
                  mutation-remote]} (:params ast)
          ast       (eql/query->ast1 txn)
          state-map @state
          target    (cond->> target
                      (keyword? target) (impl/resolve-alias-path state-map session-id)
                      (vector? target) (impl/resolve-actors-in-path state-map session-id))
          returning (cond
                      (keyword? returning) (some->
                                             (get-in state-map (impl/local-data-path session-id :fulcro/actors returning :component))
                                             (rc/registry-key->class))
                      :else returning)]
      {(or mutation-remote :remote) (fn [env]
                                      (let [env (assoc env :ast ast)]
                                        (cond-> env
                                          returning (m/returning returning)
                                          target (m/with-target target))))
       :result-action               m/default-result-action!
       :ok-action                   (fn [env]
                                      (mtrigger! env callback-id))
       :error-action                (fn [mut-env]
                                      (when-let [{:keys [env error-event error-data resolve]} (take-callback! callback-id)]
                                        (let [event-data {:fulcro/mutation-result (:result mut-env)}]
                                          (when error-event
                                            (senv/raise env error-event (merge (or error-data {}) event-data)))
                                          (resolve true))))})))

;; =============================================================================
;; Data-map constructors (primary API)
;; =============================================================================

(defn load
  "Return a composable async load operation map. Use this in expression result vectors alongside
   sync operations like `ops/assign`.

   Parameters:
   * `query-root` - A keyword or ident that will be the root of the remote query
   * `component-or-actor` - A Fulcro component class, or a keyword naming a known actor
   * `options` - Map with optional keys:
     * `::sc/ok-event` - Event to raise when the load succeeds
     * `::sc/error-event` - Event to raise when the load fails
     * `::sc/ok-data` / `::sc/error-data` - Extra data to include in events
     * `::sc/target-alias` - Target the load at a statechart alias
     * Other `df/load!` options (`:marker`, `:remote`, etc.)

   Example:
   ```clojure
   (fn [env data]
     [(ops/assign :loading? true)
      (afop/load :items Item {::sc/ok-event :items-loaded})])
   ```"
  [query-root component-or-actor options]
  {:op                 :fulcro/async-load
   ::sc/async?         true
   :query-root         query-root
   :component-or-actor component-or-actor
   :options            options})

(defn invoke-remote
  "Return a composable async remote mutation operation map. Use this in expression result vectors
   alongside sync operations like `ops/assign`.

   Parameters:
   * `txn` - A Fulcro transaction vector containing a single mutation
   * `options` - Map with optional keys:
     * `:ok-event` - Event to raise on success
     * `:error-event` - Event to raise on failure
     * `:ok-data` / `:error-data` - Extra data to include in events
     * `:target` - Data-targeting path
     * `:returning` - Component class or actor keyword for normalization
     * `:mutation-remote` - Remote name (defaults to `:remote`)
     * `:tx-options` - Options passed to `rc/transact!`

   Example:
   ```clojure
   (fn [env data]
     [(ops/assign :saving? true)
      (afop/invoke-remote `[(save-item ~params)] {:ok-event :saved})])
   ```"
  [txn options]
  (merge options
    {:op         :fulcro/async-mutation
     ::sc/async? true
     :txn        txn}))

;; =============================================================================
;; Promise helpers (advanced / internal)
;; =============================================================================

(defn await-load
  "Issue a load from a remote, returning a promesa promise that resolves when the load completes.

   Unlike `fop/load`, which sends completion events on the external queue (causing the chart to rest
   in an intermediate state), this function uses `raise` to place the completion event on the
   internal queue. Combined with the async execution engine, the chart transitions atomically
   within the same `process-event!` call.

   Parameters:
   * `env` - The statechart environment (first argument to expressions)
   * `query-root` - A keyword or ident that will be the root of the remote query
   * `component-or-actor` - A Fulcro component class, or a keyword naming a known actor
   * `options` - Map with optional keys (same as `fop/load`):
     * `:com.fulcrologic.statecharts/ok-event` - Event to raise when the load succeeds
     * `:com.fulcrologic.statecharts/error-event` - Event to raise when the load fails
     * `:com.fulcrologic.statecharts/ok-data` - Extra data to include in the ok event
     * `:com.fulcrologic.statecharts/error-data` - Extra data to include in the error event
     * `:com.fulcrologic.statecharts/target-alias` - Target the load at a statechart alias
     * Other `df/load!` options (`:marker`, `:remote`, etc.)

   Requires the async execution engine (install with `:async? true`)."
  [env query-root component-or-actor {:keys [] :as options}]
  (when-not (async-engine? env)
    (throw (ex-info "await-load requires the async execution engine. Install with :async? true." {})))
  (let [app        (:fulcro/app env)
        session-id (senv/session-id env)
        state-map  (rapp/current-state app)
        local-data (get-in state-map (impl/local-data-path session-id))
        {::sc/keys [ok-event ok-data error-event error-data target-alias]} options
        component  (if-let [component-name (and
                                             (keyword? component-or-actor)
                                             (get-in local-data [:fulcro/actors component-or-actor :component]))]
                     (rc/registry-key->class component-name)
                     (rc/registry-key->class component-or-actor))
        target     (when target-alias (impl/resolve-alias-path local-data target-alias))]
    (p/create
      (fn [resolve _reject]
        (let [ok-action    (fn [{:keys [load-params] :as load-env}]
                             (df/finish-load! load-env (dissoc load-params :ok-action))
                             (when ok-event
                               (senv/raise env ok-event (or ok-data {})))
                             (resolve true))
              error-action (fn [{:keys [load-params] :as load-env}]
                             (df/load-failed! load-env (dissoc load-params :error-action))
                             (when error-event
                               (senv/raise env error-event (or error-data {})))
                             (resolve true))
              params       (cond-> (dissoc options ::sc/ok-event ::sc/error-event ::sc/error-data ::sc/ok-data ::sc/target-alias)
                             true (assoc :ok-action ok-action :error-action error-action)
                             target (assoc :target target))]
          (df/load! app query-root component params))))))

(defn await-mutation
  "Invoke a remote mutation, returning a promesa promise that resolves when the mutation completes.

   Unlike `fop/invoke-remote`, which sends completion events on the external queue, this function
   uses `raise` to place the completion event on the internal queue. Combined with the async
   execution engine, the chart transitions atomically within the same `process-event!` call.

   Parameters:
   * `env` - The statechart environment (first argument to expressions)
   * `txn` - A Fulcro transaction vector containing a single mutation
   * `options` - Map with optional keys (same as `fop/invoke-remote`):
     * `:ok-event` - Event to raise on success
     * `:error-event` - Event to raise on failure
     * `:ok-data` - Extra data to include in the ok event
     * `:error-data` - Extra data to include in the error event
     * `:target` - Data-targeting path (vector, alias keyword, or actor-prefixed path)
     * `:returning` - Component class or actor keyword for normalization
     * `:mutation-remote` - Remote name (defaults to `:remote`)
     * `:tx-options` - Options passed to `rc/transact!`

   Requires the async execution engine (install with `:async? true`)."
  [env txn {:keys [target returning ok-event error-event ok-data error-data mutation-remote
                   tx-options]
            :as   options}]
  (when-not (async-engine? env)
    (throw (ex-info "await-mutation requires the async execution engine. Install with :async? true." {})))
  (let [app         (:fulcro/app env)
        session-id  (senv/session-id env)
        callback-id (new-uuid)]
    (p/create
      (fn [resolve _reject]
        (swap! pending-mutation-callbacks assoc callback-id
          {:env         env
           :ok-event    ok-event
           :error-event error-event
           :ok-data     ok-data
           :error-data  error-data
           :resolve     resolve
           :created-at  (now-ms)})
        (rc/transact! app [(await-mutation-delegate
                             (merge (dissoc options :tx-options)
                               {:txn         txn
                                :session-id  session-id
                                :callback-id callback-id}))]
          (or tx-options {}))))))

;; =============================================================================
;; Async op dispatch (registered on lambda-async/run-async-op!)
;; =============================================================================

(defmethod lambda-async/run-async-op! :fulcro/async-load
  [env {:keys [query-root component-or-actor options]}]
  (await-load env query-root component-or-actor options))

(defmethod lambda-async/run-async-op! :fulcro/async-mutation
  [env {:keys [txn] :as op}]
  (await-mutation env txn (dissoc op :op ::sc/async? :txn)))
