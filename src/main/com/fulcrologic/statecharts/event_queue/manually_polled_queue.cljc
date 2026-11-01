(ns com.fulcrologic.statecharts.event-queue.manually-polled-queue
  "An event queue that does NOT process event delays via any kind of notification system. Delayed events will
  be queued, and will be kept invisible until you ask for an event AFTER the timout of the event has occurred.
  This means you must manually poll this queue.

  This queue DOES support any number of sessions, and maintains separate tracking for each.

  This queue DOES NOT support communication with any other kind of system. Only other statecharts with session ids.

  There is a helper `(next-event-time session-id q)` that will tell you when the next delayed event will be visible
  (as an inst) for a given session id.

  This allows you to implement your delivery mechanism in concert with your overall system.

  The `receive-events!` of this implementation processes all available events in a loop and then returns. It takes
  each event off the queue just before handing it to the handler, so an event cancelled while an earlier event is
  handled (e.g. a `send-after` that the earlier event's transition leaves) is not delivered. Its options are:

  * `:session-id` - Only deliver the events of this session (default: every session).
  * `::session-held?` - A `(fn [session-id] boolean)`, checked before each event. While it is true for a session, that
    session's due events stay queued, in order, for a later `receive-events!` (where they can still be cancelled).

  A handler that returns `::requeue` hasn't consumed its event: the event goes back to its place in the queue, and the
  rest of its session's events wait for a later `receive-events!` too, so they stay in order.

  Each session's queued events are at `(get @(:session-queues q) session-id)`, in send order (a seq of it lists them
  so); a session with no queued events has no entry. Taking an event off is O(1) at the head and O(log n) elsewhere."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.util :refer [now-ms]]
    [taoensso.timbre :as log]))

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

(defn- due?
  "True when the queued `event` may be delivered at `now` (ms since the epoch): its delivery time has come."
  [now event]
  (<= (::delivery-time (meta event)) now))

(def ^:private by-send-order
  "Orders queued events by their send order number (`send!` numbers the events it queues, per queue, in their
   metadata)."
  #?(:clj  (reify java.util.Comparator
             (compare [_ a b]
               (Long/compare
                 (long (.valAt ^clojure.lang.ILookup (.meta ^clojure.lang.IObj a) ::send-order))
                 (long (.valAt ^clojure.lang.ILookup (.meta ^clojure.lang.IObj b) ::send-order)))))
     :cljs (fn [a b] (compare (::send-order (meta a)) (::send-order (meta b))))))

