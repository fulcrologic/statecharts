(ns com.fulcrologic.statecharts.irp.test-350
  "IRP test 350 — a session can send an event to itself using its session ID
   as the target.

   The W3C test concatenates \"#_scxml_\" + _sessionid to form the target URI.
   This library uses bare session-id UUIDs as targets; we store _sessionid in
   Var1 and pass it directly as targetexpr — same routing behaviour.

   Source: https://www.w3.org/Voice/2013/scxml-irp/350/test350.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send assign
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-350
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 nil}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          ;; capture session ID then use it as the send target
          (assign {:location [:Var1] :expr (fn [_ d] (:_sessionid d))})
          (Send {:event :s0Event :targetexpr (fn [_ d] (:Var1 d))}))
        (transition {:event :s0Event :target :pass})
        (transition {:event :* :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 350 — session can send to itself using its session ID"
  (assertions
    "reaches pass"
    (runner/passes? chart-350 events) => true))
