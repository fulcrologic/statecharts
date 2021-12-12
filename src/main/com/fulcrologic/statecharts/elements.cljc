(ns com.fulcrologic.statecharts.elements
  "The elements you can define in charts. The intention is for this model to be potentially serializable for users
   that need that. Thus, the expressions used in these data structures *MAY* use CLJC functions/code, or may represent
   such elements a strings or other (quoted) EDN. The ExecutionModel is responsible for this part of the interpretation.

   The overall data model is represented as a map. The DataModel implementation MAY choose scoping and resolution
   rules. Location expressions are interpreted by the DataModel, but it is recommended they be keywords or vectors
   of keywords.

   NOTE: The SCXML standard defines a number of elements (if, else, elseif, foreach, log) for abstract
   executable content. In cases where you want to transform an SCXML document to this library you should note that we
   treat those XML nodes as content that can be translated ON DOCUMENT READ into the code form used by this library.
   "
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
   element with `:initial? true`. The `:initial` key can be used in PLACE of a nested initial element.

   https://www.w3.org/TR/scxml/#state"
  [{:keys [id initial initial?] :as attrs} & children]
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
  (merge {:id (genid "history")}
    attrs
    {:node-type :history
     :deep?     (= type :deep)
     :type      (if (or (true? deep?) (= type :deep)) :deep :shallow)
     :children  [(if (map? default-transition)
                   default-transition
                   (transition {:target default-transition}))]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Executable Content
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(>defn raise
  "Raise an event in the current session.

  https://www.w3.org/TR/scxml/#raise"
  [{:keys [id event] :as attrs}]
  [map? => ::sc/element]
  (new-element :raise attrs nil))

(>defn log
  "Log a message.

  https://www.w3.org/TR/scxml/#log"
  [{:keys [id label expr] :as attrs}]
  [map? => ::sc/element]
  (new-element :log attrs nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Model
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(>defn datamodel
  "Create a data model (in a state or machine context).

   `:expr` is an expression that can be run by your current ExecutionModel. The result of the expression
   becomes the value of your initial data model (typically a map).
   `:src` (if the data model supports it) is a location from which to read the data for the data model.

   https://www.w3.org/TR/scxml/#data-module"
  [{:keys [id src expr] :as attrs}]
  [map? => ::sc/element]
  (new-element :data-model attrs))

(>defn assign
  "Assign the value of `expr` into the data model at `location`. Location expressions are typically vectors of
   keywords in the DataModel.

  https://www.w3.org/TR/scxml/#assign"
  [{:keys [id location expr] :as attrs} & children]
  [map? (s/* ::sc/element) => ::sc/element]
  (new-element :assign attrs children))

(>defn donedata
  "Data (calculated by expr) to return to caller when a final state is entered. See `datamodel`.

  https://www.w3.org/TR/scxml/#donedata"
  [{:keys [id expr] :as attrs}]
  [map? => ::sc/element]
  (new-element :donedata attrs))

(>defn script
  "A script to execute. MAY support loading the code via `src`, or supply the code via `expr` (in the format required
   by your ExecutionModel).

  See the documentation for you data model to understand the semantics and operation of this element.

  https://www.w3.org/TR/scxml/#script"
  [{:keys [id src expr] :as attrs}]
  [map? => ::sc/element]
  (new-element :script attrs))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; External Communication
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(>defn send
  "Sends an event to the specified (external) target (which could be an external system, this machine,
   or another machine).

  * `:event` An expression that results in the event *name* to send.
  * `:params` An expression that results in data to be included in the event.
  * `:target` An expression that gives the target to send to.
  * `:type` An expression generating a selector for which mechanism to use for sending.
  * `:delay` A number of milliseconds to delay the send, or an expression for computing it.
  * `:namelist` - List of location expressions (vector of vectors) to include from the data model.
  * `:idlocation` a vector of keywords that specifies a location in the DataModel
    to store a generated ID that uniquely identifies the event instance
    being sent. If not supplied then `id` will be the id of the element itself.
  "
  [attrs]
  [map? => ::sc/element]
  (new-element :send attrs))

(>defn cancel
  "Cancel a delayed send (see `send`'s idlocation parameter). `:sendid` can be an expression.

  https://www.w3.org/TR/scxml/#cancel"
  [{:keys [id sendid] :as attrs}]
  [map? => ::sc/element]
  (new-element :cancel attrs nil))

(>defn invoke
  "Create an instance of an external service that can return data (and send events) back to the calling state(s). An
  invoked service stays active while the state is active, and is terminated when the parent state is exited.

  When the invocation is created it will be associated with the identifier `<state-id>.<platform-id>` where `<state-id>`
  is the ID of the state in which the invocation lives, and `<platform-id>` is a unique autogenerated id. This is
  stored in `idlocation` (if supplied) in the data model. This identifier is known as the `invokeid`.

  Events generated by the invoked service will cause `finalize` to be called (if supplied). Note that each event causes
  this, and `finalize` MAY update the data model. Such processing happens before the event is handed to the rest of
  the state machine for evaluation.

  A `:done.invoke.<invokeid>` event is generated if the invoked service reaches a final state and exits.

  If the state of the (local) machine is exited before receiving the done event, then it cancels the invoked service
  and ignores any events received by it after that point.

  * `:id` The id of the element. See below for special considerations.
  * `:params` An expression in the execution model's notation that can calculate the data to send to the invocation.
  * `:finalize` An expression to run when the invocation returns, and may update the data model.
  * `:type` The type of external service (expression allowed)
  * `:autoforward?` Enable forwarding of (external) events to the invoked process.
  * `:idlocation` a vector of keywords that specifies a location in the DataModel
    to store a generated ID that uniquely identifies the event instance
    being sent. If not supplied then `id` will be the id of the send.
  * `:finalize` A `(fn [data-model-for-context event] data-model-for-context)`
  that processes results from the external process.

   NOTES:

   * The `id` *can* be supplied, but if not supplied a new ID per invocation (according to spec) must be generated
     at each execution.

  https://www.w3.org/TR/scxml/#invoke"
  [attrs]
  [map? => ::sc/element]
  (new-element :invoke attrs))
