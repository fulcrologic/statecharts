(ns com.fulcrologic.statecharts.event-queue.core-async-event-loop
  "A queue that uses core.async to enable support for delayed events and also provides a `run-event-loop!` mechanism
   for automatically processing events as they arrive (optional). You may, of course, send the queue the
   `evts/cancel-event` to exit your machine (without reaching the final state) to cause the `run-event-loop!` to
   exit.

   This queue can support any number of running statecharts via their session-ids. `send!` will reject any request that
   is missing a target that defines the target session-id. Just use the same instance as the event queue for every
   chart."
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.promise :as p]
    [taoensso.timbre :as log]))

(defn run-event-loop!
  "Initializes a new session using `sp/start!` on the processor and assigns it `session-id`.
   Then runs a continuous loop polling the event-queue for new events and processing them.

   WARNING: This event loop processes all ready events in a single `receive-events!` call
   without per-session serialization. When using an async processor that returns promises,
   events for the same session may process concurrently. For async processors, use
   `async-event-loop/run-serialized-event-loop!` instead.

   `wmem-atom` is an atom that will be updated with the latest working memory of the state
   machine, and allows you to look at the state of it from outside.  It is safe to read the active states
   from ::sc/configuration of the working memory, but you should leverage the data-model protocol for
   interfacing with the data a machine might need to see or manipulate.

   Runs a core.async loop that will run `receive-events!` at the given time resolution.

   Returns an atom containing a boolean that is what keeps the event loop running. If you swap that atom to `false` then
   the event loop will exit."
  [{::sc/keys [event-queue] :as env} resolution-ms]
  (log/debug "Event loop started")
  (let [running? (atom true)]
    (async/go-loop []
      (async/<! (async/timeout resolution-ms))
      (try
        (sp/receive-events! event-queue env
          ;; Uses the processor and store of the env it is given (a queue may pass one of its own, e.g. the Fulcro
          ;; integration's, which skips a superseded step's save).
          (fn [{::sc/keys [processor working-memory-store] :as env} {:keys [target] :as event}]
            (log/trace "Received event" event)
            (if-not target
              (log/warn "Event did not have a session target. This queue only supports events to charts." event)
              (let [session-id target
                    wmem       (sp/get-working-memory working-memory-store env session-id)
                    result     (when wmem (sp/process-event! processor env wmem event))
                    save-fn    (fn [next-mem]
                                 (if next-mem
                                   (sp/save-working-memory! working-memory-store env session-id next-mem)
                                   (log/debug "Session had no working memory. Event could not be sent to session" {:event event :id session-id}))
                                 ;; The handler returns a promise only when the processor did, never the save's value.
                                 nil)]
                (if (p/promise? result)
                  (p/then result save-fn)
                  (save-fn result)))))
          {})
        (catch #?(:clj Throwable :cljs :default) _ nil))
      (if @running?
        (recur)
        (log/debug "Event loop ended")))
    running?))
