(ns com.fulcrologic.statecharts.irp.test-318
  "IRP test 318 — verifies that `_event` stays bound to the currently-processed
   event during onexit of the source state and onentry of the target state.

   In this library, `_event` is exposed in the data model under `:_event`
   (set by the algorithm before executable content runs).

   Source: https://www.w3.org/Voice/2013/scxml-irp/318/test318.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.elements :refer [state final transition on-entry raise assign data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-318
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 nil}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {} (raise {:event :foo}))
        (transition {:event :foo :target :s1}))
      (state {:id :s1}
        (on-entry {}
          (raise {:event :bar})
          ;; capture the current event's name into Var1
          (assign {:location [:Var1]
                   :expr     (fn [_ d] (some-> d :_event ::sc/event-name))}))
        (transition {:cond (fn [_ d] (= :foo (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 318 — _event stays bound during onexit/onentry"
  (assertions
    "reaches pass"
    (runner/passes? chart-318 []) => true))
