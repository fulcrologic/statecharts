(ns com.fulcrologic.statecharts.integration.fulcro.apply-action-order-spec
  "Regression tests for the order in which the ops of a statechart expression take effect in a Fulcro app. The ops
   of an expression's returned vector must land in vector order, so a later op, and the next expression, see an
   earlier `fops/apply-action`. That must hold under Fulcro's default tx processing and under synchronous tx
   processing, including when the expression runs inside an outer transaction. Also covers what the op keeps from
   its old `transact!!` implementation: a throwing fn is logged without stopping the later ops, and Fulcro Inspect
   gets a transaction entry."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.algorithms.tx-processing.synchronous-tx-processing :as stx]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.inspect.devtool-api :as devtool]
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]
    [com.fulcrologic.fulcro.inspect.tools :as fit]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [on-entry script state]]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro-impl :as impl]
    [com.fulcrologic.statecharts.integration.fulcro.operations :as fops]
    [fulcro-spec.core :refer [=> assertions component specification]]
    [taoensso.timbre :as log]))

(defsc Item [_ _]
  {:query [:item/id :item/value]
   :ident :item/id})

(def ordering-chart
  "On entry, the first script writes `:x`, `:y` and `:z` to the Fulcro state map with an apply-action, then
   overwrites `:y` through an alias and `:z` with an assign. The second script loads `:items` with params it reads
   through the `:x` and `:y` aliases."
  (chart/statechart {:initial :running}
    (state {:id :running}
      (on-entry {}
        (script {:expr (fn [_env _data & _]
                         [(fops/apply-action assoc :x 1 :y 1 :z 1)
                          (fops/assoc-alias :y 2)
                          (ops/assign [:fulcro/state :z] 2)])})
        (script {:expr (fn [_env {:keys [x y]} & _]
                         [(fops/load :items Item {:params {:x x :y y}})])})))))

(def throwing-chart
  "On entry, runs an apply-action whose fn throws, then writes `:y` through an alias."
  (chart/statechart {:initial :running}
    (state {:id :running}
      (on-entry {}
        (script {:expr (fn [_env _data & _]
                         [(fops/apply-action (fn [_state-map] (throw (ex-info "boom" {}))))
                          (fops/assoc-alias :y 2)])})))))

(def single-apply-chart
  "On entry, writes `:x` with one apply-action."
  (chart/statechart {:initial :running}
    (state {:id :running}
      (on-entry {}
        (script {:expr (fn [_env _data & _] [(fops/apply-action assoc :x 1)])})))))

(defmutation start-ordering-chart
  "Starts `ordering-chart` from inside this mutation's action, so the chart's entry runs inside a transaction."
  [_]
  (action [{:keys [app]}]
    (scf/start! app {:machine    ::ordering
                     :session-id ::ordering
                     :data       {:fulcro/aliases {:x [:fulcro/state :x]
                                                   :y [:fulcro/state :y]}}})))

(defn- capturing-app
  "Returns a Fulcro app with statecharts installed and `ordering-chart` registered. Its `:remote` records each send
   node in the atom `sends` instead of transmitting it. When `synchronous?` it uses synchronous tx processing."
  [sends synchronous?]
  (let [base (app/fulcro-app {:remotes {:remote {:transmit! (fn [_ send-node] (swap! sends conj send-node))}}})
        a    (if synchronous? (stx/with-synchronous-transactions base) base)]
    (scf/install-fulcro-statecharts! a {:event-loop? false})
    (scf/register-statechart! a ::ordering ordering-chart)
    a))

(defn- load-params
  "Returns the parameters of the first element of `send-node`'s query."
  [send-node]
  (-> send-node ::txn/ast :children first :params))

