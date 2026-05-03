(ns com.fulcrologic.statecharts.irp.test-210
  "IRP test 210 — `<cancel sendidexpr=...>` evaluates the expression at
   cancel time. Var1 is initially \"bar\" but is reassigned to \"foo\" before
   the cancel runs, so the delayed event with id `foo` is the one cancelled.

   Source: https://www.w3.org/Voice/2013/scxml-irp/210/test210.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send cancel
                                                   assign data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-210
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 "bar"}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (Send   {:id "foo" :event :event1 :delay 1000})
          (Send   {:event :event2 :delay 1500})
          (assign {:location [:Var1] :expr (fn [_ _] "foo")})
          (cancel {:sendidexpr (fn [_ d] (:Var1 d))}))
        (transition {:event :event2 :target :pass})
        (transition {:event :*      :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 210 — cancel sendidexpr uses current value"
  (assertions
    "reaches pass"
    (runner/passes-with-delays? chart-210 []) => true))
