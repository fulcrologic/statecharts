(ns com.fulcrologic.statecharts.integration.fulcro
  "Support for statecharts in Fulcro. The statechart support defined here has the following attributes:

  * It installs the statechart components (event queue, etc) on a Fulcro app. Thus, apps have their own private
  statechart system.
  * The statechart registry is also stored in the app.
  * The statechart working session is normalized into Fulcro's app state at [::id session-id], which makes it easy
    to include a statechart in a component query if you want to cause rendering changes based on state changes.
  * The working sessions are automatically GC'd from the state map when they reach a final state.
  * The data model of the charts has two layers:
    * Statechart local: Arbitrary operation keys/paths are local to the statechart's working session
    * Fulcro state map: Any path starting with `[:fulcro/state-map ...]` or `[:fulcro/state ...]` is a path relative to the root of the entire Fulcro state map.

  The data passed to executable content will include the standard `:_event`, the current data (local statechart data),
  and a `:fulcro/state-map` key that has the current snapshot of the Fulcro database.

  Returning ops from expressions would look like:

  ```
  [(op/assign [:fulcro/state-map :root-key] {:a 1})
   (op/assign [:local] 100)]
  ```

  would put `{:a 1}` in the fulcro state map under the top-level `:root-key`, and would store the
  `:local` key under the state machine's session.

  Future expressions would see the `:local` key in the `data` argument, and would also find the full Fulcro
  state map in `:fulcro/state-map`.

  See also 'Integration with Fulcro' in Guide.adoc.
  "
  (:require
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.guardrails.malli.core :refer [=> >def >defn ?]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.algorithms.v20150901 :as alg]
    [com.fulcrologic.statecharts.algorithms.v20150901-async :as aalg]
    [com.fulcrologic.statecharts.environment]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.event-queue.core-async-event-loop :as cael]
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [com.fulcrologic.statecharts.execution-model.lambda :as lambda]
    [com.fulcrologic.statecharts.execution-model.lambda-async :as alambda]
    [com.fulcrologic.statecharts.integration.fulcro-impl :as impl]
    [com.fulcrologic.statecharts.invocation.statechart :as i.statechart]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.registry.local-memory-registry :as lmr]
    [com.fulcrologic.statecharts.util :refer [new-uuid]]
    ;; Use the statecharts promise shim (IS promesa on CLJ when present, native fallback otherwise)
    ;; so this ns loads under babashka, whose SCI cannot load promesa.
    [com.fulcrologic.statecharts.promise :as p]
    [taoensso.timbre :as log]))

(def local-data-path
  "[session-id & ks]

   Returns the Fulcro app state ident location of the local data of a specific instance of a running state machine."
  impl/local-data-path)

(def statechart-session-ident
  "[session-id]

   Returns the Fulcro app state ident location of the working memory of a specific statechart session."
  impl/statechart-session-ident)

(def resolve-aliases
  "[statechart-event-data]

  Resolve the value of all aliases defined on the statechart within executable content. For example:

  ```
  (ele/script {:expr (fn [env data & _] (let [values (resolve-aliases data)] ...))})
  ```
  "
  impl/resolve-aliases)

(>def ::expression-data [:map
                         [:_event {:optional true} [:map [:target ::sc/id]]]
                         [:fulcro/state-map map?]])
(>def ::env [:map
             [:fulcro/app :any]
             [:com.fulcrologic.statecharts/working-memory-store :any]
             [:com.fulcrologic.statecharts/processor :any]
             [:com.fulcrologic.statecharts/event-queue :any]
             [:com.fulcrologic.statecharts/statechart-registry :any]
             [:com.fulcrologic.statecharts/statechart :any]
             [:com.fulcrologic.statecharts/execution-model :any]
             [:com.fulcrologic.statecharts/vwmem :any]])

