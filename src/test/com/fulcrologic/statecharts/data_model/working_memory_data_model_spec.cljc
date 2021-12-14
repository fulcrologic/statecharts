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
        context  (fn [c] (assoc mock-env ::sc/context-element-id c))]

    (component "transact!"
      (sp/transact! DM (context :A/a) {:txn [(ops/assign :x 1)]})
      (sp/transact! DM (context :A) {:txn [(ops/assign :y 9)]})

      (assertions
        "Places values into the correct context in working memory"
        (::wmdm/data-model @vwmem) => {:A/a {:x 1}
                                       :A   {:y 9}})

      (vswap! vwmem dissoc ::wmdm/data-model)

      (sp/transact! DM (context :ROOT) {:txn [(ops/assign :x 1)]})
      (sp/transact! DM (context :ROOT) {:txn [(ops/assign :y 2)]})
      (sp/transact! DM (context :A/a) {:txn [(ops/assign :y 42)]})
      (sp/transact! DM (context :A/a) {:txn [(ops/assign [:ROOT :z] 3)]})
      (sp/transact! DM (context :A) {:txn [(ops/assign :z 9)]})

      (assertions
        "Multiple assigns to context"
        (::wmdm/data-model @vwmem) => {:ROOT {:x 1 :y 2 :z 3}
                                       :A/a  {:y 42}
                                       :A    {:z 9}}))

    (component "get-at"
      (assertions
        "shadows in the correct order"
        (sp/get-at DM (context :ROOT) :x) => 1
        (sp/get-at DM (context :A) :x) => 1
        (sp/get-at DM (context :A/a) :x) => 1)

      (assertions
        "searches until a value is found"
        (sp/get-at DM (context :A/a) :y) => 42
        (sp/get-at DM (context :A) :y) => 2
        (sp/get-at DM (context :ROOT) :y) => 2))

    (component "transacting delete"
      (sp/transact! DM (context :ROOT) {:txn [(ops/delete [:ROOT :z])]})

      (assertions
        "removes the value"
        (sp/get-at DM (context :ROOT) :z) => nil))))
