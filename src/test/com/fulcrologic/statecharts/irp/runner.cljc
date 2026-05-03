(ns com.fulcrologic.statecharts.irp.runner
  "Helpers for running W3C SCXML IRP tests against this library.

   IRP success criterion: the chart eventually reaches a `final` state with id `:pass`."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.simple :as simple]
    [com.fulcrologic.statecharts.util :refer [new-uuid]]))

(defn- env+session
  []
  (let [env        (simple/strict-env)
        session-id (new-uuid)]
    [env session-id]))

(defn- save! [{::sc/keys [working-memory-store] :as env} sid wmem]
  (sp/save-working-memory! working-memory-store env sid wmem))

(defn- load! [{::sc/keys [working-memory-store] :as env} sid]
  (sp/get-working-memory working-memory-store env sid))

(defn- in? [wmem state-id]
  (boolean (some-> wmem ::sc/configuration (contains? state-id))))

(defn- track! [seen wmem]
  (let [cfg (::sc/configuration wmem)]
    (when (contains? cfg :pass) (vswap! seen conj :pass))
    (when (contains? cfg :fail) (vswap! seen conj :fail))))

(defn run
  "Register `chart` (under a unique key), start it, then send each event in `events`
   in order. Each `e` may be a keyword (event name) or a map for `evts/new-event`.
   After each step, also drain any delayed events from the manual queue.

   Returns the final working memory, augmented with `::reached-finals` containing
   any `:pass`/`:fail` final states observed at any point (including just before
   the interpreter shut down)."
  [chart events]
  (let [[env sid] (env+session)
        chart-key (keyword "irp" (str (gensym "chart-")))
        proc      (::sc/processor env)
        seen      (volatile! #{})]
    (simple/register! env chart-key chart)
    (let [w (sp/start! proc env chart-key {::sc/session-id sid})]
      (track! seen w)
      (save! env sid w))
    (let [eq (::sc/event-queue env)
          step! (fn [evt]
                  (let [w (sp/process-event! proc env (load! env sid) evt)]
                    (track! seen w)
                    (save! env sid w)))]
      (sp/receive-events! eq env (fn [_ e] (step! e)) {:session-id sid})
      (doseq [e events]
        (let [evt (if (map? e) (evts/new-event e) (evts/new-event {:name e}))]
          (step! evt))
        (sp/receive-events! eq env (fn [_ e2] (step! e2)) {:session-id sid})))
    (assoc (load! env sid) ::reached-finals @seen)))

(defn run-with-delays
  "Like `run`, but polls the event queue for delayed events for up to
   `timeout-ms` (default 3000) after start and after each external event,
   stopping early once a top-level `:pass` or `:fail` final has been observed.
   Required for IRP tests that use `delay`/`delayexpr` on `<send>`."
  ([chart events] (run-with-delays chart events 3000))
  ([chart events timeout-ms]
   (let [[env sid] (env+session)
         chart-key (keyword "irp" (str (gensym "chart-")))
         proc      (::sc/processor env)
         eq        (::sc/event-queue env)
         seen      (volatile! #{})
         step!     (fn [evt]
                     (let [w (sp/process-event! proc env (load! env sid) evt)]
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
     (simple/register! env chart-key chart)
     (let [w (sp/start! proc env chart-key {::sc/session-id sid})]
       (track! seen w)
       (save! env sid w))
     (poll!)
     (doseq [e events]
       (let [evt (if (map? e) (evts/new-event e) (evts/new-event {:name e}))]
         (step! evt))
       (poll!))
     (assoc (load! env sid) ::reached-finals @seen))))

(defn passes-with-delays?
  "True iff the chart drives itself to `:pass` while polling for delayed events."
  ([chart] (passes-with-delays? chart []))
  ([chart events] (contains? (::reached-finals (run-with-delays chart events)) :pass)))

(defn passes?
  "True iff the chart drives itself to the `:pass` final state given `events`."
  ([chart] (passes? chart []))
  ([chart events] (contains? (::reached-finals (run chart events)) :pass)))

(defn fails?
  "True iff the chart enters the `:fail` final state."
  ([chart] (fails? chart []))
  ([chart events] (contains? (::reached-finals (run chart events)) :fail)))
