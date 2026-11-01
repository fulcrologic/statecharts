(ns com.fulcrologic.statecharts.event-queue.manually-polled-queue-spec
  (:require
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.util :refer [now-ms]]
    [fulcro-spec.core :refer [=> assertions behavior component specification when-mocking]]))

(specification "Manually Polled Queue"
  (let [queue         (mpq/new-queue)
        my-session    1
        other-session 2
        evt1          :A
        evt2          :B
        evt3          :C
        seen          (atom [])
        handler       (fn [_ evt] (swap! seen conj evt))]

    (sp/send! queue {} {:target            my-session
                        :source-session-id my-session
                        :event             evt1})
    (sp/send! queue {} {:target            other-session
                        :source-session-id my-session
                        :event             evt2})
    (sp/send! queue {} {:target            my-session
                        :source-session-id my-session
                        :event             evt3})

    (sp/receive-events! queue {} handler {:session-id other-session})

    (behavior "Sees only targeted events"
      (assertions
        (map :name @seen) => [:B])

      (reset! seen [])
      (sp/receive-events! queue {} handler {:session-id my-session})

      (assertions
        "in the order sent"
        (mapv :name @seen) => [:A :C]))

    (component "Delayed events"
      (reset! seen [])
      (when-mocking
        (now-ms) => 1

        (sp/send! queue {} {:target            my-session
                            :source-session-id my-session
                            :event             evt1})
        (sp/send! queue {} {:target            my-session
                            :source-session-id my-session
                            :delay             10
                            :event             evt2})
        (sp/send! queue {} {:target            my-session
                            :source-session-id my-session
                            :event             evt3})

        (behavior "are not visible before their time arrives"
          (sp/receive-events! queue {} handler {:session-id my-session})

          (assertions
            (mapv :name @seen) => [:A :C])))

      (reset! seen [])

      (when-mocking
        (now-ms) => 100

        (sp/receive-events! queue {} handler {:session-id my-session})

        (assertions
          "appear after their trigger time"
          (mapv :name @seen) => [:B])))


    (behavior "Unhandled delayed events can be cancelled"
      (reset! seen [])
      (when-mocking
        (now-ms) => 1

        (sp/send! queue {} {:target            my-session
                            :source-session-id my-session
                            :send-id           :ID
                            :delay             10
                            :event             evt1}))

      (sp/cancel! queue {} my-session :ID)

      (when-mocking
        (now-ms) => 15

        (sp/receive-events! queue {} handler {:session-id my-session})
        (assertions
          @seen => [])))))

(specification "has-due-events?"
  (let [queue (mpq/new-queue)
        clock (atom 1)]
    (when-mocking
      (now-ms) => @clock

      (assertions
        "is false for an empty queue"
        (mpq/has-due-events? queue) => false)
      (sp/send! queue {} {:target 1 :event :later :delay 10})
      (assertions
        "is false while the only event is a delayed one that isn't due"
        (mpq/has-due-events? queue) => false
        (mpq/has-due-events? queue 1) => false)
      (reset! clock 11)
      (assertions
        "is true from its delivery time on"
        (mpq/has-due-events? queue) => true
        (mpq/has-due-events? queue 1) => true
        "for that event's session only, when given a session"
        (mpq/has-due-events? queue 2) => false)
      (sp/receive-events! queue {} (fn [_ _]) {:session-id 1})
      (sp/send! queue {} {:target 2 :event :now})
      (assertions
        "is true at once for an event without a delay"
        (mpq/has-due-events? queue 2) => true
        "and false again once receive-events! delivered the due events"
        (mpq/has-due-events? queue 1) => false))))

(specification "receive-events! with an event cancelled while an earlier one is handled"
  (let [queue   (mpq/new-queue)
        seen    (atom [])
        handler (fn [_ {:keys [name]}]
                  (swap! seen conj name)
                  (when (= :first name) (sp/cancel! queue {} 1 :timer)))]
    (when-mocking
      (now-ms) => 100

      (do
        (sp/send! queue {} {:target 1 :event :first})
        (sp/send! queue {} {:target 1 :event :timeout :send-id :timer})
        (sp/send! queue {} {:target 1 :event :last})
        (sp/receive-events! queue {} handler {:session-id 1})))
    (assertions
      "doesn't deliver the cancelled event (both were due when the call started)"
      @seen => [:first :last])))

