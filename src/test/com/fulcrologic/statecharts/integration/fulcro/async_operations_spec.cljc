(ns com.fulcrologic.statecharts.integration.fulcro.async-operations-spec
  "Tests for async-aware Fulcro operations:
   - afop/load and afop/invoke-remote (data-map constructors)
   - afop/await-load and afop/await-mutation (promise helpers)
   - :async? option for install-fulcro-statecharts!"
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [on-entry script state transition final]]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.async-operations :as afop]
    [com.fulcrologic.statecharts.integration.fulcro.operations :as fop]
    [fulcro-spec.core :refer [=> assertions behavior component specification]]))

;; Test components
(defsc Item [this props]
  {:query         [:item/id :item/label :item/value]
   :ident         :item/id
   :initial-state {:item/id 1 :item/label "Default" :item/value 0}})

(defsc TestRoot [this props]
  {:query         [:ui/mode]
   :initial-state {:ui/mode :viewing}})

(defn async-test-app []
  (let [a (app/fulcro-app)]
    (app/set-root! a TestRoot {:initialize-state? true})
    (scf/install-fulcro-statecharts! a {:event-loop? false :async? true})
    a))

(defn sync-test-app []
  (let [a (app/fulcro-app)]
    (app/set-root! a TestRoot {:initialize-state? true})
    (scf/install-fulcro-statecharts! a {:event-loop? false})
    a))

(specification {:covers {`afop/load "data-map constructor"}} "afop/load data-map constructor"
  (behavior "returns an async operation map"
    (let [op (afop/load :items Item {::sc/ok-event :loaded})]
      (assertions
        "has :fulcro/async-load op key"
        (:op op) => :fulcro/async-load
        "is marked async"
        (::sc/async? op) => true
        "preserves query-root"
        (:query-root op) => :items
        "preserves component"
        (:component-or-actor op) => Item
        "preserves options"
        (get-in op [:options ::sc/ok-event]) => :loaded)))

  (behavior "works with actor keywords"
    (let [op (afop/load :all-things :actor/thing {::sc/ok-event :done})]
      (assertions
        "actor keyword preserved"
        (:component-or-actor op) => :actor/thing)))

  (behavior "passes through extra options"
    (let [op (afop/load :items Item {::sc/ok-event :loaded :marker :loading :remote :rest})]
      (assertions
        "extra options in :options map"
        (get-in op [:options :marker]) => :loading
        (get-in op [:options :remote]) => :rest))))

(specification {:covers {`afop/invoke-remote "data-map constructor"}} "afop/invoke-remote data-map constructor"
  (behavior "returns an async operation map"
    (let [op (afop/invoke-remote `[(save-item {})] {:ok-event :saved})]
      (assertions
        "has :fulcro/async-mutation op key"
        (:op op) => :fulcro/async-mutation
        "is marked async"
        (::sc/async? op) => true
        "has txn"
        (some? (:txn op)) => true
        "preserves ok-event"
        (:ok-event op) => :saved)))

  (behavior "passes through all options"
    (let [op (afop/invoke-remote `[(do-thing {})]
               {:ok-event :done :error-event :failed :target [:table 1] :mutation-remote :rest})]
      (assertions
        "has error-event"
        (:error-event op) => :failed
        "has target"
        (:target op) => [:table 1]
        "has mutation-remote"
        (:mutation-remote op) => :rest)))

  (behavior "mirrors fop/invoke-remote signature"
    (let [sync-op  (fop/invoke-remote `[(save-data {})] {:ok-event :saved})
          async-op (afop/invoke-remote `[(save-data {})] {:ok-event :saved})]
      (assertions
        "sync has :fulcro/invoke-remote"
        (:op sync-op) => :fulcro/invoke-remote
        "async has :fulcro/async-mutation"
        (:op async-op) => :fulcro/async-mutation
        "both have txn"
        (some? (:txn sync-op)) => true
        (some? (:txn async-op)) => true))))

