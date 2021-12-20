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
    [clojure.set :as set]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.util :refer [queue now-ms]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [taoensso.timbre :as log]))

(defrecord ManuallyPolledQueue [session-queues]
  sp/EventQueue
  (send! [_ {:keys [event data target source-session-id send-id delay]}]
    (let [target (or target source-session-id)
          now    (now-ms)
          tm     (if delay (+ now delay) (dec now))
          event  (with-meta
                   (evts/new-event {:name                  event
                                    :data                  data
                                    :type                  :external
                                    ::sc/send-id           send-id
                                    ::sc/source-session-id source-session-id})
                   {::delivery-time tm})]
      (swap! session-queues update target (fnil conj []) event)))
  (cancel! [_ session-id send-id]
    (swap! session-queues update session-id
      (fn [q]
        (vec
          (remove
            (fn [{event-send-id ::sc/send-id}] (= send-id event-send-id))
            q)))))
  (receive-events! [_ {:keys [session-id]} handler]
    ;; Run the events whose delivery time is before "now" for session-id
    (let [cutoff  (now-ms)
          [old _] (swap-vals! session-queues
                    (fn [qs]
                      (let [to-defer (into []
                                       (remove
                                         (fn [evt] (<= (::delivery-time (meta evt)) cutoff))
                                         (get qs session-id)))]
                        (assoc qs session-id to-defer))))
          to-send (filter
                    (fn [evt] (<= (::delivery-time (meta evt)) cutoff))
                    (get old session-id))]
      (doseq [event to-send]
        (try
          (handler event)
          (catch #?(:clj Throwable :cljs :default) e
            (log/error e "Event handler threw an execption")))))))

(defn new-queue []
  (->ManuallyPolledQueue (atom {})))

(defn next-event-time
  "Returns the time in ms since the epoch of the time of the next delayed event. Returns nil if there are currently
   none."
  [event-queue session-id]
  (-> event-queue
    (get [:session-queues session-id])
    (first)
    meta
    ::delivery-time))
