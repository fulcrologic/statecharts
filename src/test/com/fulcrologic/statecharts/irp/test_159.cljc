(ns com.fulcrologic.statecharts.irp.test-159
  "IRP test 159 — verifies that an error raised by an executable-content element
   causes all subsequent elements in the same block to be skipped. The <send>
   uses an illegal target so it should fail; the following increment of Var1
   must therefore be skipped, leaving Var1 = 0.

   Source: https://www.w3.org/Voice/2013/scxml-irp/159/test159.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry script Send
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-159
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 0}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (Send {:event :thisWillFail
                 :type  "x-unsupported-target-type"
                 :target "#_nonexistent"})
          (script {:expr (fn [_ d] (update d :Var1 (fnil inc 0)))}))
        (transition {:cond (fn [_ d] (= 1 (:Var1 d))) :target :fail})
        (transition {:target :pass}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 159 — error in executable content skips subsequent elements"
  (assertions
    "reaches pass"
    (runner/passes? chart-159 []) => true))