(specification {:covers {`scf/install-fulcro-statecharts! "async? option"}} "install with :async? true"
  (behavior "installs async processor and execution model"
    (let [app (app/fulcro-app)]
      (app/set-root! app TestRoot {:initialize-state? true})
      (scf/install-fulcro-statecharts! app {:event-loop? false :async? true})
      (let [env (scf/statechart-env app)]
        (assertions
          "environment is installed"
          (some? env) => true
          "has processor"
          (some? (::sc/processor env)) => true
          "has execution model"
          (some? (::sc/execution-model env)) => true))))

  (behavior "async charts work for synchronous expressions"
    (let [app        (async-test-app)
          chart      (chart/statechart {:initial :idle}
                       (state {:id :idle}
                         (transition {:event :go :target :done}))
                       (state {:id :done}))]
      (scf/register-statechart! app ::basic-chart chart)
      (let [session-id @(scf/start! app {:machine ::basic-chart})]
        (scf/send! app session-id :go)
        (scf/process-events! app)
        (assertions
          "transitions normally"
          (scf/current-configuration app session-id) => #{:done})))))

(specification {:covers {`afop/await-load "sync engine detection"}} "await-load with sync engine"
  (behavior "throws when used with sync engine"
    (let [app   (sync-test-app)
          chart (chart/statechart {:initial :start}
                  (state {:id :start}
                    (on-entry {}
                      (script {:expr (fn [env data _ _]
                                       (try
                                         (afop/await-load env :items Item {::sc/ok-event :loaded})
                                         (catch #?(:clj Throwable :cljs :default) e
                                           (swap! (::app/state-atom (:fulcro/app env))
                                             assoc :test/error-caught (ex-message e))))
                                       [])}))
                    (transition {:event :loaded :target :done}))
                  (state {:id :done}))]
      (scf/register-statechart! app ::sync-check chart)
      (scf/start! app {:machine ::sync-check})
      (scf/process-events! app)
      (assertions
        "error was caught"
        (get @(::app/state-atom app) :test/error-caught) => "await-load requires the async execution engine. Install with :async? true."))))

(specification {:covers {`afop/await-mutation "sync engine detection"}} "await-mutation with sync engine"
  (behavior "throws when used with sync engine"
    (let [app   (sync-test-app)
          chart (chart/statechart {:initial :start}
                  (state {:id :start}
                    (on-entry {}
                      (script {:expr (fn [env data _ _]
                                       (try
                                         (afop/await-mutation env `[(some-mutation {})] {:ok-event :done})
                                         (catch #?(:clj Throwable :cljs :default) e
                                           (swap! (::app/state-atom (:fulcro/app env))
                                             assoc :test/error-caught (ex-message e))))
                                       [])}))
                    (transition {:event :done :target :finished}))
                  (state {:id :finished}))]
      (scf/register-statechart! app ::sync-remote-check chart)
      (scf/start! app {:machine ::sync-remote-check})
      (scf/process-events! app)
      (assertions
        "error was caught"
        (get @(::app/state-atom app) :test/error-caught) => "await-mutation requires the async execution engine. Install with :async? true."))))

(specification {:covers {`fop/load "unchanged"}} "existing fop/load still produces data operations"
  (behavior "returns operation map unchanged"
    (let [load-op (fop/load :all-items Item {::sc/ok-event :loaded})]
      (assertions
        "has :fulcro/load op"
        (:op load-op) => :fulcro/load
        "has query-root"
        (:query-root load-op) => :all-items))))

(specification {:covers {`fop/invoke-remote "unchanged"}} "existing fop/invoke-remote still produces data operations"
  (behavior "returns operation map unchanged"
    (let [remote-op (fop/invoke-remote `[(save-data {})] {:ok-event :saved})]
      (assertions
        "has :fulcro/invoke-remote op"
        (:op remote-op) => :fulcro/invoke-remote
        "has txn"
        (some? (:txn remote-op)) => true))))
