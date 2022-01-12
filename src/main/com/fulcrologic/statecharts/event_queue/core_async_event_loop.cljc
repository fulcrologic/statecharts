(ns com.fulcrologic.statecharts.event-queue.core-async-event-loop
  "A queue that uses core.async to enable support for delayed events and also provides a `run-event-loop!` mechanism
   for automatically processing events as they arrive (optional). You may, of course, send the queue the
   `evts/cancel-event` to exit your machine (without reaching the final state) to cause the `run-event-loop!` to
   exit.

   This queue can support any number of running statecharts via their session-ids. `send!` will reject any request that
   is missing a target that defines the target session-id. Just use the same instance as the event queue for every
   chart.

   "
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.util :refer [now-ms]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [taoensso.timbre :as log]))

(defn run-event-loop!
  "Initializes a new session using `sp/start!` on the processor and assigns it `session-id`.
   Then runs a continuous loop polling the event-queue for new events and processing them.

   `wmem-atom` is an atom that will be updated with the latest working memory of the state
   machine, and allows you to look at the state of it from outside.  It is safe to read the active states
   from ::sc/configuration of the working memory, but you should leverage the data-model protocol for
   interfacing with the data a machine might need to see or manipulate.

   Runs a core.async loop that will run `receive-events!` at the given time resolution. Can be used
   with a manually-polled-queue to create an autonomous runtime.

   Returns a channel that will stay open until the session ends."
  [processor wmem-atom session-id resolution-ms]
  (let [s0 (sp/start! processor session-id)
        {::sc/keys [event-queue] :as base-env} (sp/get-base-env processor)]
    (reset! wmem-atom s0)
    (log/trace "Event loop started" event-queue)
    (async/go-loop []
      (async/<! (async/timeout resolution-ms))
      (sp/receive-events! event-queue {:session-id session-id}
        (fn [_ event]
          (reset! wmem-atom (sp/process-event! processor @wmem-atom event))))
      (if (::sc/running? @wmem-atom)
        (recur)
        (log/trace "Event loop ended")))))
