(ns com.fulcrologic.statecharts.irp.test-354
  "IRP test 354 — event.data is populated by namelist, param (via content
   merge), and standalone content.

   Restructured from the original txml: both sends occur in s0's on-entry so
   both events are drained together by the runner's initial receive-events!
   call (the original chains sends across eventless transitions, which the
   manually-polled queue cannot drain in a single pass).

   Source: https://www.w3.org/Voice/2013/scxml-irp/354/test354.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry Send
                                                   data-model]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-354
  (chart/statechart {:initial :_root}
    (data-model {:expr {:Var1 1}})
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          ;; Part 1: namelist merges Var1; content merges param1=2
          (Send {:event    :event1
                 :namelist {:Var1 [:Var1]}
                 :content  (fn [_ _] {:param1 2})})
          ;; Part 2: content only sets full event.data
          (Send {:event   :event2
                 :content (fn [_ _] {:inline-val 42})}))
        (transition {:event :event1
                     :cond  (fn [_ d]
                              (and (= 1 (get-in d [:_event :data :Var1]))
                                   (= 2 (get-in d [:_event :data :param1]))))
                     :target :s1})
        (transition {:event :* :target :fail}))
      (state {:id :s1}
        (transition {:event :event2
                     :cond  (fn [_ d] (= {:inline-val 42} (get-in d [:_event :data])))
                     :target :pass})
        (transition {:event :* :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 354 — send event.data via namelist+content and content-only"
  (assertions
    "reaches pass"
    (runner/passes? chart-354 events) => true))
