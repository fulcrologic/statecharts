(ns com.fulcrologic.statecharts.elements
  "The elements you can define in charts. The intention is for this model to be potentially serializable for users
   that need that. Thus, the expressions used in these data structures *MAY* use CLJC functions/code, or may represent
   such elements as strings or other (quoted) EDN. The ExecutionModel is responsible for this part of the interpretation.

   The overall data model is represented as a map. The DataModel implementation MAY choose scope and resolution
   rules. Location expressions are interpreted by the DataModel, but it is recommended they be keywords or vectors
   of keywords.

   ## Executable Content

   The W3C SCXML spec defines *executable content* as the action elements that do work:
   `script`, `assign`, `raise`, `send`, `log`, `cancel`, `if`/`elseif`/`else`, and `foreach`.

   These appear as children of *container* elements: `on-entry`, `on-exit`, `transition`,
   and `finalize`. Some executable content elements (`if`, `elseif`, `else`, `foreach`) can
   themselves contain executable content as children.

   The exact form of expressions (`:expr`, `:cond`) depends on the installed `ExecutionModel`.
   With the common `lambda` execution model (`execution-model.lambda`), expressions are
   Clojure functions:

   ```clojure
   ;; 2-arg form (default):
   (script {:expr (fn [env data] (println data))})

   ;; 4-arg form (when :explode-event? is true on the execution model):
   (script {:expr (fn [env data event-name event-data] ...)})
   ```

   If a script expression returns a vector, the lambda execution model will attempt to apply
   it as a data-model update operation.

   See `execution-model.lambda/new-execution-model` for full details."
  #?(:cljs (:require-macros [com.fulcrologic.statecharts.elements]))
  (:refer-clojure :exclude [send])
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    com.fulcrologic.statecharts.malli-specs
    [com.fulcrologic.guardrails.malli.core :refer [>defn =>]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.util :refer [genid]]
    [taoensso.timbre :as log]))

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
   :invoke     #{:finalize}
   :parallel   #{:on-entry :on-exit :transition :state :parallel :history :data-model :invoke}
   :transition executable-content-types
   :if         executable-content-types
   :else-if    executable-content-types
   :else       executable-content-types
   :for-each   executable-content-types})

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
;; System Variables and Predicates (W3C SCXML Section 5.7, 5.9)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn In
  "Returns a predicate function that checks if the statechart is currently in the given state.
   SCXML In() predicate (Section 5.9). Returns a `(fn [env data])` that returns true when
   `state-id` is in the current configuration. For use in `:cond` expressions.

   ```clojure
   (transition {:event :check :target :next :cond (In :some-state)})
   ```"
  [state-id]
  (fn [{::sc/keys [vwmem] :as _env} & _]
    (boolean
      (some-> vwmem deref ::sc/configuration (contains? state-id)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Core Constructs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn state
  "Atomic or compound state.

   Attrs: `:id`, `:initial` (keyword target, shorthand for nested `initial` element), `:initial?`.
   Children: `on-entry`, `on-exit`, `transition`, `state`, `parallel`, `final`, `history`,
             `data-model`, `invoke`.

   https://www.w3.org/TR/scxml/#state"
  [{:keys [id initial initial?] :as attrs} & children]
  #?(:cljs
     (when (and goog.DEBUG (not id))
       (log/warn "State is missing an explicit ID. Code reloading on an active chart will malfunction.")))
  (new-element :state attrs children))

(defn parallel
  "Parallel state — all child regions are active simultaneously.

   Attrs: `:id`.
   Children: `on-entry`, `on-exit`, `transition`, `state`, `parallel`, `history`,
             `data-model`, `invoke`.

   https://www.w3.org/TR/scxml/#parallel"
  [{:keys [id] :as attrs} & children]
  #?(:cljs
     (when (and goog.DEBUG (not id))
       (log/warn "Parallel state is missing an explicit ID. Code reloading on an active chart will malfunction.")))
  (new-element :parallel attrs children))

(defn transition
  "Event-driven or eventless transition.

   Attrs:
   - `:event` — keyword or list of keywords. A list means the transition is enabled when
     any of the listed events match (via token-based `events/name-match?`).
   - `:cond` — predicate expression; transition is enabled only when truthy
   - `:target` — keyword or list of keywords (target state(s))
   - `:type` — `:internal` or `:external` (default). An external transition will exit and
     re-enter the source state (immediate parent) even if the target is the source state
     or a descendant. An internal transition will not exit the source state when the target
     is the source or a descendant.
   - `:diagram/label` — optional human-readable label for diagram rendering
   - `:diagram/condition` — optional string representation of the condition for diagrams

   Children: `raise`, `log`, `if`, `elseif`, `else`, `foreach`, `assign`, `script`, `send`, `cancel`.

   https://www.w3.org/TR/scxml/#transition"
  [{:keys [event cond target type] :as attrs} & children]
  (let [t    (if (keyword? target) [target] target)
        type (or type :external)]
    (new-element :transition (assoc attrs :target t :type type) children)))

