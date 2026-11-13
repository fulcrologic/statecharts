(ns com.fulcrologic.statecharts.error-event-payload-spec
  "Library-generated `:error.execution` events are processed during `initialize!`, so their payload is left in
   `_event` in the working memory that `start!` saves. The payload must not contain functions, or
   serializing working-memory stores fail and the chart cannot start."
  (:require
    [clojure.walk :as walk]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
    [com.fulcrologic.statecharts.elements :as ele :refer [foreach on-entry script state transition]]
    [com.fulcrologic.statecharts.promise :as p]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.simple :as simple]
    [com.fulcrologic.statecharts.simple-async :as simple-async]
    [fulcro-spec.core :refer [=> assertions behavior specification]]))

(def chart-with-failing-startup-expression
  "The failing expression's error.execution is the last event processed during startup, so it stays in `_event`."
  (chart/statechart {:initial :s0}
    (state {:id :s0}
      (on-entry {}
        (script {:expr (fn [_ _] (throw (ex-info "boom" {})))}))
      (transition {:event :error.execution :target :handled}))
    (state {:id :handled})))

(defn- started-working-memory
  "Starts `chart-with-failing-startup-expression` in an env made by `env-fn`, and returns the saved working memory."
  [env-fn register! start!]
  (let [env    (env-fn)
        result (do
                 (register! env ::chart chart-with-failing-startup-expression)
                 (start! env ::chart ::session))]
    #?(:clj (when (p/promise? result) @result))
    (sp/get-working-memory (::sc/working-memory-store env) env ::session)))

(defn- contains-fn?
  "Returns true if any value nested anywhere in `x` is a function."
  [x]
  (let [found? (volatile! false)]
    (walk/postwalk (fn [v] (when (fn? v) (vreset! found? true)) v) x)
    @found?))

(specification "error.execution raised by a failing startup expression"
  (doseq [[label env-fn register! start!] [["sync" simple/simple-env simple/register! simple/start!]
                                           ["async" simple-async/simple-env simple-async/register! simple-async/start!]]]
    (let [wmem  (started-working-memory env-fn register! start!)
          event (get-in wmem [::wmdm/data-model :_event])]
      (behavior (str label " processor")
        (assertions
          "is handled during startup"
          (::sc/configuration wmem) => #{:handled}
          "is the event left in _event"
          (:name event) => :error.execution
          "leaves no functions in the started working memory"
          (contains-fn? wmem) => false
          "still carries the exception"
          (ex-message (get-in event [:data :error])) => "boom")))))

(defn- chart-with-failing-elements
  "A chart whose separate on-entry blocks each fail differently. Every error.execution payload is conj'd onto
   the `captured` atom."
  [captured]
  (chart/statechart {:initial :s0}
    (state {:id :s0}
      (on-entry {}
        (ele/send {:id :bad-send :event :x :type :unsupported/type :content (fn [_ _] {:a 1})}))
      (on-entry {}
        (foreach {:id :bad-foreach :array (fn [_ _] 42) :item [:i]}
          (script {:expr (fn [_ _] nil)})))
      (on-entry {:id :throwing-block}
        (script {:expr (fn [_ _] (throw (ex-info "boom" {})))}))
      (transition {:event :error.execution}
        (script {:expr (fn [_ {:keys [_event]}] (swap! captured conj (:data _event)) nil)})))))

(defn- payload-values
  "Returns `payload` plus the `ex-data` of any exception it carries, since serializers freeze `ex-data` too."
  [payload]
  (into [payload] (keep ex-data) [(:error payload) (:exception payload)]))

(specification "error.execution payloads for failing elements"
  (doseq [[label env-fn register! start!] [["sync" simple/simple-env simple/register! simple/start!]
                                           ["async" simple-async/simple-env simple-async/register! simple-async/start!]]
          strict? [false true]]
    (let [captured (atom [])
          env      (assoc (env-fn) ::sc/errors-abort-siblings? strict?)
          result   (do
                     (register! env ::chart (chart-with-failing-elements captured))
                     (start! env ::chart ::session))
          _        #?(:clj (when (p/promise? result) @result) :cljs nil)
          payloads @captured]
      (behavior (str label " processor, strict? " strict?)
        (assertions
          "reports the failed send by element id"
          (some #(when (= :send (:type %)) (:element %)) payloads) => :bad-send
          "reports the non-iterable foreach by element id"
          (some #(when (= :foreach (:type %)) (:element %)) payloads) => :bad-foreach
          "never puts an element map in :element"
          (some (comp map? :element) payloads) => nil
          "contains no functions, including in exception ex-data"
          (contains-fn? (into [] (mapcat payload-values) payloads)) => false)))))
