(ns com.fulcrologic.statecharts.routing-demo.model
  "Async data loading functions for the routing demo.

   Each loader returns a promesa promise that simulates network latency,
   then either resolves with statechart data model operations or raises
   an error event on the internal queue."
  (:require
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.environment :as senv]
    [com.fulcrologic.statecharts.routing-demo.data :as data]
    [promesa.core :as p]
    [taoensso.timbre :as log]))

(def ^:dynamic *load-delay-ms*
  "Simulated network delay in milliseconds. Rebindable for tests."
  200)

(defn- maybe-delay
  "Returns a promise that resolves after *load-delay-ms* milliseconds.
   When delay is 0, returns an already-resolved promise for synchronous execution
   in CLJS tests (p/delay 0 schedules for next microtask in JS)."
  []
  (if (zero? *load-delay-ms*)
    (p/resolved nil)
    (p/delay *load-delay-ms*)))

(defn load-event!
  "Async on-entry expression for the event state. Reads `:event-id` from
   the triggering event data, looks it up in the mock database, and either
   assigns it to the data model or raises `:error/not-found`.

   Returns a promise (suitable for the async execution engine)."
  [env data _event-name event-data]
  (let [event-id (some-> (or (:event-id event-data)
                             (get-in data [:route/params :event-id]))
                   #?(:clj Long/parseLong :cljs js/parseInt))]
    (log/info "Loading event" event-id)
    (p/let [_ (maybe-delay)]
      (if-let [event (data/lookup-event event-id)]
        (do
          (log/info "Event loaded:" (:event/name event))
          [(ops/assign [:ROOT :current-event] event)])
        (do
          (log/warn "Event not found:" event-id)
          (senv/raise env :error/not-found
            {:message (str "Event " event-id " not found")
             :type    :event})
          nil)))))

(defn load-day!
  "Async on-entry expression for the day state. Reads `:day-num` from
   the event data and the current event from the data model.

   Returns a promise. If the parent event wasn't loaded (nil `:current-event`),
   returns nil — the event-level error will handle the redirect."
  [env data _event-name event-data]
  (let [event-id (some-> (get-in data [:current-event :event/id])
                   int)
        day-num  (some-> (or (:day-num event-data)
                             (get-in data [:route/params :day-num]))
                   #?(:clj Long/parseLong :cljs js/parseInt))]
    (if-not event-id
      (do (log/debug "Skipping day load — parent event not loaded")
          nil)
      (do
        (log/info "Loading day" day-num "for event" event-id)
        (p/let [_ (maybe-delay)]
          (if-let [day (data/lookup-day event-id day-num)]
            (do
              (log/info "Day loaded:" (:day/name day))
              [(ops/assign [:ROOT :current-day] day)])
            (do
              (log/warn "Day not found:" event-id day-num)
              (senv/raise env :error/not-found
                {:message (str "Day " day-num " not found for event " event-id)
                 :type    :day})
              nil)))))))

(defn load-menu!
  "Async on-entry expression for the menu state. Reads current event/day
   from data model.

   Returns a promise. If the parent day wasn't loaded (nil `:current-day`),
   returns nil — the parent-level error will handle the redirect."
  [env data _event-name event-data]
  (let [event-id (some-> (get-in data [:current-event :event/id])
                   int)
        day-num  (some-> (get-in data [:current-day :day/num])
                   int)]
    (if-not day-num
      (do (log/debug "Skipping menu load — parent day not loaded")
          nil)
      (do
        (log/info "Loading menu for event" event-id "day" day-num)
        (p/let [_ (maybe-delay)]
          (if-let [menu (data/lookup-menu event-id day-num)]
            (do
              (log/info "Menu loaded:" (:menu/items menu))
              [(ops/assign [:ROOT :current-menu] menu)])
            (do
              (log/warn "Menu not found:" event-id day-num)
              [(ops/assign [:ROOT :current-menu] {:menu/items ["(No menu configured)"]})])))))))
