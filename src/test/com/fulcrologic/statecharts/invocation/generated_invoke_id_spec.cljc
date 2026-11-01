(ns com.fulcrologic.statecharts.invocation.generated-invoke-id-spec
  "An `invoke` with neither `:id` nor `:idlocation` gets a generated id (SCXML): leaving its state stops the session it
   started, an event from that session runs the invoke's `finalize`, and `:autoforward` reaches it. With the sync and
   the async processor."
  (:refer-clojure :exclude [send])
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
    [com.fulcrologic.statecharts.elements :refer [finalize invoke script send state transition]]
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [com.fulcrologic.statecharts.promise :as p]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.simple :as simple]
    [com.fulcrologic.statecharts.simple-async :as simple-async]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(def child-chart
  "Runs until stopped. `:ping` records `:pinged?` and sends `:pong` to its parent."
  (chart/statechart {:initial :child/running}
    (state {:id :child/running}
      (transition {:event :ping}
        (script {:expr (fn [_ _] [(ops/assign :pinged? true)])})
        (send {:target :_parent :event :pong})))))

(def parent-chart
  "`:open` enters `:open`, which invokes `child-chart` with no `:id` or `:idlocation` (forwarding its events to it,
   and counting the child's events in `finalize`); `:close` leaves it."
  (chart/statechart {:initial :idle}
    (state {:id :idle}
      (transition {:event :open :target :open}))
    (state {:id :open}
      (invoke {:type :statechart :src ::child :autoforward true}
        (finalize {}
          (script {:expr (fn [_ data] [(ops/assign :finalized (inc (or (:finalized data) 0)))])})))
      (transition {:event :close :target :idle}))))

(defn- settled [v]
  #?(:clj  (if (p/promise? v) (deref v 2000 ::timeout) v)
     :cljs v))

(defn- process-all!
  "Delivers the queued events of `env` until none is due (at most 20 rounds), saving each session's result."
  [{::sc/keys [event-queue processor working-memory-store] :as env}]
  (loop [n 0]
    (when (and (< n 20) (mpq/has-due-events? event-queue))
      (sp/receive-events! event-queue env
        (fn [env {:keys [target] :as event}]
          (when-let [wmem (sp/get-working-memory working-memory-store env target)]
            (when-let [next-wmem (settled (sp/process-event! processor env wmem event))]
              (sp/save-working-memory! working-memory-store env target next-wmem)))))
      (recur (inc n)))))

(defn- send! [env event]
  (sp/send! (::sc/event-queue env) env {:target :parent :event event})
  (process-all! env))

(defn- sessions [env] @(:storage (::sc/working-memory-store env)))

(defn- children [env]
  (into {} (filter (fn [[_ wmem]] (= :parent (::sc/parent-session-id wmem)))) (sessions env)))

(defn- data [wmem] (::wmdm/data-model wmem))

(defn- new-env [async?]
  (let [env (if async? (simple-async/simple-env) (simple/simple-env))]
    (simple/register! env ::child child-chart)
    (simple/register! env ::parent parent-chart)
    (settled ((if async? simple-async/start! simple/start!) env ::parent :parent))
    env))

(specification "An invoke without an :id or :idlocation"
  (doseq [async? #?(:clj [false true] :cljs [false])]
    (component (if async? "with the async processor" "with the sync processor")
      (let [env (new-env async?)]
        (send! env :open)
        (let [[child-id child] (first (children env))]
          (assertions
            "starts one child session, with an id generated from its state"
            (count (children env)) => 1
            (some? child) => true
            (subs (str child-id) 0 6) => ":open.")
          (send! env :ping)
          (assertions
            "forwards the parent's events to it (:autoforward)"
            (:pinged? (data (get (sessions env) child-id))) => true
            "and runs its finalize for the events the child sends"
            (:finalized (data (get (sessions env) :parent))) => 1))
        (send! env :close)
        (assertions
          "stops the child when its state is exited"
          (count (children env)) => 0
          "and drops the id it generated from the parent's working memory"
          (contains? (get (sessions env) :parent) ::sc/invocation-ids) => false)
        (dotimes [_ 3]
          (send! env :open)
          (send! env :close))
        (assertions
          "every time: leaving and re-entering the state N times leaves no child running"
          (count (children env)) => 0
          "(the parent itself still runs)"
          (::sc/configuration (get (sessions env) :parent)) => #{:idle})))))
