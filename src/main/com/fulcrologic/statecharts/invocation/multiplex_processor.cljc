(ns com.fulcrologic.statecharts.invocation.multiplex-processor
  "InvocationProcessor for the multiplex type. Spawns N children of a
   user-chosen invocation type, registers a small synthesized
   `aggregator` statechart whose only job is to count child completions
   and emit the standard `done.invoke.<mux-id>` to the grandparent when
   all are in.

   Children's `::sc/parent-session-id` is set to the multiplex's own
   session id (the aggregator), so each child's natural
   `done.invoke.<child-sid>` event routes to the aggregator. The
   aggregator is a real statechart; the W3C algorithm fires
   `done.invoke.<mux-id>` for free when its `:done` final state is
   reached.

   See `multiplex-options` for option vars and the companion
   `multiplex` namespace for the element constructor."
  (:refer-clojure :exclude [count])
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :as e]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.invocation.multiplex-options :as mo]
    [com.fulcrologic.statecharts.invocation.statechart :as i.statechart]
    [com.fulcrologic.statecharts.promise :as p]
    [com.fulcrologic.statecharts.protocols :as sp]
    [taoensso.timbre :as log]))

;;;; -------------------------------------------------------------------
;;;; Async helpers (mirrors v20150901_async_impl/maybe-then)
;;;; -------------------------------------------------------------------

(defn- maybe-then
  "If `v` is a promise, chains `f` on it. Otherwise calls `(f v)` directly.
   Local copy of the private helper in `v20150901_async_impl` so that this
   namespace does not depend on a private symbol."
  [v f]
  (if (p/promise? v)
    (p/then v f)
    (f v)))

(defn- await-all
  "Given a collection of values (some possibly promises), return either a
   plain `true` (when all are sync) or a promise resolving to `true` once
   every promise has settled. Settles sequentially via `maybe-then`, like
   `do-sequence` in the async impl — keeps the sync fast-path branchless."
  [values]
  (reduce (fn [acc v]
            (maybe-then acc (fn [_] (maybe-then v (fn [_] true)))))
          true
          values))

;;;; -------------------------------------------------------------------
;;;; Helpers
;;;; -------------------------------------------------------------------

(defn- find-processor [env type]
  (first (filter #(sp/supports-invocation-type? % type)
                 (::sc/invocation-processors env))))

(defn- statechart-processor [env]
  (or (find-processor env ::sc/chart)
      (find-processor env :statechart)))

(defn- child-invokeid
  "Build the invokeid keyword for a child at position `idx` of the
   multiplex with id `mux-id`. Form: `:multiplex.<mux-id>.<idx>`. The
   `multiplex.` prefix prevents SCXML dot-prefix matching from
   conflating per-child done events with the cohort's own done event
   (`done.invoke.<mux-id>`)."
  [mux-id idx]
  (keyword (str "multiplex." (name mux-id) "." idx)))

(defn- child-done-event-prefix
  "The event keyword the synthesized aggregator listens for. Prefix-
   matches every child's natural `done.invoke.<child-invokeid>`."
  [mux-id]
  (keyword (str "done.invoke.multiplex." (name mux-id))))

(defn- aggregator-chart-key
  "Registry key the synthesized aggregator chart is registered under.
   Must be a qualified keyword (the SCXML statechart-src spec rejects
   vectors)."
  [mux-id]
  (keyword "com.fulcrologic.statecharts.invocation.multiplex-processor"
           (str "aggregator-" (namespace mux-id) (when (namespace mux-id) "_") (name mux-id))))

(defn- aggregator-chart
  "Synthesize the aggregator chart for a given mux-id. The chart:

   - Reads `:n` from its data model (seeded via invocation-data at start).
   - Increments `:tally` on every event prefix-matching the child done
     event family.
   - When `:tally >= :n`, transitions to a final state. Reaching the
     final state causes the algorithm to fire `done.invoke.<mux-id>`
     to the parent session (the grandparent)."
  [mux-id]
  (let [done-prefix (child-done-event-prefix mux-id)]
    (chart/statechart {:initial :counting
                       :name    (str "multiplex-aggregator/" (name mux-id))}
      (e/state {:id :counting}
        (e/transition {:event done-prefix}
          (e/script {:expr (fn [_env data]
                             [(ops/assign :tally (inc (or (:tally data) 0)))])})
          ;; Re-emit the per-child done event up to the grandparent so it
          ;; can match on either the cohort done (done.invoke.<mux-id>),
          ;; the per-child family prefix (done.invoke.multiplex.<mux-id>),
          ;; or a specific child (done.invoke.multiplex.<mux-id>.K).
          (e/send {:target    :_parent
                   :eventexpr (fn [_env data] (some-> data :_event :name))
                   :content   (fn [_env data] (or (some-> data :_event :data) {}))}))
        (e/transition {:cond (fn [_env data]
                               (>= (or (:tally data) 0)
                                   (or (:n data) 0)))
                       :target :done}))
      (e/final {:id :done}))))

