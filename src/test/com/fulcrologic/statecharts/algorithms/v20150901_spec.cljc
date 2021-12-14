(ns com.fulcrologic.statecharts.algorithms.v20150901-spec
  (:require
    [com.fulcrologic.statecharts.elements :refer [state parallel script
                                                  history final initial
                                                  on-entry on-exit invoke
                                                  data-model transition]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.util :refer [queue]]
    [com.fulcrologic.statecharts.state-machine :as sm]
    [com.fulcrologic.statecharts.algorithms.v20150901-impl :as impl]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.model.simple-model :as model]
    [fulcro-spec.core :refer [specification assertions component behavior =>]]
    [taoensso.timbre :as log]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.events :as evts]))


(defn test-env
  ([machine simple-model]
   (env/new-env machine simple-model simple-model simple-model))
  ([machine]
   (test-env machine (model/new-simple-model (atom {})))))

(defn test-process-event [machine wmem event]
  (let [env (test-env machine)]
    (binding [impl/*exec?* false]
      (impl/process-event env wmem event))))

(defn test-initialize [machine]
  (let [env (test-env machine)]
    (binding [impl/*exec?* false]
      (impl/initialize env))))

(def equipment
  (impl/machine {}
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

(specification "in-final-state?"
  (let [machine (impl/machine {}
                  (state {:id :A}
                    (transition {:event  :trigger
                                 :target :B})
                    (parallel {:id :P}
                      (state {:id :P1}
                        (initial {} :p1e)
                        (final {:id :p1e}))
                      (state {:id :P2}
                        (initial {} :p2e)
                        (final {:id :p2e}))
                      (state {:id :P3 :initial :p3e}
                        (final {:id :p3e}))))
                  (state {:id :B}
                    (transition {:event  :trigger
                                 :target :A})
                    (final {:id :END})
                    (state {:id :C}
                      (state {:id :C1}))
                    (state {:id :D})))
        wmem    (test-initialize machine)
        config  (fn [c] (assoc wmem ::sc/configuration c))]
    (assertions
      "Returns true if the given compound state is in it's final state "
      (impl/in-final-state? machine (config #{}) :A) => false
      (impl/in-final-state? machine (config #{:END}) :B) => true
      (impl/in-final-state? machine (config #{:C1}) :B) => false
      "Returns true if a parallel state has ALL children in a final state"
      (impl/in-final-state? machine (config #{}) :P) => false
      (impl/in-final-state? machine (config #{:p1e :p2e}) :P) => false
      (impl/in-final-state? machine (config #{:p1e :p2e :p3e}) :P) => true)))

(specification "Root parallel state"
  (letfn [(run-assertions [equipment]
            (let [wmem (test-initialize equipment)
                  c    (fn [mem] (::sc/configuration mem))]
              (assertions
                "Enters proper states from initial"
                (c wmem) => #{:led/off :motor/off :Eq :motor :LED}
                "Transitions on events"
                (c (test-process-event equipment wmem :start)) => #{:led/on :motor/on :Eq :motor :LED}
                "Transitions on additional events"
                (c (as-> wmem $
                     (test-process-event equipment $ :start)
                     (test-process-event equipment $ :stop))) => #{:led/off :motor/off :Eq :motor :LED}
                (c (as-> wmem $
                     (test-process-event equipment $ :start)
                     (test-process-event equipment $ :stop)
                     (test-process-event equipment $ :start))) => #{:led/on :motor/on :Eq :motor :LED})))]
    (component "with an explicit initial states"
      (run-assertions (impl/machine {}
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
      (run-assertions (impl/machine {}
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

(specification ":initial attribute processing"
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

#?(:clj
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

