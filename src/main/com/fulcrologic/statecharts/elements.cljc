(ns com.fulcrologic.statecharts.elements
  "The elements you can define in charts. The intention is for this model to be potentially serializable for users
   that need that. Thus, the expressions used in these data structures *MAY* use CLJC functions/code, or may represent
   such elements a strings or other (quoted) EDN. The ExecutionModel is responsible for this part of the interpretation.

   The overall data model is represented as a map. The DataModel implementation MAY choose scope and resolution
   rules. Location expressions are interpreted by the DataModel, but it is recommended they be keywords or vectors
   of keywords.

   NOTE: The SCXML standard defines a number of elements (if, else, elseif, foreach, log) for abstract
   executable content. In cases where you want to transform an SCXML document to this library you should note that we
   treat those XML nodes as content that can be translated ON DOCUMENT READ into the code form used by this library.
   "
  #?(:cljs (:require-macros [com.fulcrologic.statecharts.elements]))
  (:refer-clojure :exclude [send])
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    com.fulcrologic.statecharts.malli-specs
    [com.fulcrologic.guardrails.malli.core :refer [>defn =>]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.util :refer [genid]]))

(def executable-content-types #{:raise :log :if :else-if :else :for-each :assign :script :send :cancel})
(def legal-children
  {:state      #{:on-entry :on-exit :transition :state :parallel :final :history :data-model :invoke}
   :initial    #{:transition}
   :final      #{:on-entry :on-exit :done-data}
   :finalize   executable-content-types
   :on-entry   executable-content-types
   :on-exit    executable-content-types
   :history    #{:transition}
   :script     #{}
   :parallel   #{:on-entry :on-exit :transition :state :parallel :history :data-model :invoke}
   :transition executable-content-types})

(defn new-element
  "Create a new element with the given `type` and `attrs`. Will auto-assign an ID if it is not supplied. Use
   this as a helper when creating new element types (e.g. executable content) to ensure consistency in operation.

   Allows for immediate children to be nested in vectors, which allows for helper functions that can emit more
   that a single element from one function."
  ([type {:keys [id] :as attrs}]
   (new-element type attrs nil))
  ([type {:keys [id] :as attrs} children]
   (let [children       (flatten children)
         children-types (into #{}
                          (map :node-type)
                          children)
         legal-types    (get legal-children type)]
     (when-let [bad-types (and (set? legal-types)
                            (seq (set/difference children-types legal-types)))]
       (throw (ex-info (str "Illegal children of " type " with attributes " attrs
                         ". That node cannot have children of type(s): " bad-types) {})))
     (merge {:id (or id (genid (name type)))}
       attrs
       (cond-> {:node-type type}
         (seq children) (assoc :children (vec (remove nil? children))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Core Constructs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn state
  "Create a state. ID will be generated if not supplied. The `initial` element is an alias for this
   element with `:initial? true`. The `:initial` key can be used in PLACE of a nested initial element.

   https://www.w3.org/TR/scxml/#state"
  [{:keys [id initial initial?] :as attrs} & children]
  (new-element :state attrs children))

(defn parallel
  "Create a parallel node.

  https://www.w3.org/TR/scxml/#parallel"
  [{:keys [id] :as attrs} & children]
  (new-element :parallel attrs children))

(defn transition
  "Define a transition. The `target` parameter can be a single keyword or a set of them (when the transition activates
   multiple specific states (e.g. parallel children).

   `:event` - Name of the event as a keyword, or a list of such keywords. See `events/name-match?` or SCXML for semantics.
   `:cond` - Expression that must be true for this transition to be enabled. See execution model.
   `:target` - Target state or parallel region(s) as a single keyword or list of them.
   `:type` - :internal or :external (default)

   https://www.w3.org/TR/scxml/#transition"
  [{:keys [event cond target type] :as attrs} & children]
  (let [t    (if (keyword? target) [target] target)
        type (or type :external)]
    (new-element :transition (assoc attrs :target t :type type) children)))

(>defn initial
  "Alias for `(state {:initial? true} (transition-or-target ...))`.

   `id` The (optional) ID of this state

   https://www.w3.org/TR/scxml/#initial"
  [{:keys [id] :as attrs} transition-or-target]
  [map? [:or keyword? ::sc/transition-element] => ::sc/state-element]
  (state (merge {:id       (genid "initial")
                 :initial? true} attrs)
    (if (keyword? transition-or-target)
      (transition {:target transition-or-target})
      transition-or-target)))

(defn final
  "https://www.w3.org/TR/scxml/#final"
  [{:keys [id] :as attrs} & children]
  (new-element :final attrs children))

(defn on-entry
  "https://www.w3.org/TR/scxml/#onentry"
  [{:keys [id] :as attrs} & children]
  (new-element :on-entry attrs children))

(defn on-exit
  "https://www.w3.org/TR/scxml/#onexit"
  [{:keys [id] :as attrs} & children]
  (new-element :on-exit attrs children))

(defn- expr-label [stmts] (str/join "\n" (map pr-str stmts)))

(declare script)

(defmacro exit-fn
  "A macro that emits a `on-exit` element, but looks more like a normal CLJC lambda:

  ```
  (exit-fn [env data] ...body...)
  ```

  is shorthand for

  ```
  (on-exit {}
    (script {:expr (fn [env data] ...body...)}))
  ```

  "
  [[env-sym data-sym] & body]
  `(on-exit {:diagram/label ~(expr-label body)}
     (script {:expr (fn [~env-sym ~data-sym]
                      ~@body)})))

(defmacro entry-fn
  "A macro that emits a `on-entry` element, but looks more like a normal CLJC lambda:

  ```
  (entry-fn [env data] ...)
  ```

  is shorthand for

  ```
  (on-entry {}
    (script {:expr (fn [env data] ...)})
  ```

  "
  [[env-sym data-sym] & body]
  `(on-entry {:diagram/label ~(expr-label body)}
     (script {:expr (fn [~env-sym ~data-sym]
                      ~@body)})))


(defn history
  "Create a history node.

   :type - :deep or :shallow (can also use `deep? as an alias). Defaults to shallow.
   `default-transition` can be a transition element with a `:target` (per standard), or as a shortcut
     simply the value you want for the target of the transition.

   https://www.w3.org/TR/scxml/#history
   "
  [{:keys [id type deep?] :as attrs} default-transition]
  (let [{:keys [cond event target]
         :as   default-transition} (if (map? default-transition)
                                     default-transition
                                     (transition {:target default-transition}))
        h (merge {:id (genid "history")}
            attrs
            {:node-type :history
             :deep?     (= type :deep)
             :type      (if (or (true? deep?) (= type :deep)) :deep :shallow)
             :children  [default-transition]})]
    (assert (nil? cond) "Default history transitions MUST NOT have a :cond")
    (assert (nil? event) "Default history transitions MUST NOT have an :event")
    (assert target "Default history transitions MUST have a :target")
    h))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Executable Content
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn raise
  "Raise an event in the current session.

  https://www.w3.org/TR/scxml/#raise"
  [{:keys [id event] :as attrs}]
  (new-element :raise attrs nil))

(defn log
  "Log a message. (Currently uses Timbre, with debug level)

  https://www.w3.org/TR/scxml/#log"
  [{:keys [id label expr] :as attrs}]
  (new-element :log attrs nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Model
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn data-model
  "Create a data model (in a state or machine context).

   `:expr` is an expression that can be run by your current ExecutionModel. The result of the expression
   becomes the value of your initial data model (typically a map).
   `:src` (if the data model supports it) is a location from which to read the data for the data model.

   https://www.w3.org/TR/scxml/#data-module"
  [{:keys [id src expr] :as attrs}]
  (new-element :data-model attrs))

(defn assign
  "Assign the value of `expr` into the data model at `location`. Location expressions are typically vectors of
   keywords in the DataModel.

  https://www.w3.org/TR/scxml/#assign"
  [{:keys [id location expr] :as attrs} & children]
  (new-element :assign attrs children))

(defn done-data
  "Data (calculated by expr) to return to caller when a final state is entered. See `data-model`.

  NOTE: Differs from spec (uses expr instead of child elements)

  https://www.w3.org/TR/scxml/#donedata"
  [{:keys [id expr] :as attrs}]
  (new-element :done-data attrs))

(defn script
  "A script to execute. MAY support loading the code via `src`, or supply the code via `expr` (in the format required
   by your ExecutionModel).

  See the documentation for you data model to understand the semantics and operation of this element.

  https://www.w3.org/TR/scxml/#script"
  [{:keys [id src expr] :as attrs}]
  (new-element :script attrs))

(defmacro script-fn
  "A macro that emits a `script` element, but looks more like a normal CLJC lambda:

  ```
  (script-fn [env data] ...)
  ```

  is shorthand for

  ```
  (script {:expr (fn [env data] ...)})
  ```

  "
  [[env-sym data-sym] & body]
  `(script {:diagram/label ~(expr-label body)
            :expr          (fn [~env-sym ~data-sym]
                             ~@body)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; External Communication
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn send
  "Sends an event to the specified (external) target (which could be an external system, this machine,
   or another machine).

   * `id` - The id of the send element. Used as the event send ID if no idlocation is provided.
   * `:idlocation` a location in the DataModel
     to store a generated ID that uniquely identifies the event instance
     being sent. If not supplied then `id` will be the id of the element itself.
   * `delay` - A literal number of ms to delay
   * `delayexpr` - A (fn [env data]) to return ms of delay
   * `namelist` - A list of locations that pull data from data model into the data of the event
   * `content` - A (fn [env data]) to generate the data for the event
   * `event` - Name of the event
   * `eventexpr` - (fn [env data]) to generate the name of the event
   * `target` - The target of the event
   * `targetexpr` - A (fn [env data]) to generate the target of the event
   * `type` - The type of event
   * `typeexpr` - A (fn [env data]) to generate the type of the event

  If BOTH namelist and content are supplied, then they will be MERGED as the event data with `content` overwriting
  any conflicting keys from `namelist`.
  "
  [attrs]
  (new-element :send attrs))

(def Send
  "[attrs]

   Same as `send`, but doesn't alias over clojure.core/send

   Sends an event to the specified (external) target (which could be an external system, this machine,
     or another machine).

   * `id` - The id of the send element. Used as the event send ID if no idlocation is provided.
   * `:idlocation` a location in the DataModel
     to store a generated ID that uniquely identifies the event instance
     being sent. If not supplied then `id` will be the id of the element itself.
   * `delay` - A literal number of ms to delay
   * `delayexpr` - A (fn [env data]) to return ms of delay
   * `namelist` - A list of locations that pull data from data model into the data of the event
   * `content` - A (fn [env data]) to generate the data for the event
   * `event` - Name of the event
   * `eventexpr` - (fn [env data]) to generate the name of the event
   * `target` - The target of the event
   * `targetexpr` - A (fn [env data]) to generate the target of the event
   * `type` - The type of event
   * `typeexpr` - A (fn [env data]) to generate the type of the event
    "
  send)

(defn cancel
  "Cancel a delayed send (see `send`'s id and idlocation parameter).

   `sendid` A literal value of the ID of the send to cancel
   `sendidexpr` A `(fn [env data] id)` to calculate the ID of the send to cancel

  https://www.w3.org/TR/scxml/#cancel"
  [{:keys [id sendid sendidexpr] :as attrs}]
  (new-element :cancel attrs nil))

(defn finalize [attrs & children]
  (new-element :finalize attrs children))

(defn invoke
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
  * `:params` A map from parameter name to expression in the execution model's notation. E.g. `{:x (fn [env data] 42)}`
  * `:namelist` A map from parameter name to location path. E.g. `{:x [:ROOT :k]}`
  * `:src` A literal URI to be passed to the external service.
  * `:srcexpr` An expression in the execution model's notation to calculate a URI to be passed to the external service.
  * `:finalize` A stand-in for the `expr` parameter on a nested `script` element.  i.e. this is a shorthand for
                nesting a `(finalize {} (script {:expr=<this value>}))`.
  * `:type` A static value for the type of external service. Defaults to statechart, which is equivalent to the URI
            'http://www.w3.org/TR/scxml/'. Implementations may choose platform-specific interpretations of this argument.
  * `:typeexpr` An expression version of `:type`. Use your ExecutionModel to run the expression to get the type.
  * `:autoforward` Enable forwarding of (external) events to the invoked process.
  * `:idlocation` a location (i.e. vector of keywords) that specifies a location in the DataModel
                  to store a generated ID that uniquely identifies the invocation instance.

   NOTES:

   * The `id` *can* be supplied, but if not supplied a new ID per invocation (according to spec) must be generated
     at each execution and stored in `idlocation`.

  https://www.w3.org/TR/scxml/#invoke"
  [attrs & children]
  (let [fattr (:finalize attrs)]
    (new-element :invoke (dissoc attrs :finalize)
      (cond-> (vec children)
        fattr (conj children (finalize {}
                               (script {:expr fattr})))))))
