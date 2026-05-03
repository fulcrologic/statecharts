(ns com.fulcrologic.statecharts.irp.test-200
  "IRP test 200 — processor supports the SCXML Event I/O Processor type.
   The library's manual queue accepts type strings starting with
   `http://www.w3.org/tr/scxml`, so the explicitly-typed send delivers
   normally and is processed before the timeout fallback.

   Source: https://www.w3.org/Voice/2013/scxml-irp/200/test200.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-200
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (Send {:event :event1
                 :type  "http://www.w3.org/TR/scxml/#SCXMLEventProcessor"})
          (Send {:event :timeout}))
        (transition {:event :event1 :target :pass})
        (transition {:event :*      :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 200 — SCXML Event I/O Processor type is supported"
  (assertions
    "reaches pass"
    (runner/passes? chart-200 []) => true))