(>defn initial
  "Shorthand for a state marked as the initial child.

   An initial state must be unique in the compound parent, and implies an immediate transition
   to a target. Statecharts always have an initial node, even if implied (auto-inserted to
   auto-transition to the first child in document order if unspecified).

   ```clojure
   (initial {} :target-state)
   ;; => (state {:initial? true} (transition {:target :target-state}))
   ```

   The second argument may be a keyword target or a `transition` element.

   https://www.w3.org/TR/scxml/#initial"
  [{:keys [id] :as attrs} transition-or-target]
  [map? [:or keyword? ::sc/transition-element] => ::sc/state-element]
  (state (merge {:id       (genid "initial")
                 :initial? true} attrs)
    (if (keyword? transition-or-target)
      (transition {:target transition-or-target})
      transition-or-target)))

(defn final
  "Final (accepting) state — entering this state generates a `done` event.

   Attrs: `:id`.
   Children: `on-entry`, `on-exit`, `done-data`.

   https://www.w3.org/TR/scxml/#final"
  [{:keys [id] :as attrs} & children]
  (new-element :final attrs children))

(defn on-entry
  "Actions to execute when entering the parent state.

   Attrs: `:id`.
   Children: `raise`, `log`, `if`, `elseif`, `else`, `foreach`, `assign`, `script`, `send`, `cancel`.

   e.g.

   ```
   (state {:id :x}
     (on-entry {}
       (script {:expr ...})))
   ```

   https://www.w3.org/TR/scxml/#onentry"
  [{:keys [id] :as attrs} & children]
  (new-element :on-entry attrs children))

(defn on-exit
  "Actions to execute when exiting the parent state.

   Attrs: `:id`.
   Children: `raise`, `log`, `if`, `elseif`, `else`, `foreach`, `assign`, `script`, `send`, `cancel`.

   e.g.

   ```
   (state {:id :x}
     (on-exit {}
       (script {:expr ...})))
   ```

   https://www.w3.org/TR/scxml/#onexit"
  [{:keys [id] :as attrs} & children]
  (new-element :on-exit attrs children))

(defn- expr-label [stmts] (str/join "\n" (map pr-str stmts)))

(declare script)

(defmacro exit-fn
  "Shorthand for an `on-exit` with a single script expression.

   ```clojure
   (exit-fn [env data] body)
   ;; => (on-exit {} (script {:expr (fn [env data] body)}))
   ```"
  [arglist & body]
  `(on-exit {:diagram/label ~(expr-label body)}
     (script {:expr (fn ~arglist ~@body)})))

(defmacro entry-fn
  "Shorthand for an `on-entry` with a single script expression.

   ```clojure
   (entry-fn [env data] body)
   ;; => (on-entry {} (script {:expr (fn [env data] body)}))
   ```"
  [arglist & body]
  `(on-entry {:diagram/label ~(expr-label body)}
     (script {:expr (fn ~arglist ~@body)})))


(defn history
  "History pseudo-state — remembers the last active child configuration.

   Attrs:
   - `:id` — identifier for this history node
   - `:type` — `:deep` or `:shallow` (default `:shallow`)
   - `:deep?` — alias for `:type :deep` when true

   The second argument is the default transition, used when the parent has never been entered.
   It may be a `transition` element or a keyword target (shorthand).

   https://www.w3.org/TR/scxml/#history"
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
  "Place an event on the internal event queue of the current session.

   Attrs:
   - `:id`
   - `:event` — keyword name of the event to raise
   - `:data` — literal data or expression (see ns docstring) to include as event data (optional)

   https://www.w3.org/TR/scxml/#raise"
  [{:keys [id event] :as attrs}]
  (new-element :raise attrs nil))

(defn log
  "Log a message. See ns docstring for expression details.

   Attrs: `:id`, `:label` (string prefix), `:expr` (expression whose result is logged),
          `:level` (passed to the logging implementation).

   https://www.w3.org/TR/scxml/#log"
  [{:keys [id label expr] :as attrs}]
  (new-element :log attrs nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Flow Control (SCXML Section 3.12)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn If
  "Conditional execution. Capitalized to avoid collision with `clojure.core/if`.

   Attrs: `:id`, `:cond` (predicate expression).
   Children: `raise`, `log`, `if`, `elseif`, `else`, `foreach`, `assign`, `script`, `send`, `cancel`.

   https://www.w3.org/TR/scxml/#if"
  [{:keys [id cond] :as attrs} & children]
  (new-element :if attrs children))

(defn elseif
  "Conditional branch within an `If` element.

   Attrs: `:id`, `:cond` (predicate expression).
   Children: `raise`, `log`, `if`, `elseif`, `else`, `foreach`, `assign`, `script`, `send`, `cancel`.

   https://www.w3.org/TR/scxml/#if"
  [{:keys [id cond] :as attrs} & children]
  (new-element :else-if attrs children))

(defn else
  "Default branch within an `If` element.

   Attrs: `:id`.
   Children: `raise`, `log`, `if`, `elseif`, `else`, `foreach`, `assign`, `script`, `send`, `cancel`.

   https://www.w3.org/TR/scxml/#if"
  [{:keys [id] :as attrs} & children]
  (new-element :else attrs children))

(defn foreach
  "Iterate over a collection, executing children for each item.

   Attrs:
   - `:id`
   - `:array` — expression returning the collection to iterate
   - `:item` — location (keyword or vector) to bind the current item
   - `:index` — location to bind the current index (optional)

   Children: `raise`, `log`, `if`, `elseif`, `else`, `foreach`, `assign`, `script`, `send`, `cancel`.

   https://www.w3.org/TR/scxml/#foreach"
  [{:keys [id array item index] :as attrs} & children]
  (new-element :for-each attrs children))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Model
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn data-model
  "Declare the data model for a state or the root chart. See ns docstring for expression details.

   Attrs:
   - `:id`
   - `:expr` — expression whose result becomes the initial data (typically a map)
   - `:src` — URI/location from which to load initial data (if the DataModel supports it)

   https://www.w3.org/TR/scxml/#data-module"
  [{:keys [id src expr] :as attrs}]
  (new-element :data-model attrs))