(>defn resolve-actors
  "Resolve the UI tree(s) for the given actors in a statechart executable element. For example:

   ```
   (ele/script
     {:expr (fn [env data & _]
              (let [{:actor/keys [form]} (resolve-actors env :actor/form)] ...))})
   ```

   NOTE: You can use `data` instead of `env`, BUT in cases where you don't have an :_event that will fail. It is recommended
   therefore that you use env.
  "
  [{:keys [_event fulcro/state-map fulcro/app] :as data-or-env} & actor-names]
  [[:or
    ::env
    ::expression-data] [:* :keyword] => [:map-of :keyword map?]]
  (let [session-id (or (:target _event) (env/session-id data-or-env))
        state-map  (or state-map (rapp/current-state app))
        local-data (get-in state-map (local-data-path session-id))
        actors     (:fulcro/actors local-data)]
    (reduce
      (fn [acc actor]
        (let [{:keys [component ident]} (get actors actor)]
          (if (and component ident)
            (assoc acc actor (fns/ui->props state-map (rc/registry-key->class component) ident))
            acc)))
      {}
      actor-names)))

(>defn resolve-actor
  "Returns the UI props of a single actor. Use `env` as the first argument. `data` is allowed to prevent a non-breaking change, but doesn't work
   when a non-event predicate is evaluated."
  [data-or-env actor-name]
  [[:or
    ::env
    ::expression-data] [:* :keyword] => (? map?)]
  (get (resolve-actors data-or-env actor-name) actor-name))

(>defn resolve-actor-class
  "Returns the current Fulcro component class that is representing `actor-key` if any."
  [data actor-key]
  [[:map [:fulcro/actors {:optional true} map?]] :keyword => (? [:fn rc/component-class?])]
  (some-> data :fulcro/actors actor-key :component rc/registry-key->class))

(>def ::ident [:tuple :any :any])
(>def ::actor [:map
               [:component qualified-keyword?]
               [:ident [:or :nil
                        ::ident]]])
(>def ::class [:fn rc/component-class?])

(>defn actor
  "Create an actor as part of the statechart data model. Give the actor a unique key at the top level of the data model,
   e.g.:

   ```
   (sf/start! app {:machine :machine-key
                   :session-id (new-uuid)
                   :data {:fulcro/actors {:actor/form (sf/actor LoginForm)}}})
   ```

   and then you can use that actor in an abstract way via data operations in executable content.

   See also:

   `resolve-aliases`, `resolve-actors`, `load-actor`

   "
  ([component-class]
   [::class => ::actor]
   {:component (rc/class->registry-key component-class)
    :ident     (rc/get-ident component-class {})})
  ([component-class ident]
   [::class ::ident => ::actor]
   {:component (rc/class->registry-key component-class)
    :ident     ident}))

(>def ::fulcro-app [:map [:com.fulcrologic.fulcro.application/runtime-atom :some]])
(>def ::fulcro-appish [:or
                       [:map [:com.fulcrologic.fulcro.application/runtime-atom :some]]
                       [:fn rc/component?]])

(>defn register-statechart!
  "Register a `statechart` definition under the (unique) key `k` on the given Fulcro application. You MUST register
   a chart or you will not be able to send events to instances of it."
  [app k statechart]
  [::fulcro-app :keyword ::sc/statechart => :any]
  (let [registry (-> app :com.fulcrologic.fulcro.application/runtime-atom
                   deref ::sc/env ::sc/statechart-registry)]
    (sp/register-statechart! registry k statechart)))

(>defn lookup-statechart
  "Attempt to return the statechart definition of the given registration k."
  [app-ish k]
  [::fulcro-appish :keyword => (? ::sc/statechart)]
  (let [registry (-> (rc/any->app app-ish) :com.fulcrologic.fulcro.application/runtime-atom
                   deref ::sc/env ::sc/statechart-registry)]
    (sp/get-statechart registry k)))

(>defn statechart-env
  "Returns the installed statechart env. "
  [app-ish]
  [::fulcro-appish => ::sc/env]
  (impl/statechart-env app-ish))

