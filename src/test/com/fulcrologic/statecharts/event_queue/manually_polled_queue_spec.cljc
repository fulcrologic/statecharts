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

(specification "receive-events! with a ::mpq/session-busy? predicate"
  (let [queue   (mpq/new-queue)
        busy    (atom #{})
        seen    (atom [])
        queued  (atom [])
        handler (fn [_ {:keys [name target]}]
                  (swap! seen conj name)
                  (swap! queued conj (mapv :name (get @(:session-queues queue) target)))
                  (when (= :A name) (swap! busy conj target)))
        options {::mpq/session-busy? #(contains? @busy %)}]
    (doseq [[target event] [[1 :A] [1 :B] [2 :C]]]
      (sp/send! queue {} {:target target :source-session-id target :event event}))
    (sp/receive-events! queue {} handler options)
    (assertions
      "takes each event off its session's queue just before its handler runs"
      @queued => [[:B] []]
      "holds a session's events once it is busy, and runs other sessions' events"
      @seen => [:A :C])
    (reset! busy #{})
    (reset! seen [])
    (sp/receive-events! queue {} handler options)
    (assertions
      "runs the held events, in order, once the session is no longer busy"
      @seen => [:B])))
