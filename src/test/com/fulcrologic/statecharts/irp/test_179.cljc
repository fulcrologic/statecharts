(ns com.fulcrologic.statecharts.irp.test-179
  "IRP test 179 — `<content>` on <send> populates the body (data) of the
   delivered event.

   The W3C test puts a raw 123 in <content>, expecting event.data == 123. This
   implementation requires `:content` to return a map (it is `merge`d with
   `:namelist`-derived data), so we instead pack it as `{:value 123}` and
   verify the value round-trips. The intent — that `<content>` populates the
   event body — is preserved.

   Source: https://www.w3.org/Voice/2013/scxml-irp/179/test179.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-179
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (Send {:event :event1 :content (fn [_ _] {:value 123})}))
        (transition {:event :event1
                     :cond  (fn [_ d] (= 123 (get-in d [:_event :data :value])))
                     :target :pass})
        (transition {:event :* :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 179 — <content> populates event body"
  (assertions
    "reaches pass"
    (runner/passes? chart-179 []) => true))
