(ns com.fulcrologic.statecharts.irp.test-372
  "IRP test 372 — entering a `final` state must generate
   `done.state.<parent>` AFTER its onentry has executed (so onentry-side
   effects are visible) but BEFORE its onexit. Var1 starts at 1, onentry
   sets it to 2, onexit would set it to 3. The `done.state.s0` transition
   must observe Var1 == 2.
   Source: https://www.w3.org/Voice/2013/scxml-irp/372/test372.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry on-exit assign
                                                   data-model initial]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-372
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 1}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (initial {} (transition {:target :s0final}))
        (transition {:event :done.state.s0
                     :cond  (fn [_ d] (= 2 (:Var1 d)))
                     :target :pass})
        (transition {:event :* :target :fail})
        (final {:id :s0final}
          (on-entry {} (assign {:location [:Var1] :expr (fn [_ _] 2)}))
          (on-exit  {} (assign {:location [:Var1] :expr (fn [_ _] 3)}))))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 372 — done.state.<id> fires after final's onentry, before its onexit"
  (assertions
    "reaches pass"
    (runner/passes? chart-372 []) => true))
