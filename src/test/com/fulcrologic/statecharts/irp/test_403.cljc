(ns com.fulcrologic.statecharts.irp.test-403
  "IRP test 403 (file `test403a.txml`) — optimal enablement: of all enabled
   transitions the processor selects ones in child states over parent
   states, and uses document order to break ties. s01 tests child-wins
   (event1 in s01 transitions to s02 even though s0 has an event1 → fail);
   s02 tests parent-wins (s02's local event2 has cond=false, so the
   parent's event2 → pass transition is selected).

   Note: the W3C distributes this test as `test403a.txml` (and there are
   sibling files 403b/403c testing related parts of optimal enablement).
   `test403.txml` itself is a 404; the manifest's automated entries are
   the lettered variants. Source:
   https://www.w3.org/Voice/2013/scxml-irp/403/test403a.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition on-entry raise]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-403
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0 :initial :s01}
        (transition {:event :event1 :target :fail})
        (transition {:event :event2 :target :pass})
        (state {:id :s01}
          (on-entry {} (raise {:event :event1}))
          (transition {:event :event1 :target :s02})
          (transition {:event :*      :target :fail}))
        (state {:id :s02}
          (on-entry {} (raise {:event :event2}))
          (transition {:event :event1 :target :fail})
          (transition {:event :event2 :cond (fn [_ _] false) :target :fail})))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 403 — child transitions preferred over parent; doc-order ties"
  (assertions
    "reaches pass"
    (runner/passes? chart-403 []) => true))
