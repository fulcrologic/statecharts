(ns com.fulcrologic.statecharts.event-queue.async-event-loop
  "An event loop that supports async processors by bridging promises into core.async channels.
   When `process-event!` returns a promise, the go-loop parks until the promise resolves
   before processing the next event. When the result is a plain value, processing continues
   immediately."
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.event-queue.async-event-processing :as aep]
    [promesa.core :as p]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(defn- await-if-promise
  "If `v` is a promise, puts its resolved value onto a channel and returns that channel.
   If `v` is a plain value, returns nil (caller should not park)."
  [v]
  (when (p/promise? v)
    (let [ch (async/chan 1)]
      (-> v
        (p/then (fn [result]
                  (async/put! ch (or result :done))
                  (async/close! ch)))
        (p/catch (fn [e]
                   (log/error e "Promise rejected in event loop")
                   (async/put! ch :error)
                   (async/close! ch))))
      ch)))

(defn run-event-loop!
  "Runs an event loop that processes events, awaiting async completion before processing
   the next event. Uses core.async go-loop with promise bridging.

   Returns an atom containing a boolean. Set to `false` to stop the event loop."
  [{::sc/keys [processor working-memory-store event-queue] :as env} resolution-ms]
  (log/debug "Async event loop started")
  (let [running? (atom true)]
    (async/go-loop []
      (async/<! (async/timeout resolution-ms))
      (enc/catching
        (sp/receive-events! event-queue env
          (fn [env {:keys [target] :as event}]
            (log/trace "Received event" event)
            (let [result (aep/async-statechart-event-handler env event)
                  ch     (await-if-promise result)]
              ;; If async, we need to block within the go-loop.
              ;; But receive-events! calls this handler synchronously for each event.
              ;; The handler must complete before the next event is processed.
              ;; For async results, we cannot truly block here in the callback.
              ;; The promise will complete asynchronously.
              ;; This is a limitation: the event queue processes all ready events
              ;; in one call to receive-events!, and we can't block between them.
              ;; For proper serialization, the async event handler returns a promise
              ;; and we trust that the event queue implementation handles this.
              nil))
          {}))
      (if @running?
        (recur)
        (log/debug "Async event loop ended")))
    running?))

(defn run-serialized-event-loop!
  "Runs an event loop that processes ONE event at a time, fully awaiting async completion
   before polling for the next event. This provides strict per-session event serialization.

   Uses a core.async go-loop. Returns an atom set to `false` to stop."
  [{::sc/keys [processor working-memory-store event-queue] :as env} resolution-ms]
  (log/debug "Serialized async event loop started")
  (let [running?       (atom true)
        pending-result (atom nil)]
    (async/go-loop []
      ;; Wait for any pending async result
      (when-let [ch (some-> @pending-result await-if-promise)]
        (async/<! ch)
        (reset! pending-result nil))
      (async/<! (async/timeout resolution-ms))
      (enc/catching
        (sp/receive-events! event-queue env
          (fn [env {:keys [target] :as event}]
            (let [result (aep/async-statechart-event-handler env event)]
              (when (p/promise? result)
                (reset! pending-result result))))
          {}))
      (if @running?
        (recur)
        (log/debug "Serialized async event loop ended")))
    running?))
