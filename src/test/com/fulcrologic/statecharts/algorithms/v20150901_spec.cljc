(ns com.fulcrologic.statecharts.algorithms.v20150901-spec
  (:require
    [com.fulcrologic.statecharts.elements :refer [state parallel script
                                                  history final initial
                                                  on-entry on-exit invoke
                                                  data-model transition]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.util :refer [queue]]
    [com.fulcrologic.statecharts.state-machine :as sm]
    [com.fulcrologic.statecharts.algorithms.v20150901 :as impl]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
    [com.fulcrologic.statecharts.execution-model.lambda :as lambda]
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [fulcro-spec.core :refer [specification assertions component behavior =>]]
    [taoensso.timbre :as log]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.events :as evts]))

(defn test-processor [machine]
  (let [data-model  (wmdm/new-model)
        event-queue (mpq/new-queue)
        executor    (lambda/new-execution-model data-model event-queue)
        p           (impl/new-processor machine {:data-model      data-model
                                                 :execution-model executor
                                                 :event-queue     event-queue})]
    p))

(def equipment
  (sm/machine {}
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
          (transition {:event :start :target :led/on}))))))

(specification "Root parallel state"
  (letfn [(run-assertions [equipment]
            (let [processor  (test-processor equipment)
                  session-id 1
                  wmem       (atom (sp/start! processor session-id))
                  next!      (fn [evt]
                               (swap! wmem (fn [m] (sp/process-event! processor m (evts/new-event evt))))
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
      (run-assertions (sm/machine {}
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
      (run-assertions (sm/machine {}
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
#_(specification "Nested parallel state"
    (let [m    (impl/machine {}
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
          wmem (test-initialize m)
          c    (fn [mem] (::sc/configuration mem))]
      (assertions
        "ancestors"
        (impl/get-proper-ancestors m :s0p/LED) => [:S0p :S0 :ROOT]
        "Enters proper states from initial"
        (c wmem) => #{:S0 :S0p :s0p/motor :s0p/LED}
        "Transitions to correct configuration when swapping parallel state set"
        (c (as-> wmem $
             (test-process-event m $ :trigger))) => #{:S1 :S1p :s1p/motor :s1p/LED}
        (c (as-> wmem $
             (test-process-event m $ :trigger)
             (test-process-event m $ :trigger))) => #{:S0 :S0p :s0p/motor :s0p/LED}
        "Can target a nested node of an alternate parallel set"
        (c (as-> wmem $
             (test-process-event m $ :trigger)
             (test-process-event m $ :remote))) => #{:S0 :S0p :s0p/motor :s0p/LED})))

#_#_(specification ":initial attribute processing"
      (let [m    (impl/machine {}
                   (state {:id :S0}
                     (transition {:event :trigger :target :S1})
                     (parallel {:id :S0p}
                       (state {:id :s0p.1 :initial #{:s0p.1.2}}
                         (state {:id :s0p.1.1})
                         (state {:id :s0p.1.2}))
                       (state {:id :s1p.1}
                         (state {:id :s1p.1.1})
                         (state {:id :s1p.1.2})))))
            wmem (test-initialize m)
            c    (fn [mem] (::sc/configuration mem))]
        (assertions
          "Uses the :initial parameter on nested nodes, when provided"
          (c wmem) => #{:S0 :S0p :s0p.1 :s1p.1.1 :s1p.1 :s0p.1.2}))
      (let [m    (impl/machine {:initial #{:s0p.1.2 :s1p.1.1}}
                   (state {:id :S0}
                     (transition {:event :trigger :target :S1})
                     (parallel {:id :S0p}
                       (state {:id :s0p.1}
                         (state {:id :s0p.1.1})
                         (state {:id :s0p.1.2}))
                       (state {:id :s1p.1}
                         (state {:id :s1p.1.1})
                         (state {:id :s1p.1.2})))))
            wmem (test-initialize m)
            c    (fn [mem] (::sc/configuration mem))]
        (assertions
          "Honors the root node initial parameter"
          (c wmem) => #{:S0 :S0p :s0p.1 :s1p.1.1 :s1p.1 :s0p.1.2})))

    (specification "Basic machine"
      (let [machine (impl/machine {}
                      (state {:id :A}
                        (transition {:event  :trigger
                                     :target :B}))
                      (state {:id :B}
                        (transition {:event  :trigger
                                     :target :A})))
            wmem    (test-initialize machine)
            c       (fn [mem] (::sc/configuration mem))]
        (assertions
          "Enters the first state if there is no initial"
          (c wmem) => #{:A}
          "Transitions on events"
          (c (test-process-event machine wmem :trigger)) => #{:B}
          (c (as-> wmem $
               (test-process-event machine $ :trigger)
               (test-process-event machine $ :trigger))) => #{:A})))

#_#?(:clj
     (specification "State Machine with a Simple Data Model" :focus
       (let [machine   (impl/machine {}
                         (data-model {:id   :model
                                      :expr {:allow? false}})
                         (initial {:id :I} (transition {:id :It :target :A}))
                         (state {:id :A}
                           (transition {:event :toggle
                                        :id    :t1
                                        :type  :internal}
                             (script {:id   :script
                                      :expr (fn script* [env data]
                                              (log/info "script")
                                              (model/replacement-data (update data :allow? not)))}))
                           (transition {:event  :trigger
                                        :id     :t2
                                        :cond   (fn cond* [env {:keys [allow?] :as data}]
                                                  (log/info "cond" data)
                                                  (log/spy :info allow?))
                                        :target :B}))
                         (state {:id :B}
                           (transition {:event  :trigger
                                        :id     :t3
                                        :target :A})))
             wmem-atom (atom {})
             M         (model/run-event-loop! machine wmem-atom)
             base-env  (env/new-env machine M M M)
             config    (fn [] (::sc/configuration @wmem-atom))
             data      (fn [] (dissoc (sp/current-data M base-env) :_x))]
         (Thread/sleep 5)
         (try
           (assertions
             "Sets the initial data model (early binding)"
             (config) => #{:A}
             (data) => {:allow? false})

           (sp/send! M base-env {:event (evts/new-event :trigger)})
           (Thread/sleep 5)

           (assertions
             "Transition conditions prevent transitions"
             (config) => #{:A}
             (data) => {:allow? false})

           (sp/send! M base-env {:event (evts/new-event :toggle)})
           (Thread/sleep 100)

           (assertions
             "Transition content evolves the data model"
             (config) => #{:A}
             (data) => {:allow? true})
           (Thread/sleep 100)

           (finally
             (sp/send! M base-env {:event evts/cancel-event}))))))

