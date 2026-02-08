(ns com.fulcrologic.statecharts.integration.fulcro-api-spec
  "Tests for the Fulcro integration core API:
   - register-statechart!
   - start!
   - actor resolution
   - alias resolution
   - current-configuration"
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.guardrails.malli.fulcro-spec-helpers :as gsh]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [on-entry script state transition final]]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.util :refer [new-uuid]]
    [fulcro-spec.core :refer [=> assertions behavior component specification]]))

;; Test components
(defsc Person [this props]
  {:query         [:person/id :person/name :person/age]
   :ident         :person/id
   :initial-state {:person/id 1 :person/name "Alice" :person/age 30}})

(defsc Account [this props]
  {:query         [:account/id :account/balance]
   :ident         :account/id
   :initial-state {:account/id 100 :account/balance 1000}})

(defsc RootComponent [this props]
  {:query         [:ui/current-view {:person (comp/get-query Person)} {:account (comp/get-query Account)}]
   :initial-state {:ui/current-view :home
                   :person          {}
                   :account         {}}})

(defn test-app []
  (let [a (app/fulcro-app)]
    (app/set-root! a RootComponent {:initialize-state? true})
    ;; Disable event loop for deterministic testing
    (scf/install-fulcro-statecharts! a {:event-loop? false})
    a))

