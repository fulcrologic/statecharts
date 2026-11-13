(ns com.fulcrologic.statecharts.invocation.multiplex-spec
  "Tests for the multiplex invocation processor.

   Coverage:
   - Element constructor: required keys, :id validation.
   - Protocol surface: supports-invocation-type?, with-multiplex idempotence.
   - End-to-end: happy path (N children all done → grandparent gets
     done.invoke.<mux-id>), count=0 fast path, cancellation cleanup,
     parent→cohort routing via mo/to, mux/reply with mo/from."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :as e :refer [final state transition]]
    [com.fulcrologic.statecharts.event-queue.event-processing :as ep]
    [com.fulcrologic.statecharts.invocation.multiplex :as mux]
    [com.fulcrologic.statecharts.invocation.multiplex-options :as mo]
    [com.fulcrologic.statecharts.invocation.multiplex-processor :as mp]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.promise :as p]
    [com.fulcrologic.statecharts.simple :as simple]
    #?(:clj [com.fulcrologic.statecharts.simple-async :as simple-async])
    [fulcro-spec.core :refer [=> assertions component specification]]))

;;;; -------------------------------------------------------------------
;;;; Helpers
;;;; -------------------------------------------------------------------

(defn build-env []
  (mp/with-multiplex (simple/simple-env)))

(defn drain!
  "Run the event loop until quiescence (no more events). Caps at
   `max-iters` to avoid infinite loops on misbehaving tests."
  ([env] (drain! env 100))
  ([env max-iters]
   (let [q (::sc/event-queue env)]
     (loop [i max-iters]
       (let [before @(:session-queues q)]
         (sp/receive-events! q env ep/standard-statechart-event-handler)
         (let [after @(:session-queues q)]
           (when (and (pos? i)
                      (not= before after))
             (recur (dec i)))))))))

(def simple-child
  "Trivial child chart: enters and transitions immediately to final."
  (chart/statechart {}
    (state {:id :child/start}
      (transition {:target :child/done}))
    (final {:id :child/done})))

(def waiting-child
  "Child that does NOT immediately terminate — waits for :proceed."
  (chart/statechart {}
    (state {:id :child/waiting}
      (transition {:event :proceed :target :child/done}))
    (final {:id :child/done})))

(defn waiting-child*
  "Build a 'waits for :go then done' chart with a chosen state-id prefix.
   Used so heterogeneous-cohort tests can distinguish child types by their
   visible state ids."
  [prefix]
  (let [wait (keyword (name prefix) "wait")
        done (keyword (name prefix) "done")]
    (chart/statechart {}
      (state {:id wait} (transition {:event :go :target done}))
      (final {:id done}))))

(defn run-parent-with-multiplex!
  "Build env, register children, register & start a parent chart, drain.
   `opts`:
     :parent-chart    — the parent statechart (required)
     :children        — map of {chart-key chart-value} to register (default {::simple-child simple-child})
     :session-id      — parent session id (default :parent-session)
     :max-drain-iters — drain cap (default 100)
   Returns {:env :wmstore :parent-session-id} so tests can probe results."
  [{:keys [parent-chart children session-id max-drain-iters]
    :or   {children        {::simple-child simple-child}
           session-id      :parent-session
           max-drain-iters 100}}]
  (let [env (build-env)]
    (doseq [[k v] children] (simple/register! env k v))
    (simple/register! env ::parent-chart parent-chart)
    (simple/start! env ::parent-chart session-id)
    (drain! env max-drain-iters)
    {:env               env
     :wmstore           (::sc/working-memory-store env)
     :parent-session-id session-id}))

