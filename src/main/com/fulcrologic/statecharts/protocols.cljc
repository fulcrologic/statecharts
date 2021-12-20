(ns com.fulcrologic.statecharts.protocols
  "Protocols for the various pluggable bits of this library:

   Processor - A state machine processing algorithm. See com.fuclrologic.statecharts.algorithms.
   EventQueue - An external event queue for cross (and loopback) communication of machines and external services.
   DataModel - A model for saving/fetching data that can be directly manipulated by a state machine.
   ExecutionModel - A component that implements the interpretation of runnable code in the machine.

   Many methods in the namespace take an `env`. This map will contain at least:

   * `::sc/vwmem` A volatile (e.g. vswap!) holding working memory. The data model MAY place its data
      in this map under namespaced keys, but it should not modify any of the preexisting things in working
      memory.
   ** The working memory will contain `::sc/session-id`
   * `::sc/context-element-id` will be the ID of the state (or :ROOT) if the method is called by the Processor while
     working on behalf of a state.

  ::sc/machine  - The machine definition
  ::sc/data-model - The current implementation of the DataModel
  ::sc/event-queue - The current implementation of the external EventQueue
  ::sc/execution-model - The current implementation of the ExecutionModel

  Implementations of a Processor MAY place (or allow you to place) other namespaced keys in `env` as well.
  ")

(defprotocol TransactionSupport
  "A protocol to add transaction support to a data model."
  (begin! [provider env] "Begin a 'transaction' where calls to `update!` on a data model are batched together in
    a transaction.")
  (commit! [provider env]
    "Commit all calls to `update!` that have happened since `begin!`.")
  (rollback! [provider env]
    "Skip the calls to `update!` that have happened since `begin!`."))

(defprotocol DataModel
  "A data model for a state machine.

   The implementation of a DataModel MAY also implement TransactionSupport to add ACID guarantees
   (e.g. if the data model is backed by durable storage) around groups of calls to `update!`."
  (load-data [provider env src]
    "OPTIONAL. Attempt to load data from the given `src` (as declared on a datamodel element in the machine.) into
     the data model.

     See ns docstring for description of `env`.

     Returns nothing.")
  (current-data [provider env]
    "Returns the current data (must be map-like) for the context in the given env.")
  (get-at [provider env path] "Pull a data value from the model
   at a given `path` (a vector of keywords) (in the context of `env`).
   The data model can define what this path means, as well as the context.

   See ns docstring for description of `env`.

   Returns the value or nil.")
  (update! [provider env {:keys [ops]}]
    "Run a sequence of operations that updates/removes data from the data model in the context of `env`.

     `opts` is a vector of data model changes.

     A DataModel implementation MUST support at least the following operations (where `path` can be a keyword or
     a vector of keywords, and value is any safely-serializable value):

     ```
     [{:op :assign :data {path1 value1 path2 value2}} ; overwrite (merge) the given path(s) with values
      {:op :delete :paths [path ...]}]                ; Remove the given items by key-path.
     ```

     See `data-model.operations` for helper functions/wrappers.

     Paths have a DataModel-specific interpretation.

     NOTE: ExecutionModel is used in contexts where the data model may need to be updated. In those contexts
     one may call the methods on the data model directly, or the execution model may allow a return value of the
     expression to act as the data model change.

     See ns docstring for description of `env`."))

(defprotocol EventQueue
  (send! [event-queue {:keys [event
                              data
                              send-id
                              source-session-id
                              target
                              type
                              delay] :as send-request}]
    "Put a send-request on the queue.

     The send request has:

     * :event - (REQUIRED) The name of the message to be sent
       may describe the supported data types an event may contain.
     * :send-id - The id of the send element or an id customized by an expression on that element.
                  Need not be unique. Cancelling via this send-id cancels all undelivered events with that
                  send-id/source-session-id.
     * :source-session-id - The globally unique session ID.
     * :data (OPTIONAL) The data to include (encode into) in the event. The state chart processor will extract this
                        data from the Data Model according to the `send` content elements or `namelist` parameter.
                        The event queue MAY use the `type` in order to decide how to encode this data for transport
                        to the `target`. The default is to simply leave the event data as the raw EDN assembled by
                        the state chart's send element. See your event queue's documentation for specifics.
     * :target - The target for the event.
                 Implementations of event queues may choose to use a URI format for target, or other shortcuts based
                 on `type`.
                 OPTIONAL: If not supplied, then the target is the `source-session-id` (the sender).
     * :type - The transport mechanism of the event. This may be a URI or other
               implementation-dependent value. OPTIONAL: The default is to deliver to other state chart sessions.
     * :delay - The number of ms to wait before delivering the event. Implementations MAY accept other formats,
                such as strings or tuples with units like `[4 :minutes]`.

     This function MAY fire the event off to an external service, in which case it can provide a mechanism for
     processing and delivering results/responses back to this queue for consumption by the sender.

     In this case the send isn't actually going *into* this event queue, but is instead being processed as described in
     SCXML standard's Event I/O Processor.")
  (cancel! [event-queue session-id send-id]
    "Cancel the (delayed) send(s) with the given `send-id` that were `sent!` by `session-id`.
     This is only possible for events that have a delay and have not yet been delivered.")
  (receive-events! [event-queue {:keys [type target session-id] :as options} handler]
    "Pull the next event(s) from the queue that matches the given `type`/`target` (if you do not specify `type`, then
     a state chart is assumed) and process it with `handler`, a `(fn [message])` that MUST process the
     event in a way that ensures the event is
     delivered, processed, and safe to remove from the event queue. The format of `message` will depend on the
     `type` used in the `send`. For statechart session events, these should be in the format created by `events/new-event`.

     `process-next-event!` function MAY block waiting for the next event, and the `options` map MAY allow you to
     pass additional parameters to affect this behavior. Your selected event queue implementation's
     documentation should be consulted for the correct way to run your event loop.

     If `handler` throws (or never returns, for example due to a server reboot) then the
     implementation SHOULD redeliver the event at a later time. This allows for things like system interruptions that
     cause unexpected exceptions that should be retried in the future.

     A proper implementation of an event queue should have IN ORDER and EXACTLY ONCE semantics for event delivery.
     In order to facilitate this your `handler` function SHOULD finish as quickly as possible (milliseconds) but MUST NOT
     exceed 5 seconds of run time. If this time limit is exceeded then an implementation MAY revert to
     AT LEAST ONCE delivery guarantees, though it MUST preserve event order.

     As such, statechart machines that use a durable event queue should be defensively written to safely
     tolerate AT LEAST ONCE message delivery. For example, instead of using a `toggle` event to switch
     between two states give each a unique name (like `turn-on` and `turn-off`)."))

(defprotocol ExecutionModel
  (run-expression! [model env expr]
    "Run an expression. `env` will include the `data-model`.
    `expr` is defined by the execution model selected. For example, it could be
    a `fn?`, an EQL transaction specification, etc.

    This method MUST return the value of the expression, and it MUST also support expressions that need to update
    the data model. If the execution model is imperative, this can be support for imperative statements. If the
    expression model is functional, then typically the return value of the expression can be used.

    See ns docstring for description of `env`."))

(defprotocol Processor
  (get-base-env [this] "Returns the base env that the processor is using. Will include the data-model,
   execution-model, event-queue, and `processor` itself.")
  (start! [this session-id]
    "Initialize the state machine processor for a new session. `session-id` should be a globally unique identifier
     for this particular instance of the machine.

     Transitions to the initial state.  If you have the return value of this function or of `process-event!`, then
     you can resume the session simply by calling `process-event!` with that value. This function will always
     start a NEW machine.

     Returns the resulting working memory (current state) of the machine.")
  (process-event! [this working-memory external-event]
    "Process an event. `working-memory` should be the value last returned from this function (or start!).

     Returns the next value of working memory."))

(defprotocol InvocationProcessor
  "A protocol for a service that can be started/stopped on entry/exit of a state, and may exchange events during that
   state's lifespan. Each invocation is associated with a particular source state chart session and invocation ID
   (invokeid). Parallel regions of a chart can run multiple instances of the same type of invocation."
  (supports-invocation-type? [this typ]
    "Returns `true` if this processor can handle invocations of the given type. The state chart processor will scan
     for the first registered InvocationProcessor that can handle a desired type using this method.")
  (start-invocation! [this env {:keys [invokeid
                                       type
                                       params]}]
    "Start an invocation of the given type, with `params`. The resulting invocation instance may send events back to the caller
     using the event-queue and session information available in `env`, but MUST ensure that `invokeid` is included on such
     events as `invokeid` so that the session can properly process them through `finalize`.

     The newly started invocation instance MUST remember `invokeid`, and respond to calls of `stop-invocation!`
     and `forward-event!`.")
  (stop-invocation! [this env {:keys [type invokeid]}] "Stop the invocation of `type` that was started with `invokeid` from the given `env`.")
  (forward-event! [this env {:keys [type invokeid event]}]
    "Forward the given event from the source `env` to the invocation of the given `type` that is identified by `invokeid`. The
     source session information can be found in the `env`."))
