(ns com.fulcrologic.statecharts.data-model.working-memory-data-model-spec
  (:require
    [com.fulcrologic.statecharts.elements :refer [state parallel script
                                                  history final initial
                                                  on-entry on-exit invoke
                                                  data-model transition]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.util :refer [queue]]
    [com.fulcrologic.statecharts.state-machine :as sm]
    [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [fulcro-spec.core :refer [specification assertions component behavior =>]]
    [com.fulcrologic.statecharts.protocols :as sp]))

(specification "Working memory model"
  (let [machine  (sm/machine {}
                   (state {:id :A}
                     (state {:id :A/a})))
        DM       (wmdm/new-model)
        vwmem    (volatile! {})
        mock-env {::sc/machine         machine
                  ::sc/data-model      DM
                  ::sc/execution-model (reify sp/ExecutionModel)
                  ::sc/event-queue     (reify sp/EventQueue)
                  ::sc/vwmem           vwmem}
        context  (fn [c] (assoc mock-env ::sc/context-element-id c))
        data     (fn [path] (get-in @vwmem (into [::wmdm/data-model] path)))]

    (component "transact!"
      (sp/transact! DM (context :A/a) {:txn (ops/set-map-txn {:x 1})})

      (assertions
        "Places values into the correct context in working memory"
        (data [:A/a]) => {:x 1}
        (data [:A]) => nil
        (data [:ROOT]) => nil))

    (vreset! vwmem {})
    (sp/transact! DM (context :A/a) {:txn (ops/set-map-txn {:x 1})})
    (sp/transact! DM (context :A) {:txn (ops/set-map-txn {:y 1 :x 2})})
    (sp/transact! DM (context :ROOT) {:txn (ops/set-map-txn {:z 1 :x 3})})

    (component "get-at"
      (assertions
        "shadows in the correct order"
        (sp/get-at DM (context :ROOT) :x) => 3
        (sp/get-at DM (context :A) :x) => 2
        (sp/get-at DM (context :A/a) :x) => 1)

      (assertions
        "searches until a value is found"
        (sp/get-at DM (context :A/a) :y) => 1
        (sp/get-at DM (context :A) :y) => 1
        (sp/get-at DM (context :ROOT) :y) => nil))

    (component "transacting delete"
      (sp/transact! DM (context :ROOT) {:txn [(ops/delete [:ROOT :z])]})

      (assertions
        "removes the value"
        (sp/get-at DM (context :ROOT) :z) => nil))))
