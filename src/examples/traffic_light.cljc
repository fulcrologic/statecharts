(ns traffic-light
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :refer [statechart]]
    [com.fulcrologic.statecharts.elements :refer [state parallel transition]]
    [com.fulcrologic.statecharts.events :refer [new-event]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.simple :as simple]
    [com.fulcrologic.statecharts.util :refer [extend-key]]))

(def nk
  "(nk :a \"b\") => :a/b
   (nk :a/b \"c\") => :a.b/c"
  extend-key)

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
      (traffic-signal :east-west :green)
      (traffic-signal :north-south :red)

      (ped-signal :cross-ew :red)
      (ped-signal :cross-ns :white))))

(defn show-states [wmem]
  (println
    (sort
      (filter #{:north-south/red
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
                :cross-ew/flashing-white}
        (::sc/configuration wmem)))))

(def env (simple/simple-env))
(simple/register! env ::lights traffic-lights)
(def processor (::sc/processor env))
(def s0 (sp/start! processor env ::lights {::sc/session-id 1}))
(show-states s0)
(def s1 (sp/process-event! processor env s0 (new-event :warn-pedestrians)))
(show-states s1)
(def s2 (sp/process-event! processor env s1 (new-event :warn-traffic)))
(show-states s2)
(def s3 (sp/process-event! processor env s2 (new-event :swap-flow)))
(show-states s3)
(def s4 (sp/process-event! processor env s3 (new-event :warn-pedestrians)))
(show-states s4)
(def s5 (sp/process-event! processor env s4 (new-event :warn-traffic)))
(show-states s5)
(def s6 (sp/process-event! processor env s5 (new-event :swap-flow)))
(show-states s6)
