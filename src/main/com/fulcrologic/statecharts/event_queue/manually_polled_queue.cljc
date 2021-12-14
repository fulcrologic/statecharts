(ns com.fulcrologic.statecharts.event-queue.manually-polled-queue
  "An event queue that does NOT process event delays via any kind of notification system. Delayed events will
  be queued, and will be kept invisible until you ask for an event AFTER the timout of the event has occured.
  This means you must manually poll this queue.

  This queue DOES support any number of sessions, and maintains separate tracking for each.

  This queue DOES NOT support communication with any other kind of system. Only other statecharts with session ids.

  There is a helper `(next-event-time session-id q)` that will tell you when the next delayed event will be visible
  (as an inst) for a given session id.

  This allows you to implement your delivery mechanism in concert with your overall system.

  The `process-next-event!` of this implementation processes all available events in a loop and then returns."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.util :refer [queue now-ms]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [taoensso.timbre :as log]))

(defrecord ManuallyPolledQueue [Qs delayed-events]
  sp/EventQueue
  (send! [event-queue {:keys [event
                              send-id
                              source-session-id
                              target
                              delay] :as send-request}]
    (if-not (and
              source-session-id
              target
              (map? event))
      (log/error "Cannot enqueue an event. The source-session-id, target, and event are required.")
      (let [event (assoc event ::sc/source-session-id source-session-id)]
        (if (and delay (number? delay) (pos? delay))
          (let [trigger-time (+ (now-ms) delay)
                evts         (sort-by ::sc/trigger-time
                               (conj (get @delayed-events target [])
                                 {:event            event
                                  ::sc/send-id      send-id
                                  ::sc/trigger-time trigger-time}))]
            (swap! delayed-events assoc target evts))
          (swap! Qs update target (fnil conj (queue)) event)))))
  (cancel! [event-queue session-id send-id]
    (if (and session-id send-id)
      (swap! delayed-events update session-id (fn [evts]
                                                (filterv
                                                  #(not= send-id (::sc/send-id %))
                                                  evts)))
      (log/warn "Cannot cancel events with a nil session/send ID")))
  (receive-events! [event-queue {:keys [session-id] :as options} handler]
    (if-not session-id
      (log/warn "Cannot receive events without the session-id")
      (let [now  (now-ms)
            [old-delayed _] (swap-vals! delayed-events update session-id
                              (fn [evts] (remove #(< (::sc/trigger-time %) now) evts)))
            [oldQs _] (swap-vals! Qs assoc session-id (queue))
            evts (into (get oldQs session-id [])
                   (comp
                     (filter #(< (::sc/trigger-time %) now))
                     (map :event))
                   (get old-delayed session-id))]
        (doseq [evt evts]
          (try
            (handler evt)
            (catch #?(:clj  Throwable
                      :cljs :default) t
              (log/error t "Handler threw an exception"))))))))

(defn new-queue []
  (->ManuallyPolledQueue (atom {}) (atom {})))

(defn next-event-time
  "Returns the time in ms since the epoch of the time of the next delayed event. Returns nil if there are currently
   none."
  [event-queue session-id]
  (-> event-queue
    (get [:delayed-events session-id])
    (first)
    ::sc/trigger-time))