(defn- ensure-aggregator-registered!
  "Register the aggregator chart for `mux-id` in the env's registry.
   Re-registers (overwrites) on every invocation — chart definitions
   are pure data and re-registration is cheap."
  [env mux-id]
  (sp/register-statechart! (::sc/statechart-registry env)
                           (aggregator-chart-key mux-id)
                           (aggregator-chart mux-id)))

(defn- with-session-id
  "Return a copy of `env` whose `::sc/vwmem` reports `sid` as the
   current session id. Used so that when we call
   `start-invocation!` on the underlying processor for children, the
   underlying processor records `parent-session-id = sid` for each
   child rather than the grandparent's actual session id."
  [env sid]
  (assoc env ::sc/vwmem (volatile! {::sc/session-id sid})))

(defn- live-child?
  "True if statechart child session `sid` can still process events: it has working memory that is not stopped. A child
   that reached a final state is no longer running (or its working memory is gone). Children of other types are always
   considered live."
  [env child-type sid]
  (let [store (::sc/working-memory-store env)]
    (or (not (#{::sc/chart :statechart} child-type))
        (nil? store)
        (let [wmem (sp/get-working-memory store env sid)]
          (and (some? wmem) (not (false? (::sc/running? wmem))))))))

(defn- send-to-child!
  "Forward an event to the given child session via the env's event
   queue. Preserves event data; sender is recorded as `source` so
   `#_parent` from inside the child resolves correctly."
  [env source target-sid event]
  (let [queue (::sc/event-queue env)]
    (sp/send! queue env
              {:target            target-sid
               :type              ::sc/chart
               :source-session-id source
               :event             (or (:name event) event)
               :data              (or (:data event) {})})))

;;;; -------------------------------------------------------------------
;;;; Processor
;;;; -------------------------------------------------------------------

(defrecord MultiplexInvocationProcessor [state]
  ;; state = (atom {mux-id -> {:children   [child-sids…]
  ;;                           :child-type kw}})
  sp/InvocationProcessor

  (supports-invocation-type? [_ t] (= t mo/type))

  (start-invocation! [_ env {:keys [invokeid params]}]
    (let [config        (get params mo/config)
          ;; The element constructor packs everything under mo/config
          ;; as a resolver fn the execution model has already called.
          n             (get config mo/count 0)
          child-type    (get config mo/child-type)
          cp-fn         (get config mo/child-params (fn [_ _ _] {}))
          grandparent   (env/session-id env)
          underlying    (find-processor env child-type)
          stchart-proc  (statechart-processor env)
          gp-data       (or (some-> (::sc/data-model env)
                                    (sp/current-data env)) {})
          children      (vec
                          (for [i (range n)]
                            (let [sid       (child-invokeid invokeid i)
                                  ;; cp-fn returns the full start-invocation
                                  ;; arg shape for the underlying processor
                                  ;; (e.g. {:src … :params …}). We override
                                  ;; :invokeid + :type and inject mo/identity
                                  ;; into the params.
                                  child-args (cp-fn env gp-data i)
                                  base-params (get child-args :params {})
                                  ident      {:sid         sid
                                              :idx         i
                                              :invokeid    invokeid
                                              :grandparent grandparent}]
                              {:sid  sid
                               :idx  i
                               :args (merge child-args
                                            {:invokeid sid
                                             :type     child-type
                                             :params   (assoc base-params
                                                              mo/identity ident)})})))]
      ;; with-multiplex guarantees stchart-proc presence; no defensive
      ;; throw needed here.
      (when-not underlying
        (throw (ex-info "multiplex: no registered processor for child-type"
                        {:mux-id invokeid :child-type child-type})))
      (swap! state assoc invokeid
        {:children   (mapv :sid children)
         :child-type child-type})
      ;; 1) Register & start the aggregator at sid = mux-id. Its
      ;;    parent-session-id is the grandparent (env's current sid),
      ;;    so its eventual done.invoke.<mux-id> reaches the
      ;;    grandparent automatically when it hits :done.
      (ensure-aggregator-registered! env invokeid)
      (let [event-queue (::sc/event-queue env)
            child-env   (with-session-id env invokeid)
            ;; Helpers used to recover from a failed child start. The
            ;; synthetic done event keeps the aggregator's tally moving
            ;; so the cohort still completes; error.platform is forwarded
            ;; to the grandparent so the parent chart can observe the
            ;; failure.
            synth-done! (fn [child-sid]
                          (when event-queue
                            (sp/send! event-queue env
                              {:target            invokeid
                               :type              ::sc/chart
                               :source-session-id child-sid
                               :event             (evts/invoke-done-event child-sid)
                               :data              {}})))
            error!      (fn [child-sid reason]
                          (when event-queue
                            (sp/send! event-queue env
                              {:target            grandparent
                               :type              ::sc/chart
                               :source-session-id invokeid
                               :event             :error.platform
                               :data              {:invokeid child-sid
                                                   :reason   reason}})))
            handle-fail (fn [child-sid]
                          (synth-done! child-sid)
                          (error! child-sid "child failed to start")
                          true)
            start-child (fn [{:keys [sid args]}]
                          (let [r (try
                                    (sp/start-invocation! underlying child-env args)
                                    (catch #?(:clj Throwable :cljs :default) e
                                      (error! sid (str "child start threw: " #?(:clj (.getMessage e) :cljs (.-message e))))
                                      (synth-done! sid)
                                      true))]
                            (cond
                              (p/promise? r)
                              (-> r
                                  (p/then (fn [v] (if (false? v) (handle-fail sid) true)))
                                  (p/catch (fn [_] (handle-fail sid))))

                              (false? r) (handle-fail sid)
                              :else      true)))
            agg-result  (sp/start-invocation! stchart-proc env
                          {:invokeid invokeid
                           :src      (aggregator-chart-key invokeid)
                           :type     ::sc/chart
                           :params   {:n n :tally 0}})]
        ;; 2) Start the N children with parent-session-id = mux-id so
        ;;    their natural done.invoke events route to the aggregator.
        ;;    Chain children onto the aggregator so the aggregator's
        ;;    session is registered to receive done events before any
        ;;    child can fire one (matters under simple-async).
        (maybe-then agg-result
          (fn [_]
            (let [child-results (mapv start-child children)]
              (await-all (cons true child-results))))))))

  (stop-invocation! [_ env {:keys [invokeid]}]
    (let [{:keys [children child-type]} (get @state invokeid)
          underlying   (find-processor env child-type)
          stchart-proc (statechart-processor env)
          ;; Stop the aggregator first so any in-flight tally updates
          ;; don't keep firing the cohort done event after we've decided
          ;; to cancel.
          agg-stop     (when stchart-proc
                         (sp/stop-invocation! stchart-proc env
                           {:invokeid invokeid :type ::sc/chart}))
          ;; Stop all children.
          child-stops  (when underlying
                         (mapv (fn [sid]
                                 (sp/stop-invocation! underlying env
                                   {:invokeid sid :type child-type}))
                               children))]
      ;; Await everything before clearing internal state, so child wmem
      ;; deletion has resolved by the time the multiplex forgets the
      ;; cohort. Sync path stays sync (await-all returns plain true).
      (maybe-then (await-all (cons agg-stop child-stops))
        (fn [_]
          (swap! state dissoc invokeid)
          true))))

  (forward-event! [_ env {:keys [invokeid event]}]
    (let [{:keys [children child-type]} (get @state invokeid)
          live?              (partial live-child? env child-type)
          source             (env/session-id env)
          data               (or (:data event) {})
          target-mux         (get data mo/target-mux)]
      (cond
        ;; A target-mux discriminator is present and does NOT name us:
        ;; another multiplex in this parent state owns this event.
        (and (some? target-mux) (not= target-mux invokeid))
        true

        :else
        (let [to            (get data mo/to :all)
              stripped-data (-> data
                                (dissoc mo/to)
                                (dissoc mo/target-mux)
                                (assoc mo/from {:sid source}))
              forwarded     (assoc event :data stripped-data)
              send!         (fn [sid] (send-to-child! env source sid forwarded))]
          ;; Only live children receive events (W3C: autoforward goes to active invocations). Without this, events
          ;; forwarded after a child finished (e.g. sibling done events) were sent to sessions that no longer exist.
          (cond
            (= to :all)      (run! send! (filterv live? children))
            (= to :any)      (some-> (first (filter live? children)) send!)
            (and (map? to)
                 (integer? (:idx to))
                 (<= 0 (:idx to)))
                             (when-let [sid (nth children (:idx to) nil)]
                               (when (live? sid) (send! sid)))
            :else            (log/warn "multiplex/forward-event!: unrecognized mo/to value" to))
          true)))))

(defn new-processor
  "Create a multiplex invocation processor. Register with
   `with-multiplex` or by adding the result to
   `::sc/invocation-processors` in your env."
  []
  (->MultiplexInvocationProcessor (atom {})))

(defn with-multiplex
  "Idempotently register the multiplex invocation processor into `env`,
   along with the statechart invocation processor (an internal dependency
   used to run multiplex's synthesized aggregator chart). Either, neither,
   or both may already be present; this fn brings whatever is missing.
   Returns the (possibly updated) env. Safe to call multiple times."
  [env]
  (let [has? (fn [t] (some #(sp/supports-invocation-type? % t)
                           (::sc/invocation-processors env)))]
    (cond-> env
      (not (has? ::sc/chart))
      (update ::sc/invocation-processors (fnil conj []) (i.statechart/new-invocation-processor))

      (not (has? mo/type))
      (update ::sc/invocation-processors (fnil conj []) (new-processor)))))

;; `reply` lives in `com.fulcrologic.statecharts.invocation.multiplex`
;; (the user-facing ns). The processor is responsible only for the
;; runtime; chart-author utilities live alongside the element
;; constructors.
