(ns com.fulcrologic.statecharts.event-queue.core-async-queue
  "WORK IN PROGRESS. NOT RECOMMENDED"
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]))


(deftype CoreAsyncQueue [Q delayed-events cancelled-events]
  sp/EventQueue
  (send! [_ {:keys [event delay]}]
    (async/go
      (if delay
        (let [nm (evts/event-name event)]
          (swap! delayed-events update nm (fnil inc 0))
          (async/<! (async/timeout delay))
          (when-not (contains? @cancelled-events nm)
            (async/>! Q event))
          (when (zero? (swap! delayed-events update nm dec))
            (swap! cancelled-events disj nm)))
        (async/>! Q event))))
  (cancel! [_ _ send-id]
    (let [nm send-id]
      (when (pos? (get @delayed-events nm))
        (swap! @cancelled-events conj nm))))
  (receive-events! [_ _ handler]
    (async/go
      (let [event (async/<! Q)]
        (handler event)
        :ok))))

(defn new-async-queue
  "Creates a new simple model that can act as a data model, event queue, AND execution model with all-local resources.
   This model MUST NOT be shared with more than one machine instance (may change in the future to enable
   local comms between machines).

   See `run-event-loop!` for a pre-implemented way to run the machine on this model.
   "
  []
  (->CoreAsyncQueue (async/chan 1000) (atom {}) (atom #{})))
