(ns com.fulcrologic.statecharts.irp.runner
  "Helpers for running W3C SCXML IRP tests against this library.

   IRP success criterion: the chart eventually reaches a `final` state with id `:pass`.

   Each predicate (`passes?`, `fails?`, `passes-with-delays?`) runs the chart under several flavors and only
   reports success if all agree:

   * the sync processor (`v20150901`)
   * the async processor (`v20150901-async`) with plain-value expressions
   * the async processor with every expression returning an already-resolved promise
   * the async processor with every expression returning a deferred (not yet resolved) promise

   On CLJ the runner blocks on each promise, so the predicates return booleans. On CLJS nothing can block, so the
   predicates return a promise of the boolean, and tests must wait on it with `async-specification`."
  #?(:cljs (:require-macros [com.fulcrologic.statecharts.irp.runner]))
  (:require
    #?(:cljs [cljs.test])
    #?(:clj [clojure.string :as str])
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.simple :as simple]
    [com.fulcrologic.statecharts.simple-async :as simple-async]
    [com.fulcrologic.statecharts.util :refer [new-uuid now-ms]]
    [fulcro-spec.core]
    [promesa.core :as p]))

;; -----------------------------------------------------------------------------
;; Promise plumbing: block on CLJ, chain on CLJS
;; -----------------------------------------------------------------------------

(defn- then*
  "Calls `(f value)` once `v` is available. On CLJ a promise `v` is blocked on (5s timeout) and the result of `f` is
   returned directly. On CLJS a promise `v` is chained with `p/then`, so the result is a promise."
  [v f]
  #?(:clj  (f (if (p/promise? v) (deref v 5000 ::timeout) v))
     :cljs (if (p/promise? v) (p/then v f) (f v))))

(defn- run-each
  "Calls `(f item)` for each of `items` in order, waiting for any promise `f` returns before the next call. Returns the
   last result (a promise on CLJS when any step was async)."
  [items f]
  (reduce (fn [prior item] (then* prior (fn [_] (f item)))) nil items))

(defn- sleep
  "Waits `ms` before calling `(f)`: blocks on CLJ, uses a timer on CLJS."
  [ms f]
  #?(:clj  (do (Thread/sleep (long ms)) (f))
     :cljs (p/then (p/delay ms) (fn [_] (f)))))