(defn- with-superseded-check
  "Returns `env` as the env of a step or start whose `(superseded?)` is true while it still runs (its promise hasn't
   settled) after a restart or stop of its session superseded it. The sends and cancels its continuation makes with
   this env are dropped then, and so are the invocation starts, stops and forwards made with it. (A send made with the
   env once the step or start is over, e.g. by a callback that kept it, goes out as usual, and so does one made with
   the app, e.g. `send!` or a routing call.)"
  [env superseded?]
  (assoc env ::superseded? superseded?))

(>defn start!
  "Starts a statechart that is registered as `machine` (keyword) under the provided session-id (default is a random UUID),
   and passes it the provided invocation `data`.

   The statechart is stored in the Fulcro state atom under [::id session-id], and is removed if the statechart reaches
   a final state.

   The new session's events wait until it is saved, so the events its initial entry causes (e.g. the ok-event of a
   load a synchronous remote answers at once) find it; with `:event-loop? :immediate` they are then processed before
   `start!` returns (or, when the entry returns a promise, once it settles). While the synchronous part of `start!`
   runs, no other event processing starts either. With `:async? true`, an initial entry that awaits (e.g.
   `afop/await-load`) holds back only this session's events: other sessions' events are processed meanwhile.

Returns the new session-id of the statechart (a promise of it when the initial entry returns a promise)."
  [app-ish {:keys [machine session-id data]
            :or   {session-id (new-uuid)
                   data       {}}}]
  [::fulcro-appish [:map
                    [:machine :keyword]
                    [:session-id {:optional true} ::sc/id]
                    [:data {:optional true} map?]] => [:maybe [:or ::sc/session-id [:fn p/promise?]]]]
  (when machine
    (let [env    (or
                   (statechart-env app-ish)
                   (throw (ex-info "Statecharts are not installed." {})))
          {::sc/keys [processor working-memory-store]} env
          ;; `current?` turns false once this start is superseded (a later start of the same session id) or the
          ;; session is stopped: a save after that (of an entry that settles later) is skipped.
          start* (fn [current? superseded?]
                   (let [env   (cond-> env superseded? (with-superseded-check superseded?))
                         s0    (sp/start! processor env machine (cond-> {::sc/session-id                                session-id
                                                                         :org.w3.scxml.event/invokeid                   (new-uuid)
                                                                         :com.fulcrologic.statecharts/parent-session-id impl/master-chart-id}
                                                                  (map? data) (assoc :com.fulcrologic.statecharts/invocation-data data)))
                         save! (fn [wmem]
                                 (if (current?)
                                   (sp/save-working-memory! working-memory-store env session-id wmem)
                                   (log/debug "Not saving a superseded start of session" session-id))
                                 session-id)]
                     (if (p/promise? s0)
                       (p/then s0 save!)
                       (save! s0))))]
      ;; The events the initial entry causes (e.g. a synchronous load's ok-event) are processed only after the
      ;; session is saved.
      (if-let [defer-processing (::defer-processing env)]
        (defer-processing session-id start*)
        (start* (constantly true) nil)))))

;; Might need to defonce this so things don't restart. One coreasync queue. Timers???
(declare process-events!)

(defn- guarded-working-memory-store
  "Returns a working memory store that delegates to `store`, except that it skips a save of `session-id` once
   `(current?)` is false: the step or start that saves was superseded by a later start of the session, or the session
   was stopped, so its save would overwrite (or resurrect) it."
  [store session-id current?]
  (reify sp/WorkingMemoryStore
    (get-working-memory [_ env id] (sp/get-working-memory store env id))
    (save-working-memory! [_ env id wmem]
      (if (and (= id session-id) (not (current?)))
        (log/debug "Not saving session" id "for a step or start that a restart or stop superseded.")
        (sp/save-working-memory! store env id wmem)))
    (delete-working-memory! [_ env id] (sp/delete-working-memory! store env id))))

