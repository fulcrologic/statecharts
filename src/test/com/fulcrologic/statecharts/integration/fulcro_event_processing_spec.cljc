(ns com.fulcrologic.statecharts.integration.fulcro-event-processing-spec
  "Event processing of the Fulcro integration: a nested `process-events!` (from inside a step) leaves the event for
   later instead of processing it against the step's stale working memory, `start!` under `:event-loop?
   :immediate` saves the new session before it processes the events its initial entry caused, and `:immediate`
   processing stops once no queued event is due.

   With `:async? true` (CLJ), a session whose working memory isn't saved yet (a start whose entry is pending) holds
   only its own events back: other sessions' events are processed meanwhile."
  (:require
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.algorithms.tx-processing.synchronous-tx-processing :as stx]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :refer [statechart]]
    [com.fulcrologic.statecharts.convenience :refer [send-after]]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [com.fulcrologic.statecharts.elements :as ele :refer [invoke on-entry script state transition]]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.async-operations :as afop]
    [com.fulcrologic.statecharts.integration.fulcro.operations :as fops]
    [com.fulcrologic.statecharts.promise :as p]
    [com.fulcrologic.statecharts.util :refer [now-ms]]
    [fulcro-spec.core :refer [=> =throws=> assertions component specification when-mocking]]))

;; ===== Re-entrancy =====

(def reentrant-chart
  "`:go` leaves `:a` for `:b`; its content sends `:edit` and processes the queue from inside the step."
  (statechart {:initial :a}
    (state {:id :a}
      (transition {:event :go :target :b}
        (script {:expr (fn [env & _]
                         (let [a (:fulcro/app env)]
                           (scf/send! a :s :edit {})
                           (scf/process-events! a))
                         nil)}))
      (transition {:event :edit :target :c}
        (script {:expr (fn [& _] [(ops/assign :edit-handled-in :a)])})))
    (state {:id :b}
      (transition {:event :edit}
        (script {:expr (fn [& _] [(ops/assign :edit-handled-in :b)])})))
    (state {:id :c})))

(defn- reentrant-app [event-loop]
  (let [a (app/fulcro-app {})]
    (scf/install-fulcro-statecharts! a {:event-loop? event-loop})
    (scf/register-statechart! a ::reentrant reentrant-chart)
    (scf/start! a {:machine ::reentrant :session-id :s :data {}})
    a))

(defn- edit-handled-in [a] (get-in (app/current-state a) [::sc/local-data :s :edit-handled-in]))

(specification "A process-events! nested in a step of the same app"
  (component "with a manual event loop"
    (let [a (reentrant-app false)]
      (scf/send! a :s :go {})
      (scf/process-events! a)
      (assertions
        "returns at once: the step's own result is saved"
        (scf/current-configuration a :s) => #{:b}
        "and the nested event wasn't processed against the step's stale working memory"
        (edit-handled-in a) => nil)
      (scf/process-events! a)
      (assertions
        "the event waits for the next processing, which sees the step's result"
        (edit-handled-in a) => :b
        (scf/current-configuration a :s) => #{:b})))
  (component "with :immediate processing"
    (let [a (reentrant-app :immediate)]
      (scf/send! a :s :go {})
      (assertions
        "the outer processing handles the event after the step"
        (edit-handled-in a) => :b
        (scf/current-configuration a :s) => #{:b})))
  #?(:clj
     (component "with the event loop"
       (let [a        (reentrant-app true)
             running? (:events-running-atom (scf/statechart-env a))]
         (try
           (scf/send! a :s :go {})
           (loop [n 0]
             (when (and (< n 200) (nil? (edit-handled-in a)))
               (Thread/sleep 10)
               (recur (inc n))))
           (assertions
             "the loop's next tick handles the event after the step"
             (edit-handled-in a) => :b
             (scf/current-configuration a :s) => #{:b})
           (finally
             (when running? (reset! running? false))))))))

;; ===== start! under :immediate =====

(defsc Thing [_ _]
  {:query [:thing/id]
   :ident :thing/id})

(def loading-chart
  "Loads on its initial entry and moves to `:state/ready` on the load's ok-event."
  (statechart {:initial :state/loading}
    (state {:id :state/loading}
      (on-entry {}
        (script {:expr (fn [& _] [(fops/load :thing Thing {::sc/ok-event :event/loaded})])}))
      (transition {:event :event/loaded :target :state/ready}))
    (state {:id :state/ready})))

(def mutating-chart
  "Runs a remote mutation on its initial entry and moves to `:state/ready` on its ok-event."
  (statechart {:initial :state/saving}
    (state {:id :state/saving}
      (on-entry {}
        (script {:expr (fn [& _] [(fops/invoke-remote `[(save-thing {})] {:ok-event :event/saved})])}))
      (transition {:event :event/saved :target :state/ready}))
    (state {:id :state/ready})))

(defn- answering-app
  "An app with synchronous transactions whose remote answers every request at once (inside `transmit!`) with `body`."
  [body]
  (let [a (stx/with-synchronous-transactions
            (app/fulcro-app {:remotes {:remote {:transmit! (fn [_ {::txn/keys [result-handler]}]
                                                             (result-handler {:status-code 200 :body body}))}}}))]
    (scf/install-fulcro-statecharts! a {:event-loop? :immediate})
    a))

