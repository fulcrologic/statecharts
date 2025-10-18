(ns viz-parallel
  "Parallel state examples for visualization testing.

  This file contains charts demonstrating parallel (orthogonal) states where
  multiple regions are active simultaneously."
  (:require
    [com.fulcrologic.statecharts.chart :refer [statechart]]
    [com.fulcrologic.statecharts.elements :refer [final parallel state transition]]))

;; Simple parallel state with two regions
(def simple-parallel
  "Basic parallel state with two independent regions."
  (statechart {:id :simple-parallel-demo}
    (state {:id :off}
      (transition {:event :power-on :target :device-on}))

    (parallel {:id :device-on}
      (transition {:event :power-off :target :off})

      ;; Region 1: Display
      (state {:id :display}
        (state {:id :display-bright}
          (transition {:event :dim :target :display-dim}))

        (state {:id :display-dim}
          (transition {:event :brighten :target :display-bright})))

      ;; Region 2: Sound
      (state {:id :sound}
        (state {:id :sound-loud}
          (transition {:event :quiet :target :sound-quiet}))

        (state {:id :sound-quiet}
          (transition {:event :louder :target :sound-loud}))))))

;; Parallel state with three regions
(def multi-region-parallel
  "Parallel state with three independent regions."
  (statechart {:id :multi-region-demo}
    (parallel {:id :application}

      ;; Region 1: Network status
      (state {:id :network}
        (state {:id :net-offline}
          (transition {:event :connect :target :net-online}))

        (state {:id :net-online}
          (transition {:event :disconnect :target :net-offline})))

      ;; Region 2: UI state
      (state {:id :ui}
        (state {:id :ui-light-mode}
          (transition {:event :toggle-theme :target :ui-dark-mode}))

        (state {:id :ui-dark-mode}
          (transition {:event :toggle-theme :target :ui-light-mode})))

      ;; Region 3: Data sync
      (state {:id :sync}
        (state {:id :sync-idle}
          (transition {:event :start-sync :target :sync-active}))

        (state {:id :sync-active}
          (transition {:event :sync-complete :target :sync-idle})
          (transition {:event :sync-error :target :sync-failed}))

        (state {:id :sync-failed}
          (transition {:event :retry :target :sync-active}))))))

;; Nested parallel states
(def nested-parallel
  "Parallel states within parallel states."
  (statechart {:id :nested-parallel-demo}
    (state {:id :app-off}
      (transition {:event :launch :target :app-running}))

    (parallel {:id :app-running}
      (transition {:event :quit :target :app-off})

      ;; Region 1: Frontend (itself parallel)
      (parallel {:id :frontend}
        ;; Frontend Region 1: View layer
        (state {:id :view}
          (state {:id :view-list}
            (transition {:event :view-detail :target :view-detail}))
          (state {:id :view-detail}
            (transition {:event :view-list :target :view-list})))

        ;; Frontend Region 2: Edit mode
        (state {:id :edit-mode}
          (state {:id :read-only}
            (transition {:event :enable-edit :target :editable}))
          (state {:id :editable}
            (transition {:event :disable-edit :target :read-only}))))

      ;; Region 2: Backend (also parallel)
      (parallel {:id :backend}
        ;; Backend Region 1: Database
        (state {:id :database}
          (state {:id :db-connected}
            (transition {:event :db-disconnect :target :db-disconnected}))
          (state {:id :db-disconnected}
            (transition {:event :db-connect :target :db-connected})))

        ;; Backend Region 2: Cache
        (state {:id :cache}
          (state {:id :cache-cold}
            (transition {:event :warm-cache :target :cache-warm}))
          (state {:id :cache-warm}
            (transition {:event :clear-cache :target :cache-cold})))))))

;; Parallel with final states (completion events)
(def parallel-with-completion
  "Parallel state where regions can complete independently."
  (statechart {:id :parallel-completion-demo}
    (state {:id :init}
      (transition {:event :start-tasks :target :tasks}))

    (parallel {:id :tasks}
      ;; When all regions reach final state, transition automatically
      (transition {:event :done.state.tasks :target :all-complete})

      ;; Region 1: Task A
      (state {:id :task-a}
        (state {:id :task-a-running}
          (transition {:event :finish-a :target :task-a-done}))
        (final {:id :task-a-done}))

      ;; Region 2: Task B
      (state {:id :task-b}
        (state {:id :task-b-running}
          (transition {:event :finish-b :target :task-b-done}))
        (final {:id :task-b-done}))

      ;; Region 3: Task C
      (state {:id :task-c}
        (state {:id :task-c-running}
          (transition {:event :finish-c :target :task-c-done}))
        (final {:id :task-c-done})))

    (final {:id :all-complete})))

;; Complex: Parallel with compound states
(def complex-parallel
  "Complex parallel state with deeply nested compound states."
  (statechart {:id :complex-parallel-demo}
    (parallel {:id :media-player}

      ;; Region 1: Playback control
      (state {:id :playback}
        (state {:id :stopped}
          (transition {:event :play :target :playing}))

        (state {:id :playing}
          (transition {:event :pause :target :paused})
          (transition {:event :stop :target :stopped})

          (state {:id :normal-speed}
            (transition {:event :fast-forward :target :double-speed}))

          (state {:id :double-speed}
            (transition {:event :normal :target :normal-speed})))

        (state {:id :paused}
          (transition {:event :play :target :playing})
          (transition {:event :stop :target :stopped})))

      ;; Region 2: Audio/Video tracks
      (parallel {:id :tracks}
        ;; Audio track
        (state {:id :audio-track}
          (state {:id :audio-track-1}
            (transition {:event :next-audio :target :audio-track-2}))
          (state {:id :audio-track-2}
            (transition {:event :prev-audio :target :audio-track-1})))

        ;; Video track (quality)
        (state {:id :video-quality}
          (state {:id :quality-auto}
            (transition {:event :set-hd :target :quality-hd})
            (transition {:event :set-sd :target :quality-sd}))
          (state {:id :quality-hd}
            (transition {:event :set-auto :target :quality-auto}))
          (state {:id :quality-sd}
            (transition {:event :set-auto :target :quality-auto}))))

      ;; Region 3: Subtitles
      (state {:id :subtitles}
        (state {:id :subs-off}
          (transition {:event :enable-subs :target :subs-on}))

        (state {:id :subs-on}
          (transition {:event :disable-subs :target :subs-off})

          (state {:id :subs-english}
            (transition {:event :subs-spanish :target :subs-spanish}))

          (state {:id :subs-spanish}
            (transition {:event :subs-english :target :subs-english})))))))
