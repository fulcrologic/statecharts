(ns com.fulcrologic.statecharts.protocols
  "Protocols for the various pluggable bits of this library:

   Processor - A state machine processing algorithm. See com.fulcrologic.statecharts.algorithms.
   EventQueue - An external event queue for cross (and loopback) communication of machines and external services.
   DataModel - A model for saving/fetching data that can be directly manipulated by a state machine.
   ExecutionModel - A component that implements the interpretation of runnable code in the machine.
   InvocationProcessor - A component that knows how to invoke a specific kind of thing.
   StatechartRegistry - A component that can register/retrieve statechart definitions by their name.
   WorkingMemoryStore - A component that can save/restore the working memory of a session.

   Many methods in the namespace take an `env`. This map will contain keys for the above.

   The data model requires a *processing* env, which is the same as above, but also includes a volatile
   holding the working memory, context information, and the full statechart definition (e.g. from the
   registry).

   See `com.fulcrologic.statecharts.specs`.

   The `env` is allowed to contain any number of user-defined (namespaced) keys.
  ")

(defprotocol DataModel
  "A data model for a state machine. Note that this protocol requires the *processing* env, which will
   include the context of the state machine on whose behalf the data is being manipulated.

   The implementation of a DataModel MAY also implement TransactionSupport to add ACID guarantees
   (e.g. if the data model is backed by durable storage) around groups of calls to `update!`."
  (load-data [provider processing-env src]
    "OPTIONAL. Attempt to load data from the given `src` (as declared on a datamodel element in the machine.) into
     the data model.

     See ns docstring for description of `env`.

     Returns nothing.")
  (current-data [provider processing-env]
    "Returns the current data (must be map-like) for the context in the given env.")
  (get-at [provider processing-env path] "Pull a data value from the model
   at a given `path` (a vector of keywords) (in the context of `env`).
   The data model can define what this path means, as well as the context.

   See ns docstring for description of `env`.

   Returns the value or nil.")
  (update! [provider processing-env {:keys [ops]}]
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
  (send! [event-queue env {:keys [event
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
     * :invoke-id - The id of the invocation, if this event is coming from a child statechart.
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
     SCXML standard's Event I/O Processor.

     Returns true if the event can be sent, and false if the type of event isn't supported.")
  (cancel! [event-queue env session-id send-id]
    "Cancel the (delayed) send(s) with the given `send-id` that were `sent!` by `session-id`.
     This is only possible for events that have a delay and have not yet been delivered.")
  (receive-events! [event-queue env handler] [event-queue env handler options]
    "Pull the next event(s) from the queue that can be delivered and run them through `handler`,
     which is a `(fn [env event])`.

     The `options` of the handler are defined by the queue implementation.

     For example, a queue capable of a transactional nature (say, implemented with an SQL database)
     might pass the database connection to the handler so it can participate in the transaction that was used to
     pull the event from the queue as part of the algorithm to ensure exactly-once event delivery.

     The format of `message` will depend on the `type` used in the `send`. For statechart session events, these should
     be in the format created by `events/new-event`, but MAY include
     any additional (preferably namespaced) keys to pass along.

     This function MAY block waiting for the next event. Your selected event queue implementation's
     documentation should be consulted for the correct way to run your event loop.

     If `handler` throws (or never returns, for example due to a server reboot) then the
     implementation SHOULD redeliver the event at a later time. This allows for things like system interruptions that
     cause unexpected exceptions that should be retried in the future.

     A proper implementation of an event queue should have IN ORDER and EXACTLY ONCE semantics for event delivery.
     In order to facilitate this your `handler` function SHOULD finish as quickly as possible (milliseconds) but MUST NOT
     exceed 5 seconds of run time. If this time limit is exceeded then an implementation MAY revert to
     AT LEAST ONCE delivery guarantees, though it MUST preserve event order.

     As such, statechart machines that use a durable event queue in a distributed environment should be defensively written
     to safely tolerate AT LEAST ONCE message delivery. For example, instead of using a `toggle` event to switch
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
  (start! [this env statechart-src options]
    "Start a new session.  The `statechart-src` is the name of the desired statechart definition you
     wish to use out of the statechart registry.

     This will start the chart, and transition to the initial state. Returns working memory, which is needed
     in order to process further events with `process-event!`.

     options can contain:

     ::sc/session-id - A unique ID for the new session. If not present then a random one will be assigned.
     :com.fulcrologic.statecharts/invocation-data - Data passed to this new session as invocation data
     :com.fulcrologic.statecharts/parent-session-id - The calling session's ID
     :org.w3.scxml.event/invokeid - The ID assigned to this if it is an invocation

     Returns the resulting working memory (current state) of the machine.")
  (exit! [this env wmem skip-done-event?]
    "Exit the statechart, but make sure to run exit handlers. Will also send a parent done event (if there is a parent)
     unless the skip-done-event? is true.")
  (process-event! [this env working-memory event]
    "Process an event.

    `env` is an ::sc/env
    `working-memory` should be the value last returned from this function (or start!).
    `event` The event to process

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
     and `forward-event!`.

     Returns `true` if the invocation was successfully started, or `false` if the invocation failed to start
     (e.g., chart not found, invalid function). Implementations SHOULD send an :error.platform event to the
     parent session when returning `false`.")
  (stop-invocation! [this env {:keys [type invokeid]}] "Stop the invocation of `type` that was started with `invokeid` from the given `env`.")
  (forward-event! [this env {:keys [type invokeid event]}]
    "Forward the given event from the source `env` to the invocation of the given `type` that is identified by `invokeid`. The
     source session information can be found in the `env`."))

(defprotocol StatechartRegistry
  (register-statechart! [this src statechart-definition]
    "Add a statechart definition to the known definitions. The `src` should be a unique well-known
     key, and is what you would look the definition up by. If your invocation support uses this
     system, then the `invoke` element's `src` will match the `src` passed here.")
  (get-statechart [this src]
    "Retrieve the definition of a statechart that is known by the well-known key `src`.")
  (all-charts [this]
    "Return a map of all registered statechart definitions, where keys are the `src` identifiers
     and values are the statechart definitions."))

(defprotocol WorkingMemoryStore
  "Persistence layer for statechart session state. Working memory is saved after each
   event processing step and retrieved at the start of the next. Implementations range
   from in-memory atoms (for development) to durable stores (for production).

   Lifecycle: `save-working-memory!` is called after `start!` and each `process-event!`.
   `get-working-memory` is called before each `process-event!`. `delete-working-memory!`
   is called when a session reaches a final state."
  (get-working-memory [this env session-id] "Get the working memory for a state machine with session-id")
  (save-working-memory! [this env session-id wmem]
    "Save working memory for session-id")
  (delete-working-memory! [this env session-id]
    "Remove the working memory for (presumably terminated) session-id"))
