(ns com.fulcrologic.statecharts.irp.test-388
  "IRP test 388 — deep and shallow history correctly recall the last active
   leaf state. Starting in s012, history is captured; transitions through
   s1 (→ deep history → s012 again) and s2 (→ shallow history → s01 →
   s011) verify recall.

   Source: https://www.w3.org/Voice/2013/scxml-irp/388/test388.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [state final transition history
                                                   on-entry script Send raise]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-388
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s012}
      (state {:id :s0 :initial :s01}
        (on-entry {}
          (script {:expr (fn [_ d] (ops/set-map-ops {:Var1 (inc (get d :Var1 0))}))}))
        (transition {:event :entering.s012 :cond (fn [_ d] (= 1 (:Var1 d))) :target :s1}
          (Send {:event :timeout :delay 2000}))
        (transition {:event :entering.s012 :cond (fn [_ d] (= 2 (:Var1 d))) :target :s2})
        (transition {:event :entering.s011 :cond (fn [_ d] (= 3 (:Var1 d))) :target :pass})
        (transition {:event :timeout :target :fail})
        (history {:id :s0HistShallow :type :shallow}
          (transition {:target :s02}))
        (history {:id :s0HistDeep :type :deep}
          (transition {:target :s022}))
        (state {:id :s01 :initial :s011}
          (state {:id :s011}
            (on-entry {} (raise {:event :entering.s011})))
          (state {:id :s012}
            (on-entry {} (raise {:event :entering.s012}))))
        (state {:id :s02 :initial :s021}
          (state {:id :s021}
            (on-entry {} (raise {:event :entering.s021})))
          (state {:id :s022}
            (on-entry {} (raise {:event :entering.s022})))))
      (state {:id :s1}
        (transition {:target :s0HistDeep}))
      (state {:id :s2}
        (transition {:target :s0HistShallow}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 388 — deep and shallow history recall"
  (assertions
    "reaches pass"
    (runner/passes? chart-388 []) => true))
