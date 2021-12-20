(ns com.fulcrologic.statecharts.event-queue.manually-polled-queue-spec
  (:require
    [com.fulcrologic.statecharts.elements :refer [state parallel script
                                                  history final initial
                                                  on-entry on-exit invoke
                                                  data-model transition]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.state-machine :as sm]
    [com.fulcrologic.statecharts.events :refer [new-event]]
    [com.fulcrologic.statecharts.util :refer [now-ms]]
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [fulcro-spec.core :refer [specification assertions component behavior => when-mocking
                              =1x=>]]
    [com.fulcrologic.statecharts.protocols :as sp]))

(specification "Manually Polled Queue"
  (let [queue         (mpq/new-queue)
        my-session    1
        other-session 2
        evt1          :A
        evt2          :B
        evt3          :C
        seen          (atom [])
        handler       (fn [evt] (swap! seen conj evt))]

    (sp/send! queue {:target            my-session
                     :source-session-id my-session
                     :event             evt1})
    (sp/send! queue {:target            other-session
                     :source-session-id my-session
                     :event             evt2})
    (sp/send! queue {:target            my-session
                     :source-session-id my-session
                     :event             evt3})

    (sp/receive-events! queue {:session-id other-session} handler)

    (behavior "Sees only targeted events"
      (assertions
        (map :name @seen) => [:B])

      (reset! seen [])
      (sp/receive-events! queue {:session-id my-session} handler)

      (assertions
        "in the order sent"
        (mapv :name @seen) => [:A :C]))

    (component "Delayed events"
      (reset! seen [])
      (when-mocking
        (now-ms) => 1

        (sp/send! queue {:target            my-session
                         :source-session-id my-session
                         :event             evt1})
        (sp/send! queue {:target            my-session
                         :source-session-id my-session
                         :delay             10
                         :event             evt2})
        (sp/send! queue {:target            my-session
                         :source-session-id my-session
                         :event             evt3})

        (behavior "are not visible before their time arrives"
          (sp/receive-events! queue {:session-id my-session} handler)

          (assertions
            (mapv :name @seen) => [:A :C])))

      (reset! seen [])
      (when-mocking
        (now-ms) => 100

        (sp/receive-events! queue {:session-id my-session} handler)

        (assertions
          "appear after their trigger time"
          (mapv :name @seen) => [:B])))


    (behavior "Unhandled delayed events can be cancelled"
      (reset! seen [])
      (when-mocking
        (now-ms) => 1

        (sp/send! queue {:target            my-session
                         :source-session-id my-session
                         :send-id           :ID
                         :delay             10
                         :event             evt1}))

      (sp/cancel! queue my-session :ID)

      (when-mocking
        (now-ms) => 15

        (sp/receive-events! queue {:session-id my-session} handler)
        (assertions
          @seen => [])))))
