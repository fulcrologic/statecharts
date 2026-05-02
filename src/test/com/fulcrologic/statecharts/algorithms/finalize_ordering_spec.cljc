(ns com.fulcrologic.statecharts.algorithms.finalize-ordering-spec
  "W3C SCXML §5.3.1: <finalize> must run BEFORE selectTransitions for an event
   received from an invoked process. The data-model side effects of finalize
   must be visible to that event's transition guards."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [assign final finalize invoke
                                                   script state transition]]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.simple :as simple]
    [fulcro-spec.core :refer [=> assertions specification behavior]]))

(def trivial-child
  (chart/statechart {}
    (state {:id :c/wait}
      (transition {:event :never :target :c/done}))
    (final {:id :c/done})))

(def parent-with-finalize
  (chart/statechart {}
    (state {:id :p/active}
      (invoke {:id   :child-1
               :type :statechart
               :src  ::trivial-child}
        (finalize {}
          (assign {:location [:captured]
                   :expr     (fn [_env {:keys [_event]}]
                               (get-in _event [:data :val]))})))
      (transition {:event :ack
                   :cond  (fn [_env data] (= 42 (:captured data)))
                   :target :p/done}))
    (state {:id :p/done})))

(specification "Finalize runs before transition selection (W3C SCXML §5.3.1)"
  (behavior "a transition guard on an invocation event sees data written by <finalize>"
    (let [{::sc/keys [processor working-memory-store] :as env} (simple/simple-env)
          session-id :parent-fin-test]
      (simple/register! env ::trivial-child trivial-child)
      (simple/register! env ::parent parent-with-finalize)
      (simple/start! env ::parent session-id)

      (let [wmem  (sp/get-working-memory working-memory-store env session-id)
            ;; External event arriving from the active invocation.
            evt   (evts/new-event {:name     :ack
                                   :invokeid :child-1
                                   :data     {:val 42}})
            wmem2 (sp/process-event! processor env wmem evt)]
        (sp/save-working-memory! working-memory-store env session-id wmem2)

        (assertions
          "guard saw the value finalize set, transition fired"
          (contains? (::sc/configuration wmem2) :p/done) => true
          "we left the source state"
          (contains? (::sc/configuration wmem2) :p/active) => false)))))
