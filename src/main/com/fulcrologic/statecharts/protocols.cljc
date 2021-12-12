(ns com.fulcrologic.statecharts.protocols
  "Many methods in the namespace take an `env`. This map will contain at least:

  :session-id - The unique session id of the instance of the machine
  :context-element-id  - The ID of the element in which the action/content appears
  :machine  - The machine definition
  :working-memory - The current working memory of the machine
  :pending-events - An atom containing a queue. Swap and conj against this to add events to the
                     internal event queue to be delivered.
  :data-model - The current implementation of the DataModel
  :event-queue - The current implementation of the external EventQueue
  :execution-model - The current implementation of the ExecutionModel
  ")

(defprotocol DataModel
  (load-data [provider src]
    "OPTIONAL. Attempt to load data from the given `src` (as declared on a datamodel element in the machine.)")
  (current-data [provider env]
    "Returns the current data (must be map-like) for the given state `machine` with `working-memory` for the
     given `context-element-id`. The data can be context-free (global for all elements), scoped, etc.

     See ns docstring for description of `env`.")
  (set-system-variable! [provider env k v]
    "Set the value of `k` to be `v` as a system variable. Passing `nil` for `v` will clear the variable.
     System variables are only used during executions and need not be durable.

     See ns docstring for description of `env`.")
  (get-at [provider env path] "Pull a data value from the model
   at a given `path`, which will be a vector of keywords. The data model can define what this path means.

   See ns docstring for description of `env`.")
  (put-at! [provider env path v]
    "Place a value `v` at the given path.

    See ns docstring for description of `env`.")
  (replace-data! [provider env new-data]
    "Replace the entire data model for `session-id` (optionally) relative to `context-element-id` with
     `new-data`.

     See ns docstring for description of `env`."))

(defprotocol EventQueue
  (send! [event-queue env send-request]
    "Put a send-request on the queue. The send request can have a delay, and must have
     an id. The id need not be unique.

     A conforming implementation MUST NOT process events synchronously on the calling thread.

     See ns docstring for description of `env`.")
  (cancel! [event-queue env send-id]
    "Cancel the send(s) with the given `id` that were `sent!` by `session-id`.
     This is only possible for events that have a delay and have not yet been delivered.

     See ns docstring for description of `env`.")
  (process-next-event! [event-queue env handler]
    "Pull the next event from the queue for `session-id` (in env) and process it with `handler`, a
     `(fn [env event])` that should side-effect in a way that ensures the event is
     delivered, processed, and safe to remove from the event queue.

     This function MAY block waiting for the next event. Your selected event queue implementation's
     documentation should be consulted for the correct way to run your event loop.

     If `handler` throws (or never returns, for example due to a server reboot) then a durable
     implementation will redeliver the event at a later time.

     A proper implementation of an event queue should have IN ORDER and EXACTLY ONCE semantics for event delivery.
     In order to facilitate this `handler` SHOULD finish as quickly as possible (milliseconds) but MUST NOT
     exceed 5 seconds of run time. If this time limit is exceeded then an implementation MAY revert to
     AT LEAST ONCE delivery guarantees, though it MUST preserve event order.

     As such, statechart machines that use a durable event queue should be defensively written to safely
     tolerate AT LEAST ONCE message delivery. For example, instead of using a `toggle` event to switch
     between two states give each a unique name (like `turn-on` and `turn-off`).

     See ns docstring for description of `env`."))

(defprotocol ExecutionModel
  (run-expression! [model env expr]
    "Run an expression. `env` will include the `data-model`.
    `expr` is defined by the execution model selected. For example, it could be
    a `fn?`, an EQL transaction specification, etc.

    This method MUST return the value of the expression, and it MUST also support expressions that need to update
    the data model. If the execution model is imperative, this can be support for imperative statements. If the
    expression model is functional, then typically the return value of the expression can be used.

    See ns docstring for description of `env`."))