(defn- held-invocation-processor
  "Wraps the invocation `processor` so that an invoked session is started like `start!` starts one: its events wait
   until it is saved (`defer-processing`, which also skips the save of a superseded start). (Stopping it deletes its
   working memory, which drops its holds.) A call with a stale env (`stale-env?`, see `with-superseded-check`), from
   the continuation of a step or start that a restart or stop superseded, is dropped: it would start a child no
   running session invoked, or stop or forward to a child of the session's new run."
  [processor defer-processing stale-env?]
  (reify sp/InvocationProcessor
    (supports-invocation-type? [_ typ] (sp/supports-invocation-type? processor typ))
    (start-invocation! [_ env {:keys [invokeid] :as data}]
      (cond
        (stale-env? env) (log/debug "Not starting invocation" invokeid "for a step or start that a restart or stop superseded.")
        (some? invokeid) (defer-processing invokeid
                           (fn [current? superseded?]
                             (sp/start-invocation! processor
                               (-> env
                                 (assoc ::sc/working-memory-store
                                   (guarded-working-memory-store (::sc/working-memory-store env) invokeid current?))
                                 (with-superseded-check superseded?))
                               data)))
        :else (sp/start-invocation! processor env data)))
    (stop-invocation! [_ env data]
      (if (stale-env? env)
        (log/debug "Not stopping invocation" (:invokeid data) "for a step or start that a restart or stop superseded.")
        (sp/stop-invocation! processor env data)))
    (forward-event! [_ env data]
      (if (stale-env? env)
        (log/debug "Not forwarding to invocation" (:invokeid data) "for a step or start that a restart or stop superseded.")
        (sp/forward-event! processor env data)))))