(defn- warnings-during!
  "Calls `f`, then `(done?)` until it is truthy (CLJ polls up to 5 s), and returns the timbre `:warn` (and above)
   messages logged on any thread meanwhile. Changes the global timbre config while it runs (and restores it after),
   because Fulcro's default tx processing runs transactions on another thread, and other specs raise the global
   `:min-level`."
  [f done?]
  (let [logs       (atom [])
        old-config log/*config*]
    (log/merge-config! {:min-level :warn
                        :appenders {::capture {:enabled? true
                                               :fn       (fn [{:keys [msg_]}] (swap! logs conj (force msg_)))}}})
    (try
      (f)
      #?(:clj  (let [deadline (+ (System/currentTimeMillis) 5000)]
                 (loop []
                   (when (and (not (done?)) (< (System/currentTimeMillis) deadline))
                     (Thread/sleep 2)
                     (recur))))
         :cljs (done?))
      (finally
        (log/set-config! old-config)))
    @logs))

(defn- run-ordering-chart-in-mutation!
  "Starts `ordering-chart` from inside a mutation on an app built with `synchronous?`. Waits for the chart's load to
   be sent. Returns the app's final state map, the params of the load and the warnings logged."
  [synchronous?]
  (let [sends    (atom [])
        a        (capturing-app sends synchronous?)
        warnings (warnings-during! #(comp/transact! a [(start-ordering-chart {})]) #(seq @sends))
        state    (app/current-state a)]
    {:state    state
     :params   (some-> @sends first load-params)
     :warnings warnings}))

(defn- started-app
  "Returns a Fulcro app (default tx processing) on which `chart` was started, with the `:y` alias, after calling
   `(before-start! app)`."
  [chart before-start!]
  (let [a (app/fulcro-app)]
    (before-start! a)
    (scf/install-fulcro-statecharts! a {:event-loop? false})
    (scf/register-statechart! a ::chart chart)
    (scf/start! a {:machine ::chart
                   :data    {:fulcro/aliases {:y [:fulcro/state :y]}}})
    a))

(specification "apply-action op ordering"
  (component "under synchronous tx processing, with the chart's entry run inside a mutation"
    (let [{:keys [state params warnings]} (run-ordering-chart-in-mutation! true)]
      (assertions
        "an alias op after the apply-action lands after it"
        (:y state) => 2
        "an assign op after the apply-action lands after it"
        (:z state) => 2
        "the next expression reads the apply-action's value through an alias, so the load is sent with it"
        (:x params) => 1
        "the next expression reads the alias op's value, so the load is sent with it"
        (:y params) => 2
        "the ops log no warnings"
        warnings => [])))
  #?(:clj
     (component "under Fulcro's default tx processing, with the chart's entry run inside a mutation"
       (let [{:keys [state params warnings]} (run-ordering-chart-in-mutation! false)]
         (assertions
           "an alias op after the apply-action lands after it"
           (:y state) => 2
           "an assign op after the apply-action lands after it"
           (:z state) => 2
           "the next expression reads the apply-action's value through an alias, so the load is sent with it"
           (:x params) => 1
           "the next expression reads the alias op's value, so the load is sent with it"
           (:y params) => 2
           "the ops log no warnings"
           warnings => []))))
  (component "when an apply-action's fn throws"
    (let [a      (atom nil)
          errors (warnings-during! #(reset! a (started-app throwing-chart identity)) (constantly true))]
      (assertions
        "logs the failure"
        (some #(str/starts-with? % "Apply action failed") errors) => true
        "still applies the later ops of the vector"
        (:y (app/current-state @a)) => 2)))
  #?(:clj
     (component "when Fulcro Inspect is enabled"
       (let [events (atom [])
             _      (with-redefs [inspect/INSPECT "true"]
                      (started-app single-apply-chart
                        (fn [a] (fit/register-tool! a (fn [_ event] (swap! events conj event))))))
             txs    (into []
                      (comp
                        (filter #(= `devtool/optimistic-action (:type %)))
                        (map :fulcro.history/tx))
                      @events)]
         (assertions
           "reports the op as one do-apply-action transaction with its fn and args"
           txs => [(list `impl/do-apply-action {:f assoc :args [:x 1]})])))))
