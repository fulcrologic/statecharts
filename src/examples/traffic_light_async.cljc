(ns traffic-light-async
  "A demo that uses the core.async queue to demonstrate a running machine that can just be
   sent events and mutates in place (and handles timers in CLJC) via core.async."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :refer [statechart]]
    [com.fulcrologic.statecharts.elements :refer [Send on-entry parallel state transition]]
    [com.fulcrologic.statecharts.event-queue.core-async-event-loop :as loop]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.simple :as simple]
    [com.fulcrologic.statecharts.util :refer [extend-key]]))

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
  (statechart {}
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
  ;; Setup steps
  (do
    (def session-id 1)
    ;; Override the working memory store so we can watch our working memory change
    (def wmem (let [a (atom {})] (add-watch a :printer (fn [_ _ _ n] (show-states n))) a))
    ;; Create an env that has all the components needed, but override the working memory store
    (def env (simple/simple-env
               {::sc/working-memory-store
                (reify sp/WorkingMemoryStore
                  (get-working-memory [_ _ _] @wmem)
                  (save-working-memory! [_ _ _ m] (reset! wmem m)))}))

    ;; Register the chart under a well-known name
    (simple/register! env ::lights traffic-lights)

    ;; Run an event loop that polls the queue every 100ms
    (def running? (loop/run-event-loop! env 100)))

  ;; Start takes the well-known ID of a chart that should be started. The working memory is tracked internally.
  ;; You should see the tracking installed above emit the traffic signal pattern
  (simple/start! env ::lights session-id)

  ;; Tell the state machine to exit abruptly
  (simple/send! env {:target session-id
                     :event  evts/cancel-event})

  ;; Stop event loop
  (reset! running? false))
