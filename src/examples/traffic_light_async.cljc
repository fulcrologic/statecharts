(ns traffic-light-async
  "A demo that uses the core.async queue to demonstrate a running machine that can just be
   sent events and mutates in place (and handles timers in CLJC) via core.async."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.state-machine :refer [machine]]
    [com.fulcrologic.statecharts.elements :refer [state parallel transition raise on-entry assign data-model
                                                  Send]]
    [com.fulcrologic.statecharts.events :refer [new-event]]
    [com.fulcrologic.statecharts.simple :refer [new-simple-machine]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [com.fulcrologic.statecharts.event-queue.core-async-event-loop :as loop]
    [com.fulcrologic.statecharts.simple :as simple]
    [com.fulcrologic.statecharts.util :refer [extend-key]]
    [com.fulcrologic.statecharts.events :as evts]))

(def nk
  "(nk :a \"b\") => :a/b
   (nk :a/b \"c\") => :a.b/c"
  extend-key)

(def flow-time "How long to wait before flashing ped warning" 2000)
(def flashing-white-time "How long do we warn peds?" 500)
(def yellow-time "How long do we warn cars" 200)

(defn timer []
  (state {:id :timer-control}
    (state {:id :timing-flow}
      (transition {:event  :warn-pedestrians
                   :target :timing-ped-warning})
      (on-entry {}
        (Send {:event :warn-pedestrians
               :delay flow-time})))
    (state {:id :timing-ped-warning}
      (transition {:event  :warn-traffic
                   :target :timing-yellow})
      (on-entry {}
        (Send {:event :warn-traffic
               :delay flashing-white-time})))
    (state {:id :timing-yellow}
      (transition {:event  :swap-flow
                   :target :timing-flow})
      (on-entry {}
        (Send {:event :swap-flow
               :delay yellow-time})))))

(defn traffic-signal [id initial]
  (let [red     (nk id "red")
        yellow  (nk id "yellow")
        green   (nk id "green")
        initial (nk id (name initial))]
    (state {:id      id
            :initial initial}
      (state {:id red}
        (transition {:event :swap-flow :target green}))
      (state {:id yellow}
        (transition {:event :swap-flow :target red}))
      (state {:id green}
        (transition {:event  :warn-traffic
                     :target yellow})))))

(defn ped-signal [id initial]
  (let [red            (nk id "red")
        flashing-white (nk id "flashing-white")
        white          (nk id "white")
        initial        (nk id (name initial))]
    (state {:id      id
            :initial initial}
      (state {:id red}
        (transition {:event :swap-flow :target white}))
      (state {:id flashing-white}
        (transition {:event :swap-flow :target red}))
      (state {:id white}
        (transition {:event :warn-pedestrians :target flashing-white})))))

(def traffic-lights
  (machine {}
    (parallel {}
      (timer)

      (traffic-signal :east-west :green)
      (traffic-signal :north-south :red)

      (ped-signal :cross-ew :red)
      (ped-signal :cross-ns :white))))

(defn show-states [wmem]
  (println (sort (filter #{:north-south/red
                           :north-south/yellow
                           :north-south/green
                           :east-west/red
                           :east-west/yellow
                           :east-west/green
                           :cross-ns/red
                           :cross-ns/white
                           :cross-ns/flashing-white
                           :cross-ew/red
                           :cross-ew/white
                           :cross-ew/flashing-white} (::sc/configuration wmem)))))

(comment

  (def session-id 1)
  (def queue (mpq/new-queue))
  (def processor (simple/new-simple-machine traffic-lights {::sc/event-queue queue}))
  (def wmem (let [a (atom {})] (add-watch a :printer (fn [_ _ _ n] (show-states n))) a))
  (loop/run-event-loop! processor wmem session-id 100)      ; should see the state changing with the timers

  ;; Tell the state machine to exit abruptly
  (sp/send! queue {:target session-id
                   :event  evts/cancel-event}))

