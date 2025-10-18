(ns viz-simple
  "Simple state chart examples for visualization testing.

  This file contains straightforward charts demonstrating basic statechart features:
  - Simple states and transitions
  - Initial and final states
  - Compound states
  - Conditional transitions"
  (:require
    [com.fulcrologic.statecharts.chart :refer [statechart]]
    [com.fulcrologic.statecharts.elements :refer [final state transition]]))

;; Basic state machine with initial and final states
(def basic-states
  "A simple linear state machine showing basic transitions."
  (statechart {:id :basic-states-demo}
    (state {:id :idle}
      (transition {:event :start :target :working}))

    (state {:id :working}
      (transition {:event :complete :target :done})
      (transition {:event :cancel :target :idle}))

    (final {:id :done})))

;; Compound states with nested children
(def compound-states
  "Demonstrates compound states with nested substates."
  (statechart {:id :compound-states-demo}
    (state {:id :app}
      (state {:id :loading}
        (transition {:event :loaded :target :ready}))

      (state {:id :ready}
        (transition {:event :edit :target :editing})

        (state {:id :viewing}
          (transition {:event :select :target :selected}))

        (state {:id :selected}
          (transition {:event :deselect :target :viewing})))

      (state {:id :editing}
        (transition {:event :save :target :ready})
        (transition {:event :cancel :target :ready})

        (state {:id :text-mode}
          (transition {:event :switch :target :code-mode}))

        (state {:id :code-mode}
          (transition {:event :switch :target :text-mode}))))))

;; Conditional transitions (guards)
(def conditional-transitions
  "Shows conditional transitions with guards."
  (statechart {:id :conditional-demo}
    (state {:id :form}
      (transition {:event :validate :target :valid
                   :cond  (fn [env data] true)})            ; Placeholder - would check data
      (transition {:event :validate :target :invalid}))

    (state {:id :valid}
      (transition {:event :submit :target :submitted})
      (transition {:event :reset :target :form}))

    (state {:id :invalid}
      (transition {:event :fix :target :form}))

    (final {:id :submitted})))

;; Multiple transitions with different events
(def multi-transition
  "A state with multiple outgoing transitions."
  (statechart {:id :multi-transition-demo}
    (state {:id :menu}
      (transition {:event :file :target :file-menu})
      (transition {:event :edit :target :edit-menu})
      (transition {:event :view :target :view-menu})
      (transition {:event :help :target :help-menu}))

    (state {:id :file-menu}
      (transition {:event :back :target :menu})
      (transition {:event :new :target :new-file})
      (transition {:event :open :target :open-file}))

    (state {:id :edit-menu}
      (transition {:event :back :target :menu}))

    (state {:id :view-menu}
      (transition {:event :back :target :menu}))

    (state {:id :help-menu}
      (transition {:event :back :target :menu}))

    (state {:id :new-file}
      (transition {:event :done :target :menu}))

    (state {:id :open-file}
      (transition {:event :done :target :menu}))))

;; Eventless (automatic) transitions
(def eventless-transitions
  "Demonstrates eventless/automatic transitions."
  (statechart {:id :eventless-demo}
    (state {:id :start}
      ;; Automatic transition (no event)
      (transition {:target :auto-next}))

    (state {:id :auto-next}
      ;; Conditional automatic transition
      (transition {:cond   (fn [env data] false)
                   :target :path-a})
      (transition {:target :path-b}))

    (state {:id :path-a}
      (transition {:event :continue :target :end}))

    (state {:id :path-b}
      (transition {:event :continue :target :end}))

    (final {:id :end})))

;; Deep nesting example
(def deeply-nested
  "Shows multiple levels of state nesting."
  (statechart {:id :deeply-nested-demo}
    (state {:id :root}
      (state {:id :level-1}
        (transition {:event :next :target :level-2})

        (state {:id :level-1a}
          (transition {:event :drill :target :level-1b}))

        (state {:id :level-1b}
          (state {:id :level-1b-i}
            (transition {:event :down :target :level-1b-ii}))

          (state {:id :level-1b-ii}
            (transition {:event :up :target :level-1b-i}))))

      (state {:id :level-2}
        (transition {:event :prev :target :level-1})
        (transition {:event :finish :target :done})

        (state {:id :level-2a}
          (transition {:event :switch :target :level-2b}))

        (state {:id :level-2b}
          (transition {:event :switch :target :level-2a}))))

    (final {:id :done})))
