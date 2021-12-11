(ns com.fulcrologic.statecharts.protocols)

(defprotocol DataModel
  (get-data [data-model machine context]
    "Returns the data model as a map that makes sense for the given context.")
  (get-item [data-model machine sessionid path]
    "Get the value in the data model at the abstract, root-based `path`. Returns nil if not found.")
  (put-item [data-model machine sessionid path value]
    "Store `value` in the data model at the abstract, root-based `path` (a vector of keywords)")
  (save-data! [data-model machine context data]
    "Replaces the data model for the given context with `data` (a map)."))

(defprotocol EventQueue
  (send! [event-queue session-id send-request]
    "Put a send-request on the queue. The send request can have a delay, and must have
     an id. The id need not be unique.

     A conforming implementation MUST NOT process events synchronously on the thread that sends them.")
  (cancel! [event-queue session-id send-id]
    "Cancel the send(s) with the given `id` that were `sent!` by `session-id`.
     This is only possible for events that have a delay and have not yet been delivered.")
  (process-next-event! [event-queue session-id handler]
    "Pull an event from the queue for `session-id` and process it with `handler`, a `(fn [session-id send-request])`
     that should side-effect in a way that ensures the event is delivered, processed, and safe to remove
     from the event queue.

     If `handler` throws then a durable implementation will leave the event on the queue for a retry.

     A proper implementation of an event queue should have IN ORDER and EXACTLY ONCE semantics for event delivery.
     In order to facilitate this `handler` SHOULD finish as quickly as possible (milliseconds) but MUST NOT
     exceed 5 seconds of run time. If this time limit is exceeded then an implementation may revert to
     AT LEAST ONCE delivery guarantees, though it MUST preserve event order.

     As such, statechart machines that use a durable event queue should be defensively written to safely
     tolerate AT LEAST ONCE message delivery. For example, instead of using a `toggle` event to switch
     between two states give each a unique name (like `turn-on` and `turn-off`)."))
