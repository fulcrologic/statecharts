(ns com.fulcrologic.statecharts.event-queue.event-processing
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [taoensso.timbre :as log]))

(defn standard-statechart-event-handler
  "A sample implementation of a handler that can be used with receive-events! in order to process
   an event bound for a statechart."
  [{::sc/keys [working-memory-store processor] :as env} {:keys [target] :as event}]
  (if-not target
    (log/warn "Event did not have a session target. This queue only supports events to charts." event)
    (let [session-id target
          wmem       (sp/get-working-memory working-memory-store env session-id)
          next-mem   (when wmem (sp/process-event! processor env wmem event))]
      (log/debug "Ran" event "on" target)
      (if next-mem
        (sp/save-working-memory! working-memory-store env session-id next-mem)
        (log/error "Session had no working memory. Event to session ignored" session-id)))))

(defn process-events
  "Processes events that are ready on the event queue. Synchronous. Returns as soon as the events
   are complete."
  [{::sc/keys [processor working-memory-store event-queue] :as env}]
  (sp/receive-events! event-queue env
    (fn [env {:keys [target] :as event}]
      (log/debug "Received event" event)
      (if-not target
        (log/warn "Event did not have a session target. This queue only supports events to charts." event)
        (let [session-id target
              wmem       (sp/get-working-memory working-memory-store env session-id)
              next-mem   (when wmem (sp/process-event! processor env wmem event))]
          (if next-mem
            (sp/save-working-memory! working-memory-store env session-id next-mem)
            (log/error "Session had no working memory. Event to session ignored" session-id)))))
    {})
  nil)
