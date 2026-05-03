(ns com.fulcrologic.statecharts.irp.test-205
  "IRP test 205 — `<send>` must not mutate the message contents. The event
   includes a parameter `aParam=1`; on receipt we copy `_event.data.aParam`
   into Var1 and verify it still equals 1.

   Source: https://www.w3.org/Voice/2013/scxml-irp/205/test205.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send assign
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-205
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 nil}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (Send {:event :event1
                 :content (fn [_ _] {:aParam 1})})
          (Send {:event :timeout}))
        (transition {:event :event1 :target :s1}
          (assign {:location [:Var1]
                   :expr (fn [{:keys [_event] :as env} d]
                           (or (get-in d [:_event :data :aParam])
                               (get-in env [:_event :data :aParam])))}))
        (transition {:event :* :target :fail}))
      (state {:id :s1}
        (transition {:cond (fn [_ d] (= 1 (:Var1 d))) :target :pass})
        (transition {:target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 205 — send param round-trips through _event.data"
  (assertions
    "reaches pass"
    (runner/passes? chart-205 []) => true))
