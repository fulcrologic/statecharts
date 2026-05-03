(ns com.fulcrologic.statecharts.irp.runner
  "Helpers for running W3C SCXML IRP tests against this library.

   IRP success criterion: the chart eventually reaches a `final` state with id `:pass`.

   Each predicate (`passes?`, `fails?`, `passes-with-delays?`) runs the chart against
   BOTH the sync (`v20150901`) and the async (`v20150901-async`) processors, and only
   reports success if both agree. IRP charts use synchronous expressions only, so the
   async processor's `maybe-then` machinery stays synchronous end-to-end — both CLJ and
   CLJS test runs exercise both code paths."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.simple :as simple]
    [com.fulcrologic.statecharts.simple-async :as simple-async]
    [com.fulcrologic.statecharts.util :refer [new-uuid]]
    [promesa.core :as p]))

(defn- await-value
  "If `v` is a promise, deref it on CLJ (the IRP suite uses only synchronous expressions
   so the async processor resolves promises synchronously via `maybe-then`; on CLJS
   `p/promise?` is still true for resolved promesa promises, so we extract via `p/then`
   on a synchronously-resolved promesa promise where possible).

   Throws on CLJS if a true microtask-deferred promise sneaks through (which would
   indicate a non-synchronous expression in an IRP chart — none should exist)."
  [v]
  (if (p/promise? v)
    #?(:clj  (deref v 5000 ::timeout)
       :cljs (let [resolved (volatile! ::pending)]
               (p/then v (fn [x] (vreset! resolved x)))
               (let [r @resolved]
                 (if (= r ::pending)
                   (throw (ex-info "IRP runner: async processor returned an unresolved promise on CLJS — IRP charts must use synchronous expressions only." {}))
                   r))))
    v))

(defn- env+session
  [strict-env-fn]
  (let [env        (strict-env-fn)
        session-id (new-uuid)]
    [env session-id]))

(defn- async-strict-env
  "Async-capable strict env (W3C §4.4 block-error semantics on)."
  []
  (assoc (simple-async/simple-env) ::sc/errors-abort-siblings? true))

(defn- save! [{::sc/keys [working-memory-store] :as env} sid wmem]
  (sp/save-working-memory! working-memory-store env sid wmem))

(defn- load! [{::sc/keys [working-memory-store] :as env} sid]
  (sp/get-working-memory working-memory-store env sid))

(defn- track! [seen wmem]
  (let [cfg (::sc/configuration wmem)]
    (when (contains? cfg :pass) (vswap! seen conj :pass))
    (when (contains? cfg :fail) (vswap! seen conj :fail))))