(specification "receive-events! with ::mpq/session-held?"
  (let [queue   (mpq/new-queue)
        held    (atom #{})
        seen    (atom [])
        opts    {::mpq/session-held? (fn [sid] (contains? @held sid))}
        handler (fn [_ {:keys [name target]}]
                  (swap! seen conj [target name])
                  ;; the handler of :hold-me holds its session (e.g. async work that started)
                  (when (= :hold-me name) (swap! held conj target)))]
    (sp/send! queue {} {:target 1 :event :a})
    (sp/send! queue {} {:target 2 :event :hold-me})
    (sp/send! queue {} {:target 2 :event :b})
    (sp/send! queue {} {:target 2 :event :c :send-id :c-id})
    (sp/send! queue {} {:target 2 :event :d})
    (reset! held #{1})
    (sp/receive-events! queue {} handler opts)
    (assertions
      "leaves a held session's events queued while delivering other sessions'"
      (contains? (set @seen) [1 :a]) => false
      "stops delivering a session's events once the handler of one of them held it"
      (filterv (comp #{2} first) @seen) => [[2 :hold-me]]
      "and has-due-events? doesn't count the held sessions' events"
      (mpq/has-due-events? queue nil (::mpq/session-held? opts)) => false
      (mpq/has-due-events? queue 2 (::mpq/session-held? opts)) => false
      (mpq/has-due-events? queue 2) => true)
    (sp/cancel! queue {} 2 :c-id)
    (reset! seen [])
    (reset! held #{})
    (sp/receive-events! queue {} handler opts)
    (assertions
      "delivers them in order once released, without the one cancelled while it waited"
      (filterv (comp #{2} first) @seen) => [[2 :b] [2 :d]]
      (filterv (comp #{1} first) @seen) => [[1 :a]])))

(specification "receive-events! with a ::mpq/session-held? that is true for one call only"
  (doseq [held-call [1 2]]
    (component (str "true on its call number " held-call " (the check before the session's event number " held-call ")")
      (let [queue   (mpq/new-queue)
            calls   (atom 0)
            seen    (atom [])
            handler (fn [_ {:keys [name]}] (swap! seen conj name))
            opts    {::mpq/session-held? (fn [_] (= held-call (swap! calls inc)))}]
        (doseq [e [:a :b :c]] (sp/send! queue {} {:target 1 :event e}))
        (sp/receive-events! queue {} handler opts)
        (let [first-call @seen]
          (sp/receive-events! queue {} handler opts)
          (assertions
            "stops delivering the session's events at that check (it doesn't skip the event and go on)"
            first-call => (subvec [:a :b :c] 0 (dec held-call))
            "and the next call delivers the rest, in order"
            @seen => [:a :b :c]))))))

(specification "Session entries of the queue"
  (let [queue    (mpq/new-queue)
        sessions (fn [] (set (keys @(:session-queues queue))))]
    (sp/send! queue {} {:target 1 :event :a})
    (sp/send! queue {} {:target 2 :event :b :send-id :b-id})
    (sp/cancel! queue {} 3 :nothing)
    (assertions
      "exist for the sessions with queued events only (a cancel for a session with none adds none)"
      (sessions) => #{1 2})
    (sp/receive-events! queue {} (fn [_ _]) {:session-id 1})
    (sp/cancel! queue {} 2 :b-id)
    (assertions
      "are dropped once a session's queue is empty, whether its events were delivered or cancelled"
      (sessions) => #{}
      "and the queue still numbers later events after the earlier ones"
      (do
        (sp/send! queue {} {:target 1 :event :c})
        (sp/send! queue {} {:target 1 :event :d})
        (mapv :name (get @(:session-queues queue) 1))) => [:c :d])))

(specification "receive-events! with events behind a delayed one that isn't due"
  (let [queue   (mpq/new-queue)
        seen    (atom [])
        handler (fn [_ {:keys [name]}]
                  (swap! seen conj name)
                  (when (= :b name) (sp/cancel! queue {} 1 :c-id)))]
    (when-mocking
      (now-ms) => 100

      (do
        (sp/send! queue {} {:target 1 :event :timer :delay 50 :send-id :timer-id})
        (sp/send! queue {} {:target 1 :event :a})
        (sp/send! queue {} {:target 1 :event :b})
        (sp/send! queue {} {:target 1 :event :c :send-id :c-id})
        (sp/send! queue {} {:target 1 :event :d})
        (sp/receive-events! queue {} handler {})))
    (assertions
      "delivers the due ones, in order, without one cancelled meanwhile"
      @seen => [:a :b :d]
      "and keeps the delayed one queued"
      (mapv :name (get @(:session-queues queue) 1)) => [:timer])
    (reset! seen [])
    (when-mocking
      (now-ms) => 200

      (do
        (sp/send! queue {} {:target 1 :event :e})
        (sp/receive-events! queue {} handler {})))
    (assertions
      "which is delivered once due, before the events sent after it"
      @seen => [:timer :e])
    (when-mocking
      (now-ms) => 300

      (do
        (sp/send! queue {} {:target 1 :event :late :delay 50 :send-id :late-id})
        (sp/send! queue {} {:target 1 :event :f})
        (sp/receive-events! queue {} handler {})
        (sp/cancel! queue {} 1 :late-id)))
    (assertions
      "and a delayed one left behind that way can be cancelled"
      (get @(:session-queues queue) 1) => nil)))

(specification "receive-events! with a handler that returns ::mpq/requeue"
  (doseq [delayed-head? [false true]]
    (component (if delayed-head? "behind a delayed event that isn't due" "at the head of the queue")
      (let [queue    (mpq/new-queue)
            seen     (atom [])
            refuse?  (atom true)
            handler  (fn [_ {:keys [name target]}]
                       (if (and (= :b name) @refuse?)
                         ::mpq/requeue
                         (do (swap! seen conj [target name]) nil)))]
        (when-mocking
          (now-ms) => 100

          (do
            (when delayed-head? (sp/send! queue {} {:target 1 :event :timer :delay 500}))
            (doseq [e [:a :b :c]] (sp/send! queue {} {:target 1 :event e}))
            (sp/send! queue {} {:target 2 :event :other})
            (sp/receive-events! queue {} handler {})))
        (assertions
          "puts the event back and stops delivering its session's events for that call"
          (filterv (comp #{1} first) @seen) => [[1 :a]]
          "(other sessions' are delivered)"
          (filterv (comp #{2} first) @seen) => [[2 :other]]
          "at its place in send order"
          (mapv :name (get @(:session-queues queue) 1)) => (if delayed-head? [:timer :b :c] [:b :c]))
        (reset! refuse? false)
        (when-mocking
          (now-ms) => 100

          (sp/receive-events! queue {} handler {}))
        (assertions
          "so a later call delivers it first"
          (filterv (comp #{1} first) @seen) => [[1 :a] [1 :b] [1 :c]])))))

(specification "cancel-self-sent-events!"
  (let [queue (mpq/new-queue)]
    (sp/send! queue {} {:target 1 :source-session-id 1 :event :own-timeout :delay 1000})
    (sp/send! queue {} {:target 1 :source-session-id 1 :event :own-now})
    (sp/send! queue {} {:target 1 :source-session-id 2 :event :from-2})
    (sp/send! queue {} {:target 1 :event :from-app})
    (sp/send! queue {} {:target 2 :source-session-id 1 :event :to-2})
    (mpq/cancel-self-sent-events! queue 1)
    (assertions
      "removes the session's events that it sent itself, delayed or not"
      (mapv :name (get @(:session-queues queue) 1)) => [:from-2 :from-app]
      "and leaves other sessions' queues alone"
      (mapv :name (get @(:session-queues queue) 2)) => [:to-2])))

#?(:clj
   (specification "receive-events! cost per event"
     (let [ms      (fn [f] (let [t (System/nanoTime)] (f) (/ (- (System/nanoTime) t) 1e6)))
           filled  (fn [n delayed-head?]
                     (let [q (mpq/new-queue)]
                       (when delayed-head? (sp/send! q {} {:target 0 :event :timer :delay 60000}))
                       (dotimes [i n] (sp/send! q {} {:target 0 :event :e :data {:n i}}))
                       q))
           drain   (fn [q] (ms #(sp/receive-events! q {} (fn [_ _]) {})))
           held-q  (filled 30000 false)
           held?   {::mpq/session-held? (fn [_] true)}]
       (assertions
         "is about constant: 30k due events are delivered in well under a second (it was quadratic)"
         (< (drain (filled 30000 false)) 1000) => true
         "also behind a delayed event that isn't due"
         (< (drain (filled 30000 true)) 1000) => true
         "and polling a held session doesn't walk its queued events (1000 polls of one with 30k)"
         (< (ms #(dotimes [_ 1000] (sp/receive-events! held-q {} (fn [_ _]) held?))) 200) => true))))
