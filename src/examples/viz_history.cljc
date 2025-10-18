(ns viz-history
  "Comprehensive history node examples for visualization testing.

  This file contains multiple charts demonstrating different history scenarios:
  - Shallow history (H)
  - Deep history (H*)
  - Multiple history nodes in different regions
  - History with parallel states"
  (:require
    [com.fulcrologic.statecharts.chart :refer [statechart]]
    [com.fulcrologic.statecharts.elements :refer [final history parallel state transition]]))

;; Simple shallow history example
(def shallow-history
  "Basic shallow history demonstration. History restores the last active direct child state."
  (statechart {:id :shallow-history-demo}
    (state {:id :off}
      (transition {:event :turn-on :target :device}))
    (state {:id :device}
      (transition {:event :turn-off :target :off})
      (transition {:event :restart :target :device-history})

      ;; Shallow history node
      (history {:id :device-history :type :shallow}
        (transition {:target :settings-basic}))

      (state {:id :settings-basic}
        (transition {:event :advanced :target :settings-advanced}))
      (state {:id :settings-advanced}
        (transition {:event :basic :target :settings-basic})))))

;; Deep history example
(def deep-history
  "Deep history demonstration. History restores the entire nested state hierarchy."
  (statechart {:id :deep-history-demo}
    (state {:id :menu}
      (transition {:event :enter :target :main-menu-h}))

    (state {:id :main-menu}
      (transition {:event :back :target :menu})

      ;; Deep history node
      (history {:id :main-menu-h :type :deep}
        (transition {:target :file}))

      (state {:id :file}
        (transition {:event :goto-edit :target :edit})
        (state {:id :file-new}
          (transition {:event :save :target :file-save}))
        (state {:id :file-save}
          (transition {:event :new :target :file-new})))

      (state {:id :edit}
        (transition {:event :goto-file :target :file})
        (state {:id :edit-copy}
          (transition {:event :paste :target :edit-paste}))
        (state {:id :edit-paste}
          (transition {:event :copy :target :edit-copy}))))))

;; History with parallel states
(def parallel-history
  "History in parallel regions - each region can have independent history."
  (statechart {:id :parallel-history-demo}
    (state {:id :idle}
      (transition {:event :start :target :active}))

    (parallel {:id :active}
      (transition {:event :stop :target :idle})
      (transition {:event :resume :target :active-h})

      ;; History for the entire parallel state
      (history {:id :active-h :type :deep}
        (transition {:target :active}))

      ;; Region 1: Audio
      (state {:id :audio-region}
        (history {:id :audio-h :type :shallow}
          (transition {:target :audio-playing}))

        (state {:id :audio-playing}
          (transition {:event :pause-audio :target :audio-paused}))
        (state {:id :audio-paused}
          (transition {:event :play-audio :target :audio-playing})))

      ;; Region 2: Video
      (state {:id :video-region}
        (history {:id :video-h :type :shallow}
          (transition {:target :video-playing}))

        (state {:id :video-playing}
          (transition {:event :pause-video :target :video-paused}))
        (state {:id :video-paused}
          (transition {:event :play-video :target :video-playing}))))))

;; Complex multi-level history
(def multilevel-history
  "Multiple levels of nested history demonstrating shallow vs deep behavior."
  (statechart {:id :multilevel-history-demo}
    (state {:id :app}
      (transition {:event :restart :target :app-h})

      (history {:id :app-h :type :deep}
        (transition {:target :section-a}))

      (state {:id :section-a}
        (transition {:event :goto-b :target :section-b})

        (history {:id :section-a-h :type :shallow}
          (transition {:target :a1}))

        (state {:id :a1}
          (transition {:event :next :target :a2})
          (state {:id :a1-sub1}
            (transition {:event :sub-next :target :a1-sub2}))
          (state {:id :a1-sub2}
            (transition {:event :sub-prev :target :a1-sub1})))

        (state {:id :a2}
          (transition {:event :prev :target :a1})
          (state {:id :a2-sub1}
            (transition {:event :sub-next :target :a2-sub2}))
          (state {:id :a2-sub2}
            (transition {:event :sub-prev :target :a2-sub1}))))

      (state {:id :section-b}
        (transition {:event :goto-a :target :section-a})

        (history {:id :section-b-h :type :deep}
          (transition {:target :b1}))

        (state {:id :b1}
          (transition {:event :next :target :b2})
          (state {:id :b1-sub1}
            (transition {:event :sub-next :target :b1-sub2}))
          (state {:id :b1-sub2}
            (transition {:event :sub-prev :target :b1-sub1})))

        (state {:id :b2}
          (transition {:event :prev :target :b1})
          (state {:id :b2-sub1}
            (transition {:event :sub-next :target :b2-sub2}))
          (state {:id :b2-sub2}
            (transition {:event :sub-prev :target :b2-sub1})))))))

;; History with final states
(def history-with-final
  "History behavior when transitions to final states are involved."
  (statechart {:id :history-final-demo}
    (state {:id :workflow}
      (transition {:event :restart :target :workflow-h})

      (history {:id :workflow-h :type :shallow}
        (transition {:target :step-1}))

      (state {:id :step-1}
        (transition {:event :next :target :step-2}))

      (state {:id :step-2}
        (transition {:event :next :target :step-3})
        (transition {:event :prev :target :step-1}))

      (state {:id :step-3}
        (transition {:event :complete :target :done})
        (transition {:event :prev :target :step-2}))

      (final {:id :done}))))
