(ns com.fulcrologic.statecharts.algorithms.v20150901.initialization-spec
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.algorithms.v20150901-impl :as impl]
    [com.fulcrologic.statecharts.algorithms.v20150901.setup :refer [test-env]]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
    [com.fulcrologic.statecharts.elements :refer [data-model initial parallel
                                                  script state transition]]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    [fulcro-spec.core :refer [=> assertions component specification]]
    [taoensso.timbre :as log]))

(specification "Root parallel state"
  (letfn [(run-assertions [m]
            (let [{::sc/keys [processor] :as env} (test-env m)
                  session-id 1
                  wmem       (atom (sp/start! processor env ::m {::sc/session-id session-id}))
                  next!      (fn [evt]
                               (swap! wmem (fn [m]
                                             (log/trace "next" m)
                                             (sp/process-event! processor env m (evts/new-event evt))))
                               (::sc/configuration @wmem))]
              (assertions
                "Enters proper states from initial"
                (::sc/configuration @wmem) => #{:led/off :motor/off :Eq :motor :LED}
                "Transitions on events"
                (next! :start) => #{:led/on :motor/on :Eq :motor :LED}
                "Transitions on additional events"
                (next! :stop) => #{:led/off :motor/off :Eq :motor :LED}
                (next! :start) => #{:led/on :motor/on :Eq :motor :LED})))]
    (component "with an explicit initial states"
      (run-assertions (chart/statechart {}
                        (initial {}
                          (transition {:target :Eq}))
                        (parallel {:id :Eq}
                          (state {:id :motor}
                            (initial {}
                              (transition {:target :motor/off}))
                            (state {:id :motor/off}
                              (transition {:event :start :target :motor/on}))
                            (state {:id :motor/on}
                              (transition {:event :stop :target :motor/off})))
                          (state {:id :LED}
                            (initial {}
                              (transition {:target :led/off}))
                            (state {:id :led/on}
                              (transition {:event :stop :target :led/off}))
                            (state {:id :led/off}
                              (transition {:event :start :target :led/on})))))))
    (component "without an explicit initial states"
      (run-assertions (chart/statechart {}
                        (parallel {:id :Eq}
                          (state {:id :motor}
                            (state {:id :motor/off}
                              (transition {:event :start :target :motor/on}))
                            (state {:id :motor/on}
                              (transition {:event :stop :target :motor/off})))
                          (state {:id :LED}
                            (state {:id :led/off}
                              (transition {:event :start :target :led/on}))
                            (state {:id :led/on}
                              (transition {:event :stop :target :led/off})))))))))

(specification "Nested parallel state"
  (let [m          (chart/statechart {}
                     (state {:id :S0}
                       (transition {:event :trigger :target :S1})
                       (parallel {:id :S0p}
                         (state {:id :s0p/motor})
                         (state {:id :s0p/LED})))
                     (state {:id :S1}
                       (transition {:event :trigger :target :S0})
                       (parallel {:id :S1p}
                         (state {:id :s1p/motor}
                           (transition {:event :remote :target :s0p/LED}))
                         (state {:id :s1p/LED}))))
        {::sc/keys [processor] :as env} (test-env m)
        session-id 1
        wmem       (atom (sp/start! processor env ::m {::sc/session-id session-id}))
        next!      (fn [evt]
                     (swap! wmem (fn [m] (sp/process-event! processor env m (evts/new-event evt))))
                     (::sc/configuration @wmem))]
    (assertions
      "Enters proper states from initial"
      (::sc/configuration @wmem) => #{:S0 :S0p :s0p/motor :s0p/LED}
      "Transitions to correct configuration when swapping parallel state set"
      (next! :trigger) => #{:S1 :S1p :s1p/motor :s1p/LED}
      (next! :trigger) => #{:S0 :S0p :s0p/motor :s0p/LED}
      "Can target a nested node of an alternate parallel set"
      (do
        (next! :trigger)
        (next! :remote)) => #{:S0 :S0p :s0p/motor :s0p/LED})))

(specification ":initial attribute processing"
  (let [m    (chart/statechart {}
               (state {:id :S0}
                 (transition {:event :trigger :target :S1})
                 (parallel {:id :S0p}
                   (state {:id :s0p.1 :initial #{:s0p.1.2}}
                     (state {:id :s0p.1.1})
                     (state {:id :s0p.1.2}))
                   (state {:id :s1p.1}
                     (state {:id :s1p.1.1})
                     (state {:id :s1p.1.2})))))
        {::sc/keys [processor] :as env} (test-env m)
        wmem (atom (sp/start! processor env ::m {::sc/session-id 1}))]
    (assertions
      "Uses the :initial parameter on nested nodes, when provided"
      (::sc/configuration @wmem) => #{:S0 :S0p :s0p.1 :s1p.1.1 :s1p.1 :s0p.1.2}))
  (let [m    (chart/statechart {:initial #{:s0p.1.2 :s1p.1.1}}
               (state {:id :S0}
                 (transition {:event :trigger :target :S1})
                 (parallel {:id :S0p}
                   (state {:id :s0p.1}
                     (state {:id :s0p.1.1})
                     (state {:id :s0p.1.2}))
                   (state {:id :s1p.1}
                     (state {:id :s1p.1.1})
                     (state {:id :s1p.1.2})))))
        {::sc/keys [processor] :as env} (test-env m)
        wmem (atom (sp/start! processor env ::m {::sc/session-id 1}))]
    (assertions
      "Honors the root node initial parameter"
      (::sc/configuration @wmem) => #{:S0 :S0p :s0p.1 :s1p.1.1 :s1p.1 :s0p.1.2})))


(specification "State Machine with a Simple Data Model"
  (let [machine (chart/statechart {}
                  (data-model {:id   :model
                               :expr {:allow? false}})
                  (initial {:id :I} (transition {:id :It :target :A}))
                  (state {:id :A}
                    (transition {:event :toggle
                                 :id    :t1
                                 :type  :internal}
                      (script {:id   :script
                               :expr (fn script* [env data]
                                       [(ops/assign [:ROOT :allow?] true)])}))
                    (transition {:event  :trigger
                                 :id     :t2
                                 :cond   (fn cond* [env {:keys [allow?] :as data}] allow?)
                                 :target :B}))
                  (state {:id :B}
                    (transition {:event  :trigger
                                 :id     :t3
                                 :target :A})))
        {::sc/keys [processor] :as env} (test-env machine)
        wmem    (atom (sp/start! processor env ::m {::sc/session-id 1}))
        next!   (fn [evt]
                  (swap! wmem #(sp/process-event! processor env % (evts/new-event evt)))
                  (::sc/configuration @wmem))
        config  (fn [] (::sc/configuration @wmem))
        data    (fn [context]
                  (let [{::sc/keys [data-model] :as env} (impl/processing-env env ::m @wmem)]
                    (impl/in-state-context env context
                      (sp/current-data data-model env))))]
    (try
      (assertions
        "Saves the data model in working memory"
        (:allow? (:ROOT (::wmdm/data-model @wmem))) => false
        "Sets the initial data model (early binding)"
        (config) => #{:A}
        (:allow? (data :ROOT)) => false)

      (assertions
        "Transition conditions prevent transitions"
        (next! :trigger) => #{:A}
        (:allow? (data :ROOT)) => false)

      (assertions
        "Transition content evolves the data model"
        (next! :toggle) => #{:A}
        (:allow? (data :ROOT)) => true))))