(>defn install-fulcro-statecharts!
  "Create a statecharts environment that is set up to work with the given Fulcro app.

  Options can include:

  * `:extra-env` - A map of additional keys to include in the `env` parameter that all executable content receives.
  * `:on-save` - A `(fn [session-id EDN])` that is called every time the statechart reaches a stable state and
           has working memory saved to the Fulcro app database. Allows you to do things like make statechart
           data durable across sessions.
  * `:on-delete` - A `(fn [session-id])` that is called when a statechart reaches a final state and is removed.
  * `:event-loop?` - Controls event processing. `true` (default) starts an async core.async event loop.
        `false` requires manual `process-events!` calls. `:immediate` processes events synchronously
        during `send!`, eliminating the need for sleeps or manual processing in tests. CLJ-only.
        Under `:immediate`, processing goes on until no queued event is due; a delayed event (e.g. `send-after`) has
        no timer, and is delivered by the first processing (a later `send!` or `process-events!`) at or after its
        delivery time.
        In every mode, processing is not re-entrant: a `process-events!` (or loop tick) that starts while events are
        being processed returns at once, and its events wait for the processing in progress (`:immediate`) or the next.
  * `:async?` - If true, use the async-capable processor and execution model (promesa-based). This enables
        expressions to return promises that the algorithm awaits, allowing `afop/await-load` and
        `afop/await-mutation` to work. Requires promesa on the classpath. Defaults to false. When enabled,
        the chart will atomically transition through states awaiting promise resolution, rather than
        resting in intermediate states. A session whose step (or initial entry, see `start!`) awaits holds back only
        its own events until the step's result is saved; other sessions' events are processed meanwhile. Its
        events (including delayed ones that fall due) wait in the queue, so a `cancel` still reaches them. So a step
        whose promise never settles freezes its session: its events, timeouts and `send-after`s wait, and pile up.
        A step that awaits an event of its own session deadlocks, as do two sessions that await each other's events.
        Restarting the session (`start!` with its id) or stopping it (exiting the state that invoked it) drops its
        holds. A restart also drops the events the old session sent itself that are still queued (e.g. its
        `send-after` timeouts); events others sent to the session id are delivered to the new session. (An old
        self-sent event that the queue had already taken off for delivery when the restart landed still reaches the
        new session: at once after a synchronous restart, or once an async restart's start settles.) When the old
        step's promise settles, its continuation's result isn't saved, and the sends, cancels and invocation starts,
        stops and forwards it makes with its env are dropped (the results of loads it issued still go to the session
        id), but its expressions still run: KNOWN LIMITATION, their data operations (`assign`, Fulcro state ops) still
        apply, to the session's local data and Fulcro state, which the new session shares, and the sends and routes
        they make with the app (e.g. `send!` or `routing/route-to!`) still go out.

   IMPORTANT: The execution model for Fulcro calls expressions with 4 args: env, data, event-name, and event-data. The
   last two are available in `:_event` of `data`, but are passed as addl args for convenience. If you use this
   in CLJ the arity must match or your expression will crash.

   Additionally, the data model will automatically resolve all aliases into the `data` map in expressions as well.

   The statecharts components are installed onto the app itself.

   NOTE: There is currently no way to make the event queue durable (across browser reload), so if you do add some kind
   of statechart durability realize that timed events can get lost.
   "
  ([app]
   [::fulcro-app => ::sc/env]
   (install-fulcro-statecharts! app {}))
  ([app {:keys [extra-env on-save on-delete event-loop? async?] :or {event-loop? true async? false}}]
   [::fulcro-app [:map
                  [:extra-env {:optional true} map?]
                  [:on-save {:optional true} fn?]
                  [:on-delete {:optional true} fn?]
                  [:event-loop? {:optional true} [:or :boolean [:= :immediate]]]
                  [:async? {:optional true} :boolean]] => ::sc/env]
   (let [runtime-atom (:com.fulcrologic.fulcro.application/runtime-atom app)
         immediate?   (= event-loop? :immediate)
         ;; Re-entrancy guard: true while events are being processed (by the event loop, `process-events!`, an
         ;; :immediate `send!`) or while the synchronous part of `start!` runs. A nested processing call returns at
         ;; once, and its events wait for the processing in progress (:immediate) or for the next one. It is never
         ;; held while a promise is pending: see `holds`.
         processing?  (atom false)
         ;; Per-session holds: session-id -> generation. A session is held while `start!` (or an invocation) starts
         ;; it, while one of its events is processed, and while that step's promise is pending (`:async? true`), until
         ;; its working memory is saved. A held session's events stay in the queue, in order (where `cancel!` still
         ;; reaches them), while other sessions' events are processed. A step holds its session only when nothing
         ;; holds it (its event goes back to the queue otherwise). Each (re)start gets a new generation: it replaces
         ;; the session's hold, and a release or save of an older generation (a superseded start or step) is ignored.
         ;; Stopping the session (deleting its working memory) drops its hold. A step or start whose hold was
         ;; replaced or dropped while it still runs is superseded: its sends, cancels and invocation calls are
         ;; dropped (`with-superseded-check`).
         holds        (atom {})
         generations  (atom 0)
         ;; Set when a hold is released (on another thread, or inside a step) so the processing in progress, if any,
         ;; delivers the released session's events before it returns (:immediate).
         released?    (atom false)]
     (when-not (contains? @runtime-atom ::sc/env)
       (let [dm                   (impl/new-fulcro-data-model app)
             real-queue           (mpq/new-queue)
             session-held?        (fn [session-id] (contains? @holds session-id))
             ;; Holds the session unless something holds it already: returns the new hold's generation, else nil.
             try-hold!            (fn [session-id]
                                    (let [candidate (swap! generations inc)
                                          [old _] (swap-vals! holds
                                                    (fn [m] (if (contains? m session-id) m (assoc m session-id candidate))))]
                                      (when-not (contains? old session-id) candidate)))
             ;; A (re)start: the session's only hold, of a new generation. Returns the generation.
             restart-hold!        (fn [session-id]
                                    (let [generation (swap! generations inc)]
                                      (swap! holds assoc session-id generation)
                                      generation))
             release!             (fn [session-id generation]
                                    (swap! holds (fn [m] (if (= generation (get m session-id)) (dissoc m session-id) m))))
             forget-session!      (fn [session-id] (swap! holds dissoc session-id))
             current?             (fn [session-id generation] (= generation (get @holds session-id)))
             ;; True while the step or start of `generation` runs but no longer holds its session (a restart or
             ;; stop superseded it). `running` is reset before the release, so it is never true for one that's over.
             superseded-check     (fn [session-id generation running]
                                    #(and @running (not (current? session-id generation))))
             stale-env?           (fn [env] (when-let [superseded? (::superseded? env)] (superseded?)))
             release-and-process! (fn [session-id generation]
                                    (release! session-id generation)
                                    (when immediate?
                                      (reset! released? true)
                                      (process-events! app)))
             run-event!           (fn [{::sc/keys    [working-memory-store]
                                        :fulcro/keys [app]                  :as env} handler event]
                                    (let [session-id (:target event)
                                          generation (when (some? session-id) (try-hold! session-id))
                                          running    (atom true)]
                                      (if (and (some? session-id) (nil? generation))
                                        ;; Held since the queue checked (a restart, on another thread): the event goes
                                        ;; back to its place, and waits with the session's others for the release.
                                        ::mpq/requeue
                                        (let [;; A step's save is skipped once the session is restarted or stopped,
                                              ;; and so are the sends, cancels and invocation calls it makes after that.
                                              step-env (cond-> env
                                                         generation (-> (assoc ::sc/working-memory-store
                                                                          (guarded-working-memory-store working-memory-store
                                                                            session-id #(current? session-id generation)))
                                                                      (with-superseded-check
                                                                        (superseded-check session-id generation running))))
                                              result   (try
                                                         (handler step-env event)
                                                         ;; W3C SCXML: event handler errors must not crash the event loop.
                                                         (catch #?(:clj Throwable :cljs :default) e
                                                           (log/error e "Event handler threw an exception")
                                                           nil))]
                                          ;; An async step saves the session's working memory when its promise
                                          ;; settles: until then the session's events wait (they would see the working
                                          ;; memory the step is about to overwrite).
                                          (when generation
                                            (if (p/promise? result)
                                              (-> result
                                                (p/then (fn [_]
                                                          (reset! running false)
                                                          (release-and-process! session-id generation)
                                                          nil))
                                                (p/catch (fn [e]
                                                           (log/error e "Processing an event failed" {:session-id session-id})
                                                           (reset! running false)
                                                           (release-and-process! session-id generation)
                                                           nil)))
                                              (do
                                                (reset! running false)
                                                (release! session-id generation))))
                                          (inspect/ilet [session-id (:target event)
                                                         {::sc/keys [configuration] :as wmem} (sp/get-working-memory working-memory-store env session-id)]
                                            (if (map? event)
                                              (impl/statechart-event! app session-id (:name event) (:data event) configuration)
                                              (impl/statechart-event! app session-id event {} configuration)))
                                          result))))
             ;; Processes one round: the queue's due events, except a held session's (they wait, in order).
             process-round!       (fn [env handler options]
                                    (sp/receive-events! real-queue env
                                      (fn [env event] (run-event! env handler event))
                                      (assoc options ::mpq/session-held? session-held?)))
             ;; A stale env's (see `with-superseded-check`) sends and cancels are dropped: they come from the
             ;; continuation of a step or start that a restart or stop superseded, and would reach the session's new
             ;; run (e.g. cancel its timeout, which has the same send id).
             instrumented-queue   (reify sp/EventQueue
                                    (send! [_ env send-request]
                                      (if (stale-env? env)
                                        (do
                                          (log/debug "Not sending" (:event send-request) "for a step or start that a restart or stop superseded.")
                                          ;; Accepted as far as the superseded step is concerned (a refused send
                                          ;; raises an error event in it).
                                          true)
                                        (let [result (sp/send! real-queue env send-request)]
                                          (when (and immediate? (not @processing?))
                                            (process-events! app))
                                          result)))
                                    (cancel! [_ env session-id send-id]
                                      (if (stale-env? env)
                                        (log/debug "Not cancelling" send-id "for a step or start that a restart or stop superseded.")
                                        (sp/cancel! real-queue env session-id send-id)))
                                    (receive-events! [this env handler] (sp/receive-events! this env handler {}))
                                    (receive-events! [_ env handler options]
                                      (if (compare-and-set! processing? false true)
                                        (do
                                          (reset! released? false)
                                          (try
                                            (process-round! env handler options)
                                            ;; :immediate: also process the events sent while processing (and those
                                            ;; of sessions released meanwhile), until no event is due. A delayed
                                            ;; event that isn't due yet waits for a later processing (counting it
                                            ;; made every send poll the queue 100 times), as do a held session's.
                                            (when immediate?
                                              (loop [safety 100]
                                                (when (and (pos? safety)
                                                        (mpq/has-due-events? real-queue (:session-id options) session-held?))
                                                  (process-round! env handler options)
                                                  (recur (dec safety)))))
                                            (finally
                                              (reset! processing? false)))
                                          ;; A session released on another thread while this ran (its release found
                                          ;; processing in progress) has its events delivered now.
                                          (when (and immediate? (compare-and-set! released? true false)
                                                  (mpq/has-due-events? real-queue nil session-held?))
                                            (process-events! app)))
                                        (log/trace "Event processing is already in progress. The events wait for it (or for the next processing)."))))
             defer-processing     (fn [session-id f]
                                    ;; Runs `(f current? superseded?)`, a (re)start of session `session-id` (its env
                                    ;; gets `superseded?`, see `with-superseded-check`). The session's events wait
                                    ;; until `f` returns, or its promise settles (it has saved the session then).
                                    ;; `(current?)` turns false once a later start of the session supersedes this one,
                                    ;; or it is stopped: `f` skips its save then. The app's processing is held only
                                    ;; while `f` runs synchronously (when none is in progress), never while its
                                    ;; promise is pending. Then (:immediate) the events that can be delivered are
                                    ;; processed.
                                    (let [generation (restart-hold! session-id)
                                          running    (atom true)
                                          ;; The old session's own queued events (e.g. its timeouts) mean nothing to
                                          ;; the new one. Other senders' events wait for it.
                                          _          (mpq/cancel-self-sent-events! real-queue session-id)
                                          own-guard? (compare-and-set! processing? false true)
                                          result     (try
                                                       (f #(current? session-id generation)
                                                         (superseded-check session-id generation running))
                                                       (catch #?(:clj Throwable :cljs :default) e
                                                         (reset! running false)
                                                         (release! session-id generation)
                                                         (throw e))
                                                       (finally
                                                         (when own-guard? (reset! processing? false))))
                                          over!      (fn [] (reset! running false) (release-and-process! session-id generation))]
                                      (if (p/promise? result)
                                        ;; The release is attached before any processing, so nothing processing
                                        ;; throws can leave the hold in place.
                                        (let [settled (-> result
                                                        (p/then (fn [v] (over!) v))
                                                        (p/catch (fn [e] (over!) (throw e))))]
                                          (when (and own-guard? immediate?) (process-events! app))
                                          settled)
                                        (do
                                          ;; (Without the guard, the processing in progress may be another thread's
                                          ;; that is about to end: the release flag makes it, or this, deliver.)
                                          (over!)
                                          result))))
             ex                   (if async?
                                    (alambda/new-execution-model dm instrumented-queue {:explode-event? true})
                                    (lambda/new-execution-model dm instrumented-queue {:explode-event? true}))
             registry             (lmr/new-registry)
             ;; Deleting a session's working memory (it stopped) drops its holds.
             wmstore              (impl/->FulcroWorkingMemoryStore app on-save
                                    (fn [session-id]
                                      (forget-session! session-id)
                                      (when on-delete (on-delete session-id))))
             env                  (-> (merge {:fulcro/app                app
                                              ::sc/statechart-registry   registry
                                              ::sc/data-model            dm
                                              ::sc/event-queue           instrumented-queue
                                              ::sc/working-memory-store  wmstore
                                              ::sc/processor             (if async?
                                                                           (aalg/new-processor)
                                                                           (alg/new-processor))
                                              ::sc/invocation-processors [(i.statechart/new-invocation-processor)]
                                              ::sc/execution-model       ex
                                              ::defer-processing         defer-processing}
                                        extra-env)
                                    ;; An invoked session is held while it starts, like a `start!`.
                                    (update ::sc/invocation-processors
                                      (fn [processors]
                                        (mapv #(held-invocation-processor % defer-processing stale-env?) processors))))
             env-with-loop        (if (and event-loop? (not immediate?))
                                    (assoc env :events-running-atom (cael/run-event-loop! env 16))
                                    env)]
         (swap! runtime-atom assoc ::sc/env env-with-loop)))
     (register-statechart! app impl/master-chart-id impl/application-chart)
     (start! app {:machine    impl/master-chart-id
                  :session-id impl/master-chart-id
                  :data       {}})
     (::sc/env @runtime-atom))))

(defn current-configuration
  "Returns the current statechart configuration (set of active states) for the statechart of the given
   session id."
  [app-ish session-id]
  (let [state-map (rapp/current-state app-ish)
        {::sc/keys [configuration]} (get-in state-map [::sc/session-id session-id])]
    (or configuration #{})))

(defn send!
  "Send an event to a running statechart."
  ([app-ish session-id event] (send! app-ish session-id event {}))
  ([app-ish session-id event data]
   (let [{::sc/keys [event-queue] :as env} (statechart-env app-ish)]
     (sp/send! event-queue env {:event  event
                                :data   data
                                :target session-id}))))

(defn process-events!
  "Process all pending statechart events synchronously. This is useful when you have installed
   statecharts with `:event-loop? false` and want to process events manually.

   Call this after `send!` or any action that queues events to process them immediately.

   Not re-entrant: called while events are already being processed (e.g. from an expression, or from a DOM handler a
   step triggers synchronously), it returns at once and the events wait for the next processing. Processing them
   then would use working memory that the step in progress is about to overwrite. For the same reason, the events of
   a session whose step returned a promise that hasn't settled yet (`:async? true`) wait for it; this processes the
   other sessions' events, and the next processing after the promise settles processes the waiting ones.

   Example usage in tests:
   ```clojure
   (let [app (app/fulcro-app)]
     (app/set-root! app Root {:initialize-state? true})
     (scf/install-fulcro-statecharts! app {:event-loop? false})
     (scf/register-statechart! app ::my-chart my-chart)
     (scf/start! app {:machine ::my-chart :session-id ::session})

     ;; Send an event and process it synchronously
     (scf/send! app ::session :some-event)
     (scf/process-events! app)

     ;; Check the resulting state
     (scf/current-configuration app ::session))
   ```"
  [app-ish]
  (let [env (statechart-env app-ish)]
    (sp/receive-events! (::sc/event-queue env) env
      (fn [{::sc/keys [working-memory-store processor] :as env} {:keys [target] :as event}]
        (when target
          (let [wmem    (sp/get-working-memory working-memory-store env target)
                result  (when wmem (sp/process-event! processor env wmem event))
                save-fn (fn [next-mem]
                          (when next-mem
                            (sp/save-working-memory! working-memory-store env target next-mem))
                          ;; Not the save's value: an `:on-save` that returns a promise must not hold the session.
                          nil)]
            ;; A promise only when the processor returned one (the step awaits): the session is held until it settles.
            (if (p/promise? result)
              (p/then result save-fn)
              (save-fn result)))))
      {})))

(defn mutation-result
  "Extracts the mutation result from an event that was triggered by the completion of a mutation.
   `data` is the second parameter of the executable content lambda."
  [data]
  (some-> data :_event :data :fulcro/mutation-result :body vals first))
