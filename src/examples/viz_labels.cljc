(ns viz-labels
  "Label demo chart showing UML-style transition labels and activity compartments.

  Demonstrates:
  - Named predicates via :diagram/condition on transitions
  - Entry/exit activity labels via :diagram/label on script elements
  - Choice nodes with named conditions
  - Custom transition labels"
  #?(:cljs (:require-macros [com.fulcrologic.statecharts.convenience-macros :refer [choice]]))
  (:require
    [com.fulcrologic.statecharts.chart :refer [statechart]]
    [com.fulcrologic.statecharts.convenience :refer [on]]
    #?(:clj [com.fulcrologic.statecharts.convenience-macros :refer [choice]])
    [com.fulcrologic.statecharts.elements :refer [final on-entry on-exit script state transition]]))

(defn valid-form? [_ _] true)
(defn has-errors? [_ _] false)

(def label-demo
  "A chart demonstrating UML-standard labels with named guards and activity compartments."
  (statechart {:initial :idle}
    (state {:id :idle}
      (on-entry {}
        (script {:expr (fn [_ _] nil) :diagram/label "reset-form"}))
      (on :submit :validating))

    (choice {:id :validating}
      valid-form? :processing
      has-errors? :idle
      :else :error)

    (state {:id :processing}
      (on-entry {}
        (script {:expr (fn [_ _] nil) :diagram/label "load-data"})
        (script {:expr (fn [_ _] nil) :diagram/label "start-spinner"}))
      (on-exit {}
        (script {:expr (fn [_ _] nil) :diagram/label "stop-spinner"}))
      (on :success :done)
      (on :failure :error))

    (state {:id :error}
      (on-entry {}
        (script {:expr (fn [_ _] nil) :diagram/label "show-error"}))
      (on :retry :idle))

    (final {:id :done})))
