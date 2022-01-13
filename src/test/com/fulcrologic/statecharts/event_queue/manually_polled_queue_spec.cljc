(ns com.fulcrologic.statecharts.event-queue.manually-polled-queue-spec
  (:require
    [com.fulcrologic.statecharts.util :refer [now-ms]]
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [fulcro-spec.core :refer [specification assertions component behavior => when-mocking]]
    [com.fulcrologic.statecharts.protocols :as sp]))

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
