(ns history-sample
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :refer [statechart]]
    [com.fulcrologic.statecharts.elements :refer [history state transition]]
    [com.fulcrologic.statecharts.events :refer [new-event]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.simple :as simple]))

(def sample
  (statechart {}
    (state {:id :TOP}
      (state {:id :A}
        (transition {:event :top :target :B}))
      (state {:id :B}
        ;; Could transition to :C, but that won't restore history.
        ;; Transitioning to (one of) the history nodes in C
        ;; directly restores history (you can have more than
        ;; one because you might want different "default" targets
        ;; for when there is no history).
        (transition {:event :top :target :Ch}))
      (state {:id :C}
        (transition {:event :top :target :A})
        (history {:id :Ch}
          (transition {:target :C1}))
        (state {:id :C1}
          (transition {:event :sub :target :C2}))
        (state {:id :C2}
          (transition {:event :sub :target :C1}))))))

(defn show-states [wmem] (println (sort (::sc/configuration wmem))))
(def env (simple/simple-env))
(simple/register! env `sample sample)
(def processor (::sc/processor env))

(def s0 (sp/start! processor env `sample {::sc/session-id 1}))
(show-states s0)
;; :TOP :A

(def s1 (sp/process-event! processor env s0 (new-event :top)))
(show-states s1)
;; :TOP :B

(def s2 (sp/process-event! processor env s1 (new-event :top)))
(show-states s2)
;; :TOP :C :C1

(def s3 (sp/process-event! processor env s2 (new-event :sub)))
(show-states s3)
;; :TOP :C :C2

(def s4 (sp/process-event! processor env s3 (new-event :top)))
(show-states s4)
;; :TOP :A

(def s5 (sp/process-event! processor env s4 (new-event :top)))
(show-states s5)
;; :TOP :B

(def s6 (sp/process-event! processor env s5 (new-event :top)))
(show-states s6)
;; :TOP :C :C2 (history remembered)