(defn resolve-all
  "Returns a promise of the vector of values of `thunks`, each called in order after the previous value resolves.
   Used by `async-assertions`."
  [thunks]
  (reduce (fn [acc thunk]
            (p/then acc (fn [values]
                          ;; Calling `thunk` inside `p/then` turns a synchronous throw into a rejection.
                          (-> (p/resolved nil)
                            (p/then (fn [_] (thunk)))
                            (p/then #(conj values %))))))
    (p/resolved [])
    thunks))

;; -----------------------------------------------------------------------------
;; Running a chart
;; -----------------------------------------------------------------------------

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

(defn- ready-events
  "Removes and returns the events currently deliverable to session `sid` on `event-queue`."
  [event-queue env sid]
  (let [batch (volatile! [])]
    (sp/receive-events! event-queue env (fn [_ e] (vswap! batch conj e)) {:session-id sid})
    @batch))

(defn- ->event [e] (if (map? e) (evts/new-event e) (evts/new-event {:name e})))

(defn with-helper-charts
  "Returns `chart` annotated so the runner also registers `helper-charts` (a map from registry key to chart) in each
   session's env, e.g. child charts the chart under test invokes by `:src`."
  [chart helper-charts]
  (vary-meta chart assoc ::helper-charts helper-charts))

(defn- session-runner
  "Registers `chart` in a fresh env from `strict-env-fn` and returns a map of fns that drive one session of it:

   * `:start!` - Starts the session.
   * `:step!` - `(fn [event])` processes one event.
   * `:poll!` - Processes the events that are currently deliverable, in order.
   * `:done?` - True once `:pass` or `:fail` has been entered.
   * `:result` - Final working memory with `::reached-finals` set to the finals observed.

   Every fn may return a promise on CLJS."
  [strict-env-fn register-fn chart]
  (let [env       (strict-env-fn)
        sid       (new-uuid)
        chart-key (keyword "irp" (str (gensym "chart-")))
        proc      (::sc/processor env)
        eq        (::sc/event-queue env)
        seen      (volatile! #{})
        record!   (fn [wmem] (track! seen wmem) (save! env sid wmem))
        step!     (fn [evt] (then* (sp/process-event! proc env (load! env sid) evt) record!))]
    (doseq [[k helper] (::helper-charts (meta chart))]
      (register-fn env k helper))
    (register-fn env chart-key chart)
    {:start! (fn [] (then* (sp/start! proc env chart-key {::sc/session-id sid}) record!))
     :step!  step!
     :poll!  (fn [] (run-each (ready-events eq env sid) step!))
     :done?  (fn [] (or (contains? @seen :pass) (contains? @seen :fail)))
     :result (fn [] (assoc (load! env sid) ::reached-finals @seen))}))

(defn- run*
  "Run `chart` with `events` against the env produced by `strict-env-fn` (sync or async), delivering only events that
   are ready immediately. Returns final working memory with `::reached-finals` augmented (a promise on CLJS)."
  [strict-env-fn register-fn chart events]
  (let [{:keys [start! step! poll! result]} (session-runner strict-env-fn register-fn chart)]
    (-> (start!)
      (then* (fn [_] (poll!)))
      (then* (fn [_] (run-each events (fn [e] (then* (step! (->event e)) (fn [_] (poll!)))))))
      (then* (fn [_] (result))))))

(defn- run-with-delays*
  "Like `run*`, but each poll keeps delivering events (including delayed ones) until the chart enters `:pass` or
   `:fail`, or `timeout-ms` elapses."
  [strict-env-fn register-fn chart events timeout-ms]
  (let [{:keys [start! step! poll! done? result]} (session-runner strict-env-fn register-fn chart)
        poll-until-done! (fn poll-until-done! [deadline]
                           (then* (poll!)
                             (fn [_]
                               (when (and (not (done?)) (< (now-ms) deadline))
                                 (sleep 20 #(poll-until-done! deadline))))))
        poll-with-delays! (fn [] (poll-until-done! (+ (now-ms) timeout-ms)))]
    (-> (start!)
      (then* (fn [_] (poll-with-delays!)))
      (then* (fn [_] (run-each events (fn [e] (then* (step! (->event e)) (fn [_] (poll-with-delays!)))))))
      (then* (fn [_] (result))))))

(defn run
  "Run the chart through the SYNC processor. Returns final working memory with
   `::reached-finals` containing any :pass/:fail finals observed (a promise on CLJS)."
  [chart events]
  (run* simple/strict-env simple/register! chart events))

(defn run-async
  "Run the chart through the ASYNC processor. Returns final working memory with
   `::reached-finals` containing any :pass/:fail finals observed (a promise on CLJS)."
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

(defn- wrap-fn-deferred
  "Wrap a `(fn [env data])` so it returns a promise that resolves only after a timer
   tick. Forces the algorithm down its true async branches (catches latent
   dropped-promise bugs that resolved-only tests miss)."
  [orig]
  (fn [env data]
    (-> (p/delay 1)
      (p/then (fn [_] (orig env data))))))

(defn- promise-walk
  "Return a copy of `chart` with every fn-valued expression slot wrapped via
   `wrap-fn`. Walks `::sc/elements-by-id`."
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

(defn run-async-deferred
  "Run the chart under the async processor with every expression wrapped to return a
   deferred promise. Catches latent dropped-promise bugs."
  [chart events]
  (run* async-strict-env simple-async/register! (promise-walk chart wrap-fn-deferred) events))

(defn run-with-delays-async-deferred
  ([chart events] (run-with-delays-async-deferred chart events 3000))
  ([chart events timeout-ms]
   (run-with-delays* async-strict-env simple-async/register! (promise-walk chart wrap-fn-deferred) events timeout-ms)))

;; -----------------------------------------------------------------------------
;; Predicates
;; -----------------------------------------------------------------------------

(defn- finals-of [run-result] (::reached-finals run-result))

(defn- pass-set [run-result] (contains? (finals-of run-result) :pass))
(defn- fail-set [run-result] (contains? (finals-of run-result) :fail))

(defn- all-flavors?
  "Runs each of `run-thunks` in order, stopping at the first whose result fails `ok?`. Returns true iff all pass
   (a promise of that on CLJS)."
  [ok? run-thunks]
  (reduce (fn [prior run-thunk]
            (then* prior (fn [ok-so-far?] (if ok-so-far? (then* (run-thunk) ok?) false))))
    true
    run-thunks))

(defn passes?
  "True iff the chart drives itself to `:pass` under: sync, async (sync exprs),
   async-resolved-promise exprs, and async-deferred-promise exprs. Returns a promise of the answer on CLJS."
  ([chart] (passes? chart []))
  ([chart events]
   (all-flavors? pass-set
     [#(run chart events)
      #(run-async chart events)
      #(run-async-resolved chart events)
      #(run-async-deferred chart events)])))

(defn fails?
  "True iff the chart enters `:fail` under all processors/expression flavors. Returns a promise of the answer on CLJS."
  ([chart] (fails? chart []))
  ([chart events]
   (all-flavors? fail-set
     [#(run chart events)
      #(run-async chart events)
      #(run-async-resolved chart events)
      #(run-async-deferred chart events)])))

(defn passes-with-delays?
  "True iff the chart reaches `:pass` (with delayed-event polling) under all flavors. Returns a promise of the answer
   on CLJS."
  ([chart] (passes-with-delays? chart []))
  ([chart events]
   (all-flavors? pass-set
     [#(run-with-delays chart events)
      #(run-with-delays-async chart events)
      #(run-with-delays-async-resolved chart events)
      #(run-with-delays-async-deferred chart events)])))

;; -----------------------------------------------------------------------------
;; Test macro
;; -----------------------------------------------------------------------------

#?(:clj
   (defn- test-symbol
     "Returns a deftest var name derived from the specification `title`."
     [title]
     (symbol (str "__" (str/replace title #"[^A-Za-z0-9-]+" "-") "__"))))

#?(:clj
   (defn- async-assertions-form
     "Returns a `cljs.test/async` form that waits for every actual (left-hand) value of the fulcro-spec
      `assertion-forms` before asserting them with `fulcro-spec.core/assertions`."
     [assertion-forms]
     (let [done (gensym "done")
           [rewritten actuals]
           (loop [remaining assertion-forms rewritten [] actuals []]
             (cond
               (empty? remaining) [rewritten actuals]
               (string? (first remaining)) (recur (rest remaining) (conj rewritten (first remaining)) actuals)
               :else (let [[actual arrow expected & more] remaining
                           value-sym (gensym "actual")]
                       (recur more (conj rewritten value-sym arrow expected) (conj actuals [value-sym actual])))))]
       `(cljs.test/async ~done
          (-> (resolve-all [~@(map (fn [[_ actual]] `(fn [] ~actual)) actuals)])
            (p/then (fn [[~@(map first actuals)]]
                      (fulcro-spec.core/assertions ~@rewritten)
                      (~done)))
            (p/catch (fn [e#]
                       (cljs.test/do-report {:type     :error
                                             :message  "async-specification: evaluating an actual value threw"
                                             :expected nil
                                             :actual   e#})
                       (~done))))))))

#?(:clj
   (defmacro async-specification
     "A fulcro-spec specification titled `title` whose body is `fulcro-spec.core/assertions` of `assertion-forms`,
      where the actual (left-hand) side of each `actual => expected` may be a promise, as the runner predicates are on
      CLJS.

      On CLJ this is exactly `(specification title (assertions ...))`, because the runner blocks. On CLJS it is a
      `cljs.test/deftest` whose body is a `cljs.test/async` test. fulcro-spec's `specification` cannot be used there:
      it discards its body's value, so an async body inside it never runs and the test passes vacuously."
     [title & assertion-forms]
     (if-not (:ns &env)
       `(fulcro-spec.core/specification ~title (fulcro-spec.core/assertions ~@assertion-forms))
       `(cljs.test/deftest ~(test-symbol title)
          ~(async-assertions-form assertion-forms)))))
