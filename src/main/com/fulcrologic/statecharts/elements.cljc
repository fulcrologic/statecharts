(ns com.fulcrologic.statecharts.elements
  "The elements you can define in charts.

   NOTE: The SCXML standard defines a number of elements for executable content. In cases where you want to transform
   an SCXML document to this library you should note that we treat those XML nodes as content
   that can be translated ON DOCUMENT READ into the code form used by this library."
  (:refer-clojure :exclude [send])
  (:require
    com.fulcrologic.statecharts.specs
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn >defn- => ?]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.util :refer [genid]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Core Constructs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- new-element
  ([type {:keys [id] :as attrs}]
   (new-element type attrs nil))
  ([type {:keys [id] :as attrs} children]
   (merge {:id (or id (genid (str type)))}
     attrs
     (cond-> {:node-type type}
       (seq children) (assoc :children (vec children))))))

(>defn state
  "Create a state. ID will be generated if not supplied. The `initial` element is an alias for this
   element with `:initial? true`.

   https://www.w3.org/TR/scxml/#state"
  [{:keys [id initial?] :as attrs} & children]
  [map? (s/* ::sc/element) => ::sc/element]
  (new-element :state attrs children))

(>defn parallel
  "Create a parallel node.

  https://www.w3.org/TR/scxml/#parallel"
  [{:keys [id] :as attrs} & children]
  [map? (s/* ::sc/element) => ::sc/element]
  (new-element :parallel attrs children))

(>defn transition
  "Define a transition. The `target` parameter can be a single keyword or a set of them (when the transition activates
   multiple specific states (e.g. parallel children).

   `:event` - Name of the event as a keyword, or a list of such keywords. See `events/name-match?` or SCXML for semantics.
   `:cond` - Expression that must be true for this transition to be enabled. See execution model.
   `:target` - Target state or parallel region(s) as a single keyword or list of them.
   `:type` - :internal or :external

   https://www.w3.org/TR/scxml/#transition"
  [{:keys [event cond target type] :as attrs} & children]
  [map? (s/* ::sc/element) => ::sc/element]
  (let [t    (if (keyword? target) [target] target)
        type (or type :external)]
    (new-element :transition (assoc attrs :target t :type type) children)))

(>defn initial
  "Alias for `(state {:initial? true} (transition-or-target ...))`.

   `id` The (optional) ID of this state

   https://www.w3.org/TR/scxml/#initial"
  [{:keys [id] :as attrs} transition-or-target]
  [map? (s/or
          :target keyword?
          :transition ::sc/transition-element) => ::sc/state-element]
  (state (merge {:id       (genid "initial")
                 :initial? true} attrs)
    (if (keyword? transition-or-target)
      (transition {:target transition-or-target})
      transition-or-target)))

(>defn final
  "https://www.w3.org/TR/scxml/#final"
  [{:keys [id] :as attrs} & children]
  [map? (s/* ::sc/element) => ::sc/element]
  (new-element :final attrs children))

(>defn onentry
  "https://www.w3.org/TR/scxml/#onentry"
  [{:keys [id] :as attrs} & children]
  [map? (s/* ::sc/element) => ::sc/element]
  (new-element :onentry attrs children))

(>defn onexit
  "https://www.w3.org/TR/scxml/#onexit"
  [{:keys [id] :as attrs} & children]
  [map? (s/* ::sc/element) => ::sc/element]
  (new-element :onexit attrs children))

(>defn history
  "Create a history node.

   :type - :deep or :shallow (can also use `deep? as an alias). Defaults to shallow.
   `default-transition` can be a transition element with a `:target` (per standard), or as a shortcut
     simply the value you want for the target of the transition.

   https://www.w3.org/TR/scxml/#history
   "
  [{:keys [id type deep?] :as attrs} default-transition]
  [map? ::sc/transition-element => ::sc/element]
  (let [t (if (keyword? transition) #{transition} (set transition))]
    (merge {:id (genid "history")}
      attrs
      {:node-type  :history
       :deep?      (= type :deep)
       :type       (if (or (true? deep?) (= type :deep)) :deep :shallow)
       :children   [(if (map? default-transition)
                      default-transition
                      (transition {:target default-transition}))]
       :transition t})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Executable Content
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(>defn raise
  "Raise an event in the current session.

  https://www.w3.org/TR/scxml/#raise"
  [{:keys [id event] :as attrs}]
  [map? => ::sc/element]
  (new-element :raise attrs nil))

(comment
  ;; These are defined in the spec, but should be converted by an XML reader into the executable content format
  ;; of the execution engine.
  (>defn if
    "https://www.w3.org/TR/scxml/#if"
    [{:keys [id cond] :as attrs} & children]
    [map? (s/* ::sc/element) => ::sc/element]
    (new-element :if attrs children))

  (>defn elseif
    "https://www.w3.org/TR/scxml/#elseif"
    [{:keys [id cond] :as attrs}]
    [map? => ::sc/element]
    (new-element :elseif attrs nil))

  (>defn else
    "https://www.w3.org/TR/scxml/#else"
    [{:keys [id] :as attrs}]
    [map? => ::sc/element]
    (new-element :else attrs children))

  (>defn foreach
    "https://www.w3.org/TR/scxml/#foreach"
    [{:keys [id array item index] :as attrs} & children]
    [map? (s/+ ::sc/element) => ::sc/element]
    (new-element :foreach attrs children))

  (>defn log
    "https://www.w3.org/TR/scxml/#log"
    [{:keys [id label expr] :as attrs}]
    [map? => ::sc/element]
    (new-element :log attrs nil)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Model
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(>defn data-model
  "Create a data model (in a state or machine context). `expr` is an expression
   (value or function) that will result in the initial value of the data.

   If the expression is a value, then you are using early binding. If it is
   a lambda, then the data will be bound when its surrounding state is entered, but
   before any `on-entry` is invoked.

   https://www.w3.org/TR/scxml/#data-module"
  [{:keys [id] :as attrs} & children]
  [map? (s/* ::sc/element) => ::sc/element]
  (new-element :data-model attrs children))

(>defn data
  "https://www.w3.org/TR/scxml/#data"
  [{:keys [id src expr] :as attrs} & children]
  [map? (s/* ::sc/element) => ::sc/element]
  (new-element :data attrs children))

(>defn assign
  "https://www.w3.org/TR/scxml/#assign"
  [{:keys [id location expr] :as attrs} & children]
  [map? (s/* ::sc/element) => ::sc/element]
  (new-element :assign attrs children))

(>defn donedata
  "Value to return when final state is entered.

  https://www.w3.org/TR/scxml/#done-data"
  [{:keys [id] :as attrs} & children]
  [map? (s/* ::sc/element) => ::sc/element]
  (new-element :donedata attrs children))

(>defn param
  "Parameter to include. Use `location` to pull the value from the data model, and `expr` to calculate it.

   https://www.w3.org/TR/scxml/#param"
  [{:keys [id name location expr] :as attrs}]
  [map? => ::sc/element]
  (new-element :param attrs nil))

(>defn script
  "https://www.w3.org/TR/scxml/#script"
  [{:keys [id src] :as attrs} & children]
  [map? (s/* ::sc/element) => ::sc/element]
  (new-element :script attrs children))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; External Communication
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(>defn send
  "Sends an event to the specified (external) target, and can also be used to place events on the current machine's
   external event queue (e.g. with some delay).

  * `:event` is the external event to send.
  * `:eventexpr` is the external event to send.
  * `:target` is where to send it.
  * `:targetexpr` is where to send it.
  * `:type` is which mechanism to use for sending.
  * `:typeexpr` is which mechanism to use for sending.
  * `:delay` ms to wait before delivery (note everything is evaluated
    BEFORE this delay).
  * `:delayexpr`
  * `:namelist` - List of location expression (to include from the data model)
  * `:idlocation` a vector of keywords that specifies a location in the DataModel
    to store a generated ID that uniquely identifies the event instance
    being sent. If not supplied then `id` will be the id of the element itself.
  "
  [{:keys [id idlocation event type typeexpr eventexpr
           target targetexpr delay] :as attrs} & children]
  [map? (s/* ::sc/element) => ::sc/element]
  (new-element :send attrs children))

(>defn cancel
  "Cancel a delayed send.

  https://www.w3.org/TR/scxml/#cancel"
  [{:keys [id sendid sendidexpr] :as attrs}]
  [map? => ::sc/element]
  (new-element :cancel attrs nil))

(>defn invoke
  "Create an instance of an external service.

  * `:id` The id of the element. See below for special considerations.
  * `:type` The type of external service
  * `:typeexpr` Expression variant for `:type`
  * `:src` A URI to send to the external service. Can be string or expression that results in a URI.
  * `:srcexpr` Expression alt for src
  * `:namelist` A vector of data model locations from the data model to include in the invocation.
  * `:autoforward` Enable forwarding of (external) events to the invoked process.
  * `:idlocation` a vector of keywords that specifies a location in the DataModel
    to store a generated ID that uniquely identifies the event instance
    being sent. If not supplied then `id` will be the id of the send.
  * `:finalize` A `(fn [data-model-for-context event] data-model-for-context)`
  that processes results from the external process.

   NOTES:

   * The `id` *can* be supplied, but if not supplied a new ID per invocation (according to spec) must be generated
     at each execution.

  https://www.w3.org/TR/scxml/#invoke"
  [{:keys [id type typeexpr id idlocation autoforward namelist src srcexpr] :as attrs} & children]
  [map? (s/* ::sc/element) => ::sc/element]
  (new-element :invoke attrs children))

(>defn finalize
  "Update the data model (via executable content) with data contained in events returned by an invoked session.

  Executable content can find the returned event in `_event`.

  https://www.w3.org/TR/scxml/#finalize"
  [{:keys [id] :as attrs} & children]
  [map? (s/* ::sc/element) => ::sc/element]
  (new-element :finalize attrs children))