(specification "start! with :immediate processing and a remote that answers at once"
  (let [a (answering-app {:thing {:thing/id 1}})]
    (scf/register-statechart! a ::loading loading-chart)
    (scf/start! a {:machine ::loading :session-id :loader})
    (assertions
      "saves the session before processing its initial entry's events: a load's ok-event is handled"
      (scf/current-configuration a :loader) => #{:state/ready}
      "(the load merged)"
      (get-in (app/current-state a) [:thing/id 1]) => {:thing/id 1}))
  (let [a (answering-app {`save-thing {}})]
    (scf/register-statechart! a ::mutating mutating-chart)
    (scf/start! a {:machine ::mutating :session-id :saver})
    (assertions
      "and so is a remote mutation's"
      (scf/current-configuration a :saver) => #{:state/ready})))

;; ===== :immediate with a delayed event pending =====

(def delayed-chart
  "Sends itself `:timeout` one second after entering `:waiting`, and counts the `:bump`s it gets there."
  (statechart {:initial :waiting}
    (state {:id :waiting}
      (send-after {:id :timer :event :timeout :delay 1000})
      (transition {:event :timeout :target :timed-out})
      (transition {:event :bump}
        (script {:expr (fn [_ data & _] [(ops/assign :bumps (inc (or (:bumps data) 0)))])})))
    (state {:id :timed-out})))

(specification "Sends with :immediate processing while a delayed event is pending"
  (let [clock       (atom 1000)
        clock-reads (atom 0)
        bumps       (fn [a] (get-in (app/current-state a) [::sc/local-data :d :bumps]))]
    (when-mocking
      (now-ms) => (do (swap! clock-reads inc) @clock)

      (let [a (app/fulcro-app {})]
        (scf/install-fulcro-statecharts! a {:event-loop? :immediate})
        (scf/register-statechart! a ::delayed delayed-chart)
        (scf/start! a {:machine ::delayed :session-id :d})
        (reset! clock-reads 0)
        (scf/send! a :d :bump {})
        (let [reads-for-one-send @clock-reads]
          (scf/send! a :d :bump {})
          (assertions
            "process the events sent"
            (bumps a) => 2
            "and stop polling the queue once no event is due (every poll reads the clock), instead of polling it up to the drain loop's limit (100 times)"
            (< reads-for-one-send 10) => true
            "without delivering the delayed event before it's due"
            (scf/current-configuration a :d) => #{:waiting}))
        (reset! clock 2000)
        (scf/send! a :d :bump {})
        (assertions
          "the first processing at its delivery time delivers it (before the event sent after it)"
          (scf/current-configuration a :d) => #{:timed-out}
          (bumps a) => 2)))))

;; ===== :async? true: a session that isn't saved yet holds back only its own events =====

(def ^:private gate
  "The resolve fn of the last `pending-promise`."
  (atom nil))

(defn- pending-promise
  "A promise that settles when `open-gate!` is called."
  []
  (p/create (fn [resolve _] (reset! gate resolve))))

(defn- open-gate! [] (@gate true))

(defn- bump-transition []
  (transition {:event :bump}
    (script {:expr (fn [_ data & _] [(ops/assign :count (inc (or (:count data) 0)))])})))

(def counter-chart
  "Counts the `:bump`s it gets."
  (statechart {:initial :idle}
    (state {:id :idle}
      (bump-transition))))

(def slow-entry-chart
  "Its initial entry returns a promise that settles on `open-gate!`. Counts the `:bump`s it gets."
  (statechart {:initial :loading}
    (state {:id :loading}
      (on-entry {}
        (script {:expr (fn [& _] (p/then (pending-promise) (fn [_] nil)))}))
      (bump-transition))))

(def notifying-slow-chart
  "Its initial entry sends `:bump` to the session `:other`, then returns a promise that settles on `open-gate!`."
  (statechart {:initial :loading}
    (state {:id :loading}
      (on-entry {}
        (script {:expr (fn [env & _]
                         (scf/send! (:fulcro/app env) :other :bump {})
                         (p/then (pending-promise) (fn [_] nil)))})))))

(def launcher-chart
  "`:launch` starts a `slow-entry-chart` session `:started` from inside its step."
  (statechart {:initial :idle}
    (state {:id :idle}
      (transition {:event :launch}
        (script {:expr (fn [env & _]
                         (scf/start! (:fulcro/app env) {:machine ::slow-entry :session-id :started})
                         nil)})))))

(defn- async-app
  "An `:async? true` app with `event-loop` processing and the test charts registered. `options` are merged into the
   install options."
  ([event-loop] (async-app event-loop {}))
  ([event-loop options]
   (let [a (app/fulcro-app {})]
     (scf/install-fulcro-statecharts! a (merge {:event-loop? event-loop :async? true} options))
     (scf/register-statechart! a ::counter counter-chart)
     (scf/register-statechart! a ::slow-entry slow-entry-chart)
     (scf/register-statechart! a ::launcher launcher-chart)
     (scf/register-statechart! a ::notifying-slow notifying-slow-chart)
     a)))

(defn- stop-loop! [a] (when-let [running? (:events-running-atom (scf/statechart-env a))] (reset! running? false)))

(defn- bumps [a session-id] (get-in (app/current-state a) [::sc/local-data session-id :count]))

#?(:clj
   (defn- settle!
     "Lets `a`'s `event-loop` deliver what it can: `false` processes the events, `true` (the loop) waits until `done?`
      is true (or 2s), or 100ms when no `done?` is given. `:immediate` has done it already."
     ([a event-loop] (settle! a event-loop nil))
     ([a event-loop done?]
      (case event-loop
        false (scf/process-events! a)
        true (if done?
               (loop [n 0] (when (and (< n 200) (not (done?))) (Thread/sleep 10) (recur (inc n))))
               (Thread/sleep 100))
        nil))))

#?(:clj
   (specification "start! of an :async? chart whose initial entry hasn't settled"
     (doseq [event-loop [:immediate true false]]
       (component (str "with :event-loop? " event-loop)
         (let [a (async-app event-loop)]
           (try
             (let [_       (scf/start! a {:machine ::counter :session-id :other})
                   started (scf/start! a {:machine ::slow-entry :session-id :slow})]
               (scf/send! a :other :bump {})
               (settle! a event-loop #(= 1 (bumps a :other)))
               (scf/send! a :slow :bump {})
               (settle! a event-loop)
               (assertions
                 "processes other sessions' events meanwhile"
                 (bumps a :other) => 1
                 "holds back the starting session's events, since its working memory isn't saved yet"
                 (scf/current-configuration a :slow) => #{}
                 (bumps a :slow) => nil)
               (open-gate!)
               (settle! a event-loop #(= 1 (bumps a :slow)))
               (assertions
                 "saves the session once its entry settles"
                 (scf/current-configuration a :slow) => #{:loading}
                 "and then processes the event that waited for it"
                 (bumps a :slow) => 1
                 "start! returns a promise of the session id"
                 (deref started 1000 ::timeout) => :slow))
             (finally (stop-loop! a))))))))

#?(:clj
   (specification "start! of an :async? chart whose entry sends to another session before it awaits"
     (let [a (async-app :immediate)]
       (scf/start! a {:machine ::counter :session-id :other})
       (scf/start! a {:machine ::notifying-slow :session-id :slow})
       (assertions
         "processes that event (:immediate) once the start's synchronous part is over, before the entry settles"
         (bumps a :other) => 1
         (scf/current-configuration a :slow) => #{}))))

(def opener-chart
  "`:open` settles the pending promise (`open-gate!`) from inside its step."
  (statechart {:initial :idle}
    (state {:id :idle}
      (transition {:event :open}
        (script {:expr (fn [& _] (open-gate!) nil)})))))

#?(:clj
   (specification "start! of an :async? chart whose initial entry settles during another session's step"
     (let [a (async-app :immediate)]
       (scf/register-statechart! a ::opener opener-chart)
       (scf/start! a {:machine ::opener :session-id :opener})
       (scf/start! a {:machine ::slow-entry :session-id :slow})
       (scf/send! a :slow :bump {})
       (scf/send! a :opener :open {})
       (assertions
         "processes the started session's waiting events in that processing"
         (scf/current-configuration a :slow) => #{:loading}
         (bumps a :slow) => 1))))

#?(:clj
   (specification "start! of an :async? chart, from a step, whose initial entry hasn't settled"
     (let [a (async-app :immediate)]
       (scf/start! a {:machine ::launcher :session-id :launcher})
       (scf/send! a :launcher :launch {})
       (scf/send! a :started :bump {})
       (assertions
         "holds back the started session's events until it is saved"
         (bumps a :started) => nil)
       (open-gate!)
       (assertions
         "then processes them"
         (scf/current-configuration a :started) => #{:loading}
         (bumps a :started) => 1))))

(specification "A start! that throws (its :on-save throws)"
  (let [fail-save? (atom true)
        a          (app/fulcro-app {})]
    (scf/install-fulcro-statecharts! a {:event-loop? :immediate
                                        :on-save     (fn [session-id _]
                                                       (when (and (= :boom session-id) @fail-save?)
                                                         (throw (ex-info "save failed" {}))))})
    (scf/register-statechart! a ::counter counter-chart)
    (scf/start! a {:machine ::counter :session-id :other})
    (assertions
      "throws to its caller"
      (scf/start! a {:machine ::counter :session-id :boom}) =throws=> #"save failed")
    (scf/send! a :other :bump {})
    (assertions
      "leaves event processing free: a later send is processed"
      (bumps a :other) => 1)
    (reset! fail-save? false)
    (scf/start! a {:machine ::counter :session-id :boom})
    (scf/send! a :boom :bump {})
    (assertions
      "and doesn't hold its session back: the events of a later start of the same session are processed"
      (bumps a :boom) => 1)))

(specification "A start! whose :on-save throws after the session was saved"
  (let [fail-save? (atom true)
        a          (app/fulcro-app {})]
    (scf/install-fulcro-statecharts! a {:event-loop? :immediate
                                        :on-save     (fn [session-id _]
                                                       (when (and (= :boom session-id) @fail-save?)
                                                         (throw (ex-info "save failed" {}))))})
    (scf/register-statechart! a ::counter counter-chart)
    (assertions
      "throws to its caller"
      (scf/start! a {:machine ::counter :session-id :boom}) =throws=> #"save failed")
    (reset! fail-save? false)
    (scf/send! a :boom :bump {})
    (assertions
      "releases its session: the session it saved gets its events, without a restart"
      (bumps a :boom) => 1)))

#?(:clj
   (specification "start! of an :async? chart whose saved start fails (its :on-save throws once the entry settles)"
     (let [fail-save? (atom true)
           a          (async-app :immediate {:on-save (fn [session-id _]
                                                        (when (and (= :boom session-id) @fail-save?)
                                                          (throw (ex-info "save failed" {}))))})
           started    (scf/start! a {:machine ::slow-entry :session-id :boom})]
       (open-gate!)
       (assertions
         "returns a promise that rejects"
         (try (deref started 1000 ::timeout) (catch Exception e (some-> e ex-cause ex-message))) => "save failed")
       (reset! fail-save? false)
       (scf/start! a {:machine ::slow-entry :session-id :boom})
       (open-gate!)
       (scf/send! a :boom :bump {})
       (assertions
         "and doesn't hold its session back: the events of a later start of the same session are processed"
         (bumps a :boom) => 1))))

(def awaiting-chart
  "Awaits a load on its initial entry (`afop/await-load`) and moves to `:state/ready` on its ok-event."
  (statechart {:initial :state/loading}
    (state {:id :state/loading}
      (on-entry {}
        (script {:expr (fn [env & _] (afop/await-load env :thing Thing {::sc/ok-event :event/loaded}))}))
      (transition {:event :event/loaded :target :state/ready}))
    (state {:id :state/ready})))

#?(:clj
   (specification "start! of an :async? chart with :immediate processing and a remote that answers at once"
     (let [answering (fn []
                       (let [a (stx/with-synchronous-transactions
                                 (app/fulcro-app {:remotes {:remote {:transmit! (fn [_ {::txn/keys [result-handler]}]
                                                                                  (result-handler {:status-code 200 :body {:thing {:thing/id 1}}}))}}}))]
                         (scf/install-fulcro-statecharts! a {:event-loop? :immediate :async? true})
                         (scf/register-statechart! a ::loading loading-chart)
                         (scf/register-statechart! a ::awaiting awaiting-chart)
                         a))]
       (let [a (answering)]
         (scf/start! a {:machine ::loading :session-id :loader})
         (assertions
           "handles an on-entry load's ok-event"
           (scf/current-configuration a :loader) => #{:state/ready}))
       (let [a (answering)]
         (scf/start! a {:machine ::awaiting :session-id :awaiter})
         (assertions
           "and an awaited load's"
           (scf/current-configuration a :awaiter) => #{:state/ready})))))

;; ===== :async? true: a step whose promise hasn't settled =====

(def slow-step-chart
  "`:go` leaves `:a` for `:b` with a script that returns a promise that settles on `open-gate!`. `:edit` records the
   state that handled it."
  (statechart {:initial :a}
    (state {:id :a}
      (transition {:event :go :target :b}
        (script {:expr (fn [& _] (p/then (pending-promise) (fn [_] nil)))}))
      (transition {:event :edit :target :c}
        (script {:expr (fn [& _] [(ops/assign :edit-handled-in :a)])})))
    (state {:id :b}
      (transition {:event :edit}
        (script {:expr (fn [& _] [(ops/assign :edit-handled-in :b)])})))
    (state {:id :c})))

(defn- slow-step-app
  ([event-loop] (slow-step-app event-loop {}))
  ([event-loop options]
   (let [a (async-app event-loop options)]
     (scf/register-statechart! a ::slow-step slow-step-chart)
     a)))

(defn- edit-handled-in* [a] (get-in (app/current-state a) [::sc/local-data :s :edit-handled-in]))

#?(:clj
   (specification "An :async? step whose promise hasn't settled"
     (doseq [event-loop [:immediate true false]]
       (component (str "with :event-loop? " event-loop)
         (let [a (slow-step-app event-loop)]
           (try
             (scf/start! a {:machine ::counter :session-id :other})
             (scf/start! a {:machine ::slow-step :session-id :s})
             (settle! a event-loop #(= #{:a} (scf/current-configuration a :s)))
             (scf/send! a :s :go {})
             (settle! a event-loop)
             (scf/send! a :s :edit {})
             (scf/send! a :other :bump {})
             (settle! a event-loop #(= 1 (bumps a :other)))
             (assertions
               "processes other sessions' events meanwhile"
               (bumps a :other) => 1
               "holds back the session's later events, since the step's working memory isn't saved yet"
               (edit-handled-in* a) => nil
               (scf/current-configuration a :s) => #{:a})
             (open-gate!)
             (settle! a event-loop #(some? (edit-handled-in* a)))
             (assertions
               "saves the step's result once its promise settles"
               (scf/current-configuration a :s) => #{:b}
               "and then processes the event that waited, against that result"
               (edit-handled-in* a) => :b)
             (finally (stop-loop! a))))))))

#?(:clj
   (specification "An :async? step whose promise rejects (its :on-save throws once it settles)"
     (let [fail-save? (atom false)
           a          (slow-step-app :immediate {:on-save (fn [session-id _]
                                                            (when (and (= :s session-id) @fail-save?)
                                                              (throw (ex-info "save failed" {}))))})]
       (scf/start! a {:machine ::slow-step :session-id :s})
       (scf/send! a :s :go {})
       (reset! fail-save? true)
       (open-gate!)
       (reset! fail-save? false)
       (scf/send! a :s :edit {})
       (assertions
         "doesn't hold its session back: its later events are processed"
         (edit-handled-in* a) => :b))))

;; ===== :async? true: a held session's queued events, restarts and stops =====

(def timeout-chart
  "`:go` enters `:loading`, which schedules a `:timeout` one second later (`send-after`, cancelled on exit). Its child
   `:fetching` awaits a promise (settled by `open-gate!`) on entry, then leaves `:loading` for `:loaded` at once."
  (statechart {:initial :top}
    (state {:id :top :initial :idle}
      (transition {:event :timeout :target :timed-out})
      (state {:id :idle}
        (transition {:event :go :target :loading}))
      (state {:id :loading :initial :fetching}
        (send-after {:id :timer :event :timeout :delay 1000})
        (state {:id :fetching}
          (on-entry {}
            (script {:expr (fn [& _] (p/then (pending-promise) (fn [_] nil)))}))
          (transition {:target :loaded})))
      (state {:id :loaded})
      (state {:id :timed-out}))))

#?(:clj
   (specification "A delayed event that falls due while its session's step is pending, then is cancelled by that step"
     (doseq [event-loop [:immediate true false]]
       (component (str "with :event-loop? " event-loop)
         (let [clock (atom 1000)
               reads (atom 0)]
           (when-mocking
             (now-ms) => (do (swap! reads inc) @clock)

             (let [a (async-app event-loop)]
               (try
                 (scf/register-statechart! a ::timeout timeout-chart)
                 (scf/start! a {:machine ::counter :session-id :other})
                 (scf/start! a {:machine ::timeout :session-id :s})
                 (settle! a event-loop #(= #{:top :idle} (scf/current-configuration a :s)))
                 (scf/send! a :s :go {})
                 (settle! a event-loop)
                 (reset! clock 2500)
                 (reset! reads 0)
                 ;; the timeout is due now; a processing round passes over it while the session is held
                 (scf/send! a :other :bump {})
                 (let [reads-for-the-send @reads]
                   (settle! a event-loop #(= 1 (bumps a :other)))
                   (assertions
                     "processing goes on for other sessions"
                     (bumps a :other) => 1
                     "while the held session's due timeout waits"
                     (scf/current-configuration a :s) => #{:top :idle})
                   (when (= :immediate event-loop)
                     (assertions
                       "(and :immediate processing doesn't keep polling for it: every poll reads the clock)"
                       (< reads-for-the-send 20) => true)))
                 (open-gate!)
                 (settle! a event-loop #(= #{:top :loaded} (scf/current-configuration a :s)))
                 (settle! a event-loop)
                 (assertions
                   "the step's cancel removes the waiting timeout: it is never delivered"
                   (scf/current-configuration a :s) => #{:top :loaded})
                 (finally (stop-loop! a))))))))))

(def ^:private gates
  "The resolve fns of the `gated-promise`s, by name."
  (atom {}))

(defn- gated-promise [k] (p/create (fn [resolve _] (swap! gates assoc k resolve))))
(defn- open! [k] ((get @gates k) true))

(def gated-entry-chart
  "Its initial entry awaits the gate named by its `:gate` data (`open!`). Counts the `:bump`s it gets."
  (statechart {:initial :loading}
    (state {:id :loading}
      (on-entry {}
        (script {:expr (fn [_ data & _] (p/then (gated-promise (:gate data)) (fn [_] nil)))}))
      (bump-transition))))

#?(:clj
   (specification "start! of a session id whose earlier start or step hasn't settled"
     (doseq [event-loop [:immediate true false]]
       (component (str "with :event-loop? " event-loop)
         (let [a (async-app event-loop)]
           (try
             (scf/start! a {:machine ::slow-entry :session-id :s})
             (scf/start! a {:machine ::counter :session-id :s})
             (scf/send! a :s :bump {})
             (settle! a event-loop #(= 1 (bumps a :s)))
             (assertions
               "drops the old start's hold: the restarted session's events are processed"
               (bumps a :s) => 1)
             (open-gate!)
             (settle! a event-loop)
             (assertions
               "and skips the late save of the superseded start once its entry settles"
               (scf/current-configuration a :s) => #{:idle})
             (finally (stop-loop! a)))))
       (component (str "a pending step, with :event-loop? " event-loop)
         (let [a (slow-step-app event-loop)]
           (try
             (scf/start! a {:machine ::slow-step :session-id :s})
             (settle! a event-loop #(= #{:a} (scf/current-configuration a :s)))
             (scf/send! a :s :go {})
             (settle! a event-loop)
             (scf/start! a {:machine ::counter :session-id :s})
             (scf/send! a :s :bump {})
             (settle! a event-loop #(= 1 (bumps a :s)))
             (assertions
               "drops the pending step's hold"
               (bumps a :s) => 1)
             (open-gate!)
             (settle! a event-loop)
             (assertions
               "and skips the step's late save"
               (scf/current-configuration a :s) => #{:idle})
             (finally (stop-loop! a)))))
       (component (str "a start that settles while a later start of the same id is pending, with :event-loop? " event-loop)
         (let [a (async-app event-loop)]
           (try
             (scf/register-statechart! a ::gated-entry gated-entry-chart)
             (scf/start! a {:machine ::gated-entry :session-id :s :data {:gate :first}})
             (scf/start! a {:machine ::gated-entry :session-id :s :data {:gate :second}})
             (open! :first)
             (scf/send! a :s :bump {})
             (settle! a event-loop)
             (assertions
               "ignores the superseded start's release: the session's events wait for the later start"
               (bumps a :s) => nil)
             (open! :second)
             (settle! a event-loop #(= 1 (bumps a :s)))
             (assertions
               "which releases them"
               (bumps a :s) => 1)
             (finally (stop-loop! a))))))))

(def ^:private on-save-gate
  "The resolve fn of the promise the `:on-save` of `on-save-promise-app` returns."
  (atom nil))

#?(:clj
   (specification "An :on-save that returns a promise, with a synchronous processor (:async? false)"
     (doseq [event-loop [:immediate true false]]
       (component (str "with :event-loop? " event-loop)
         (let [a (app/fulcro-app {})]
           (scf/install-fulcro-statecharts! a {:event-loop? event-loop
                                               :on-save     (fn [session-id _]
                                                              (when (= :s session-id)
                                                                (p/create (fn [resolve _] (reset! on-save-gate resolve)))))})
           (try
             (scf/register-statechart! a ::counter counter-chart)
             (scf/start! a {:machine ::counter :session-id :s})
             (scf/send! a :s :bump {})
             (settle! a event-loop #(= 1 (bumps a :s)))
             (scf/send! a :s :bump {})
             (settle! a event-loop #(= 2 (bumps a :s)))
             (assertions
               "doesn't hold its session: each event is processed without waiting for the promise"
               (bumps a :s) => 2)
             (@on-save-gate true)
             (finally (stop-loop! a))))))))

#?(:clj
   (specification "start! of an :async? chart when the processing after its synchronous part throws"
     (let [a       (async-app :immediate)
           real    scf/process-events!
           thrown? (atom false)]
       (with-redefs [scf/process-events! (fn [app]
                                           (if (compare-and-set! thrown? false true)
                                             (throw (ex-info "processing failed" {}))
                                             (real app)))]
         (assertions
           "throws to its caller"
           (scf/start! a {:machine ::slow-entry :session-id :slow}) =throws=> #"processing failed"))
       (open-gate!)
       (scf/send! a :slow :bump {})
       (assertions
         "and still releases the session once its entry settles: its events are processed"
         (bumps a :slow) => 1))))

(def noting-step-chart
  "`:go` leaves `:a` for `:b` with a script that returns a promise that settles on `open-gate!`. In `:b`, `:note`
   appends its `:n` to `:notes`."
  (statechart {:initial :a}
    (state {:id :a}
      (transition {:event :go :target :b}
        (script {:expr (fn [& _] (p/then (pending-promise) (fn [_] nil)))})))
    (state {:id :b}
      (transition {:event :note}
        (script {:expr (fn [_ data _ {:keys [n]}] [(ops/assign :notes (conj (or (:notes data) []) n))])})))))

#?(:clj
   (specification "Events that wait for an :async? step, with a manual event loop"
     (let [a     (async-app false)
           notes (fn [] (get-in (app/current-state a) [::sc/local-data :s :notes]))]
       (scf/register-statechart! a ::noting-step noting-step-chart)
       (scf/start! a {:machine ::noting-step :session-id :s})
       (scf/send! a :s :go {})
       (scf/process-events! a)
       (scf/send! a :s :note {:n 1})
       (scf/send! a :s :note {:n 2})
       (scf/process-events! a)
       (assertions
         "wait while the step is pending"
         (notes) => nil)
       (open-gate!)
       (scf/process-events! a)
       (assertions
         "are all processed, in order, by the next processing once it settles"
         (notes) => [1 2]))))

(def pinging-child-chart
  "Its (async) initial entry sends `:ping` to its own session `:child`, then awaits `open-gate!`. Counts its pings."
  (statechart {:initial :c}
    (state {:id :c}
      (on-entry {}
        (script {:expr (fn [env & _]
                         (scf/send! (:fulcro/app env) :child :ping {})
                         (p/then (pending-promise) (fn [_] nil)))}))
      (transition {:event :ping}
        (script {:expr (fn [_ data & _] [(ops/assign :pings (inc (or (:pings data) 0)))])})))))

(def pinging-parent-chart
  "`:open` enters `:open`, which invokes `pinging-child-chart` as session `:child`."
  (statechart {:initial :idle}
    (state {:id :idle}
      (transition {:event :open :target :open}))
    (state {:id :open}
      (invoke {:id :child :type :statechart :src ::pinging-child}))))

#?(:clj
   (specification "An invoked :async? child whose initial entry sends itself an event before it awaits"
     (doseq [event-loop [:immediate true false]]
       (component (str "with :event-loop? " event-loop)
         (let [a     (async-app event-loop)
               pings (fn [] (get-in (app/current-state a) [::sc/local-data :child :pings]))]
           (try
             (scf/register-statechart! a ::pinging-child pinging-child-chart)
             (scf/register-statechart! a ::pinging-parent pinging-parent-chart)
             (scf/start! a {:machine ::pinging-parent :session-id :p})
             (settle! a event-loop #(= #{:idle} (scf/current-configuration a :p)))
             (scf/send! a :p :open {})
             (settle! a event-loop)
             (assertions
               "holds the event back while the child's start is pending"
               (pings) => nil)
             (open-gate!)
             (settle! a event-loop #(= 1 (pings)))
             (settle! a event-loop)
             (assertions
               "and processes it once the child is saved"
               (scf/current-configuration a :child) => #{:c}
               (pings) => 1)
             (finally (stop-loop! a))))))))

(def held-child-chart
  "`:go` leaves `:a` for `:b` with a script that returns a promise that settles on `open-gate!`. In `:b`, `:edit`
   records `:edited`."
  (statechart {:initial :a}
    (state {:id :a}
      (transition {:event :go :target :b}
        (script {:expr (fn [& _] (p/then (pending-promise) (fn [_] nil)))})))
    (state {:id :b}
      (transition {:event :edit}
        (script {:expr (fn [& _] [(ops/assign :edited true)])})))))

(def child-host-chart
  "`:open` invokes `held-child-chart` as session `:hchild`; `:leave` exits it (stopping the child)."
  (statechart {:initial :open}
    (state {:id :open}
      (invoke {:id :hchild :type :statechart :src ::held-child})
      (transition {:event :leave :target :closed}))
    (state {:id :closed})))

#?(:clj
   (specification "An invoked :async? child stopped while its step is pending"
     (doseq [event-loop [:immediate true false]]
       (component (str "with :event-loop? " event-loop)
         (let [a      (async-app event-loop)
               edited (fn [] (get-in (app/current-state a) [::sc/local-data :hchild :edited]))]
           (try
             (scf/register-statechart! a ::held-child held-child-chart)
             (scf/register-statechart! a ::child-host child-host-chart)
             (scf/start! a {:machine ::child-host :session-id :host})
             (settle! a event-loop #(= #{:a} (scf/current-configuration a :hchild)))
             (scf/send! a :hchild :go {})
             (settle! a event-loop)
             (scf/send! a :hchild :edit {})
             (scf/send! a :host :leave {})
             (settle! a event-loop #(= #{:closed} (scf/current-configuration a :host)))
             (open-gate!)
             (settle! a event-loop)
             (settle! a event-loop)
             (assertions
               "stays stopped: the step's late save doesn't bring it back"
               (scf/current-configuration a :host) => #{:closed}
               (scf/current-configuration a :hchild) => #{}
               (get-in (app/current-state a) [::sc/session-id :hchild]) => nil
               "and the event that waited for it isn't applied"
               (edited) => nil)
             (finally (stop-loop! a))))))))

#?(:clj
   (specification "A session released while :immediate processing ends (after its last check for due events)"
     (let [a      (slow-step-app :immediate)
           real   mpq/has-due-events?
           armed? (atom false)]
       (scf/start! a {:machine ::counter :session-id :other})
       (scf/start! a {:machine ::slow-step :session-id :s})
       (scf/send! a :s :go {})
       (scf/send! a :s :edit {})
       ;; The step settles (releasing the session) inside the processing's last check, which then reports nothing due.
       (with-redefs [mpq/has-due-events? (fn [& args]
                                           (let [due? (apply real args)]
                                             (when (and (not due?) (compare-and-set! armed? true false))
                                               (open-gate!))
                                             due?))]
         (reset! armed? true)
         (scf/send! a :other :bump {}))
       (assertions
         "the release found processing in progress"
         @armed? => false
         "and the session's waiting event is processed before that processing returns"
         (edit-handled-in* a) => :b))))

;; ===== The continuation of a superseded or stopped step, restarts, and the hold race =====

(defn- open-if! [k] (when-let [f (get @gates k)] (f true)))

(def ghost-chart
  "With `:gen` 1 in its data, its initial entry awaits the gate `:ghost` (`open!`) and then assigns `:who :old`; else it
   assigns `:who :new`. Then it waits in `:waiting`, whose `send-after` (id `:t`) times out in 1s. In `:waiting`, a
   session whose `:who` is `:old` leaves at once (cancelling `:t`) for `:ghost-exit`, which sends itself `:from-ghost`.
   `:from-ghost` records `:got-from-ghost`."
  (statechart {:initial :top}
    (state {:id :top :initial :boot}
      (transition {:event :from-ghost}
        (script {:expr (fn [& _] [(ops/assign :got-from-ghost true)])}))
      (state {:id :boot}
        (on-entry {}
          (script {:expr (fn [_ data & _]
                           (if (= 1 (:gen data))
                             (p/then (gated-promise :ghost) (fn [_] [(ops/assign :who :old)]))
                             [(ops/assign :who :new)]))}))
        (transition {:target :waiting}))
      (state {:id :waiting}
        (send-after {:id :t :event :timeout :delay 1000})
        (transition {:event :timeout :target :timed-out})
        (transition {:cond (fn [_ data & _] (= :old (:who data))) :target :ghost-exit}))
      (state {:id :ghost-exit}
        (on-entry {} (ele/send {:event :from-ghost})))
      (state {:id :timed-out}))))

#?(:clj
   (specification "The continuation of a start that a restart superseded"
     (doseq [event-loop [:immediate true false]]
       (component (str "with :event-loop? " event-loop)
         (let [clock (atom 1000)]
           (when-mocking
             (now-ms) => @clock

             (let [a (async-app event-loop)]
               (try
                 (scf/register-statechart! a ::ghost ghost-chart)
                 (scf/start! a {:machine ::ghost :session-id :s :data {:gen 1}})
                 (scf/start! a {:machine ::ghost :session-id :s :data {:gen 2}})
                 (settle! a event-loop #(= #{:top :waiting} (scf/current-configuration a :s)))
                 ;; the superseded start's entry settles: it sends its own `:t`, cancels it on leaving `:waiting`, and
                 ;; sends `:from-ghost`
                 (open! :ghost)
                 (settle! a event-loop)
                 (reset! clock 2500)
                 (when-not (true? event-loop) (scf/process-events! a))
                 (settle! a event-loop #(= #{:top :timed-out} (scf/current-configuration a :s)))
                 (settle! a event-loop)
                 (assertions
                   "doesn't cancel the restarted session's timeout (same send id): its cancels are dropped"
                   (scf/current-configuration a :s) => #{:top :timed-out}
                   "and its sends are dropped"
                   (get-in (app/current-state a) [::sc/local-data :s :got-from-ghost]) => nil)
                 (finally (stop-loop! a))))))))))

(def zombie-child-chart (statechart {:initial :c/running} (state {:id :c/running})))

(def zombie-parent-chart
  "`:go` leaves `:p/a` for `:p/b` with a script that awaits `open-gate!`; `:p/b` invokes `zombie-child-chart` as `:c`."
  (statechart {:initial :p/a}
    (state {:id :p/a}
      (transition {:event :go :target :p/b}
        (script {:expr (fn [& _] (p/then (pending-promise) (fn [_] nil)))})))
    (state {:id :p/b}
      (invoke {:id :c :type :statechart :src ::zombie-child}))))

(def zombie-host-chart
  "Invokes `zombie-parent-chart` as `:p` in `:g/open`; `:leave` exits it (stopping `:p`)."
  (statechart {:initial :g/open}
    (state {:id :g/open}
      (invoke {:id :p :type :statechart :src ::zombie-parent})
      (transition {:event :leave :target :g/closed}))
    (state {:id :g/closed})))

#?(:clj
   (specification "The continuation of a step of an invoked session stopped while the step was pending"
     (doseq [event-loop [:immediate true false]]
       (component (str "with :event-loop? " event-loop)
         (let [a (async-app event-loop)]
           (try
             (scf/register-statechart! a ::zombie-child zombie-child-chart)
             (scf/register-statechart! a ::zombie-parent zombie-parent-chart)
             (scf/register-statechart! a ::zombie-host zombie-host-chart)
             (scf/start! a {:machine ::zombie-host :session-id :g})
             (settle! a event-loop #(= #{:p/a} (scf/current-configuration a :p)))
             (scf/send! a :p :go {})
             (settle! a event-loop)
             (scf/send! a :g :leave {})
             (settle! a event-loop #(= #{:g/closed} (scf/current-configuration a :g)))
             (open-gate!)
             (settle! a event-loop)
             (settle! a event-loop)
             (assertions
               "doesn't start the invocation its continuation enters: no session runs without its invoker"
               (scf/current-configuration a :c) => #{}
               (get-in (app/current-state a) [::sc/session-id :c]) => nil
               "(and the stopped session stays stopped)"
               (scf/current-configuration a :p) => #{})
             (finally (stop-loop! a))))))))

(def frozen-chart
  "`:go` enters `:loading`, which schedules a `:timeout` (`send-after`, 1s) and awaits a promise that never settles."
  (statechart {:initial :idle}
    (state {:id :idle}
      (transition {:event :go :target :loading}))
    (state {:id :loading}
      (send-after {:id :t :event :timeout :delay 1000})
      (on-entry {} (script {:expr (fn [& _] (p/create (fn [_ _])))})))))

(def fresh-chart
  "Counts the `:bump`s it gets, and the `:timeout`s."
  (statechart {:initial :ready}
    (state {:id :ready}
      (bump-transition)
      (transition {:event :timeout}
        (script {:expr (fn [_ data & _] [(ops/assign :timeouts (inc (or (:timeouts data) 0)))])})))))

#?(:clj
   (specification "Restarting a frozen session (its step never settles)"
     (doseq [event-loop [:immediate true false]]
       (component (str "with :event-loop? " event-loop)
         (let [clock (atom 1000)]
           (when-mocking
             (now-ms) => @clock

             (let [a (async-app event-loop)]
               (try
                 (scf/register-statechart! a ::frozen frozen-chart)
                 (scf/register-statechart! a ::fresh fresh-chart)
                 (scf/start! a {:machine ::frozen :session-id :s})
                 (settle! a event-loop #(= #{:idle} (scf/current-configuration a :s)))
                 (scf/send! a :s :go {})
                 (settle! a event-loop)
                 (dotimes [_ 3] (scf/send! a :s :bump {}))
                 ;; its timeout falls due while it is frozen
                 (reset! clock 2500)
                 (settle! a event-loop)
                 (scf/start! a {:machine ::fresh :session-id :s})
                 (settle! a event-loop #(= 3 (bumps a :s)))
                 (settle! a event-loop)
                 (assertions
                   "drops the old session's own queued events: its timeout doesn't reach the new session"
                   (get-in (app/current-state a) [::sc/local-data :s :timeouts]) => nil
                   "while the events others sent to the session id are delivered to the new session"
                   (bumps a :s) => 3)
                 (finally (stop-loop! a))))))))))

(def racing-old-chart
  "`:e` runs a script that awaits the gate `:old` (`open!`), then records `:old-got-e`."
  (statechart {:initial :old/idle}
    (state {:id :old/idle}
      (transition {:event :e}
        (script {:expr (fn [& _] (p/then (gated-promise :old) (fn [_] [(ops/assign :old-got-e true)])))})))))

(def racing-new-chart
  "Its initial entry awaits the gate `:new` (`open!`). `:e` records `:new-got-e`."
  (statechart {:initial :new/ready}
    (state {:id :new/ready}
      (on-entry {} (script {:expr (fn [& _] (p/then (gated-promise :new) (fn [_] nil)))}))
      (transition {:event :e}
        (script {:expr (fn [& _] [(ops/assign :new-got-e true)])})))))

#?(:clj
   (specification "A restart that holds the session between the queue's check and the step's hold"
     (doseq [event-loop [:immediate true false]]
       (component (str "with :event-loop? " event-loop)
         (let [a      (async-app event-loop)
               real   @#'mpq/without-event
               armed? (atom false)]
           (try
             (reset! gates {})
             (scf/register-statechart! a ::racing-old racing-old-chart)
             (scf/register-statechart! a ::racing-new racing-new-chart)
             (scf/start! a {:machine ::racing-old :session-id :x})
             (settle! a event-loop #(= #{:old/idle} (scf/current-configuration a :x)))
             ;; The queue takes `:e` off (the session isn't held), then the restart (pending) holds it, before the
             ;; step for `:e` holds it. (The restart runs inside the take-off, so the window is hit every time.)
             (with-redefs [mpq/without-event (fn [q event]
                                               (when (and (= :e (:name event)) (compare-and-set! armed? true false))
                                                 (scf/start! a {:machine ::racing-new :session-id :x}))
                                               (real q event))]
               (reset! armed? true)
               (scf/send! a :x :e {})
               (settle! a event-loop #(contains? @gates :new)))
             (open! :new)
             (settle! a event-loop #(get-in (app/current-state a) [::sc/local-data :x :new-got-e]))
             (open-if! :old)
             (settle! a event-loop)
             (assertions
               "the restart happened in the window"
               @armed? => false
               "the event's step doesn't run in the old session (it can't join the restart's hold)"
               (get-in (app/current-state a) [::sc/local-data :x :old-got-e]) => nil
               "the event waits for the restarted session, which gets it"
               (get-in (app/current-state a) [::sc/local-data :x :new-got-e]) => true
               "and the restarted session is the one saved"
               (get-in (app/current-state a) [::sc/session-id :x ::sc/statechart-src]) => ::racing-new)
             (finally (stop-loop! a))))))))

(def kid-starts
  "The number of `gated-kid-chart` starts so far."
  (atom 0))

(def gated-kid-chart
  "Start n of it awaits the gate `[:kid n]` (`open!`) on entry, records `:start-no` n, and goes to `:kid/one` (n = 1),
   which sends itself `:from-ghost`, or to `:kid/two`, where `:from-ghost` records `:got-from-ghost`."
  (statechart {:initial :kid/boot}
    (state {:id :kid/boot}
      (on-entry {}
        (script {:expr (fn [& _]
                         (let [n (swap! kid-starts inc)]
                           (p/then (gated-promise [:kid n]) (fn [_] [(ops/assign :start-no n)]))))}))
      (transition {:cond (fn [_ data & _] (= 1 (:start-no data))) :target :kid/one})
      (transition {:target :kid/two}))
    (state {:id :kid/one}
      (on-entry {} (ele/send {:event :from-ghost})))
    (state {:id :kid/two}
      (transition {:event :from-ghost}
        (script {:expr (fn [& _] [(ops/assign :got-from-ghost true)])})))))

(def kid-host-chart
  "`:open` enters `:open`, which invokes `gated-kid-chart` as `:kid`."
  (statechart {:initial :idle}
    (state {:id :idle}
      (transition {:event :open :target :open}))
    (state {:id :open}
      (invoke {:id :kid :type :statechart :src ::gated-kid}))))

#?(:clj
   (specification "An invoked session whose start a later start of its session id superseded"
     (doseq [event-loop [:immediate true false]]
       (component (str "with :event-loop? " event-loop)
         (let [a (async-app event-loop)]
           (try
             (reset! kid-starts 0)
             (scf/register-statechart! a ::gated-kid gated-kid-chart)
             (scf/register-statechart! a ::kid-host kid-host-chart)
             (scf/start! a {:machine ::kid-host :session-id :host})
             (settle! a event-loop #(= #{:idle} (scf/current-configuration a :host)))
             (scf/send! a :host :open {})
             (settle! a event-loop #(= 1 @kid-starts))
             (scf/start! a {:machine ::gated-kid :session-id :kid})
             (open! [:kid 2])
             (settle! a event-loop #(= #{:kid/two} (scf/current-configuration a :kid)))
             (open! [:kid 1])
             (settle! a event-loop #(= #{:open} (scf/current-configuration a :host)))
             (settle! a event-loop)
             (assertions
               "keeps the later start's session: the invoked start's late save is skipped"
               (scf/current-configuration a :kid) => #{:kid/two}
               "and drops the invoked start's late sends"
               (get-in (app/current-state a) [::sc/local-data :kid :got-from-ghost]) => nil)
             (finally (stop-loop! a))))))))

(specification "guarded-working-memory-store"
  (let [saved   (atom {})
        store   (reify com.fulcrologic.statecharts.protocols/WorkingMemoryStore
                  (get-working-memory [_ _ id] (get @saved id))
                  (save-working-memory! [_ _ id wmem] (swap! saved assoc id wmem))
                  (delete-working-memory! [_ _ id] (swap! saved dissoc id)))
        current (atom true)
        guarded (#'scf/guarded-working-memory-store store :s #(deref current))
        save!   (fn [id v] (com.fulcrologic.statecharts.protocols/save-working-memory! guarded {} id v))]
    (save! :s 1)
    (assertions
      "saves its session while its step or start is current"
      (get @saved :s) => 1)
    (reset! current false)
    (save! :s 2)
    (save! :other 3)
    (assertions
      "skips its session's save once the step or start is superseded"
      (get @saved :s) => 1
      "and still saves other sessions (e.g. a session the step started)"
      (get @saved :other) => 3)))

(specification "held-invocation-processor"
  (let [calls     (atom [])
        inner     (reify com.fulcrologic.statecharts.protocols/InvocationProcessor
                    (supports-invocation-type? [_ typ] (= :statechart typ))
                    (start-invocation! [_ env data] (swap! calls conj [:start (::scf/superseded? env) (:invokeid data)]) true)
                    (stop-invocation! [_ _ data] (swap! calls conj [:stop (:invokeid data)]) true)
                    (forward-event! [_ _ data] (swap! calls conj [:forward (:invokeid data)]) true))
        deferred  (atom [])
        defer     (fn [sid f] (swap! deferred conj sid) (f (constantly true) ::kid-superseded?))
        stale     #{:stale-env}
        wrapped   (#'scf/held-invocation-processor inner defer (fn [env] (contains? stale (:tag env))))
        call-all! (fn [tag]
                    (reset! calls [])
                    (com.fulcrologic.statecharts.protocols/start-invocation! wrapped {:tag tag} {:invokeid :kid})
                    (com.fulcrologic.statecharts.protocols/stop-invocation! wrapped {:tag tag} {:invokeid :kid})
                    (com.fulcrologic.statecharts.protocols/forward-event! wrapped {:tag tag} {:invokeid :kid})
                    @calls)]
    (assertions
      "starts an invocation as a (re)start of its session, whose env carries that start's superseded check"
      (call-all! :current-env) => [[:start ::kid-superseded? :kid] [:stop :kid] [:forward :kid]]
      @deferred => [:kid]
      "and drops the start, stop and forward of a stale env (a superseded or stopped step's continuation)"
      (call-all! :stale-env) => []
      "while still answering which types it supports"
      (com.fulcrologic.statecharts.protocols/supports-invocation-type? wrapped :statechart) => true)))

#?(:clj (def ^:private latch-wait-ms 5000))

#?(:clj
   (def blocking-step-chart
     "`:block` runs a (synchronous) script that waits for the `:step-latch` promise."
     (statechart {:initial :idle}
       (state {:id :idle}
         (transition {:event :block}
           (script {:expr (fn [_ data & _] (deref (:step-latch data) latch-wait-ms nil) nil)}))))))

#?(:clj
   (def blocking-start-chart
     "Its (synchronous) initial entry sends itself `:ping`, then waits for the `:entry-latch` promise. Counts its pings."
     (statechart {:initial :ready}
       (state {:id :ready}
         (on-entry {}
           (script {:expr (fn [env data & _]
                            (scf/send! (:fulcro/app env) :late :ping {})
                            (deref (:entry-latch data) latch-wait-ms nil)
                            nil)}))
         (transition {:event :ping}
           (script {:expr (fn [_ data & _] [(ops/assign :pings (inc (or (:pings data) 0)))])}))))))

#?(:clj
   (specification "A synchronous start! on another thread that ends after the processing in progress ended"
     (let [a           (app/fulcro-app {})
           step-latch  (promise)
           entry-latch (promise)]
       (scf/install-fulcro-statecharts! a {:event-loop? :immediate})
       (scf/register-statechart! a ::blocking-step blocking-step-chart)
       (scf/register-statechart! a ::blocking-start blocking-start-chart)
       (scf/start! a {:machine ::blocking-step :session-id :busy :data {:step-latch step-latch}})
       (let [processing (future (scf/send! a :busy :block {}))
             _          (Thread/sleep 50)
             starting   (future (scf/start! a {:machine ::blocking-start :session-id :late :data {:entry-latch entry-latch}}))]
         (Thread/sleep 50)
         ;; the processing in progress ends while the start still holds its session (its `:ping` waits)
         (deliver step-latch true)
         (deref processing latch-wait-ms nil)
         (deliver entry-latch true)
         (deref starting latch-wait-ms nil)
         (assertions
           "delivers the started session's waiting event once the start releases it (:immediate)"
           (get-in (app/current-state a) [::sc/local-data :late :pings]) => 1)))))

(def ^:private kept-env
  "The env the last `:keep` step kept."
  (atom nil))

(def loading-step-chart
  "Its initial entry keeps its env (`kept-env`). `:go` issues a load of `Thing` (ok-event `:event/loaded`); with
   `:freeze?` in its data, the step then awaits a promise that never settles. `:keep` keeps its step's env; with
   `:async?` in its data, the step returns a promise (that settles at once)."
  (statechart {:initial :idle}
    (state {:id :idle}
      (on-entry {}
        (script {:expr (fn [env & _] (reset! kept-env env) nil)}))
      (transition {:event :go}
        (script {:expr (fn [& _] [(fops/load :thing Thing {::sc/ok-event :event/loaded})])})
        (script {:expr (fn [_ _ _ {:keys [freeze?]}] (when freeze? (p/create (fn [_ _]))))}))
      (transition {:event :keep}
        (script {:expr (fn [env _ _ {:keys [async?]}]
                         (reset! kept-env env)
                         (when async? (p/create (fn [resolve _] (resolve nil)))))})))))

(def loaded-counter-chart
  "Counts the `:event/loaded`s it gets."
  (statechart {:initial :ready}
    (state {:id :ready}
      (transition {:event :event/loaded}
        (script {:expr (fn [_ data & _] [(ops/assign :loaded (inc (or (:loaded data) 0)))])})))))

#?(:clj
   (specification "Events to a restarted session id that its old run caused"
     (let [answer  (atom nil)
           new-app (fn []
                     (let [a (stx/with-synchronous-transactions
                               (app/fulcro-app {:remotes {:remote {:transmit! (fn [_ {::txn/keys [result-handler]}]
                                                                                (reset! answer result-handler))}}}))]
                       (scf/install-fulcro-statecharts! a {:event-loop? :immediate :async? true})
                       (scf/register-statechart! a ::loading-step loading-step-chart)
                       (scf/register-statechart! a ::loaded-counter loaded-counter-chart)
                       (scf/start! a {:machine ::loading-step :session-id :s})
                       a))
           loaded  (fn [a] (get-in (app/current-state a) [::sc/local-data :s :loaded]))]
       (doseq [[label freeze?] [["a load issued by a step that is over" false]
                                ["a load issued by a step that a restart superseded while it still runs" true]]]
         (component label
           (let [a (new-app)]
             (scf/send! a :s :go {:freeze? freeze?})
             (scf/start! a {:machine ::loaded-counter :session-id :s})
             (@answer {:status-code 200 :body {:thing {:thing/id 1}}})
             (assertions
               "delivers the load's result to the new session (an event to the session id)"
               (loaded a) => 1))))
       (doseq [[label keep!] [["a synchronous step" (fn [a] (scf/send! a :s :keep {}))]
                              ["an :async? step whose promise settled" (fn [a] (scf/send! a :s :keep {:async? true}))]
                              ["a start (its initial entry)" (fn [_])]]]
         (component (str "a send with the env of " label " that is over (e.g. from a callback that kept it)")
           (let [a (new-app)]
             (keep! a)
             (scf/start! a {:machine ::loaded-counter :session-id :s})
             (let [env @kept-env]
               (com.fulcrologic.statecharts.protocols/send! (::sc/event-queue env) env {:target :s :event :event/loaded}))
             (assertions
               "goes out: only a superseded step's or start's sends while it still runs are dropped"
               (loaded a) => 1)))))))
