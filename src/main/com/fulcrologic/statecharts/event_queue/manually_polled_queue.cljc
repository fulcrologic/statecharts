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
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.util :refer [now-ms]]
    [taoensso.timbre :as log]
    [clojure.string :as str]))

(defn supported-type?
  "Returns true if the given type looks like a statechart type. This is tolerant of the values:
   :statechart
   ::sc/chart
   and the recommended URL http://www.w3.org/tr/scxml."
  [type]
  (or
    (nil? type)
    (and (string? type) (str/starts-with? (str/lower-case type) "http://www.w3.org/tr/scxml"))
    (= type ::sc/chart)
    (= type :statechart)))

(defrecord ManuallyPolledQueue [session-queues]
  sp/EventQueue
  (send! [_ _env {:keys [event data type target source-session-id send-id invoke-id delay]}]
    (if (supported-type? type)
      (let [target (or target source-session-id)
            now    (now-ms)
            tm     (if delay (+ now delay) (dec now))
            event  (with-meta
                     (evts/new-event (cond-> {:name   event
                                              :type   (or type ::sc/chart)
                                              :target target
                                              :data   (or data {})}
                                       source-session-id (assoc ::sc/source-session-id source-session-id)
                                       send-id (assoc :sendid send-id ::sc/send-id send-id)
                                       invoke-id (assoc :invokeid invoke-id)))
                     {::delivery-time tm})]
        (swap! session-queues update target (fnil conj []) event)
        true)
      false))
  (cancel! [_ _env session-id send-id]
    (swap! session-queues update session-id
      (fn [q]
        (vec
          (remove
            (fn [{event-send-id ::sc/send-id}] (= send-id event-send-id))
            q)))))
  (receive-events! [this env handler]
    ;; Run the events whose delivery time is before "now" for session-id
    (doseq [sid (keys @session-queues)]
      (sp/receive-events! this env handler {:session-id sid})))
  (receive-events! [this env handler {:keys [session-id]}]
    ;; Run the events whose delivery time is before "now" for session-id
    (if (nil? session-id)
      (doseq [sid (keys @session-queues)]
        (sp/receive-events! this env handler {:session-id sid}))
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
            (handler env event)
            (catch #?(:clj Throwable :cljs :default) e
              (log/error e "Event handler threw an execption"))))))))

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
