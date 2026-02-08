(ns com.fulcrologic.statecharts.integration.fulcro-operations-spec
  "Tests for Fulcro integration operations:
   - fop/load
   - fop/invoke-remote
   - fop/assoc-alias
   - fop/apply-action
   - fop/set-actor"
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.guardrails.malli.fulcro-spec-helpers :as gsh]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [on-entry script state transition]]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.operations :as fop]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.util :refer [new-uuid]]
    [fulcro-spec.core :refer [=> assertions behavior component specification]]))

;; Test components
(defsc Item [this props]
  {:query         [:item/id :item/label :item/value]
   :ident         :item/id
   :initial-state {:item/id 1 :item/label "Default" :item/value 0}})

(defsc OtherItem [this props]
  {:query [:other-item/id :other-item/name]
   :ident :other-item/id})

(defsc ItemList [this props]
  {:query         [:list/id {:list/items (comp/get-query Item)}]
   :ident         :list/id
   :initial-state {:list/id 1 :list/items []}})

(defsc TestRoot [this props]
  {:query         [:ui/mode {:item-list (comp/get-query ItemList)}]
   :initial-state {:ui/mode :viewing :item-list {}}})

(defn test-app []
  (let [a (app/fulcro-app)]
    (app/set-root! a TestRoot {:initialize-state? true})
    (scf/install-fulcro-statecharts! a {:event-loop? false})
    a))

(specification {:covers {`fop/assoc-alias "PLACEHOLDER"}} "assoc-alias"
  (let [app        (test-app)
        state-atom (::app/state-atom app)
        chart      (chart/statechart {:initial :active}
                     (state {:id :active}
                       (on-entry {}
                         (script {:expr (fn [_ _ _ _]
                                          [(fop/assoc-alias :name "Bob" :age 25)])}))))]

    (scf/register-statechart! app ::test-chart chart)

    (behavior "assigns values via aliases"
      (let [session-id (scf/start! app {:machine    ::test-chart
                                        :data       {:fulcro/aliases {:name [:fulcro/state :person/name]
                                                                      :age  [:fulcro/state :person/age]}}})]
        ;; Process entry actions
        (scf/process-events! app)

        (assertions
          "name alias writes to state"
          (get-in @state-atom [:person/name]) => "Bob"
          "age alias writes to state"
          (get-in @state-atom [:person/age]) => 25)))

    (behavior "works with actor aliases"
      (let [chart2     (chart/statechart {:initial :active}
                         (state {:id :active}
                           (on-entry {}
                             (script {:expr (fn [_ _ _ _]
                                              [(fop/assoc-alias :item-label "Updated Label")])}))))
            session-id (do
                         (scf/register-statechart! app ::test-chart2 chart2)
                         (scf/start! app {:machine    ::test-chart2
                                          :data       {:fulcro/aliases {:item-label [:actor/item :item/label]}
                                                       :fulcro/actors  {:actor/item (scf/actor Item [:item/id 1])}}}))]
        (scf/process-events! app)

        (assertions
          "writes to actor field"
          (get-in @state-atom [:item/id 1 :item/label]) => "Updated Label")))))

(specification {:covers {`fop/apply-action "PLACEHOLDER"}} "apply-action"
  (let [app        (test-app)
        state-atom (::app/state-atom app)
        chart      (chart/statechart {:initial :active}
                     (state {:id :active}
                       (on-entry {}
                         (script {:expr (fn [_ _ _ _]
                                          [(fop/apply-action
                                             (fn [state-map x y]
                                               (assoc state-map :computed/sum (+ x y)))
                                             10 20)])}))))]

    (scf/register-statechart! app ::test-chart chart)

    (behavior "applies function to Fulcro state map"
      (let [session-id (scf/start! app {:machine ::test-chart})]
        (scf/process-events! app)

        (assertions
          "function was applied"
          (get-in @state-atom [:computed/sum]) => 30)))

    (behavior "function receives args correctly"
      (let [chart2     (chart/statechart {:initial :active}
                         (state {:id :active}
                           (on-entry {}
                             (script {:expr (fn [_ _ _ _]
                                              [(fop/apply-action
                                                 (fn [state-map & args]
                                                   (assoc state-map :collected/args (vec args)))
                                                 "a" "b" "c")])}))))
            session-id (do
                         (scf/register-statechart! app ::test-chart2 chart2)
                         (scf/start! app {:machine ::test-chart2}))]
        (scf/process-events! app)

        (assertions
          "all args are collected"
          (get-in @state-atom [:collected/args]) => ["a" "b" "c"])))))

