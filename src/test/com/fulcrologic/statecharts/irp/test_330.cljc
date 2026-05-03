(ns com.fulcrologic.statecharts.irp.test-330
  "IRP test 330 — required fields (name, type, data) are present in both
   internal (raise) and external (send) events.

   Note: The W3C test also checks sendid/origin/origintype/invokeid — fields
   this library does not populate. We check the subset this library does
   provide (name, type, data).

   Source: https://www.w3.org/Voice/2013/scxml-irp/330/test330.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition
                                                   on-entry raise Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(defn- event-fields-bound? [_ d]
  (let [e (get d :_event)]
    (and (some? e)
         (some? (:name e))
         (some? (:type e))
         (contains? e :data))))

(def chart-330
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (raise {:event :foo}))
        (transition {:event :foo :cond event-fields-bound? :target :s1})
        (transition {:event :* :target :fail}))
      (state {:id :s1}
        (on-entry {}
          (Send {:event :foo}))
        (transition {:event :foo :cond event-fields-bound? :target :pass})
        (transition {:event :* :target :fail}))
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 330 — required event fields (name, type, data) are present"
  (assertions
    "reaches pass"
    (runner/passes? chart-330 events) => true))