(defn assign
  "Assign the result of `expr` into the data model at `location`.

   Attrs: `:id`, `:location` (keyword or vector of keywords), `:expr` (expression).

   https://www.w3.org/TR/scxml/#assign"
  [{:keys [id location expr] :as attrs} & children]
  (new-element :assign attrs children))

(defn done-data
  "Data to include in the `done` event when a `final` state is entered.

   Attrs: `:id`, `:expr` (expression whose result becomes the event data).

   NOTE: Uses `:expr` rather than child `<param>`/`<content>` elements as in the XML spec.

   https://www.w3.org/TR/scxml/#donedata"
  [{:keys [id expr] :as attrs}]
  (new-element :done-data attrs))

(defn script
  "A script to execute. MAY support loading the code via `src`, or supply the code via `expr` (in the format required
   by your ExecutionModel). See ns docstring for expression details.

   Attrs:
   - `:id`
   - `:expr` — expression to execute (format depends on ExecutionModel)
   - `:src` — URI from which to load the script (if supported)
   - `:diagram/label` — human-readable label for diagram rendering

   https://www.w3.org/TR/scxml/#script"
  [{:keys [id src expr] :as attrs}]
  (new-element :script attrs))

(defmacro script-fn
  "Shorthand for a `script` with a lambda expression.

   ```clojure
   (script-fn [env data] body)
   ;; => (script {:expr (fn [env data] body)})
   ```"
  [arglist & body]
  `(script {:diagram/label ~(expr-label body)
            :expr          (fn ~arglist ~@body)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; External Communication
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn send
  "Sends an event to the specified (external) target (which could be an external system, this machine,
   or another machine). See ns docstring for expression details.

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

  https://www.w3.org/TR/scxml/#send"
  [attrs]
  (new-element :send attrs))

(def Send
  "[attrs]

   Same as `send`, but doesn't alias over clojure.core/send.
   See ns docstring for expression details.

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

(defn finalize
  "Executable content that runs when an event from an invoked child service is received,
   BEFORE the event is processed by the parent state machine. Used inside `invoke` elements
   to update the data model with data from the child.

   Children: `raise`, `log`, `if`, `elseif`, `else`, `foreach`, `assign`, `script`, `send`, `cancel`.

   https://www.w3.org/TR/scxml/#finalize"
  [attrs & children]
  (new-element :finalize attrs children))

(defn invoke
  "Create an instance of an external service that can return data (and send events) back to the calling state(s). An
  invoked service stays active while the state is active, and is terminated when the parent state is exited.
  See ns docstring for expression details.

  Parameters (via :namelist or :params) will be pushed into the data model of the invoked chart.

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
  * `:params` A map from parameter name to expression in the execution model's notation. E.g. `{[:x] (fn [env data] 42)}`, OR an execution model expression (e.g. `(fn [evn data] {[ks] value})`) that
     results in a data map to use as params. MAY be specified with :namelist as well.
  * `:namelist` A map from parameter source location to target location path. E.g. `{[:x] [:ROOT :k]}`. These values will be copied from the current statechart into parameters for the invocation.
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

   Children: `finalize`.

   NOTES:

   * The `id` *can* be supplied. If it is, then it will be the session id of an invoked chart. The specification
     says that you should specify one or the other, but this implementation allows you to specify both. If you
     don't supply an id, then an auto-generated one will be made for you.
   ** If `idlocation` AND `id` are supplied, then your ID is honored, AND stored at the given location.
   ** If ONLY `idlocation` then an autogenerated ID will be
     made for the chart's session ID as a string of <parent-state-id>.<genid> and stored there.
   ** If ONLY `id` is given, then that will be the session id of the invoked chart.
   ** If neither are given, then an id will be generated, but not stored, and there is no guaranteed value of the id.

  https://www.w3.org/TR/scxml/#invoke"
  [{:keys [id] :as attrs} & children]
  (let [fattr (:finalize attrs)]
    (new-element :invoke (cond-> (dissoc attrs :finalize)
                           id (assoc :explicit-id? true))
      (cond-> (vec children)
        fattr (conj children (finalize {}
                               (script {:expr fattr})))))))