(defn- run*
  "Run `chart` with `events` against the env produced by `strict-env-fn` (sync or async).
   Returns final working memory with `::reached-finals` augmented."
  [strict-env-fn register-fn chart events]
  (let [[env sid] (env+session strict-env-fn)
        chart-key (keyword "irp" (str (gensym "chart-")))
        proc      (::sc/processor env)
        seen      (volatile! #{})
        step!     (fn [evt]
                    (let [w (await-value (sp/process-event! proc env (load! env sid) evt))]
                      (track! seen w)
                      (save! env sid w)))]
    (register-fn env chart-key chart)
    (let [w (await-value (sp/start! proc env chart-key {::sc/session-id sid}))]
      (track! seen w)
      (save! env sid w))
    (let [eq (::sc/event-queue env)]
      (sp/receive-events! eq env (fn [_ e] (step! e)) {:session-id sid})
      (doseq [e events]
        (let [evt (if (map? e) (evts/new-event e) (evts/new-event {:name e}))]
          (step! evt))
        (sp/receive-events! eq env (fn [_ e2] (step! e2)) {:session-id sid})))
    (assoc (load! env sid) ::reached-finals @seen)))

(defn- run-with-delays*
  [strict-env-fn register-fn chart events timeout-ms]
  (let [[env sid] (env+session strict-env-fn)
        chart-key (keyword "irp" (str (gensym "chart-")))
        proc      (::sc/processor env)
        eq        (::sc/event-queue env)
        seen      (volatile! #{})
        step!     (fn [evt]
                    (let [w (await-value (sp/process-event! proc env (load! env sid) evt))]
                      (track! seen w)
                      (save! env sid w)))
        done?     (fn [] (or (contains? @seen :pass) (contains? @seen :fail)))
        poll!     (fn []
                    #?(:clj
                       (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
                         (loop []
                           (sp/receive-events! eq env (fn [_ e] (step! e)) {:session-id sid})
                           (when (and (not (done?)) (< (System/currentTimeMillis) deadline))
                             (Thread/sleep 20)
                             (recur))))
                       :cljs
                       (sp/receive-events! eq env (fn [_ e] (step! e)) {:session-id sid})))]
    (register-fn env chart-key chart)
    (let [w (await-value (sp/start! proc env chart-key {::sc/session-id sid}))]
      (track! seen w)
      (save! env sid w))
    (poll!)
    (doseq [e events]
      (let [evt (if (map? e) (evts/new-event e) (evts/new-event {:name e}))]
        (step! evt))
      (poll!))
    (assoc (load! env sid) ::reached-finals @seen)))

(defn run
  "Run the chart through the SYNC processor. Returns final working memory with
   `::reached-finals` containing any :pass/:fail finals observed."
  [chart events]
  (run* simple/strict-env simple/register! chart events))

(defn run-async
  "Run the chart through the ASYNC processor. Returns final working memory with
   `::reached-finals` containing any :pass/:fail finals observed."
  [chart events]
  (run* async-strict-env simple-async/register! chart events))

(defn run-with-delays
  "Like `run`, but polls for delayed events. Sync processor."
  ([chart events] (run-with-delays chart events 3000))
  ([chart events timeout-ms]
   (run-with-delays* simple/strict-env simple/register! chart events timeout-ms)))

(defn run-with-delays-async
  "Like `run-with-delays`, but uses the async processor."
  ([chart events] (run-with-delays-async chart events 3000))
  ([chart events timeout-ms]
   (run-with-delays* async-strict-env simple-async/register! chart events timeout-ms)))

;; -----------------------------------------------------------------------------
;; Promise-wrapping (force every expression through the async/promise path)
;; -----------------------------------------------------------------------------

(def ^:private expr-slots
  "Element attribute keys whose value, when a function, is invoked by the
   ExecutionModel as `(fn [env data] ...)` and may legally return a promise."
  #{:expr :cond :array :eventexpr :delayexpr :targetexpr :typeexpr :srcexpr :data})

(defn- wrap-fn-resolved
  "Wrap a `(fn [env data])` so it returns `(p/resolved (orig env data))`. If the
   original throws, the exception is re-thrown synchronously (matching W3C error
   semantics)."
  [orig]
  (fn [env data]
    (p/resolved (orig env data))))

#?(:clj
   (defn- wrap-fn-deferred
     "Wrap a `(fn [env data])` so it returns a microtask-deferred promise. Forces
      the algorithm down its true async branches (catches latent dropped-promise
      bugs that resolved-only tests miss). CLJ-only because CLJS test bodies
      cannot block on microtasks."
     [orig]
     (fn [env data]
       (-> (p/delay 1)
         (p/then (fn [_] (orig env data)))))))

(defn- promise-walk
  "Return a copy of `chart` with every fn-valued expression slot wrapped via
   `wrap-fn`. Walks `::sc/elements-by-id` and the chart's :script (if any)."
  [chart wrap-fn]
  (let [wrap-element (fn [el]
                       (reduce (fn [el k]
                                 (let [v (get el k)]
                                   (if (fn? v) (assoc el k (wrap-fn v)) el)))
                         el expr-slots))]
    (-> chart
      (update ::sc/elements-by-id
        (fn [m] (into {} (map (fn [[k v]] [k (wrap-element v)])) m))))))

(defn run-async-resolved
  "Run the chart under the async processor with every expression wrapped to return
   `(p/resolved …)`. Forces algorithm paths that handle promises."
  [chart events]
  (run* async-strict-env simple-async/register! (promise-walk chart wrap-fn-resolved) events))

(defn run-with-delays-async-resolved
  ([chart events] (run-with-delays-async-resolved chart events 3000))
  ([chart events timeout-ms]
   (run-with-delays* async-strict-env simple-async/register! (promise-walk chart wrap-fn-resolved) events timeout-ms)))

#?(:clj
   (defn run-async-deferred
     "CLJ-only. Run the chart under the async processor with every expression wrapped
      to return a microtask-deferred promise. Catches latent dropped-promise bugs."
     [chart events]
     (run* async-strict-env simple-async/register! (promise-walk chart wrap-fn-deferred) events)))

#?(:clj
   (defn run-with-delays-async-deferred
     ([chart events] (run-with-delays-async-deferred chart events 3000))
     ([chart events timeout-ms]
      (run-with-delays* async-strict-env simple-async/register! (promise-walk chart wrap-fn-deferred) events timeout-ms))))

(defn- finals-of [run-result] (::reached-finals run-result))

(defn- pass-set [run-result] (contains? (finals-of run-result) :pass))
(defn- fail-set [run-result] (contains? (finals-of run-result) :fail))

(defn passes?
  "True iff the chart drives itself to `:pass` under: sync, async (sync exprs),
   async-resolved-promise exprs, and (CLJ only) async-deferred-promise exprs."
  ([chart] (passes? chart []))
  ([chart events]
   (and (pass-set (run chart events))
        (pass-set (run-async chart events))
        (pass-set (run-async-resolved chart events))
        #?(:clj (pass-set (run-async-deferred chart events))
           :cljs true))))

(defn fails?
  "True iff the chart enters `:fail` under all processors/expression flavors."
  ([chart] (fails? chart []))
  ([chart events]
   (and (fail-set (run chart events))
        (fail-set (run-async chart events))
        (fail-set (run-async-resolved chart events))
        #?(:clj (fail-set (run-async-deferred chart events))
           :cljs true))))

(defn passes-with-delays?
  "True iff the chart reaches `:pass` (with delayed-event polling) under all flavors."
  ([chart] (passes-with-delays? chart []))
  ([chart events]
   (and (pass-set (run-with-delays chart events))
        (pass-set (run-with-delays-async chart events))
        (pass-set (run-with-delays-async-resolved chart events))
        #?(:clj (pass-set (run-with-delays-async-deferred chart events))
           :cljs true))))