(def ^:private empty-fifo #?(:clj clojure.lang.PersistentQueue/EMPTY :cljs cljs.core/PersistentQueue.EMPTY))

(def ^:private empty-sorted (sorted-set-by by-send-order))

;; A session's queue holds its events in send order (a seq of it lists them so): a FIFO (`PersistentQueue`), where
;; taking off the head is O(1), until an event has to come off (or go back) elsewhere, e.g. a due event behind a
;; delayed one that isn't due yet. Then it becomes a set sorted by send order (O(log n) anywhere) until it is empty.

(defn- sorted-queue
  "The session queue `q` as a set sorted by send order."
  [q]
  (if (sorted? q) q (into empty-sorted q)))

(defn- without-event
  "Returns the session queue `q` without `event` (`q` itself when it doesn't hold it, e.g. it was cancelled)."
  [q event]
  (cond
    (sorted? q) (disj q event)
    (identical? event (peek q)) (pop q)
    (some #(identical? event %) q) (disj (sorted-queue q) event)
    :else q))

(defn- with-requeued-event
  "Returns the session queue `q` with `event` (one taken off it earlier) back at its place in send order."
  [q event]
  (conj (sorted-queue q) event))

(defn- without-matching
  "Returns the session queue `q` without the events for which `(pred event)` is true (`q` itself when there are none)."
  [q pred]
  (if (some pred q)
    (if (sorted? q)
      (reduce (fn [q event] (if (pred event) (disj q event) q)) q q)
      (into empty-fifo (remove pred) q))
    q))

(defn- update-session-queue
  "Returns the queues `qs` with the events of `session-id` replaced by `(f events x)`. A session whose queue becomes
   empty is dropped; `qs` itself is returned when nothing changed."
  [qs session-id f x]
  (let [q  (get qs session-id empty-fifo)
        q' (f q x)]
    (cond
      (identical? q q') qs
      (empty? q') (dissoc qs session-id)
      :else (assoc qs session-id q'))))

(defn- next-due
  "The seq of `events` from the first one that is due at `now`, or nil when none is."
  [now events]
  (loop [s (seq events)]
    (when s
      (if (due? now (first s))
        s
        (recur (next s))))))

(defn- receive-session-events!
  "Delivers the events of `session-id` in `session-queues` that are due now to `handler`, in order, taking each one off
   the queue just before its delivery: an event cancelled (or otherwise removed) while an earlier one is handled isn't
   delivered. Events queued meanwhile wait for a later call. While `(session-held? session-id)` is true (checked first,
   and then before each event), the session's remaining events stay queued, in order. A handler that returns
   `::requeue` puts its event back at its place, and the session's remaining events wait for a later call too."
  [session-queues env handler session-id session-held?]
  (let [cutoff (now-ms)]
    (loop [pending (get @session-queues session-id)]
      (when-not (and session-held? (session-held? session-id))
        (when-let [s (next-due cutoff pending)]
          (let [event     (first s)
                [old new] (swap-vals! session-queues update-session-queue session-id without-event event)
                result    (when-not (identical? old new)
                            (try
                              (handler env event)
                              ;; W3C SCXML: event handler errors must not crash the event loop.
                              (catch #?(:clj Throwable :cljs :default) e
                                (log/error e "Event handler threw an exception"))))]
            (if (= ::requeue result)
              (swap! session-queues update-session-queue session-id with-requeued-event event)
              (recur (next s)))))))))

(defrecord ManuallyPolledQueue [session-queues]
  sp/EventQueue
  (send! [_ _env {:keys [event data type target source-session-id send-id invoke-id delay
                         origin origintype]}]
    (if (and (supported-type? type) (or target source-session-id))
      (let [target (or target source-session-id)
            now    (now-ms)
            tm     (if delay (+ now delay) (dec now))
            event  (evts/new-event (cond-> {:name   event
                                            :type   (or type ::sc/chart)
                                            :target target
                                            :data   (or data {})}
                                     source-session-id (assoc ::sc/source-session-id source-session-id)
                                     ;; W3C §5.10.1: :origin is a routable target identifying the sender;
                                     ;; for chart-to-chart sends we use the source session id.
                                     (or origin source-session-id) (assoc :origin (or origin source-session-id))
                                     ;; W3C §5.10.1: :origintype is the IO Processor type that delivered
                                     ;; the event. For internal SCXML chart sends we surface ::sc/chart.
                                     true (assoc :origintype (or origintype type ::sc/chart))
                                     send-id (assoc :sendid send-id ::sc/send-id send-id)
                                     invoke-id (assoc :invokeid invoke-id)))]
        ;; The send order number is taken in the same swap that queues the event, so it is the queueing order.
        (swap! session-queues
          (fn [qs]
            (let [n (inc (::last-send-order (meta qs) 0))]
              (-> qs
                (update-session-queue target conj (with-meta event {::delivery-time tm ::send-order n}))
                (vary-meta assoc ::last-send-order n)))))
        true)
      false))
  (cancel! [_ _env session-id send-id]
    (swap! session-queues update-session-queue session-id without-matching
      (fn [event] (= send-id (::sc/send-id event)))))
  (receive-events! [this env handler]
    ;; Run the events whose delivery time is before "now" for all sessions registered
    (sp/receive-events! this env handler {}))
  (receive-events! [_ env handler {:keys [session-id] ::keys [session-held?]}]
    ;; Run the events whose delivery time is before "now" for session-id (every session when nil), one at a time
    (if (nil? session-id)
      (doseq [sid (keys @session-queues)]
        (receive-session-events! session-queues env handler sid session-held?))
      (receive-session-events! session-queues env handler session-id session-held?))))

(defn new-queue []
  (->ManuallyPolledQueue (atom {})))

(defn has-due-events?
  "Returns true when `event-queue` (a `ManuallyPolledQueue`) holds an event that is due now, i.e. one that
   `receive-events!` would deliver now: for `session-id` when given, else for any session. A delayed event whose
   delay hasn't passed yet doesn't count, nor does an event of a session for which `session-held?` (optional, see
   `receive-events!`'s `::session-held?`) is true."
  ([event-queue] (has-due-events? event-queue nil nil))
  ([event-queue session-id] (has-due-events? event-queue session-id nil))
  ([event-queue session-id session-held?]
   (let [queues @(:session-queues event-queue)
         now    (now-ms)
         due-in (fn [sid] (and (not (and session-held? (session-held? sid))) (some (partial due? now) (get queues sid))))]
     (boolean
       (if (some? session-id)
         (due-in session-id)
         (some due-in (keys queues)))))))

(defn cancel-self-sent-events!
  "Removes from `event-queue` (a `ManuallyPolledQueue`) the queued events of `session-id` that the session sent itself
   (`::sc/source-session-id` is `session-id`), e.g. its `send-after` timeouts, due or not. Other senders' events stay.
   For a restart of the session id: the old session's own events mean nothing to the new one."
  [event-queue session-id]
  (swap! (:session-queues event-queue) update-session-queue session-id without-matching
    (fn [event] (= session-id (::sc/source-session-id event))))
  nil)

(defn next-event-time
  "Returns the time in ms since the epoch of the time of the next delayed event. Returns nil if there are currently
   none."
  [event-queue session-id]
  (-> event-queue
    (get [:session-queues session-id])
    (first)
    meta
    ::delivery-time))
