(ns history-sample
  (:require
    [com.fulcrologic.statecharts.state-machine :refer [machine]]
    [com.fulcrologic.statecharts.elements :refer [state parallel transition on-entry final done-data
                                                  data-model log on-exit script history]]
    [com.fulcrologic.statecharts.events :refer [new-event]]
    [com.fulcrologic.statecharts.simple :refer [new-simple-machine]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.util :refer [extend-key]]))

(def sample
  (machine {}
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

(def processor (new-simple-machine sample {}))

(def s0 (sp/start! processor 1))
;; :TOP :A

(def s1 (sp/process-event! processor s0 (new-event :top)))
;; :TOP :B

(def s2 (sp/process-event! processor s1 (new-event :top)))
;; :TOP :C :C1

(def s3 (sp/process-event! processor s2 (new-event :sub)))
;; :TOP :C :C2

(def s4 (sp/process-event! processor s3 (new-event :top)))
;; :TOP :A

(def s5 (sp/process-event! processor s4 (new-event :top)))
;; :TOP :B

(def s6 (sp/process-event! processor s5 (new-event :top)))
;; :TOP :C :C2 (history remembered)
