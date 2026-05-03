(ns com.fulcrologic.statecharts.irp.test-198
  "IRP test 198 — `<send>` without an explicit `type` defaults to the SCXML
   event-i/o processor. The resulting event's origin-type field must reflect
   the SCXML processor (in this library, `::sc/chart`).
   Source: https://www.w3.org/Voice/2013/scxml-irp/198/test198.txml

   Translation: the W3C `originTypeEq=\"http://www.w3.org/TR/scxml/#SCXMLEventProcessor\"`
   maps to checking that the received event's `:type` is `::sc/chart`
   (the library's internal sentinel for the SCXML processor; see
   `manually_polled_queue/send!`)."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-198
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (Send {:event :event1})
          (Send {:event :timeout}))
        (transition {:event :event1
                     :cond  (fn [_ d] (= ::sc/chart (:type (:_event d))))
                     :target :pass})
        (transition {:event :* :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 198 — default send origintype is the SCXML processor"
  (assertions
    "reaches pass"
    (runner/passes? chart-198 []) => true))
