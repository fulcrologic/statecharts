(ns com.fulcrologic.statecharts.irp.test-580
  "IRP test 580 — history states are never part of the configuration.
   `In(sh1)` should always be false, even when the machine enters via a history pseudo-state.

   Source: https://www.w3.org/Voice/2013/scxml-irp/580/test580.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state parallel final transition initial history on-exit assign data-model]]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-580
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 0}})
    (state {:id :_root :initial :p1}
      (parallel {:id :p1}
        (state {:id :s0}
          ;; sh1 should never be in config
          (transition {:cond (fn [e _] (env/is-in-state? e :sh1)) :target :fail})
          (transition {:event :timeout :target :fail}))
        (state {:id :s1}
          (initial {}
            (transition {:target :sh1}))
          (history {:id :sh1}
            (transition {:target :s11}))
          (state {:id :s11}
            (transition {:cond (fn [e _] (env/is-in-state? e :sh1)) :target :fail})
            (transition {:target :s12}))
          (state {:id :s12})
          (on-exit {} (assign {:location [:Var1] :expr (fn [_ d] (inc (:Var1 d)))}))
          (transition {:cond (fn [e _] (env/is-in-state? e :sh1)) :target :fail})
          (transition {:cond (fn [_ d] (= 0 (:Var1 d))) :target :sh1})
          (transition {:cond (fn [_ d] (= 1 (:Var1 d))) :target :pass})))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 580 — history state is never in the configuration"
  (assertions
    "reaches pass"
    (runner/passes? chart-580 events) => true))
