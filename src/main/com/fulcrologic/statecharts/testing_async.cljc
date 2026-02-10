(ns com.fulcrologic.statecharts.testing-async
  "Utility functions to help with testing state charts using the async processor.
   Works with both sync and async expressions. On CLJ, promises can be deref'd
   for blocking in tests."
  (:require
    [clojure.set :as set]
    [clojure.test :refer [is]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.algorithms.v20150901-async-impl :refer [configuration-problems]]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.working-memory-data-model :refer [new-flat-model]]
    [com.fulcrologic.statecharts.events :refer [new-event]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.simple-async :as simple-async]
    [com.fulcrologic.statecharts.testing :as testing]
    [promesa.core :as p]
    [taoensso.timbre :as log]))

(defn- resolve-maybe-promise
  "If `v` is a promise, deref it (blocking on CLJ). On CLJS this assumes the promise
   has already resolved (for use in sync test contexts with sync expressions)."
  [v]
  (if (p/promise? v)
    #?(:clj  @v
       :cljs v)
    v))

(defn- handle-result
  "Process expression `result`, applying data model updates if it's a vector of ops.
   If `result` is a promise, chains the handling via `p/then`."
  [data-model env result]
  (let [apply-update (fn [v]
                       (when (and (not (::sc/raw-result? env)) (vector? v))
                         (log/trace "trying vector result as a data model update" v)
                         (sp/update! data-model env {:ops v}))
                       v)]
    (if (p/promise? result)
      (p/then result apply-update)
      (apply-update result))))

(defrecord AsyncMockExecutionModel [data-model
                                    expressions-seen
                                    call-counts
                                    mocks
                                    options]
  testing/Clearable
  (clear! [_]
    (reset! expressions-seen [])
    (reset! call-counts {}))
  testing/ExecutionChecks
  (ran? [_ fref] (contains? @call-counts fref))
  (ran-in-order? [_ frefs]
    (loop [remaining @expressions-seen
           to-find   frefs]
      (let [first-element (first to-find)
            found?        (boolean (seq (filter #(= % first-element) remaining)))
            remainder     (drop-while #(not= first-element %) remaining)
            left-to-find  (rest to-find)]
        (cond
          (and found? (empty? left-to-find)) true
          (and found? (seq left-to-find) (seq remainder)) (recur remainder left-to-find)
          :else false))))
  sp/ExecutionModel
  (run-expression! [_model env expr]
    (let [{:keys [run-unmocked?]} options
          raw-result? (::sc/raw-result? env)]
      (swap! expressions-seen conj expr)
      (swap! call-counts update expr (fnil inc 0))
      (cond
        (fn? (get @mocks expr)) (let [env     (assoc env :ncalls (get @call-counts expr))
                                      expr    (get @mocks expr)
                                      data    (sp/current-data data-model env)
                                      result  (log/spy :trace "expr => " (expr env data))]
                                  (handle-result data-model env result))
        (contains? @mocks expr) (get @mocks expr)
        (and run-unmocked? (fn? expr)) (let [env    (assoc env :ncalls (get @call-counts expr))
                                             data   (sp/current-data data-model env)
                                             result (try
                                                      (log/spy :trace "expr => " (expr env data))
                                                      (catch #?(:clj Throwable :cljs :default) e
                                                        (log/error e "Expression threw")
                                                        (throw e)))]
                                         (handle-result data-model env result))
        (not (fn? expr)) expr))))

(defn new-async-mock-execution
  "Create an async-aware mock execution model. Like `testing/new-mock-execution` but handles
   expressions that return promises by chaining data model updates via `p/then`."
  ([data-model]
   (->AsyncMockExecutionModel data-model (atom []) (atom {}) (atom {}) {:run-unmocked? false}))
  ([data-model mocks]
   (->AsyncMockExecutionModel data-model (atom []) (atom {}) (atom mocks) {}))
  ([data-model mocks options]
   (->AsyncMockExecutionModel data-model (atom []) (atom {}) (atom mocks) options)))

(defn new-testing-env
  "Returns a new testing object that uses the async processor. Configuration is the same
   as `testing/new-testing-env` but uses the async processor by default.

   The `mocks` parameter is a map from expression *value* to either a literal value or a
   `(fn [env])`. Mock return values may be promises for testing async behavior.

   See `testing/new-testing-env` for full documentation."
  [{:keys [statechart processor-factory data-model-factory event-queue validator session-id mocking-options]
    :or   {data-model-factory new-flat-model
           event-queue        (testing/new-mock-queue)
           validator          configuration-problems}} mocks]
  (assert statechart "Statechart is supplied")
  (let [data-model (data-model-factory)
        exec-model (new-async-mock-execution data-model (or mocks {}) mocking-options)
        env        (simple-async/simple-env (cond-> {:statechart                statechart
                                                     ::sc/execution-model       exec-model
                                                     ::sc/data-model            data-model
                                                     ::sc/invocation-processors [(testing/new-mock-invocations)]
                                                     ::sc/event-queue           event-queue}
                                              validator (assoc :configuration-validator validator)
                                              processor-factory (assoc ::sc/processor (processor-factory))))]
    (simple-async/register! env ::chart statechart)
    (testing/map->TestingEnv {:statechart statechart
                              :session-id (or session-id :test)
                              :env        env})))

(defn start!
  "Start the machine in the testing env. Handles async processor results.
   On CLJ, blocks until initialization is complete."
  [{:keys                                                [session-id]
    {::sc/keys [working-memory-store processor] :as env} :env}]
  (let [result (sp/start! processor env ::chart {::sc/session-id session-id})]
    (if (p/promise? result)
      (resolve-maybe-promise
        (p/then result
          (fn [s0]
            (sp/save-working-memory! working-memory-store env session-id s0))))
      (sp/save-working-memory! working-memory-store env session-id result))))

(defn run-events!
  "Run the given sequence of events against the testing runtime. Returns the
   updated working memory. Handles async processor results (blocks on CLJ)."
  [{:keys                                                [session-id]
    {::sc/keys [processor working-memory-store] :as env} :env} & events]
  (doseq [e events]
    (let [wmem   (sp/get-working-memory working-memory-store env session-id)
          result (sp/process-event! processor env wmem (new-event e))]
      (if (p/promise? result)
        (resolve-maybe-promise
          (p/then result
            (fn [wmem2]
              (sp/save-working-memory! working-memory-store env session-id wmem2))))
        (sp/save-working-memory! working-memory-store env session-id result))))
  (sp/get-working-memory working-memory-store env session-id))

;; Delegate to the sync testing namespace for utilities that don't touch the processor
(def configuration-for-states testing/configuration-for-states)
(def goto-configuration! testing/goto-configuration!)
(def current-configuration testing/current-configuration)
(def in? testing/in?)
(def will-send testing/will-send)
(def data testing/data)

(defn running?
  "Check if the machine in the testing env is still running (has not reached a top-level final state)."
  [{:keys                                      [session-id]
    {::sc/keys [working-memory-store] :as env} :env}]
  (let [wmem (sp/get-working-memory working-memory-store env session-id)]
    (boolean (::sc/running? wmem))))
