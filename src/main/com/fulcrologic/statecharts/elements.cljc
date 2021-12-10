(ns com.fulcrologic.statecharts.elements
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.util :refer [genid]]))

(defn state
  "Create a state. ID will be generated if not supplied. The `initial` element is an alias for this
   element with `:initial? true`."
  [{:keys [id initial?] :as attrs} & children]
  (merge {:id (or id (genid "state"))} attrs {:node-type :state
                                              :children  (vec children)}))

(defn history
  "Create a history node. Set `:deep? true` for deep history. `transition` is required, and specifies the
   configuration of the machine in which it is embedded if there is no history (as a single keyword or a
   set of state IDs)."
  [{:keys [deep? id transition] :as attrs}]
  (let [t (if (keyword? transition) #{transition} (set transition))]
    (merge {:id (genid "history")}
      attrs
      {:node-type  :history
       :transition t})))

(defn final [{:keys [id] :as attrs} & children]
  (merge {:id (genid "final")}
    attrs
    {:node-type :final
     :children  (vec children)}))

(defn initial "Alias for `(state {:initial? true ...} ...)`."
  [attrs & children]
  (apply state (assoc attrs :initial? true)
    children))

(defn on-enter [{:keys [id expr] :as attrs}]
  (merge
    {:id (or id (genid "enter"))}
    attrs
    {:node-type :on-entry
     :function  expr}))

(defn on-exit [{:keys [id expr] :as attrs}]
  (merge
    {:id (genid "exit")}
    attrs
    {:node-type :on-exit
     :function  expr}))

(defn parallel [attrs & children]
  (merge
    {:id (genid "parallel")}
    attrs
    {:node-type :parallel
     :children  (vec children)}))

(defn data-model
  "Create a data model (in a state or machine context). `expr` is an expression
   (value or function) that will result in the initial value of the data.

   If the expression is a value, then you are using early binding. If it is
   a lambda, then the data will be bound when its surrounding state is entered, but
   before any `on-entry` is invoked.
   "
  [{:keys [id expr] :as attrs}]
  (merge
    {:id (genid "data-model")}
    attrs
    {:node-type  :data-model
     :expression expr}))

(defn done-data
  "A node that can be included in a `final` state, which is returned to the
   parent context. In particular the done-data of a top-level final state is
   returned to the environment that invoked the state machine to begin with.

   `:expr` - A literal map, or a `(fn [data-model-for-context] map?)`.

   TODO: evaluation of lambda, and also a mechanism for actual return."
  [{:keys [id expr] :as attrs}]
  (merge
    {:id         (genid "done-data")
     :expression (or expr {})}
    attrs
    {:node-type :done-data}))

(defn transition
  "Define a transition. The `target` parameter can be a single keyword or a set of them (when the transition activates
   multiple specific states (e.g. parallel children).

   :type - :internal or :external
   :event - Name of the event as a keyword
   :target - Target state(s)
   :action (known internally as :content) - Action to run. See execution model.
   :cond - Expression that must be true for this transition to be enabled. See execution model."
  [{:keys [event cond target type action content] :as attrs}]
  (let [content (or action content)
        t       (if (keyword? target) #{target} target)]
    (merge
      {:id   (genid "transition")
       :type (or type :external)}
      attrs
      {:node-type :transition
       :content   content
       :target    t})))

(defn send
  "Sens an event to the specified (external) target.

  `:event`, `:target`, `:type`, and `:delay` accept literal values or functions.
  If you provide a function the signature is `(fn [data-model-for-context] value)`.

  * `:event` is the external event to send.
  * `:target` is where to send it. TODO: format
  * `:type` is which mechanism to use for sending. TODO: format
  * `:delay` ms to wait before delivery (note everything is evaluated
    BEFORE this delay).
  * `:idlocation` a vector of keywords that specifies a location in the DataModel
    to store a generated ID that uniquely identifies the event instance
    being sent. If not supplied then `id` will be the id of the send.
  "
  [{:keys [id idlocation
           event
           target
           type
           delay] :as attrs}]
  (merge
    {:id         (genid "done-data")
     :expression (or expr {})}
    attrs
    {:node-type :done-data}))

(defn invoke
  "NOT IMPLEMENTED YET.

  * `:type` is which mechanism to use for sending. TODO: format
  * `:delay` ms to wait before delivery (note everything is evaluated
    BEFORE this delay).
  * `:idlocation` a vector of keywords that specifies a location in the DataModel
    to store a generated ID that uniquely identifies the event instance
    being sent. If not supplied then `id` will be the id of the send.
  * `:finalize` A `(fn [data-model-for-context event] data-model-for-context)`
  that processes results from the external process.

   NOTES:

   * The `src` (see SCXML spec) is autogenerated for the invocation to be
     the unique ID of the session."
  [{:keys [id idlocation
           event
           target
           type
           finalize
           delay] :as attrs}]
  (assert (or id idlocation) "invoke MUST have either an id or idlocation")
  (merge attrs
    {:id        (or id (genid "invoke"))
     :node-type :invoke}))