(defn seeded-mux-fixture
  "Build a multiplex test fixture without going through the chart lifecycle.
   Manually plants `state` into the processor so forward-event!/stop-invocation!
   tests can poke at it directly.
     :mux-id        — invokeid to seed (default :j)
     :children      — vector of child sids (default [])
     :child-type    — invocation type stored alongside children (default ::sc/chart)
     :grandparent   — sid of the simulated parent (default :gp)
     :finished      — set of child sids that have already finished (no working memory); all others are live
   Returns {:env :queue :processor :parent-env :mux-id}."
  [{:keys [mux-id children child-type grandparent finished]
    :or   {mux-id :j children [] child-type ::sc/chart grandparent :gp finished #{}}}]
  (let [env       (build-env)
        queue     (::sc/event-queue env)
        processor (first (filter #(sp/supports-invocation-type? % mo/type)
                                 (::sc/invocation-processors env)))]
    ;; Live statechart children have working memory; finished ones have had it deleted.
    (simple/register! env ::waiting-child waiting-child)
    (doseq [sid children
            :when (not (contains? finished sid))]
      (sp/save-working-memory! (::sc/working-memory-store env) env sid
        {::sc/session-id sid ::sc/statechart-src ::waiting-child ::sc/configuration #{:child/waiting}}))
    (swap! (:state processor) assoc mux-id {:children children :child-type child-type})
    {:env        env
     :queue      queue
     :processor  processor
     :parent-env (assoc env ::sc/vwmem (volatile! {::sc/session-id grandparent}))
     :mux-id     mux-id}))

(defn events-named
  "Return the events with `event-name` that were enqueued on `sid`."
  [queue sid event-name]
  (filterv #(= event-name (:name %)) (get @(:session-queues queue) sid)))

(defn received?
  "True if any event with `event-name` was enqueued on `sid`."
  [queue sid event-name]
  (boolean (seq (events-named queue sid event-name))))

;;;; -------------------------------------------------------------------
;;;; Element constructor
;;;; -------------------------------------------------------------------

(specification "multiplex element constructor"
  (component "produces a valid invoke node"
    (let [el (mux/multiplex {:id            :judges
                             mo/child-type  ::sc/chart
                             mo/count       3
                             mo/child-params (fn [_ _ idx] {:src ::dummy :idx idx})})]
      (assertions
        "node-type is :invoke"
        (:node-type el) => :invoke
        "type is the multiplex invocation type"
        (:type el) => mo/type
        "id is preserved"
        (:id el) => :judges
        "params contains a single key under mo/config"
        (keys (:params el)) => [mo/config]
        "the value is a fn (resolver) that evaluates lazily"
        (fn? (get (:params el) mo/config)) => true)))

  (component "resolver returns the resolved config when invoked"
    (let [el       (mux/multiplex {:id            :j
                                   mo/child-type  ::sc/chart
                                   mo/count       (fn [_ d] (:n d))
                                   mo/child-params (fn [_ _ idx] {:idx idx})})
          resolver (get (:params el) mo/config)
          resolved (resolver {} {:n 5})]
      (assertions
        "count is evaluated against data"
        (get resolved mo/count) => 5
        "child-type is a literal keyword"
        (get resolved mo/child-type) => ::sc/chart
        "child-params remains a fn for per-idx invocation"
        (fn? (get resolved mo/child-params)) => true)))

  (component "validation"
    (let [msg (fn [thunk]
                (try (thunk) nil
                     (catch #?(:clj Throwable :cljs :default) e
                       (or #?(:clj (.getMessage e) :cljs (.-message e)) ""))))]
      (assertions
        "requires :id"
        (try (mux/multiplex {mo/child-type ::sc/chart mo/count 1}) false
             (catch #?(:clj Throwable :cljs :default) _ true)) => true
        "id error message mentions :id"
        (str/includes? (msg #(mux/multiplex {mo/child-type ::sc/chart mo/count 1})) ":id") => true
        "rejects :id :multiplex"
        (try (mux/multiplex {:id :multiplex mo/child-type ::sc/chart mo/count 1}) false
             (catch #?(:clj Throwable :cljs :default) _ true)) => true
        "rejects :id :multiplex/foo (namespaced multiplex)"
        (try (mux/multiplex {:id :multiplex/foo mo/child-type ::sc/chart mo/count 1}) false
             (catch #?(:clj Throwable :cljs :default) _ true)) => true
        "rejects :id :foo/multiplex (multiplex name in namespace)"
        (try (mux/multiplex {:id :foo/multiplex mo/child-type ::sc/chart mo/count 1}) false
             (catch #?(:clj Throwable :cljs :default) _ true)) => true
        "reserved-id error mentions multiplex / reserved"
        (str/includes? (msg #(mux/multiplex {:id :multiplex mo/child-type ::sc/chart mo/count 1})) "multiplex") => true
        "requires mo/child-type"
        (try (mux/multiplex {:id :j mo/count 1}) false
             (catch #?(:clj Throwable :cljs :default) _ true)) => true
        "child-type error message mentions child-type"
        (str/includes? (msg #(mux/multiplex {:id :j mo/count 1})) "child-type") => true
        "requires mo/count"
        (try (mux/multiplex {:id :j mo/child-type ::sc/chart}) false
             (catch #?(:clj Throwable :cljs :default) _ true)) => true
        "count error message mentions count"
        (str/includes? (msg #(mux/multiplex {:id :j mo/child-type ::sc/chart})) "count") => true
        "rejects non-fn mo/child-params"
        (try (mux/multiplex {:id :j mo/child-type ::sc/chart mo/count 1
                             mo/child-params {:not :a-fn}})
             false
             (catch #?(:clj Throwable :cljs :default) _ true)) => true
        "child-params error message mentions child-params or function"
        (let [m (msg #(mux/multiplex {:id :j mo/child-type ::sc/chart mo/count 1
                                       mo/child-params {:not :a-fn}}))]
          (or (str/includes? m "child-params") (str/includes? m "function"))) => true
        "accepts fn for mo/child-type"
        (some? (mux/multiplex {:id :j
                               mo/child-type  (fn [_ _] ::sc/chart)
                               mo/count       1
                               mo/child-params (fn [_ _ _] {:src ::x})})) => true))))

;;;; -------------------------------------------------------------------
;;;; Protocol surface
;;;; -------------------------------------------------------------------

(specification "multiplex processor protocol surface"
  (component "supports-invocation-type?"
    (let [p (mp/new-processor)]
      (assertions
        "true for mo/type"
        (sp/supports-invocation-type? p mo/type) => true
        "false for ::sc/chart"
        (sp/supports-invocation-type? p ::sc/chart) => false
        "false for arbitrary keywords"
        (sp/supports-invocation-type? p :http) => false)))

  (component "with-multiplex"
    (let [env  (simple/simple-env)
          env1 (mp/with-multiplex env)
          env2 (mp/with-multiplex env1)]
      (assertions
        "adds processor when absent"
        (some #(sp/supports-invocation-type? % mo/type)
              (::sc/invocation-processors env1)) => true
        "idempotent — second call does not add a duplicate"
        (count (filter #(sp/supports-invocation-type? % mo/type)
                       (::sc/invocation-processors env2))) => 1))))

;;;; -------------------------------------------------------------------
;;;; End-to-end: happy path
;;;; -------------------------------------------------------------------

(specification "multiplex end-to-end: N children all complete"
  (let [parent-chart (chart/statechart {}
                       (state {:id :parent/active}
                         (mux/multiplex {:id            :judges
                                         mo/child-type  ::sc/chart
                                         mo/count       3
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::simple-child})})
                         (transition {:event  :done.invoke.judges
                                      :target :parent/complete}))
                       (state {:id :parent/complete}))
        {:keys [env wmstore]} (run-parent-with-multiplex! {:parent-chart parent-chart})
        parent-wmem (sp/get-working-memory wmstore env :parent-session)]
    (assertions
      "parent transitions to complete after all children done"
      (contains? (::sc/configuration parent-wmem) :parent/complete) => true)))

(specification "child invokeid naming scheme"
  (let [parent-chart (chart/statechart {}
                       (state {:id :parent/active}
                         (mux/multiplex {:id            :judges
                                         mo/child-type  ::sc/chart
                                         mo/count       3
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::waiting-child})})))
        {:keys [env wmstore]} (run-parent-with-multiplex!
                                {:parent-chart parent-chart
                                 :children     {::waiting-child waiting-child}})]
    (assertions
      "child sids carry the multiplex.<mux-id>.<idx> form"
      (some? (sp/get-working-memory wmstore env :multiplex.judges.0)) => true
      (some? (sp/get-working-memory wmstore env :multiplex.judges.1)) => true
      (some? (sp/get-working-memory wmstore env :multiplex.judges.2)) => true
      "each child's parent-session-id is the multiplex's own sid"
      (::sc/parent-session-id (sp/get-working-memory wmstore env :multiplex.judges.0))
      => :judges
      "aggregator session exists at sid=mux-id"
      (some? (sp/get-working-memory wmstore env :judges)) => true
      "aggregator's parent is the grandparent"
      (::sc/parent-session-id (sp/get-working-memory wmstore env :judges))
      => :parent-session)))

;;;; -------------------------------------------------------------------
;;;; End-to-end: count = 0 fast path
;;;; -------------------------------------------------------------------

(specification "multiplex with count=0"
  (let [parent-chart (chart/statechart {}
                       (state {:id :parent/active}
                         (mux/multiplex {:id            :empty
                                         mo/child-type  ::sc/chart
                                         mo/count       0
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::simple-child})})
                         (transition {:event  :done.invoke.empty
                                      :target :parent/complete}))
                       (state {:id :parent/complete}))
        {:keys [env wmstore]} (run-parent-with-multiplex! {:parent-chart parent-chart})
        parent-wmem (sp/get-working-memory wmstore env :parent-session)]
    (assertions
      "count=0 fires done.invoke immediately"
      (contains? (::sc/configuration parent-wmem) :parent/complete) => true)))

;;;; -------------------------------------------------------------------
;;;; Parent → cohort routing
;;;; -------------------------------------------------------------------

(specification "forward-event! routes via mo/to"
  (component ":all (default) broadcasts"
    (let [{:keys [queue processor parent-env mux-id]}
          (seeded-mux-fixture {:children [:multiplex.j.0 :multiplex.j.1 :multiplex.j.2]})]
      (sp/forward-event! processor parent-env
        {:invokeid mux-id
         :event    {:name :ping
                    :data {:x 1}}})
      (assertions
        "every child receives the event"
        (received? queue :multiplex.j.0 :ping) => true
        (received? queue :multiplex.j.1 :ping) => true
        (received? queue :multiplex.j.2 :ping) => true)))

  (component ":any sends to one child"
    (let [{:keys [queue processor parent-env mux-id]}
          (seeded-mux-fixture {:children [:multiplex.j.0 :multiplex.j.1]})]
      (sp/forward-event! processor parent-env
        {:invokeid mux-id
         :event    {:name :one
                    :data {mo/to :any}}})
      (let [total (+ (count (events-named queue :multiplex.j.0 :one))
                     (count (events-named queue :multiplex.j.1 :one)))]
        (assertions
          "exactly one child received the event"
          total => 1))))

  (component "{:idx K} targets a specific child"
    (let [{:keys [queue processor parent-env mux-id]}
          (seeded-mux-fixture {:children [:multiplex.j.0 :multiplex.j.1 :multiplex.j.2]})]
      (sp/forward-event! processor parent-env
        {:invokeid mux-id
         :event    {:name :targeted
                    :data {mo/to {:idx 1}}}})
      (assertions
        "only idx 1 received"
        (received? queue :multiplex.j.0 :targeted) => false
        (received? queue :multiplex.j.1 :targeted) => true
        (received? queue :multiplex.j.2 :targeted) => false)))

  (component "mo/to is stripped and mo/from is added"
    (let [{:keys [queue processor parent-env mux-id]}
          (seeded-mux-fixture {:children [:multiplex.j.0]})]
      (sp/forward-event! processor parent-env
        {:invokeid mux-id
         :event    {:name :hello
                    :data {mo/to :all :payload :x}}})
      (let [evt (first (events-named queue :multiplex.j.0 :hello))]
        (assertions
          "mo/to is removed from the delivered event"
          (contains? (:data evt) mo/to) => false
          "mo/target-mux is removed from the delivered event"
          (contains? (:data evt) mo/target-mux) => false
          "payload survives"
          (:payload (:data evt)) => :x
          "mo/from records the sender"
          (:sid (get (:data evt) mo/from)) => :gp)))))

(specification "forward-event! skips children that have already finished"
  (component ":all only reaches live children"
    (let [{:keys [env queue processor parent-env mux-id]}
          (seeded-mux-fixture {:children [:multiplex.j.0 :multiplex.j.1 :multiplex.j.2]
                               :finished #{:multiplex.j.1}})]
      ;; A child that reached final during start keeps its working memory, marked not running.
      (sp/save-working-memory! (::sc/working-memory-store env) env :multiplex.j.2
        {::sc/session-id :multiplex.j.2 ::sc/statechart-src ::waiting-child ::sc/running? false})
      (sp/forward-event! processor parent-env {:invokeid mux-id :event {:name :ping}})
      (assertions
        "the live child receives the event"
        (received? queue :multiplex.j.0 :ping) => true
        "a child with no working memory does not"
        (received? queue :multiplex.j.1 :ping) => false
        "a child whose working memory is no longer running does not"
        (received? queue :multiplex.j.2 :ping) => false)))

  (component ":any picks a live child"
    (let [{:keys [queue processor parent-env mux-id]}
          (seeded-mux-fixture {:children [:multiplex.j.0 :multiplex.j.1]
                               :finished #{:multiplex.j.0}})]
      (sp/forward-event! processor parent-env {:invokeid mux-id :event {:name :one :data {mo/to :any}}})
      (assertions
        "the first live child receives it"
        (received? queue :multiplex.j.1 :one) => true
        (received? queue :multiplex.j.0 :one) => false)))

  (component "{:idx K} to a finished child is dropped"
    (let [{:keys [queue processor parent-env mux-id]}
          (seeded-mux-fixture {:children [:multiplex.j.0 :multiplex.j.1]
                               :finished #{:multiplex.j.1}})]
      (sp/forward-event! processor parent-env {:invokeid mux-id :event {:name :targeted :data {mo/to {:idx 1}}}})
      (assertions
        "nothing is sent"
        (received? queue :multiplex.j.1 :targeted) => false))))

;;;; -------------------------------------------------------------------
;;;; Forward-event! edge cases
;;;; -------------------------------------------------------------------

(specification "forward-event! degenerate-input behavior"
  (component "unknown mux-id is a no-op"
    (let [{:keys [processor parent-env]} (seeded-mux-fixture {})]
      (assertions
        "unknown mux-id returns true without throwing"
        (sp/forward-event! processor parent-env
          {:invokeid :unknown :event {:name :x :data {}}}) => true)))

  (component "{:idx K} out of range silently drops"
    (let [{:keys [queue processor parent-env mux-id]}
          (seeded-mux-fixture {:children [:multiplex.j.0]})
          before (count (apply concat (vals @(:session-queues queue))))]
      (sp/forward-event! processor parent-env
        {:invokeid mux-id :event {:name :oops :data {mo/to {:idx 99}}}})
      (let [after (count (apply concat (vals @(:session-queues queue))))]
        (assertions
          "no event delivered when idx exceeds child count"
          (= before after) => true))))

  (component ":any with no children is harmless"
    (let [{:keys [processor parent-env mux-id]} (seeded-mux-fixture {:children []})]
      (assertions
        "returns true without throwing"
        (sp/forward-event! processor parent-env
          {:invokeid mux-id :event {:name :x :data {mo/to :any}}}) => true))))

;;;; -------------------------------------------------------------------
;;;; Dynamism
;;;; -------------------------------------------------------------------

(specification "mo/count may be a fn of the parent's data model"
  (let [parent-chart (chart/statechart {}
                       (e/data-model {:expr {:n 2}})
                       (state {:id :parent/active}
                         (mux/multiplex {:id            :dyn
                                         mo/child-type  ::sc/chart
                                         mo/count       (fn [_ data] (:n data))
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::waiting-child})})))
        {:keys [env wmstore]} (run-parent-with-multiplex!
                                {:parent-chart parent-chart
                                 :children     {::waiting-child waiting-child}})]
    (assertions
      "spawns exactly :n children"
      (some? (sp/get-working-memory wmstore env :multiplex.dyn.0)) => true
      (some? (sp/get-working-memory wmstore env :multiplex.dyn.1)) => true
      "and no more"
      (some? (sp/get-working-memory wmstore env :multiplex.dyn.2)) => false)))

(specification "mo/child-params receives idx and can vary per child"
  (let [calls        (atom [])
        parent-chart (chart/statechart {}
                       (state {:id :parent/active}
                         (mux/multiplex {:id            :varied
                                         mo/child-type  ::sc/chart
                                         mo/count       3
                                         mo/child-params (fn [_ _ idx]
                                                           (swap! calls conj idx)
                                                           {:src ::waiting-child})})))]
    (run-parent-with-multiplex!
      {:parent-chart parent-chart
       :children     {::waiting-child waiting-child}})
    (assertions
      "child-params was called once per child, with consecutive idx values"
      (sort @calls) => [0 1 2])))

(specification "heterogeneous children: child-params returns a different :src per idx"
  (let [child-a      (waiting-child* :a)
        child-b      (waiting-child* :b)
        parent-chart (chart/statechart {}
                       (state {:id :parent/active}
                         (mux/multiplex {:id            :het
                                         mo/child-type  ::sc/chart
                                         mo/count       2
                                         mo/child-params (fn [_ _ idx]
                                                           {:src (if (zero? idx) ::ca ::cb)})})))
        {:keys [env wmstore]} (run-parent-with-multiplex!
                                {:parent-chart parent-chart
                                 :children     {::ca child-a ::cb child-b}})]
    (assertions
      "child 0 runs child-a (its initial state is :a/wait)"
      (contains? (::sc/configuration (sp/get-working-memory wmstore env :multiplex.het.0))
                 :a/wait) => true
      "child 1 runs child-b"
      (contains? (::sc/configuration (sp/get-working-memory wmstore env :multiplex.het.1))
                 :b/wait) => true)))

;;;; -------------------------------------------------------------------
;;;; Identity seeding
;;;; -------------------------------------------------------------------

(specification "mo/identity is seeded into each child's data model"
  (let [parent-chart (chart/statechart {}
                       (state {:id :parent/active}
                         (mux/multiplex {:id            :ids
                                         mo/child-type  ::sc/chart
                                         mo/count       2
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::waiting-child})})))
        {:keys [env wmstore]} (run-parent-with-multiplex!
                                {:parent-chart parent-chart
                                 :children     {::waiting-child waiting-child}})
        child0-data (::sc/invocation-data (sp/get-working-memory wmstore env :multiplex.ids.0))
        ident0      (get child0-data mo/identity)]
    (assertions
      "identity is present"
      (some? ident0) => true
      "identity contains sid"
      (:sid ident0) => :multiplex.ids.0
      "identity contains idx"
      (:idx ident0) => 0
      "identity contains the multiplex's invokeid"
      (:invokeid ident0) => :ids
      "identity contains the grandparent sid"
      (:grandparent ident0) => :parent-session)))

;;;; -------------------------------------------------------------------
;;;; mux/reply helper
;;;; -------------------------------------------------------------------

(specification "mux/reply throws when identity is missing"
  (let [env (build-env)]
    (assertions
      "throws when no mo/identity in data model"
      (try
        (mux/reply (assoc env
                         ::sc/vwmem (volatile! {::sc/session-id :solo}))
                  :nope {})
        false
        (catch #?(:clj Throwable :cljs :default) _ true))
      => true)))

(specification "child invokeids never collide with cohort done event"
  ;; Pure logic check: name-match semantics ensure done.invoke.judges (cohort)
  ;; does not match done.invoke.multiplex.judges.K (per-child).
  (let [match-cohort? (fn [evt-name]
                        ;; emulate dot-prefix matching: candidate is :done.invoke.judges
                        (let [c (str/split (name :done.invoke.judges) #"\.")
                              e (str/split (name evt-name) #"\.")]
                          (and (<= (count c) (count e))
                               (= c (subvec e 0 (count c))))))]
    (assertions
      "cohort transition matches its own event"
      (match-cohort? :done.invoke.judges) => true
      "cohort transition does NOT match child K's done event"
      (match-cohort? :done.invoke.multiplex.judges.0) => false
      "cohort transition does NOT match the multiplex-prefix family"
      (match-cohort? :done.invoke.multiplex.judges) => false)))

;;;; -------------------------------------------------------------------
;;;; Lifecycle / cancellation
;;;; -------------------------------------------------------------------

(specification "stop-invocation! removes processor state"
  (let [{:keys [env processor mux-id]} (seeded-mux-fixture {:children [:multiplex.j.0]})]
    (sp/stop-invocation! processor env {:invokeid mux-id})
    (assertions
      "internal state for the mux-id is dropped"
      (contains? @(:state processor) mux-id) => false
      "returns true"
      (sp/stop-invocation! processor env {:invokeid mux-id}) => true)))

(specification "parent exit cancels all multiplex children"
  (let [parent-chart (chart/statechart {}
                       (state {:id :parent/active}
                         (mux/multiplex {:id            :cx
                                         mo/child-type  ::sc/chart
                                         mo/count       2
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::waiting-child})})
                         (transition {:event :abort :target :parent/dead}))
                       (state {:id :parent/dead}))
        {:keys [env wmstore]} (run-parent-with-multiplex!
                                {:parent-chart parent-chart
                                 :children     {::waiting-child waiting-child}})]
    ;; Verify everything is alive
    (assertions
      "children are running before abort"
      (some? (sp/get-working-memory wmstore env :multiplex.cx.0)) => true
      "aggregator is running before abort"
      (some? (sp/get-working-memory wmstore env :cx)) => true)
    ;; Send :abort to parent, drain
    (sp/send! (::sc/event-queue env) env
              {:target :parent-session :event :abort :data {}
               :source-session-id :external})
    (drain! env)
    (assertions
      "children are cleaned up after parent exits the multiplex's state"
      (some? (sp/get-working-memory wmstore env :multiplex.cx.0)) => false
      (some? (sp/get-working-memory wmstore env :multiplex.cx.1)) => false
      "aggregator is cleaned up too"
      (some? (sp/get-working-memory wmstore env :cx)) => false)))

;;;; -------------------------------------------------------------------
;;;; Phase 3 additions — dynamism (child-type as fn)
;;;; -------------------------------------------------------------------

(specification "mo/child-type may be a fn of the parent's data model"
  (let [parent-chart (chart/statechart {}
                       (e/data-model {:expr {:kind ::waiting-child}})
                       (state {:id :parent/active}
                         (mux/multiplex {:id            :dyn-type
                                         mo/child-type  ::sc/chart
                                         mo/count       2
                                         mo/child-params (fn [_ data _]
                                                           {:src (:kind data)})})))
        {:keys [env wmstore]} (run-parent-with-multiplex!
                                {:parent-chart parent-chart
                                 :children     {::waiting-child waiting-child}})]
    (assertions
      "children spawn using src resolved dynamically from data"
      (some? (sp/get-working-memory wmstore env :multiplex.dyn-type.0)) => true
      (some? (sp/get-working-memory wmstore env :multiplex.dyn-type.1)) => true)))

;;;; -------------------------------------------------------------------
;;;; Phase 3 — forward-event! edge cases
;;;; -------------------------------------------------------------------

(specification "mo/to with negative idx is silently dropped"
  (let [{:keys [queue processor parent-env mux-id]}
        (seeded-mux-fixture {:children [:multiplex.j.0 :multiplex.j.1]})
        before (count (apply concat (vals @(:session-queues queue))))
        result (sp/forward-event! processor parent-env
                 {:invokeid mux-id :event {:name :neg :data {mo/to {:idx -1}}}})
        after  (count (apply concat (vals @(:session-queues queue))))]
    (assertions
      "no event delivered"
      (= before after) => true
      "no event named :neg on any seeded child"
      (received? queue :multiplex.j.0 :neg) => false
      (received? queue :multiplex.j.1 :neg) => false
      "forward-event! still returns true"
      result => true)))

(specification "mo/to with non-integer idx silently drops"
  (component "string idx"
    (let [{:keys [queue processor parent-env mux-id]}
          (seeded-mux-fixture {:children [:multiplex.j.0]})]
      (sp/forward-event! processor parent-env
        {:invokeid mux-id :event {:name :nope :data {mo/to {:idx "5"}}}})
      (assertions
        "no event delivered to the child"
        (received? queue :multiplex.j.0 :nope) => false)))
  (component "nil idx"
    (let [{:keys [queue processor parent-env mux-id]}
          (seeded-mux-fixture {:children [:multiplex.j.0]})]
      (sp/forward-event! processor parent-env
        {:invokeid mux-id :event {:name :nope :data {mo/to {:idx nil}}}})
      (assertions
        "no event delivered to the child"
        (received? queue :multiplex.j.0 :nope) => false))))

(specification "unrecognized mo/to value is a no-op"
  (let [{:keys [queue processor parent-env mux-id]}
        (seeded-mux-fixture {:children [:multiplex.j.0 :multiplex.j.1]})
        result (sp/forward-event! processor parent-env
                 {:invokeid mux-id :event {:name :bogus :data {mo/to :bogus}}})]
    (assertions
      "no events delivered"
      (received? queue :multiplex.j.0 :bogus) => false
      (received? queue :multiplex.j.1 :bogus) => false
      "forward-event! returns true (warn-and-drop)"
      result => true)))

;;;; -------------------------------------------------------------------
;;;; Phase 3 — start-invocation failure modes
;;;; -------------------------------------------------------------------

(specification "start-invocation throws when child-type has no registered processor"
  (let [{:keys [env processor]} (seeded-mux-fixture {})
        config-fn (fn resolve-config
                    ([_ _]
                     {mo/child-type   ::no-such-type
                      mo/count        1
                      mo/child-params (fn [_ _ _] {:src ::simple-child})})
                    ([_ _ _ _]
                     {mo/child-type   ::no-such-type
                      mo/count        1
                      mo/child-params (fn [_ _ _] {:src ::simple-child})}))
        thrown? (try
                  (sp/start-invocation! processor env
                    {:invokeid :nope
                     :params   {mo/config (config-fn nil nil)}})
                  false
                  (catch #?(:clj Throwable :cljs :default) e
                    (str/includes? (or #?(:clj (.getMessage e) :cljs (.-message e)) "")
                                   "child-type")))]
    (assertions
      "throws ex-info naming child-type"
      thrown? => true)))

(specification "with-multiplex auto-installs the statechart invocation processor"
  ;; The aggregator chart is a real statechart, so multiplex needs the
  ;; statechart invocation processor to be present. with-multiplex installs
  ;; it on the caller's behalf when absent, so a user who only intends to
  ;; multiplex non-statechart children (e.g. HTTP) does not need to know
  ;; about the internal dependency.
  (let [raw-env  {::sc/invocation-processors []}
        ready    (mp/with-multiplex raw-env)
        has?     (fn [t] (some #(sp/supports-invocation-type? % t)
                               (::sc/invocation-processors ready)))]
    (assertions
      "multiplex processor is installed"
      (has? mo/type) => true
      "statechart processor is installed automatically"
      (has? ::sc/chart) => true
      "calling with-multiplex twice does not duplicate processors"
      (let [twice (mp/with-multiplex ready)]
        (count (filter #(sp/supports-invocation-type? % mo/type)
                       (::sc/invocation-processors twice)))) => 1
      "calling with-multiplex twice does not duplicate the statechart processor"
      (let [twice (mp/with-multiplex ready)]
        (count (filter #(sp/supports-invocation-type? % ::sc/chart)
                       (::sc/invocation-processors twice)))) => 1)))

;;;; -------------------------------------------------------------------
;;;; Phase 3 — cohort vs per-child runtime matching
;;;; -------------------------------------------------------------------

(specification "done.invoke.<mux-id> fires exactly once on the grandparent (end-to-end)"
  ;; The aggregator re-emits each per-child done event (done.invoke.multiplex.<mux-id>.K)
  ;; up to the grandparent in addition to the cohort done (done.invoke.<mux-id>),
  ;; so the grandparent can match on either the cohort or any specific child.
  ;; Each event name has its OWN dot-prefix family: matching :done.invoke.judges
  ;; does NOT match :done.invoke.multiplex.judges.K (different prefix segments).
  (let [events-seen  (atom [])
        parent-chart (chart/statechart {}
                       (state {:id :parent/active}
                         (mux/multiplex {:id            :judges
                                         mo/child-type  ::sc/chart
                                         mo/count       3
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::simple-child})})
                         (transition {:event :done.invoke}
                           (e/script {:expr (fn [_ data]
                                              (swap! events-seen conj
                                                     (get-in data [:_event :name]))
                                              nil)}))))]
    (run-parent-with-multiplex! {:parent-chart parent-chart})
    (let [cohort-hits    (clojure.core/count
                           (filter #(= % :done.invoke.judges) @events-seen))
          per-child-hits (clojure.core/count
                           (filter #(and % (re-matches
                                             #"done\.invoke\.multiplex\.judges\.\d+"
                                             (name %))) @events-seen))]
      (assertions
        "cohort done.invoke.<mux-id> fires exactly once on the grandparent"
        cohort-hits => 1
        "per-child done.invoke.multiplex.<mux-id>.K events are re-emitted
         to the grandparent (one per child)"
        per-child-hits => 3))))

;;;; -------------------------------------------------------------------
;;;; Phase 3 — mux/reply round-trip and parent→child send pipeline
;;;; -------------------------------------------------------------------

(def reply-on-entry-child
  "Child chart whose on-entry calls (mux/reply env :ack {:n idx}). The idx
   comes from the child's data-model via mo/identity (seeded at start)."
  (chart/statechart {}
    (state {:id :child/start}
      (e/on-entry {}
        (e/script {:expr (fn [env data]
                           (let [idx (get-in data [mo/identity :idx])]
                             (mux/reply env :ack {:n idx})
                             nil))}))
      (transition {:target :child/done}))
    (final {:id :child/done})))

(specification "mux/reply round-trip delivers event to grandparent with full mo/from"
  (let [captured     (atom [])
        parent-chart (chart/statechart {}
                       (state {:id :parent/active}
                         (mux/multiplex {:id            :judges
                                         mo/child-type  ::sc/chart
                                         mo/count       2
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::reply-child})})
                         (transition {:event :ack}
                           (e/script {:expr (fn [_ data]
                                              (swap! captured conj
                                                     (get-in data [:_event :data]))
                                              nil)}))))]
    (run-parent-with-multiplex!
      {:parent-chart parent-chart
       :children     {::reply-child reply-on-entry-child}})
    (let [acks @captured
          sorted-by-idx (sort-by #(get-in % [mo/from :idx]) acks)]
      (assertions
        "grandparent received exactly two :ack events"
        (count acks) => 2
        "first ack carries idx 0 and full mo/from"
        (get-in (first sorted-by-idx) [mo/from :idx]) => 0
        (get-in (first sorted-by-idx) [mo/from :sid]) => :multiplex.judges.0
        (get-in (first sorted-by-idx) [mo/from :invokeid]) => :judges
        (get-in (first sorted-by-idx) [mo/from :grandparent]) => :parent-session
        "second ack carries idx 1"
        (get-in (second sorted-by-idx) [mo/from :idx]) => 1
        (get-in (second sorted-by-idx) [mo/from :sid]) => :multiplex.judges.1
        "payload :n survives"
        (:n (first sorted-by-idx)) => 0
        (:n (second sorted-by-idx)) => 1))))

(def reply-on-targeted-event-child
  "Child chart that waits for an :evaluate event and then mux/reply's :ack
   with the idx it learned at start."
  (chart/statechart {}
    (state {:id :child/wait}
      (transition {:event :evaluate :target :child/done}
        (e/script {:expr (fn [env data]
                           (let [idx (get-in data [mo/identity :idx])]
                             (mux/reply env :ack {:n idx})
                             nil))})))
    (final {:id :child/done})))

(specification "parent targets child K via mo/to + autoforward, child replies via mux/reply"
  ;; Uses :autoforward to route the parent's :evaluate event through the
  ;; multiplex processor's forward-event! method (which is the documented
  ;; way to address a specific child via mo/to). The single targeted child
  ;; reaches its :evaluate transition and calls mux/reply with its idx.
  (let [captured     (atom [])
        parent-chart (chart/statechart {}
                       (state {:id :parent/active}
                         (mux/multiplex {:id            :judges
                                         :autoforward   true
                                         mo/child-type  ::sc/chart
                                         mo/count       3
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::reply-child})})
                         (transition {:event :ack}
                           (e/script {:expr (fn [_ data]
                                              (swap! captured conj
                                                     (get-in data [:_event :data]))
                                              nil)}))))
        {:keys [env]} (run-parent-with-multiplex!
                        {:parent-chart parent-chart
                         :children     {::reply-child reply-on-targeted-event-child}})]
    (sp/send! (::sc/event-queue env) env
              {:target :parent-session :event :evaluate
               :data   {mo/to {:idx 2}}
               :source-session-id :external})
    (drain! env)
    (assertions
      "grandparent received exactly one :ack event (only one child was targeted)"
      (count @captured) => 1
      "the reply is tagged with idx 2"
      (get-in (first @captured) [mo/from :idx]) => 2
      (get-in (first @captured) [mo/from :sid]) => :multiplex.judges.2
      "payload :n matches the responding child's idx"
      (:n (first @captured)) => 2)))

;;;; -------------------------------------------------------------------
;;;; Phase 3 — autoforward through multiplex
;;;; -------------------------------------------------------------------

(def autoforward-listener-child
  "Child chart that transitions on :ping into a non-final :child/heard state.
   Using a non-final state ensures the child session is NOT terminated by the
   algorithm (which would clean up its wmem) so the test can still inspect it."
  (chart/statechart {}
    (state {:id :child/wait}
      (transition {:event :ping :target :child/heard}))
    (state {:id :child/heard})))

(specification "autoforward true on multiplex routes parent events to children via forward-event!"
  (let [parent-chart (chart/statechart {}
                       (state {:id :parent/active}
                         (mux/multiplex {:id            :judges
                                         :autoforward   true
                                         mo/child-type  ::sc/chart
                                         mo/count       2
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::af-child})})))
        {:keys [env wmstore]} (run-parent-with-multiplex!
                                {:parent-chart parent-chart
                                 :children     {::af-child autoforward-listener-child}})]
    (sp/send! (::sc/event-queue env) env
              {:target :parent-session :event :ping :data {:hello :world}
               :source-session-id :external})
    (drain! env)
    (assertions
      "child 0 transitioned via the autoforwarded :ping (reached :child/heard)"
      (contains? (::sc/configuration (sp/get-working-memory wmstore env :multiplex.judges.0))
                 :child/heard) => true
      "child 1 transitioned via the autoforwarded :ping"
      (contains? (::sc/configuration (sp/get-working-memory wmstore env :multiplex.judges.1))
                 :child/heard) => true)))

;;;; -------------------------------------------------------------------
;;;; Phase 3 — nested multiplex
;;;; -------------------------------------------------------------------

(def inner-mux-chart
  "Parent chart that contains an inner multiplex of N=3 simple-child leaves.
   Used as the child chart of an outer multiplex."
  (chart/statechart {}
    (state {:id :inner/active}
      (mux/multiplex {:id             :inner
                      mo/child-type   ::sc/chart
                      mo/count        3
                      mo/child-params (fn [_ _ _] {:src ::simple-child})}))))

(specification "nested multiplex spawns N*M leaves with non-colliding sids"
  (let [parent-chart (chart/statechart {}
                       (state {:id :parent/active}
                         (mux/multiplex {:id            :outer
                                         mo/child-type  ::sc/chart
                                         mo/count       2
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::inner-mux})})))
        {:keys [env wmstore]} (run-parent-with-multiplex!
                                {:parent-chart    parent-chart
                                 :children        {::inner-mux    inner-mux-chart
                                                   ::simple-child simple-child}
                                 :max-drain-iters 200})]
    (assertions
      "outer child 0 exists"
      (some? (sp/get-working-memory wmstore env :multiplex.outer.0)) => true
      "outer child 1 exists"
      (some? (sp/get-working-memory wmstore env :multiplex.outer.1)) => true
      "outer aggregator exists"
      (some? (sp/get-working-memory wmstore env :outer)) => true)))

;;;; -------------------------------------------------------------------
;;;; Phase 3 — non-statechart child receives mo/identity
;;;; -------------------------------------------------------------------

(specification "non-statechart child receives mo/identity in params"
  (let [received    (atom [])
        test-proc   (reify sp/InvocationProcessor
                      (supports-invocation-type? [_ t] (= t ::test-proc))
                      (start-invocation! [_ _env args]
                        (swap! received conj (:params args))
                        true)
                      (stop-invocation! [_ _env _args] true)
                      (forward-event! [_ _env _args] true))
        env         (-> (simple/simple-env)
                        (update ::sc/invocation-processors (fnil conj []) test-proc)
                        (mp/with-multiplex))
        mux-proc    (first (filter #(sp/supports-invocation-type? % mo/type)
                                   (::sc/invocation-processors env)))
        parent-env  (assoc env ::sc/vwmem (volatile! {::sc/session-id :gp}))]
    (sp/start-invocation! mux-proc parent-env
      {:invokeid :probe
       :params   {mo/config {mo/child-type   ::test-proc
                             mo/count        2
                             mo/child-params (fn [_ _ idx] {:params {:custom idx}})}}})
    (let [params @received
          sorted (sort-by #(get-in % [mo/identity :idx]) params)]
      (assertions
        "test processor received params for both children"
        (count params) => 2
        "child 0 carries mo/identity"
        (get-in (first sorted) [mo/identity :idx]) => 0
        (get-in (first sorted) [mo/identity :sid]) => :multiplex.probe.0
        (get-in (first sorted) [mo/identity :invokeid]) => :probe
        (get-in (first sorted) [mo/identity :grandparent]) => :gp
        "child 1 carries mo/identity"
        (get-in (second sorted) [mo/identity :idx]) => 1
        (get-in (second sorted) [mo/identity :sid]) => :multiplex.probe.1
        "user params are preserved alongside the injected identity"
        (:custom (first sorted)) => 0
        (:custom (second sorted)) => 1))))

;;;; -------------------------------------------------------------------
;;;; Phase 3 — two side-by-side multiplexes
;;;; -------------------------------------------------------------------

(specification "two distinct multiplexes coexist with independent lifecycles"
  (let [parent-chart (chart/statechart {}
                       (state {:id :parent/active}
                         (mux/multiplex {:id            :judges-a
                                         mo/child-type  ::sc/chart
                                         mo/count       2
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::waiting-child})})
                         (mux/multiplex {:id            :judges-b
                                         mo/child-type  ::sc/chart
                                         mo/count       2
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::waiting-child})})
                         (transition {:event :abort-a :target :parent/half}))
                       (state {:id :parent/half}))
        {:keys [env wmstore]} (run-parent-with-multiplex!
                                {:parent-chart parent-chart
                                 :children     {::waiting-child waiting-child}})]
    (assertions
      "all 4 children alive before abort"
      (some? (sp/get-working-memory wmstore env :multiplex.judges-a.0)) => true
      (some? (sp/get-working-memory wmstore env :multiplex.judges-a.1)) => true
      (some? (sp/get-working-memory wmstore env :multiplex.judges-b.0)) => true
      (some? (sp/get-working-memory wmstore env :multiplex.judges-b.1)) => true
      "both aggregators alive"
      (some? (sp/get-working-memory wmstore env :judges-a)) => true
      (some? (sp/get-working-memory wmstore env :judges-b)) => true
      "non-colliding sids: a and b family sids differ"
      (= :multiplex.judges-a.0 :multiplex.judges-b.0) => false)
    ;; Exit parent state — both multiplexes share a state, so both should clean up.
    (sp/send! (::sc/event-queue env) env
              {:target :parent-session :event :abort-a :data {}
               :source-session-id :external})
    (drain! env)
    (assertions
      "leaving the state cleans up BOTH multiplexes' children"
      (some? (sp/get-working-memory wmstore env :multiplex.judges-a.0)) => false
      (some? (sp/get-working-memory wmstore env :multiplex.judges-b.0)) => false
      "and both aggregators"
      (some? (sp/get-working-memory wmstore env :judges-a)) => false
      (some? (sp/get-working-memory wmstore env :judges-b)) => false)))

;;;; -------------------------------------------------------------------
;;;; Phase 3 — repeated entry/exit (Q5)
;;;; -------------------------------------------------------------------

(specification "multiplex can be entered, exited, and re-entered cleanly"
  (let [done-count   (atom 0)
        parent-chart (chart/statechart {}
                       (state {:id :parent/idle}
                         (transition {:event :go :target :parent/active}))
                       (state {:id :parent/active}
                         (mux/multiplex {:id             :reentry
                                         mo/child-type   ::sc/chart
                                         mo/count        2
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::simple-child})})
                         (transition {:event  :done.invoke.reentry
                                      :target :parent/idle}
                           (e/script {:expr (fn [_ _] (swap! done-count inc) nil)}))))
        env (build-env)
        _   (do (simple/register! env ::simple-child simple-child)
                (simple/register! env ::reentry-parent parent-chart)
                (simple/start! env ::reentry-parent :parent-session))]
    ;; First cycle: enter active, children complete, exit back to idle
    (sp/send! (::sc/event-queue env) env
              {:target :parent-session :event :go :data {}
               :source-session-id :external})
    (drain! env)
    (let [first-count @done-count]
      ;; Second cycle: re-enter
      (sp/send! (::sc/event-queue env) env
                {:target :parent-session :event :go :data {}
                 :source-session-id :external})
      (drain! env)
      (assertions
        "first entry fired done.invoke.<mux-id> once"
        first-count => 1
        "re-entry also fired done.invoke.<mux-id> — counter is now 2"
        @done-count => 2))))

;;;; -------------------------------------------------------------------
;;;; Phase 3 — child-start failure (Q2)
;;;; -------------------------------------------------------------------

(specification "child start failure: synthetic done fires + error.platform forwarded"
  (let [cohort-hits  (atom 0)
        platform-evs (atom [])
        parent-chart (chart/statechart {}
                       (state {:id :parent/active}
                         (mux/multiplex {:id             :mixed
                                         mo/child-type   ::sc/chart
                                         mo/count        3
                                         mo/child-params (fn [_ _ idx]
                                                           ;; idx 1 references an unregistered chart
                                                           {:src (if (= idx 1)
                                                                   ::no-such-chart
                                                                   ::simple-child)})})
                         (transition {:event :done.invoke.mixed
                                      :target :parent/done}
                           (e/script {:expr (fn [_ _] (swap! cohort-hits inc) nil)}))
                         (transition {:event :error.platform}
                           (e/script {:expr (fn [_ data]
                                              (swap! platform-evs conj
                                                     (get-in data [:_event :data]))
                                              nil)})))
                       (state {:id :parent/done}))]
    (run-parent-with-multiplex! {:parent-chart    parent-chart
                                 :max-drain-iters 200})
    (assertions
      "cohort done.invoke.<mux-id> fired exactly once (aggregator tally reached N)"
      @cohort-hits => 1
      "parent received at least one :error.platform event"
      (pos? (count @platform-evs)) => true
      "error.platform carries :invokeid for the failed child"
      (some #(= :multiplex.mixed.1 (:invokeid %)) @platform-evs) => true)))

;;;; -------------------------------------------------------------------
;;;; Phase 3 — aggregator counts all N done events
;;;; -------------------------------------------------------------------

(specification "aggregator counts all N child-done events even when emitted in same macrostep"
  (let [cohort-hits  (atom 0)
        parent-chart (chart/statechart {}
                       (state {:id :parent/active}
                         (mux/multiplex {:id             :big
                                         mo/child-type   ::sc/chart
                                         mo/count        5
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::simple-child})})
                         (transition {:event :done.invoke.big
                                      :target :parent/complete}
                           (e/script {:expr (fn [_ _] (swap! cohort-hits inc) nil)})))
                       (state {:id :parent/complete}))
        {:keys [env wmstore]} (run-parent-with-multiplex!
                                {:parent-chart parent-chart})
        parent-wmem (sp/get-working-memory wmstore env :parent-session)]
    (assertions
      "cohort done.invoke fires exactly once for N=5"
      @cohort-hits => 1
      "parent reached completion"
      (contains? (::sc/configuration parent-wmem) :parent/complete) => true)))

;;;; -------------------------------------------------------------------
;;;; Phase 3 — late child completion after parent exit
;;;; -------------------------------------------------------------------

(specification "late child completion after parent exit does not leak done.invoke.<mux-id>"
  (let [cohort-hits  (atom 0)
        parent-chart (chart/statechart {}
                       (state {:id :parent/active}
                         (mux/multiplex {:id             :late
                                         mo/child-type   ::sc/chart
                                         mo/count        2
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::waiting-child})})
                         (transition {:event :abort :target :parent/dead})
                         (transition {:event :done.invoke.late :target :parent/oops}
                           (e/script {:expr (fn [_ _] (swap! cohort-hits inc) nil)})))
                       (state {:id :parent/dead})
                       (state {:id :parent/oops}))
        {:keys [env]} (run-parent-with-multiplex!
                        {:parent-chart parent-chart
                         :children     {::waiting-child waiting-child}})]
    ;; Abort before children finish
    (sp/send! (::sc/event-queue env) env
              {:target :parent-session :event :abort :data {}
               :source-session-id :external})
    (drain! env)
    ;; Now try to drive any lingering child to complete by sending :proceed
    ;; broadcast to all (children should already be gone — this is a no-op).
    (sp/send! (::sc/event-queue env) env
              {:target :multiplex.late.0 :event :proceed :data {}
               :source-session-id :external})
    (sp/send! (::sc/event-queue env) env
              {:target :multiplex.late.1 :event :proceed :data {}
               :source-session-id :external})
    (drain! env)
    (assertions
      "no cohort done.invoke.<mux-id> ever leaked to the grandparent"
      @cohort-hits => 0)))

;;;; -------------------------------------------------------------------
;;;; Phase 3.5 — per-child done event re-emission to the grandparent
;;;; -------------------------------------------------------------------

(specification "aggregator re-emits per-child done events to the grandparent"
  (component "any-child match: prefix transition captures all N per-child events"
    (let [captured     (atom [])
          parent-chart (chart/statechart {}
                         (state {:id :parent/active}
                           (mux/multiplex {:id            :judges
                                           mo/child-type  ::sc/chart
                                           mo/count       3
                                           mo/child-params (fn [_ _ _]
                                                             {:src ::simple-child})})
                           (transition {:event :done.invoke.multiplex.judges}
                             (e/script {:expr (fn [_ data]
                                                (swap! captured conj
                                                       (get-in data [:_event :name]))
                                                nil)}))))]
      (run-parent-with-multiplex! {:parent-chart parent-chart})
      (assertions
        "grandparent saw all three per-child done events"
        (set @captured) => #{:done.invoke.multiplex.judges.0
                             :done.invoke.multiplex.judges.1
                             :done.invoke.multiplex.judges.2}
        "count is exactly 3"
        (clojure.core/count @captured) => 3)))

  (component "specific-child match: transition on exact child K"
    (let [captured     (atom [])
          parent-chart (chart/statechart {}
                         (state {:id :parent/active}
                           (mux/multiplex {:id            :judges
                                           mo/child-type  ::sc/chart
                                           mo/count       3
                                           mo/child-params (fn [_ _ _]
                                                             {:src ::simple-child})})
                           (transition {:event :done.invoke.multiplex.judges.1}
                             (e/script {:expr (fn [_ data]
                                                (swap! captured conj
                                                       (get-in data [:_event :name]))
                                                nil)}))))]
      (run-parent-with-multiplex! {:parent-chart parent-chart})
      (assertions
        "grandparent saw exactly the .1 event"
        @captured => [:done.invoke.multiplex.judges.1]))))

;;;; -------------------------------------------------------------------
;;;; Phase 3.5 — autoforward defaults to true
;;;; -------------------------------------------------------------------

(specification "multiplex defaults :autoforward to true"
  (component "no explicit autoforward → element carries :autoforward true"
    (let [el (mux/multiplex {:id            :j
                             mo/child-type  ::sc/chart
                             mo/count       1
                             mo/child-params (fn [_ _ _] {:src ::x})})]
      (assertions
        "default true"
        (:autoforward el) => true)))
  (component "explicit autoforward false is preserved"
    (let [el (mux/multiplex {:id            :j
                             :autoforward   false
                             mo/child-type  ::sc/chart
                             mo/count       1
                             mo/child-params (fn [_ _ _] {:src ::x})})]
      (assertions
        "explicit override wins"
        (:autoforward el) => false)))
  (component "explicit autoforward true also works"
    (let [el (mux/multiplex {:id            :j
                             :autoforward   true
                             mo/child-type  ::sc/chart
                             mo/count       1
                             mo/child-params (fn [_ _ _] {:src ::x})})]
      (assertions
        "explicit true"
        (:autoforward el) => true))))

;;;; -------------------------------------------------------------------
;;;; Phase 3.5 — mux/send constructor
;;;; -------------------------------------------------------------------

(specification "mux/send constructor produces a valid send element"
  (component "default :all routing"
    (let [el          (mux/send {:target :judges :event :go :data {:x 1}})
          content-fn  (:content el)
          resolved    (content-fn nil nil)]
      (assertions
        "node-type is :send"
        (:node-type el) => :send
        "uses targetexpr to address the current session (autoforward path)"
        (fn? (:targetexpr el)) => true
        "event is preserved"
        (:event el) => :go
        "default :all omits mo/to from data"
        (contains? resolved mo/to) => false
        "mo/target-mux discriminator names the addressed multiplex"
        (get resolved mo/target-mux) => :judges
        "user data is carried"
        (:x resolved) => 1)))

  (component ":idx K targets a specific child"
    (let [el       (mux/send {:target :judges :idx 2 :event :go :data {:x 1}})
          resolved ((:content el) nil nil)]
      (assertions
        "mo/to encodes the index"
        (get resolved mo/to) => {:idx 2}
        "mo/target-mux still present"
        (get resolved mo/target-mux) => :judges
        "user data still carried"
        (:x resolved) => 1)))

  (component ":idx :any picks one child"
    (let [el       (mux/send {:target :judges :idx :any :event :go})
          resolved ((:content el) nil nil)]
      (assertions
        "mo/to is :any"
        (get resolved mo/to) => :any
        "mo/target-mux is present"
        (get resolved mo/target-mux) => :judges
        "no user data keys leak (beyond routing discriminators)"
        (dissoc resolved mo/to mo/target-mux) => {})))

  (component "validation"
    (assertions
      "requires keyword :target"
      (try (mux/send {:event :go}) false
           (catch #?(:clj Throwable :cljs :default) _ true)) => true
      "requires :event"
      (try (mux/send {:target :judges}) false
           (catch #?(:clj Throwable :cljs :default) _ true)) => true
      "rejects bogus :idx values"
      (try (mux/send {:target :judges :event :go :idx :wat}) false
           (catch #?(:clj Throwable :cljs :default) _ true)) => true)))

(def mux-send-listener-child
  "Child chart that transitions on :ping into a non-final :child/heard state."
  (chart/statechart {}
    (state {:id :child/wait}
      (transition {:event :ping :target :child/heard}))
    (state {:id :child/heard})))

(specification "mux/send integration: targeted delivery via autoforward"
  (let [parent-chart (chart/statechart {}
                       (state {:id :parent/idle}
                         (transition {:event :fire :target :parent/active}))
                       (state {:id :parent/active}
                         (mux/multiplex {:id             :judges
                                         mo/child-type   ::sc/chart
                                         mo/count        2
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::ms-child})})
                         (transition {:event :go-shoot}
                           (mux/send {:target :judges :idx 0 :event :ping}))))
        {:keys [env wmstore]} (run-parent-with-multiplex!
                                {:parent-chart parent-chart
                                 :children     {::ms-child mux-send-listener-child}})]
    ;; Drive parent from idle to active so the multiplex spawns.
    (sp/send! (::sc/event-queue env) env
              {:target :parent-session :event :fire :data {}
               :source-session-id :external})
    (drain! env)
    ;; Fire the mux/send via :go-shoot
    (sp/send! (::sc/event-queue env) env
              {:target :parent-session :event :go-shoot :data {}
               :source-session-id :external})
    (drain! env)
    (assertions
      "child 0 received :ping and transitioned to :child/heard"
      (contains? (::sc/configuration (sp/get-working-memory wmstore env :multiplex.judges.0))
                 :child/heard) => true
      "child 1 did NOT receive :ping"
      (contains? (::sc/configuration (sp/get-working-memory wmstore env :multiplex.judges.1))
                 :child/heard) => false)))

;;;; -------------------------------------------------------------------
;;;; Phase 3.6 — multi-multiplex isolation via mo/target-mux
;;;; -------------------------------------------------------------------

(specification "mux/send targets a specific multiplex when multiple are siblings"
  ;; Parent state with two multiplexes, :judges and :auditors. Both autoforward.
  ;; mux/send addressed at :judges with :idx 0 should reach ONLY judges.0, not
  ;; judges.1 and not any auditors child.
  (let [parent-chart (chart/statechart {}
                       (state {:id :parent/idle}
                         (transition {:event :fire :target :parent/active}))
                       (state {:id :parent/active}
                         (mux/multiplex {:id             :judges
                                         mo/child-type   ::sc/chart
                                         mo/count        2
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::ms-child})})
                         (mux/multiplex {:id             :auditors
                                         mo/child-type   ::sc/chart
                                         mo/count        2
                                         mo/child-params (fn [_ _ _]
                                                           {:src ::ms-child})})
                         (transition {:event :go-shoot}
                           (mux/send {:target :judges :idx 0 :event :ping}))))
        {:keys [env wmstore]} (run-parent-with-multiplex!
                                {:parent-chart parent-chart
                                 :children     {::ms-child mux-send-listener-child}})]
    (sp/send! (::sc/event-queue env) env
              {:target :parent-session :event :fire :data {}
               :source-session-id :external})
    (drain! env)
    (sp/send! (::sc/event-queue env) env
              {:target :parent-session :event :go-shoot :data {}
               :source-session-id :external})
    (drain! env)
    (assertions
      "judges.0 received :ping"
      (contains? (::sc/configuration (sp/get-working-memory wmstore env :multiplex.judges.0))
                 :child/heard) => true
      "judges.1 did NOT receive :ping (idx targeting within the chosen mux)"
      (contains? (::sc/configuration (sp/get-working-memory wmstore env :multiplex.judges.1))
                 :child/heard) => false
      "auditors.0 did NOT receive :ping (other mux filtered out by target-mux)"
      (contains? (::sc/configuration (sp/get-working-memory wmstore env :multiplex.auditors.0))
                 :child/heard) => false
      "auditors.1 did NOT receive :ping"
      (contains? (::sc/configuration (sp/get-working-memory wmstore env :multiplex.auditors.1))
                 :child/heard) => false)))

(specification "forward-event! honors mo/target-mux discriminator"
  (component "non-matching target-mux: this multiplex no-ops"
    (let [{:keys [queue processor parent-env]}
          (seeded-mux-fixture {:mux-id   :j
                               :children [:multiplex.j.0 :multiplex.j.1]})
          ;; Also seed a sibling :k with its own children
          _ (swap! (:state processor) assoc :k
                   {:children [:multiplex.k.0] :child-type ::sc/chart})
          result (sp/forward-event! processor parent-env
                   {:invokeid :j
                    :event    {:name :ping
                               :data {mo/target-mux :k :payload :x}}})]
      (assertions
        "forward-event! still returns true (no-op success)"
        result => true
        "no child of :j received the event"
        (received? queue :multiplex.j.0 :ping) => false
        (received? queue :multiplex.j.1 :ping) => false
        "no child of :k received either (we only called forward-event! on :j)"
        (received? queue :multiplex.k.0 :ping) => false)))

  (component "matching target-mux: this multiplex delivers and strips the discriminator"
    (let [{:keys [queue processor parent-env]}
          (seeded-mux-fixture {:mux-id   :j
                               :children [:multiplex.j.0 :multiplex.j.1]})
          _ (sp/forward-event! processor parent-env
              {:invokeid :j
               :event    {:name :hello
                          :data {mo/target-mux :j :payload :x}}})
          evt (first (events-named queue :multiplex.j.0 :hello))]
      (assertions
        "j's children received the event"
        (received? queue :multiplex.j.0 :hello) => true
        (received? queue :multiplex.j.1 :hello) => true
        "mo/target-mux is stripped from the delivered event"
        (contains? (:data evt) mo/target-mux) => false
        "payload survives"
        (:payload (:data evt)) => :x
        "mo/from records the sender"
        (:sid (get (:data evt) mo/from)) => :gp))))

;;;; -------------------------------------------------------------------
;;;; Phase 4 — Async test infrastructure
;;;;
;;;; CLJ-only. Async tests under CLJS would need cljs.test/async with
;;;; (p/then ...) chains because microtask-deferred promises cannot be
;;;; blocked on in test bodies. The Phase-2 promise threading lives in
;;;; production code (multiplex_processor.cljc await-all + maybe-then) so
;;;; CLJS still benefits at runtime; we just don't author async-specific
;;;; CLJS test cases here. Future work: add a CLJS variant using
;;;; cljs.test/async + p/then for the happy-path async cases.
;;;; -------------------------------------------------------------------

#?(:clj
   (defn build-async-env []
     (mp/with-multiplex (simple-async/simple-env))))

#?(:clj
   (defn async-drain!
     "Like `drain!` but awaits the promise that `receive-events!` returns
      under the async processor. Modeled on `irp/runner.cljc:20-83`.
      CLJ-only — uses `p/await!`."
     ([env] (async-drain! env 100))
     ([env max-iters]
      (let [q (::sc/event-queue env)]
        (loop [i max-iters]
          (let [before @(:session-queues q)
                result (sp/receive-events! q env ep/standard-statechart-event-handler)]
            ;; Receive-events! may return a promise under async; await it.
            (when (p/promise? result) (p/await! result))
            (let [after @(:session-queues q)]
              (when (and (pos? i) (not= before after))
                (recur (dec i))))))))))

#?(:clj
   (defn run-parent-with-multiplex-async!
     "Async sibling of `run-parent-with-multiplex!`. Builds an async env,
      registers children + parent, starts (awaiting the start promise),
      drains async. Returns {:env :wmstore :parent-session-id}."
     [{:keys [parent-chart children session-id max-drain-iters]
       :or   {children        {::simple-child simple-child}
              session-id      :parent-session
              max-drain-iters 100}}]
     (let [env (build-async-env)]
       (doseq [[k v] children] (simple-async/register! env k v))
       (simple-async/register! env ::parent-chart parent-chart)
       (let [start-result (simple-async/start! env ::parent-chart session-id)]
         (when (p/promise? start-result) (p/await! start-result)))
       (async-drain! env max-drain-iters)
       {:env               env
        :wmstore           (::sc/working-memory-store env)
        :parent-session-id session-id})))

;;;; -------------------------------------------------------------------
;;;; Phase 4 — Async tests
;;;; -------------------------------------------------------------------

#?(:clj
   (specification "async: happy path — N children all complete under async processor"
     (let [parent-chart (chart/statechart {}
                          (state {:id :parent/active}
                            (mux/multiplex {:id            :judges
                                            mo/child-type  ::sc/chart
                                            mo/count       3
                                            mo/child-params (fn [_ _ _]
                                                              {:src ::simple-child})})
                            (transition {:event  :done.invoke.judges
                                         :target :parent/complete}))
                          (state {:id :parent/complete}))
           {:keys [env wmstore]} (run-parent-with-multiplex-async! {:parent-chart parent-chart})
           parent-wmem (sp/get-working-memory wmstore env :parent-session)]
       (assertions
         "parent transitions to complete after all children done under async"
         (contains? (::sc/configuration parent-wmem) :parent/complete) => true))))

(def async-resolved-child
  "Child chart whose on-entry returns (p/resolved [...]) — its script
   yields a promise that resolves to nil (no operations). The child
   then transitions immediately to done."
  (chart/statechart {}
    (state {:id :child/start}
      (e/on-entry {}
        (e/script {:expr (fn [_ _] (p/resolved nil))}))
      (transition {:target :child/done}))
    (final {:id :child/done})))

#?(:clj
   (specification "async: children with promise-returning on-entry expressions"
     (let [cohort-hits  (atom 0)
           parent-chart (chart/statechart {}
                          (state {:id :parent/active}
                            (mux/multiplex {:id            :judges
                                            mo/child-type  ::sc/chart
                                            mo/count       3
                                            mo/child-params (fn [_ _ _]
                                                              {:src ::async-child})})
                            (transition {:event :done.invoke.judges
                                         :target :parent/complete}
                              (e/script {:expr (fn [_ _]
                                                 (swap! cohort-hits inc) nil)})))
                          (state {:id :parent/complete}))
           {:keys [env wmstore]} (run-parent-with-multiplex-async!
                                   {:parent-chart parent-chart
                                    :children     {::async-child async-resolved-child}})
           parent-wmem (sp/get-working-memory wmstore env :parent-session)]
       (assertions
         "parent reached complete with all N async-expr children done"
         (contains? (::sc/configuration parent-wmem) :parent/complete) => true
         "cohort done.invoke.<mux-id> fires exactly once"
         @cohort-hits => 1))))

#?(:clj
   (specification "async: cancellation cleans up all child wmem (no orphans)"
     (let [parent-chart (chart/statechart {}
                          (state {:id :parent/active}
                            (mux/multiplex {:id            :cx
                                            mo/child-type  ::sc/chart
                                            mo/count       2
                                            mo/child-params (fn [_ _ _]
                                                              {:src ::waiting-child})})
                            (transition {:event :abort :target :parent/dead}))
                          (state {:id :parent/dead}))
           {:keys [env wmstore]} (run-parent-with-multiplex-async!
                                   {:parent-chart parent-chart
                                    :children     {::waiting-child waiting-child}})]
       (assertions
         "children alive before abort under async"
         (some? (sp/get-working-memory wmstore env :multiplex.cx.0)) => true
         (some? (sp/get-working-memory wmstore env :multiplex.cx.1)) => true
         "aggregator alive"
         (some? (sp/get-working-memory wmstore env :cx)) => true)
       (sp/send! (::sc/event-queue env) env
                 {:target :parent-session :event :abort :data {}
                  :source-session-id :external})
       (async-drain! env)
       (assertions
         "all child wmem cleaned up under async"
         (some? (sp/get-working-memory wmstore env :multiplex.cx.0)) => false
         (some? (sp/get-working-memory wmstore env :multiplex.cx.1)) => false
         "aggregator wmem also cleaned up"
         (some? (sp/get-working-memory wmstore env :cx)) => false))))

#?(:clj
   (specification "async: mo/count = 0 still fires done.invoke immediately"
     (let [parent-chart (chart/statechart {}
                          (state {:id :parent/active}
                            (mux/multiplex {:id            :empty
                                            mo/child-type  ::sc/chart
                                            mo/count       0
                                            mo/child-params (fn [_ _ _]
                                                              {:src ::simple-child})})
                            (transition {:event  :done.invoke.empty
                                         :target :parent/complete}))
                          (state {:id :parent/complete}))
           {:keys [env wmstore]} (run-parent-with-multiplex-async! {:parent-chart parent-chart})
           parent-wmem (sp/get-working-memory wmstore env :parent-session)]
       (assertions
         "count=0 still fires done.invoke immediately under async"
         (contains? (::sc/configuration parent-wmem) :parent/complete) => true))))

#?(:clj
   (specification "async: multiplex's own start-invocation! awaits microtask-deferred children"
     ;; Use a test-only InvocationProcessor whose start-invocation! returns a
     ;; microtask-deferred promise. Multiplex's own start-invocation! MUST
     ;; return a promise that resolves only after all child start promises
     ;; resolve (Phase 2 promise threading).
     (let [started   (atom 0)
           resolved? (atom 0)
           test-proc (reify sp/InvocationProcessor
                       (supports-invocation-type? [_ t] (= t ::deferred-proc))
                       (start-invocation! [_ _env _args]
                         (swap! started inc)
                         ;; Microtask-deferred promise: create + deliver from
                         ;; another thread. Forces multiplex's start to chain
                         ;; on it via maybe-then rather than seeing a sync val.
                         (p/create (fn [resolve _reject]
                                     (future
                                       (Thread/sleep 1)
                                       (swap! resolved? inc)
                                       (resolve true)))))
                       (stop-invocation! [_ _env _args] true)
                       (forward-event! [_ _env _args] true))
           env       (-> (simple-async/simple-env)
                         (update ::sc/invocation-processors (fnil conj []) test-proc)
                         (mp/with-multiplex))
           mux-proc  (first (filter #(sp/supports-invocation-type? % mo/type)
                                    (::sc/invocation-processors env)))
           parent-env (assoc env ::sc/vwmem (volatile! {::sc/session-id :gp}))
           result    (sp/start-invocation! mux-proc parent-env
                       {:invokeid :probe
                        :params   {mo/config {mo/child-type   ::deferred-proc
                                              mo/count        3
                                              mo/child-params (fn [_ _ _] {:params {}})}}})]
       (assertions
         "multiplex's start-invocation! returned a promise"
         (p/promise? result) => true)
       ;; Await it. After this, ALL child start promises must have resolved.
       (let [final (p/await! result)]
         (assertions
           "promise resolves to a truthy value"
           (boolean final) => true
           "all 3 child start-invocations were called"
           @started => 3
           "all 3 child start promises resolved before multiplex's promise settled"
           @resolved? => 3)))))

#?(:clj
   (specification "async: forward-event delivers to targeted child after drain"
     (let [parent-chart (chart/statechart {}
                          (state {:id :parent/idle}
                            (transition {:event :fire :target :parent/active}))
                          (state {:id :parent/active}
                            (mux/multiplex {:id             :judges
                                            mo/child-type   ::sc/chart
                                            mo/count        2
                                            mo/child-params (fn [_ _ _]
                                                              {:src ::ms-child})})
                            (transition {:event :go-shoot}
                              (mux/send {:target :judges :idx 1 :event :ping}))))
           {:keys [env wmstore]} (run-parent-with-multiplex-async!
                                   {:parent-chart parent-chart
                                    :children     {::ms-child mux-send-listener-child}})]
       (sp/send! (::sc/event-queue env) env
                 {:target :parent-session :event :fire :data {}
                  :source-session-id :external})
       (async-drain! env)
       (sp/send! (::sc/event-queue env) env
                 {:target :parent-session :event :go-shoot :data {}
                  :source-session-id :external})
       (async-drain! env)
       (assertions
         "idx 1 child received :ping under async"
         (contains? (::sc/configuration (sp/get-working-memory wmstore env :multiplex.judges.1))
                    :child/heard) => true
         "idx 0 child did NOT receive :ping"
         (contains? (::sc/configuration (sp/get-working-memory wmstore env :multiplex.judges.0))
                    :child/heard) => false))))

#?(:clj
   (specification "async: mux/reply round-trip delivers full mo/from to grandparent"
     (let [captured     (atom [])
           parent-chart (chart/statechart {}
                          (state {:id :parent/active}
                            (mux/multiplex {:id            :judges
                                            mo/child-type  ::sc/chart
                                            mo/count       2
                                            mo/child-params (fn [_ _ _]
                                                              {:src ::reply-child})})
                            (transition {:event :ack}
                              (e/script {:expr (fn [_ data]
                                                 (swap! captured conj
                                                        (get-in data [:_event :data]))
                                                 nil)}))))]
       (run-parent-with-multiplex-async!
         {:parent-chart parent-chart
          :children     {::reply-child reply-on-entry-child}})
       (let [acks         @captured
             sorted-by-idx (sort-by #(get-in % [mo/from :idx]) acks)]
         (assertions
           "grandparent received exactly two :ack events under async"
           (count acks) => 2
           "idx 0 ack carries full mo/from"
           (get-in (first sorted-by-idx) [mo/from :idx]) => 0
           (get-in (first sorted-by-idx) [mo/from :sid]) => :multiplex.judges.0
           (get-in (first sorted-by-idx) [mo/from :invokeid]) => :judges
           (get-in (first sorted-by-idx) [mo/from :grandparent]) => :parent-session
           "idx 1 ack carries idx 1"
           (get-in (second sorted-by-idx) [mo/from :idx]) => 1)))))

#?(:clj
   (specification "async: nested multiplex spawns N*M leaves with non-colliding sids"
     ;; Async parity check for the existing sync `nested multiplex spawns N*M
     ;; leaves` spec. The sync test only verifies leaf existence (the outer
     ;; aggregator's done-event propagation in nested multiplexes is an
     ;; existing limitation independent of async), so we mirror those
     ;; assertions here. Future work: investigate why
     ;; `done.invoke.<outer-mux-id>` does NOT reach the grandparent even
     ;; under sync when the inner multiplex's child is itself a chart that
     ;; embeds a multiplex.
     (let [parent-chart (chart/statechart {}
                          (state {:id :parent/active}
                            (mux/multiplex {:id            :outer
                                            mo/child-type  ::sc/chart
                                            mo/count       2
                                            mo/child-params (fn [_ _ _]
                                                              {:src ::inner-mux})})))
           {:keys [env wmstore]} (run-parent-with-multiplex-async!
                                   {:parent-chart    parent-chart
                                    :children        {::inner-mux    inner-mux-chart
                                                      ::simple-child simple-child}
                                    :max-drain-iters 200})]
       (assertions
         "outer child 0 exists under async"
         (some? (sp/get-working-memory wmstore env :multiplex.outer.0)) => true
         "outer child 1 exists under async"
         (some? (sp/get-working-memory wmstore env :multiplex.outer.1)) => true
         "outer aggregator exists under async"
         (some? (sp/get-working-memory wmstore env :outer)) => true))))