(specification {:covers {`scf/register-statechart! "PLACEHOLDER"}} "register-statechart!"
  (let [app   (test-app)
        chart (chart/statechart {} (state {:id :idle}))]

    (behavior "registers a statechart under a keyword"
      (scf/register-statechart! app ::my-chart chart)
      (assertions
        "chart is retrievable by key"
        (scf/lookup-statechart app ::my-chart) => chart))

    (behavior "allows multiple charts to be registered"
      (let [chart2 (chart/statechart {} (state {:id :active}))]
        (scf/register-statechart! app ::chart-1 chart)
        (scf/register-statechart! app ::chart-2 chart2)
        (assertions
          "both charts are retrievable"
          (scf/lookup-statechart app ::chart-1) => chart
          (scf/lookup-statechart app ::chart-2) => chart2)))

    (behavior "overwrites existing registration"
      (let [new-chart (chart/statechart {} (state {:id :replaced}))]
        (scf/register-statechart! app ::my-chart chart)
        (scf/register-statechart! app ::my-chart new-chart)
        (assertions
          "returns the new chart"
          (scf/lookup-statechart app ::my-chart) => new-chart)))))

(specification {:covers {`scf/start! "PLACEHOLDER"}} "start!"
  (let [app   (test-app)
        chart (chart/statechart {:initial :idle}
                (state {:id :idle}
                  (transition {:event :go :target :active}))
                (state {:id :active}))]

    (scf/register-statechart! app ::test-chart chart)

    (behavior "starts a registered statechart with default session-id"
      (let [session-id (scf/start! app {:machine ::test-chart})]
        (assertions
          "returns a session-id"
          (some? session-id) => true
          "session has initial configuration"
          (scf/current-configuration app session-id) => #{:idle})))

    (behavior "starts a statechart with specified session-id"
      (let [custom-id (new-uuid)]
        (scf/start! app {:machine    ::test-chart
                         :session-id custom-id})
        (assertions
          "session exists with specified id"
          (scf/current-configuration app custom-id) => #{:idle})))

    (behavior "stores invocation data in local-data"
      (let [session-id (scf/start! app {:machine ::test-chart
                                        :data    {:custom/value 42
                                                  :custom/name  "test"}})
            state-atom (::app/state-atom app)
            local-data (get-in @state-atom (scf/local-data-path session-id))]
        (assertions
          "custom data is stored"
          (:custom/value local-data) => 42
          (:custom/name local-data) => "test")))

    (behavior "allows multiple concurrent sessions"
      (let [id1 (scf/start! app {:machine ::test-chart})
            id2 (scf/start! app {:machine ::test-chart})]
        (assertions
          "both sessions are active"
          (scf/current-configuration app id1) => #{:idle}
          (scf/current-configuration app id2) => #{:idle}
          "sessions have distinct ids"
          (not= id1 id2) => true)))))

(specification {:covers {`scf/actor "PLACEHOLDER"}} "actor"
  (behavior "creates actor descriptor with class and ident"
    (let [actor-desc (scf/actor Person [:person/id 42])]
      (assertions
        "has component registry key"
        (:component actor-desc) => :com.fulcrologic.statecharts.integration.fulcro-api-spec/Person
        "has specified ident"
        (:ident actor-desc) => [:person/id 42])))

  (behavior "creates actor descriptor with RootComponent (singleton)"
    (let [actor-desc (scf/actor RootComponent)]
      (assertions
        "has component registry key"
        (:component actor-desc) => :com.fulcrologic.statecharts.integration.fulcro-api-spec/RootComponent
        "has nil ident (no ident function on Root)"
        (:ident actor-desc) => nil))))

(specification {:covers {`scf/resolve-actor-class "PLACEHOLDER"}} "resolve-actor-class"
  (let [local-data {:fulcro/actors {:actor/person  (scf/actor Person [:person/id 1])
                                    :actor/account (scf/actor Account [:account/id 100])}}]

    (behavior "resolves component class from actor keyword"
      (assertions
        "returns Person class"
        (scf/resolve-actor-class local-data :actor/person) => Person
        "returns Account class"
        (scf/resolve-actor-class local-data :actor/account) => Account))

    (behavior "returns nil for unknown actor"
      (assertions
        (scf/resolve-actor-class local-data :actor/unknown) => nil))

    (behavior "returns nil when no actors defined"
      (assertions
        (scf/resolve-actor-class {} :actor/person) => nil))))

(specification {:covers {`scf/resolve-actors "PLACEHOLDER"}} "resolve-actors"
  (let [app        (test-app)
        state-atom (::app/state-atom app)
        session-id (new-uuid)
        chart      (chart/statechart {:initial :active}
                     (state {:id :active}))]

    (scf/register-statechart! app ::test-chart chart)
    (scf/start! app {:machine    ::test-chart
                     :session-id session-id
                     :data       {:fulcro/actors {:actor/person  (scf/actor Person [:person/id 1])
                                                  :actor/account (scf/actor Account [:account/id 100])}}})

    (behavior "resolves single actor to UI props"
      (let [actors (scf/resolve-actors {:_event           {:target session-id}
                                        :fulcro/state-map @state-atom}
                     :actor/person)]
        (assertions
          "returns map with actor key"
          (contains? actors :actor/person) => true
          "actor has expected props"
          (get-in actors [:actor/person :person/name]) => "Alice"
          (get-in actors [:actor/person :person/age]) => 30)))

    (behavior "resolves multiple actors"
      (let [actors (scf/resolve-actors {:_event           {:target session-id}
                                        :fulcro/state-map @state-atom}
                     :actor/person :actor/account)]
        (assertions
          "returns both actors"
          (count actors) => 2
          "person actor has props"
          (get-in actors [:actor/person :person/name]) => "Alice"
          "account actor has props"
          (get-in actors [:actor/account :account/balance]) => 1000)))

    (behavior "returns empty map for unknown actors"
      (let [actors (scf/resolve-actors {:_event           {:target session-id}
                                        :fulcro/state-map @state-atom}
                     :actor/unknown)]
        (assertions
          (empty? actors) => true)))))

(specification {:covers {`scf/resolve-actor "PLACEHOLDER"}} "resolve-actor"
  (let [app        (test-app)
        state-atom (::app/state-atom app)
        session-id (new-uuid)
        chart      (chart/statechart {:initial :active}
                     (state {:id :active}))]

    (scf/register-statechart! app ::test-chart chart)
    (scf/start! app {:machine    ::test-chart
                     :session-id session-id
                     :data       {:fulcro/actors {:actor/person (scf/actor Person [:person/id 1])}}})

    (behavior "resolves single actor to props map"
      (let [actor-props (scf/resolve-actor {:_event           {:target session-id}
                                            :fulcro/state-map @state-atom}
                          :actor/person)]
        (assertions
          "returns props map"
          (:person/name actor-props) => "Alice"
          (:person/age actor-props) => 30)))

    (behavior "returns nil for unknown actor"
      (let [actor-props (scf/resolve-actor {:_event           {:target session-id}
                                            :fulcro/state-map @state-atom}
                          :actor/unknown)]
        (assertions
          actor-props => nil)))))

(specification {:covers {`scf/resolve-aliases "PLACEHOLDER"}} "resolve-aliases"
  (let [app        (test-app)
        state-atom (::app/state-atom app)
        session-id (new-uuid)
        chart      (chart/statechart {:initial :active}
                     (state {:id :active}))]

    (scf/register-statechart! app ::test-chart chart)
    (scf/start! app {:machine    ::test-chart
                     :session-id session-id
                     :data       {:fulcro/aliases {:name-alias [:actor/person :person/name]
                                                   :view-alias [:fulcro/state :ui/current-view]
                                                   :age-alias  [:actor/person :person/age]}
                                  :fulcro/actors  {:actor/person (scf/actor Person [:person/id 1])}}})

    (behavior "resolves alias to actor field"
      (let [aliases (scf/resolve-aliases {:_event           {:target session-id}
                                          :fulcro/state-map @state-atom})]
        (assertions
          "name alias resolves"
          (:name-alias aliases) => "Alice"
          "age alias resolves"
          (:age-alias aliases) => 30)))

    (behavior "resolves alias to fulcro state"
      (let [aliases (scf/resolve-aliases {:_event           {:target session-id}
                                          :fulcro/state-map @state-atom})]
        (assertions
          "view alias resolves"
          (:view-alias aliases) => :home)))

    (behavior "returns empty map when no aliases defined"
      (let [session-id2 (scf/start! app {:machine ::test-chart})
            aliases     (scf/resolve-aliases {:_event           {:target session-id2}
                                              :fulcro/state-map @state-atom})]
        (assertions
          (empty? aliases) => true)))))

(specification {:covers {`scf/current-configuration "PLACEHOLDER"}} "current-configuration"
  (let [app   (test-app)
        chart (chart/statechart {:initial :idle}
                (state {:id :idle}
                  (transition {:event :go :target :active}))
                (state {:id :active}
                  (transition {:event :done :target :finished}))
                (final {:id :finished}))]

    (scf/register-statechart! app ::test-chart chart)

    (behavior "returns initial configuration"
      (let [session-id (scf/start! app {:machine ::test-chart})]
        (assertions
          "is in idle state"
          (scf/current-configuration app session-id) => #{:idle})))

    (behavior "returns empty set for non-existent session"
      (assertions
        (scf/current-configuration app (new-uuid)) => #{}))

    (behavior "tracks state changes"
      (let [session-id (scf/start! app {:machine ::test-chart})]
        (scf/send! app session-id :go)
        (scf/process-events! app)
        (assertions
          "moves to active state"
          (scf/current-configuration app session-id) => #{:active})))))

(specification {:covers {`scf/send! "PLACEHOLDER"
                         `scf/process-events! "PLACEHOLDER"}} "send! and process-events!"
  (let [app          (test-app)
        state-atom   (::app/state-atom app)
        entry-called (atom [])
        chart        (chart/statechart {:initial :idle}
                       (state {:id :idle}
                         (on-entry {}
                           (script {:expr (fn [_ _ _ _] (swap! entry-called conj :idle) [])}))
                         (transition {:event :go :target :active}))
                       (state {:id :active}
                         (on-entry {}
                           (script {:expr (fn [_ _ _ _] (swap! entry-called conj :active) [])}))))]

    (scf/register-statechart! app ::test-chart chart)

    (behavior "send! queues event"
      (let [session-id (scf/start! app {:machine ::test-chart})]
        (reset! entry-called [])
        (scf/send! app session-id :go)
        (assertions
          "event not processed yet"
          (scf/current-configuration app session-id) => #{:idle})))

    (behavior "process-events! executes queued events"
      (let [session-id (scf/start! app {:machine ::test-chart})]
        (reset! entry-called [])
        (scf/send! app session-id :go)
        (scf/process-events! app)
        (assertions
          "state changed"
          (scf/current-configuration app session-id) => #{:active}
          "entry actions executed"
          (last @entry-called) => :active)))

    (behavior "send! with event data"
      (let [received-data (atom nil)
            chart-with-data (chart/statechart {:initial :idle}
                              (state {:id :idle}
                                (transition {:event :data-event :target :done}
                                  (script {:expr (fn [_ data event-name event-data]
                                                   (reset! received-data event-data)
                                                   [])})))
                              (state {:id :done}))
            session-id      (do
                              (scf/register-statechart! app ::data-chart chart-with-data)
                              (scf/start! app {:machine ::data-chart}))]
        (scf/send! app session-id :data-event {:value 42 :name "test"})
        (scf/process-events! app)
        (assertions
          "event data is received"
          (:value @received-data) => 42
          (:name @received-data) => "test")))))

(specification {:covers {`scf/statechart-env "PLACEHOLDER"}} "statechart-env"
  (let [app (test-app)]

    (behavior "returns installed environment"
      (let [env (scf/statechart-env app)]
        (assertions
          "env contains statechart registry"
          (contains? env ::sc/statechart-registry) => true
          "env contains data model"
          (contains? env ::sc/data-model) => true
          "env contains event queue"
          (contains? env ::sc/event-queue) => true
          "env contains working memory store"
          (contains? env ::sc/working-memory-store) => true
          "env contains processor"
          (contains? env ::sc/processor) => true
          "env contains execution model"
          (contains? env ::sc/execution-model) => true
          "env contains fulcro app"
          (contains? env :fulcro/app) => true)))

    (behavior "returns same env on multiple calls"
      (let [env1 (scf/statechart-env app)
            env2 (scf/statechart-env app)]
        (assertions
          "envs are identical"
          (identical? env1 env2) => true)))))

(specification {:covers {`scf/lookup-statechart "PLACEHOLDER"}} "lookup-statechart"
  (let [app   (test-app)
        chart (chart/statechart {} (state {:id :test}))]

    (scf/register-statechart! app ::my-chart chart)

    (behavior "retrieves registered statechart"
      (assertions
        "returns the chart"
        (scf/lookup-statechart app ::my-chart) => chart))

    (behavior "returns nil for unregistered key"
      (assertions
        (scf/lookup-statechart app ::unknown) => nil))

    (behavior "works with component this"
      (let [chart2 (chart/statechart {} (state {:id :test2}))]
        (scf/register-statechart! app ::component-chart chart2)
        (assertions
          "lookup works with app"
          (scf/lookup-statechart app ::component-chart) => chart2)))))

(specification {:covers {`scf/install-fulcro-statecharts! "PLACEHOLDER"}} "install-fulcro-statecharts!"
  (behavior "installs statechart system on app"
    (let [app (app/fulcro-app)]
      (app/set-root! app RootComponent {:initialize-state? true})
      (scf/install-fulcro-statecharts! app)
      (let [env (scf/statechart-env app)]
        (assertions
          "environment is installed"
          (some? env) => true
          "has event loop running"
          (contains? env :events-running-atom) => true))))

  (behavior "installs with event-loop? false"
    (let [app (app/fulcro-app)]
      (app/set-root! app RootComponent {:initialize-state? true})
      (scf/install-fulcro-statecharts! app {:event-loop? false})
      (let [env (scf/statechart-env app)]
        (assertions
          "environment is installed"
          (some? env) => true
          "no event loop atom"
          (contains? env :events-running-atom) => false))))

  (behavior "accepts extra-env option"
    (let [app (app/fulcro-app)]
      (app/set-root! app RootComponent {:initialize-state? true})
      (scf/install-fulcro-statecharts! app {:extra-env      {:custom/data 42}
                                            :event-loop? false})
      (let [env (scf/statechart-env app)]
        (assertions
          "custom data is in env"
          (:custom/data env) => 42))))

  (behavior "on-save callback is invoked"
    (let [app        (app/fulcro-app)
          save-calls (atom [])]
      (app/set-root! app RootComponent {:initialize-state? true})
      (scf/install-fulcro-statecharts! app {:on-save      (fn [session-id wmem] (swap! save-calls conj session-id))
                                            :event-loop? false})
      (let [chart      (chart/statechart {:initial :idle}
                         (state {:id :idle}
                           (transition {:event :go :target :done}))
                         (state {:id :done}))
            session-id (do
                         (scf/register-statechart! app ::test-chart chart)
                         (scf/start! app {:machine ::test-chart}))]
        (scf/send! app session-id :go)
        (scf/process-events! app)
        (assertions
          "on-save was called"
          (some #(= % session-id) @save-calls) => true))))

  (behavior "accepts on-delete callback"
    (let [app (app/fulcro-app)]
      (app/set-root! app RootComponent {:initialize-state? true})
      (assertions
        "on-delete callback can be provided"
        (some? (scf/install-fulcro-statecharts! app {:on-delete    (fn [_] :ok)
                                                      :event-loop? false})) => true))))
