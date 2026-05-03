(ns com.fulcrologic.statecharts.irp.test-319
  "IRP test 319 — verifies that `_event` is not bound (nil) before any event
   has been raised. An `<if>` checks whether `_event` is bound; if it is, it
   raises `bound`; otherwise it raises `unbound`. The chart must reach pass
   (transition on `unbound`).

   Source: https://www.w3.org/Voice/2013/scxml-irp/319/test319.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry raise If else]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-319
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (If {:cond (fn [_ d] (some? (:_event d)))}
            (raise {:event :bound})
            (else {}
              (raise {:event :unbound}))))
        (transition {:event :unbound :target :pass})
        (transition {:event :bound   :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 319 — _event is unbound (nil) before first event"
  (assertions
    "reaches pass"
    (runner/passes? chart-319 []) => true))
