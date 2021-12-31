(ns com.fulcrologic.statecharts.testing
  "Utility functions to help with testing state charts."
  (:require
    [clojure.set :as set]
    [clojure.test :refer [is testing]]
    [com.fulcrologic.statecharts.algorithms.v20150901-impl :refer [configuration-problems]]
    [com.fulcrologic.statecharts.algorithms.v20150901 :refer [new-processor]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.data-model.working-memory-data-model :refer [new-flat-model]]
    [com.fulcrologic.statecharts.events :refer [new-event]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.state-machine :as sm]
    [taoensso.timbre :as log]))

(defprotocol Clearable
  (clear! [this] "Clear the recordings of the given mock"))

(defprotocol ExecutionChecks
  (ran? [execution-model fref]
    "Returns true if there was an attempt to run the function with reference `fref`")
  (ran-in-order? [execution-model frefs]
    "Returns true if there was an attempt to run every function reference `frefs` (a vector), in the order listed.
     Note, this only requires the functions be called in that relative order, and is fine if there
     are other calls to (other) things in between."))

(defn- has-element?
  "Does sequence v contain element `ele`?"
  [v ele]
  (boolean (seq (filter #(= ele %) v))))

(defrecord MockExecutionModel [data-model
                               expressions-seen
                               call-counts
                               mocks]
  Clearable
  (clear! [_]
    (reset! expressions-seen [])
    (reset! call-counts {}))
  ExecutionChecks
  (ran? [_ fref] (contains? @call-counts fref))
  (ran-in-order? [_ frefs]
    (loop [remaining @expressions-seen
           to-find   frefs]
      (let [found?       (has-element? remaining to-find)
            remainder    (drop-while #(not= to-find %) remaining)
            left-to-find (drop 1 to-find)]
        (cond
          (and found? (empty? left-to-find)) true
          (and found? (seq left-to-find) (seq remainder)) (recur remainder left-to-find)
          :else false))))
  sp/ExecutionModel
  (run-expression! [model env expr]
    (swap! expressions-seen conj expr)
    (swap! call-counts update expr (fnil inc 0))
    (cond
      (fn? (get @mocks expr)) (let [mock (get @mocks expr)]
                                (mock (assoc env :ncalls (get @expressions-seen expr))))
      (contains? @mocks expr) (get @mocks expr))))

(defn new-mock-execution
  "Create a mock exection model. Records the expressions seen. If the expression has an
   entry in the supplied `mocks` map, then if it is a fn, it will be called with `env` which
   will contain the normal stuff along with `:ncalls` (how many times that expression has been
   called). Otherwise the value in the map is returned.

   You can use `ran?`, `ran-in-order?` and other ExecutionChecks to do verifications.
   "
  ([data-model]
   (->MockExecutionModel data-model (atom []) (atom {}) (atom {})))
  ([data-model mocks]
   (->MockExecutionModel data-model (atom []) (atom {}) (atom mocks))))

(defprotocol SendChecks
  (sent? [_ required-elements-of-send-request]
    "Checks that a send request was submitted that had at LEAST the elements of
     `required-elements-of-send-request` in it. If the value of an element in the
     required elelments is a `fn?`, then it will be treated as a predicate.")
  (cancelled? [_ session-id send-id]
    "Checks that an attempt was made to cancel the send with the given id."))

(defrecord MockEventQueue [sends-seen cancels-seen]
  Clearable
  (clear! [_]
    (reset! sends-seen [])
    (reset! cancels-seen []))
  SendChecks
  (sent? [_ req]
    (some
      (fn [send]
        (let [sent (select-keys send (keys req))]
          (= sent req)))
      sends-seen))
  (cancelled? [_ session-id send-id]
    (has-element? cancels-seen {:send-id    send-id
                                :session-id session-id}))
  sp/EventQueue
  (send! [this send-request] (swap! sends-seen conj send-request))
  (cancel! [this session-id send-id] (swap! cancels-seen conj {:send-id    send-id
                                                               :session-id session-id}))
  (receive-events! [this opts handler]))

(defn new-mock-queue
  "Create an event queue that simply records what it sees. Use `sent?` and `cancelled?` to
   check these whenever you want."
  []
  (->MockEventQueue (atom []) (atom [])))

(defrecord TestingEnv [machine working-memory configuration-validator
                       data-model execution-model event-queue
                       processor]
  sp/EventQueue
  (send! [_ r] (sp/send! event-queue r))
  (cancel! [_ s si] (sp/cancel! event-queue s si))
  (receive-events! [_ _ _])
  sp/Processor
  (get-base-env [this] this)
  (start! [_ session-id] (sp/start! processor session-id))
  (process-event! [_ wm evt] (sp/process-event! processor wm evt))
  SendChecks
  (sent? [_ eles] (sent? event-queue eles))
  (cancelled? [_ s si] (cancelled? event-queue s si))
  ExecutionChecks
  (ran? [_ fref] (ran? execution-model fref))
  (ran-in-order? [_ frefs] (ran-in-order? execution-model frefs)))

(defn new-testing-env
  "Returns a new testing `env` that can be used to run events against a state machine or otherwise
   manipulate it for tests.

   `mocks` is a map from expression *value* (e.g. fn ref) to either a literal value or a `(fn [env])`, where
   `env` will be the testing env with :ncalls set to the ordinal (from 1) number of times that expression
   has been run.

   `validator` is a `(fn [machine working-memory] vector?)` that returns a list of problems with the
   state machine's configuration (if any). These problems indicate either a bug in the underlying
   implementation, or with your initial setup of working memory.

   The default data model is the flat working memory model, the default processor is the v20150901 version,
   and the validator checks things according to that same version.
   "
  [{:keys [machine validator processor-factory data-model-factory]
    :or   {data-model-factory new-flat-model
           validator          configuration-problems
           processor-factory  new-processor}} mocks]
  (let [data-model (data-model-factory)
        mock-queue (new-mock-queue)
        exec-model (new-mock-execution data-model (or mocks {}))
        base-env   {:working-memory          (atom {})
                    :machine                 machine
                    :configuration-validator validator
                    :data-model              data-model
                    :execution-model         exec-model
                    :event-queue             mock-queue}
        processor  (processor-factory machine base-env)]
    (map->TestingEnv (merge base-env {:processor processor}))))

(defn goto-configuration!
  "Runs the given data-ops on the data model, then sets the working memory configuration to `configuration`.

   Previously recorded state history (via history nodes) is UNAFFECTED.

   Throws if the configuration validator returns a non-empty problems list."
  [{:keys [data-model
           machine
           configuration-validator
           working-memory] :as test-env} data-ops configuration]
  (when (seq data-ops)
    (sp/update! data-model test-env {:ops data-ops}))
  (swap! working-memory assoc ::sc/configuration configuration)
  (when configuration-validator
    (when-let [problems (seq (configuration-validator machine @working-memory))]
      (throw (ex-info "Invalid configuration!" {:configuration configuration
                                                :problems      problems})))))

(defn start!
  "Start the machine in the testing env, and assign it the given session-id."
  [{:keys [working-memory processor] :as testing-env} session-id]
  (reset! working-memory (sp/start! testing-env session-id)))

(defn run-events!
  "Run the given sequence of events (names or maps) against the testing runtime. Returns the
   updated working memory (side effects against testing runtime, so you can run this multiple times
   to progressively walk the machine)"
  [{:keys [processor working-memory] :as runtime} & events]
  (doseq [e events]
    (swap! working-memory #(sp/process-event! processor % (new-event e))))
  @working-memory)

(defn in?
  "Check to see that the machine in the testing-env is in the given state."
  [{:keys [working-memory] :as testing-env} state-name]
  (contains? (get @working-memory ::sc/configuration) state-name))

(defn will-send
  "Test assertions. Find `event-name` on the sends seen, and verify it will be sent after the
   given delay-ms."
  [{:keys [event-queue] :as testing-env} event delay-ms]
  (let [{:keys [sends-seen]} event-queue
        sends       (filter #(and (= (:event %) event) %) @sends-seen)
        event-seen? (boolean (seq sends))
        has-delay?  (boolean (some #(= delay-ms (:delay %)) (log/spy :info (vec sends))))]
    (is event-seen?)
    (is has-delay?)
    true))

(defn target-states
  "Returns a set of states that *should* represent a valid configuration of `machine` in the testing
   env AS LONG AS you list a valid set of leaf states. For example, if you have a top-level parallel state,
   then `states` MUST contain a valid atomic state for each sub-region.

   Another way to express this is that this function returns the union of the set of `states`
   and their proper ancestors."
  [{:keys [machine] :as testing-env} states]
  (into (set/union (set states) #{})
    (mapcat (fn [sid] (sm/get-proper-ancestors machine sid)))
    states))