(specification {:covers {`fop/set-actor "PLACEHOLDER"}} "set-actor"
  (let [app        (test-app)
        state-atom (::app/state-atom app)]

    (behavior "sets actor with both class and ident"
      (let [chart      (chart/statechart {:initial :active}
                         (state {:id :active}
                           (on-entry {}
                             (script {:expr (fn [_ data _ _]
                                              [(fop/set-actor data :actor/item {:class Item
                                                                                :ident [:item/id 42]})])}))))
            session-id (do
                         (scf/register-statechart! app ::test-chart chart)
                         (scf/start! app {:machine ::test-chart}))]
        (scf/process-events! app)

        (let [local-data (get-in @state-atom (scf/local-data-path session-id))]
          (assertions
            "actor is set"
            (scf/resolve-actor-class local-data :actor/item) => Item
            "ident is correct"
            (get-in local-data [:fulcro/actors :actor/item :ident]) => [:item/id 42]))))

    (behavior "changes actor ident only"
      (let [chart      (chart/statechart {:initial :init}
                         (state {:id :init}
                           (transition {:event :change-ident :target :changed}
                             (script {:expr (fn [_ data _ _]
                                              [(fop/set-actor data :actor/item {:ident [:item/id 99]})])})))
                         (state {:id :changed}))
            session-id (do
                         (scf/register-statechart! app ::test-chart2 chart)
                         (scf/start! app {:machine    ::test-chart2
                                          :data       {:fulcro/actors {:actor/item (scf/actor Item [:item/id 1])}}}))]
        (scf/send! app session-id :change-ident)
        (scf/process-events! app)

        (let [local-data (get-in @state-atom (scf/local-data-path session-id))]
          (assertions
            "class unchanged"
            (scf/resolve-actor-class local-data :actor/item) => Item
            "ident updated"
            (get-in local-data [:fulcro/actors :actor/item :ident]) => [:item/id 99]))))

    (behavior "changes actor class only"
      (let [chart      (chart/statechart {:initial :init}
                         (state {:id :init}
                           (transition {:event :change-class :target :changed}
                             (script {:expr (fn [_ data _ _]
                                              [(fop/set-actor data :actor/item {:class OtherItem})])})))
                         (state {:id :changed}))
            session-id (do
                         (scf/register-statechart! app ::test-chart3 chart)
                         (scf/start! app {:machine    ::test-chart3
                                          :data       {:fulcro/actors {:actor/item (scf/actor Item [:item/id 1])}}}))]
        (scf/send! app session-id :change-class)
        (scf/process-events! app)

        (let [local-data (get-in @state-atom (scf/local-data-path session-id))]
          (assertions
            "class changed"
            (scf/resolve-actor-class local-data :actor/item) => OtherItem))))))

(specification {:covers {`fop/load "PLACEHOLDER"}} "load"
  (let [app        (test-app)
        state-atom (::app/state-atom app)]

    (behavior "creates load operation with query-root and component"
      (let [load-op (fop/load :all-items Item {})]
        (assertions
          "has :fulcro/load op"
          (:op load-op) => :fulcro/load
          "has query-root"
          (:query-root load-op) => :all-items
          "has component"
          (:component-or-actor load-op) => Item
          "has options"
          (map? (:options load-op)) => true)))

    (behavior "accepts actor keyword instead of component"
      (let [load-op (fop/load :all-items :actor/item {})]
        (assertions
          "actor keyword stored"
          (:component-or-actor load-op) => :actor/item)))

    (behavior "passes through options"
      (let [load-op (fop/load :all-items Item {:marker     :loading
                                                :params     {:filter "active"}
                                                ::sc/target-alias :items-list})]
        (assertions
          "marker option preserved"
          (get-in load-op [:options :marker]) => :loading
          "params option preserved"
          (get-in load-op [:options :params]) => {:filter "active"}
          "statechart options preserved"
          (get-in load-op [:options ::sc/target-alias]) => :items-list)))

    (behavior "execution triggers ok-event on success"
      (let [events-received (atom [])
            chart           (chart/statechart {:initial :loading}
                              (state {:id :loading}
                                (on-entry {}
                                  (script {:expr (fn [_ _ _ _]
                                                   [(fop/load :test-items Item {::sc/ok-event :load-success})])}))
                                (transition {:event :load-success :target :loaded}))
                              (state {:id :loaded}
                                (on-entry {}
                                  (script {:expr (fn [_ _ _ _] (swap! events-received conj :loaded) [])}))))
            session-id      (do
                              (scf/register-statechart! app ::load-chart chart)
                              (scf/start! app {:machine ::load-chart}))]
        ;; Simulate load completion by directly triggering the event
        ;; (In real usage, the load operation would complete and trigger this)
        (scf/process-events! app)
        (scf/send! app session-id :load-success)
        (scf/process-events! app)

        (assertions
          "transitions to loaded state"
          (scf/current-configuration app session-id) => #{:loaded}
          "entry action executed"
          @events-received => [:loaded])))))

