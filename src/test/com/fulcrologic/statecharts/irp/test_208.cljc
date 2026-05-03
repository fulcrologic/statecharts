(ns com.fulcrologic.statecharts.irp.test-208
  "IRP test 208 — `<cancel sendid=...>` cancels a previously-sent delayed
   event. event1 is scheduled for 1s with id `foo`, event2 for 1.5s. The
   cancel for `foo` fires immediately, so only event2 is delivered.

   Source: https://www.w3.org/Voice/2013/scxml-irp/208/test208.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send cancel]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-208
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (Send   {:id "foo" :event :event1 :delay 1000})
          (Send   {:event :event2 :delay 1500})
          (cancel {:sendid "foo"}))
        (transition {:event :event2 :target :pass})
        (transition {:event :*      :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 208 — cancel removes a delayed send"
  (assertions
    "reaches pass"
    (runner/passes-with-delays? chart-208 []) => true))
