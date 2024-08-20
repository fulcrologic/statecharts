(ns com.fulcrologic.statecharts.data-model.working-memory-data-model-spec
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
    [com.fulcrologic.statecharts.elements :refer [state]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(specification "Working memory model"
  (let [machine  (chart/statechart {}
                   (state {:id :A}
                     (state {:id :A/a})))
        DM       (wmdm/new-model)
        vwmem    (volatile! {})
        mock-env {::sc/statechart      machine
                  ::sc/data-model      DM
                  ::sc/execution-model (reify sp/ExecutionModel)
                  ::sc/event-queue     (reify sp/EventQueue)
                  ::sc/vwmem           vwmem}
        context  (fn [c] (assoc mock-env ::sc/context-element-id c))]

    (component "update!"
      (sp/update! DM (context :A/a) {:ops [(ops/assign :x 1)]})
      (sp/update! DM (context :A) {:ops [(ops/assign :y 9)]})

      (assertions
        "Places values into the correct context in working memory"
        (::wmdm/data-model @vwmem) => {:A/a {:x 1}
                                       :A   {:y 9}})

      (vswap! vwmem dissoc ::wmdm/data-model)

      (sp/update! DM (context :ROOT) {:ops [(ops/assign :x 1)]})
      (sp/update! DM (context :ROOT) {:ops [(ops/assign :y 2)]})
      (sp/update! DM (context :A/a) {:ops [(ops/assign :y 42)]})
      (sp/update! DM (context :A/a) {:ops [(ops/assign [:ROOT :z] 3)]})
      (sp/update! DM (context :A) {:ops [(ops/assign :z 9)]})

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

    (component "run a delete"
      (sp/update! DM (context :ROOT) {:ops [(ops/delete [:ROOT :z])]})

      (assertions
        "removes the value"
        (sp/get-at DM (context :ROOT) :z) => nil))))

(specification "Flat Working memory model" :focus
  (let [machine  (chart/statechart {}
                   (state {:id :A}
                     (state {:id :A/a})))
        DM       (wmdm/new-flat-model)
        vwmem    (volatile! {})
        mock-env {::sc/statechart      machine
                  ::sc/data-model      DM
                  ::sc/execution-model (reify sp/ExecutionModel)
                  ::sc/event-queue     (reify sp/EventQueue)
                  ::sc/vwmem           vwmem}
        context  (fn [c] (assoc mock-env ::sc/context-element-id c))]

    (component "update!"
      (sp/update! DM (context :A/a) {:ops [(ops/assign :x 1)]})
      (sp/update! DM (context :A) {:ops [(ops/assign :y 9)]})

      (assertions
        "Places values into the root of the data model"
        (::wmdm/data-model @vwmem) => {:x 1
                                       :y 9})

      (vswap! vwmem dissoc ::wmdm/data-model)

      (sp/update! DM (context :ROOT) {:ops [(ops/assign :x 1)]})
      (sp/update! DM (context :ROOT) {:ops [(ops/assign :y 2)]})
      (sp/update! DM (context :A/a) {:ops [(ops/assign :y 42)]})
      (sp/update! DM (context :A/a) {:ops [(ops/assign [:a :b :c] 99)]})
      (sp/update! DM (context :A/a) {:ops [(ops/assign [:a :c] 100)]})
      (sp/update! DM (context :A/a) {:ops [(ops/assign [:ROOT :z] 3)]})

      (assertions
        "Assignments are merged into the root, but paths work as assoc-in"
        (dissoc (::wmdm/data-model @vwmem) :z) => {:x 1 :y 42 :a {:b {:c 99}
                                                                  :c 100}}
        "Special root paths are properly processed"
        (select-keys (::wmdm/data-model @vwmem) [:z]) => {:z 3}))

    (component "get-at"
      (assertions
        "Always looks relative to root"
        (sp/get-at DM (context :A/a) :y) => 42
        (sp/get-at DM (context :A) :y) => 42
        (sp/get-at DM (context :ROOT) :y) => 42))

    (component "run a delete"
      (sp/update! DM (context :ROOT) {:ops [(ops/delete [:ROOT :z])]})
      (sp/update! DM (context :ROOT) {:ops [(ops/delete [:a :b])]})

      (assertions
        "removes the value"
        (sp/get-at DM (context :ROOT) [:a :b]) => nil
        (sp/get-at DM (context :ROOT) :z) => nil
        "Leaves surrounding data"
        (sp/get-at DM (context :ROOT) [:a :c]) => 100))))
