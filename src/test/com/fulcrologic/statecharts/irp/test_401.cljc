(ns com.fulcrologic.statecharts.irp.test-401
  "IRP test 401 — errors go in the INTERNAL event queue, so they are processed
   before external events that were already sent.

   Chart sends itself an external `foo`, then performs an illegal assign which
   should raise `error.execution` on the internal queue. The internal `error.*`
   event must win the next macrostep, reaching `:pass`. If the error were placed
   on the external queue, `foo` (sent first) would arrive first and reach `:fail`.

   Source: https://www.w3.org/Voice/2013/scxml-irp/401/test401.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry assign Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-401
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (Send {:event :foo})
          ;; assigning to a non-existent location should raise error.execution
          (assign {:location [:nonexistent :deeply :nested :path]
                   :expr     (fn [_ _] (throw (ex-info "illegal location" {})))}))
        (transition {:event :foo :target :fail})
        (transition {:event :error :target :pass}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 401 — error.execution is internal (precedes external events)"
  (assertions
    "reaches pass"
    (runner/passes? chart-401 []) => true))
