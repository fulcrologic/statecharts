(ns com.fulcrologic.statecharts.event-queue.async-event-processing
  "Event processing functions that handle async processor results. When `process-event!`
   returns a promise (from an async processor), working memory is saved after the promise
   resolves."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [promesa.core :as p]
    [taoensso.timbre :as log]))

(defn async-statechart-event-handler
  "An event handler for use with `receive-events!` that supports async processors.
   Returns a promise if the processor is async, or nil if sync. The handler ensures
   working memory is saved after processing completes (even for async results)."
  [{::sc/keys [working-memory-store processor] :as env} {:keys [target] :as event}]
  (if-not target
    (do
      (log/warn "Event did not have a session target. This queue only supports events to charts." event)
      nil)
    (let [session-id target
          wmem       (sp/get-working-memory working-memory-store env session-id)]
      (if-not wmem
        (do
          (log/error "Session had no working memory. Event to session ignored" session-id)
          nil)
        (let [result (sp/process-event! processor env wmem event)]
          (if (p/promise? result)
            (p/then result
              (fn [next-mem]
                (log/debug "Ran (async)" event "on" target)
                (when next-mem
                  (sp/save-working-memory! working-memory-store env session-id next-mem))))
            (do
              (log/debug "Ran" event "on" target)
              (when result
                (sp/save-working-memory! working-memory-store env session-id result)))))))))

(defn process-events
  "Processes events that are ready on the event queue. Handles both sync and async processors.
   Returns nil (synchronous processing with async support via event handler)."
  [{::sc/keys [event-queue] :as env}]
  (sp/receive-events! event-queue env
    (partial async-statechart-event-handler env)
    {})
  nil)