(specification {:covers {`fop/invoke-remote "PLACEHOLDER"}} "invoke-remote"
  (let [app (test-app)]

    (behavior "creates invoke-remote operation"
      (let [remote-op (fop/invoke-remote `[(some-mutation {:param 1})] {})]
        (assertions
          "has :fulcro/invoke-remote op"
          (:op remote-op) => :fulcro/invoke-remote
          "has txn"
          (:txn remote-op) => `[(some-mutation {:param 1})])))

    (behavior "accepts target option"
      (let [remote-op (fop/invoke-remote `[(test-mutation {})] {:target [:list/id 1 :list/items]})]
        (assertions
          "target preserved"
          (:target remote-op) => [:list/id 1 :list/items])))

    (behavior "accepts returning option with component"
      (let [remote-op (fop/invoke-remote `[(create-item {})] {:returning Item})]
        (assertions
          "returning preserved"
          (:returning remote-op) => Item)))

    (behavior "accepts returning option with actor keyword"
      (let [remote-op (fop/invoke-remote `[(update-item {})] {:returning :actor/item})]
        (assertions
          "actor keyword preserved"
          (:returning remote-op) => :actor/item)))

    (behavior "accepts ok-event and error-event"
      (let [remote-op (fop/invoke-remote `[(save-data {})] {:ok-event    :save-success
                                                             :error-event :save-failed
                                                             :ok-data     {:status :ok}
                                                             :error-data  {:reason "network"}})]
        (assertions
          "ok-event preserved"
          (:ok-event remote-op) => :save-success
          "error-event preserved"
          (:error-event remote-op) => :save-failed
          "ok-data preserved"
          (:ok-data remote-op) => {:status :ok}
          "error-data preserved"
          (:error-data remote-op) => {:reason "network"})))

    (behavior "accepts mutation-remote option"
      (let [remote-op (fop/invoke-remote `[(sync-data {})] {:mutation-remote :sync-remote})]
        (assertions
          "mutation-remote preserved"
          (:mutation-remote remote-op) => :sync-remote)))

    (behavior "accepts tx-options"
      (let [remote-op (fop/invoke-remote `[(test-mutation {})] {:tx-options {:abort-id :test-abort}})]
        (assertions
          "tx-options preserved"
          (:tx-options remote-op) => {:abort-id :test-abort})))))

(specification "Combined operations in statechart"
  (let [app        (test-app)
        state-atom (::app/state-atom app)]

    (behavior "multiple operations can be combined"
      (let [chart      (chart/statechart {:initial :init}
                         (state {:id :init}
                           (on-entry {}
                             (script {:expr (fn [_ _ _ _]
                                              [(fop/assoc-alias :mode "editing")
                                               (fop/apply-action (fn [s] (assoc s :updated true)))
                                               (ops/assign [:local/value] 42)])}))))
            session-id (do
                         (scf/register-statechart! app ::combined-chart chart)
                         (scf/start! app {:machine    ::combined-chart
                                          :data       {:fulcro/aliases {:mode [:fulcro/state :ui/mode]}}}))]
        (scf/process-events! app)

        (assertions
          "alias operation executed"
          (get-in @state-atom [:ui/mode]) => "editing"
          "apply-action executed"
          (get-in @state-atom [:updated]) => true
          "local assign executed"
          (get-in @state-atom (scf/local-data-path session-id :local/value)) => 42)))))

(specification "Operation integration with actors and aliases"
  (let [app        (test-app)
        state-atom (::app/state-atom app)]

    (behavior "operations can use actor paths"
      (let [chart      (chart/statechart {:initial :active}
                         (state {:id :active}
                           (on-entry {}
                             (script {:expr (fn [_ _ _ _]
                                              [(ops/assign [:actor/item :item/value] 999)])}))))
            session-id (do
                         (scf/register-statechart! app ::actor-ops-chart chart)
                         (scf/start! app {:machine    ::actor-ops-chart
                                          :data       {:fulcro/actors {:actor/item (scf/actor Item [:item/id 1])}}}))]
        (scf/process-events! app)

        (assertions
          "writes to actor's ident path"
          (get-in @state-atom [:item/id 1 :item/value]) => 999)))

    (behavior "operations can read and write via aliases"
      (let [chart      (chart/statechart {:initial :active}
                         (state {:id :active}
                           (on-entry {}
                             (script {:expr (fn [env data _ _]
                                              (let [aliases (scf/resolve-aliases data)]
                                                [(fop/assoc-alias :label (str "Updated: " (:label aliases)))]))}))))
            session-id (do
                         (scf/register-statechart! app ::alias-ops-chart chart)
                         (scf/start! app {:machine    ::alias-ops-chart
                                          :data       {:fulcro/aliases {:label [:actor/item :item/label]}
                                                       :fulcro/actors  {:actor/item (scf/actor Item [:item/id 1])}}}))]
        (scf/process-events! app)

        (assertions
          "alias read and write work together"
          (get-in @state-atom [:item/id 1 :item/label]) => "Updated: Default")))))
