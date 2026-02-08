(ns com.fulcrologic.statecharts.data-model.integration.fulcro-spec
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [on-entry script state transition]]
    [com.fulcrologic.statecharts.event-queue.event-processing :refer [process-events]]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.operations :as fop]
    [com.fulcrologic.statecharts.protocols :as sp]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(defsc Thing [this props]
  {:query         [:thing/id :thing/field]
   :ident         :thing/id
   :initial-state {:thing/id 1 :thing/field "foo"}})

(defsc Root [this props]
  {:query         [:ui/flag? {:thing (comp/get-query Thing)}]
   :initial-state {:ui/flag? nil
                   :thing    {}}})

(defn test-app []
  (let [a (app/fulcro-app)]
    (app/set-root! a Root {:initialize-state? true})
    ;; Use :event-loop? false so we can process events manually for deterministic testing
    (scf/install-fulcro-statecharts! a {:event-loop? false})
    a))

(specification "General Operation"
  (let [app            (test-app)
        state-atom     (::app/state-atom app)
        chart          (chart/statechart {}
                         (state {:id :A}
                           (on-entry {}
                             (script {:expr (fn [_ _ _ _]
                                              [(ops/assign [:fulcro/state :ui/flag?] false)
                                               (ops/assign [:actor/thing :thing/field] "bar")])}))
                           (transition {:event  :ping
                                        :target :B}))
                         (state {:id :B}))
        {::sc/keys [data-model event-queue] :as env} (scf/statechart-env app)
        processing-env (assoc env ::sc/vwmem (volatile! {::sc/session-id ::session}))]
    (scf/register-statechart! app ::c chart)
    (scf/start! app {:machine    ::c
                     :data       {:fulcro/aliases {:a     [:fulcro/state :a]
                                                   :field [:actor/thing :thing/field]}
                                  :fulcro/actors  {:actor/thing (scf/actor Thing [:thing/id 1])
                                                   :actor/root  (scf/actor Root)}}
                     :session-id ::session})

    (assertions
      "Places start data in the local data of the statechart"
      (dissoc (get-in @state-atom (scf/local-data-path ::session)) :_sessionid :_name)
      => #:fulcro{:aliases {:a     [:fulcro/state :a]
                            :field [:actor/thing :thing/field]}
                  :actors  #:actor{:thing
                                   {:component
                                    :com.fulcrologic.statecharts.data-model.integration.fulcro-spec/Thing
                                    :ident [:thing/id 1]}
                                   :root
                                   {:component
                                    :com.fulcrologic.statecharts.data-model.integration.fulcro-spec/Root
                                    :ident nil}}}

      "Assignment operations can place data anywhere in the state map"
      (false? (get @state-atom :ui/flag?)) => true)

    (component "Can define actors"
      (let [local-data (get-in @state-atom (scf/local-data-path ::session))]
        (assertions
          "and get their class"
          (scf/resolve-actor-class local-data :actor/root) => Root
          (scf/resolve-actor-class local-data :actor/thing) => Thing)))

    (component "Resolving actor data"
      (assertions
        "Pulls the ui props from state"
        (scf/resolve-actors {:_event           {:target ::session}
                             :fulcro/state-map @state-atom} :actor/thing) => {:actor/thing {:thing/id    1
                                                                                            :thing/field "bar"}}))

    (component "Updating via state map path"
      (sp/update! data-model processing-env {:ops [(ops/assign [:fulcro/state :ui/flag?] true)]})

      (assertions
        "Places the value relative to the state atom"
        (get-in @state-atom [:ui/flag?]) => true))

    (component "Updating via aliases"
      (sp/update! data-model processing-env {:ops [(ops/assign :a "A")
                                                   (ops/assign :field "F")]})

      (assertions
        "Writes to the targeted alias path"
        (get-in @state-atom [:a]) => "A"
        (get-in @state-atom [:thing/id 1 :thing/field]) => "F"))

    (component "Resolving aliases"
      (assertions
        "Pulls the data correctly"
        (scf/resolve-aliases {:_event           {:target ::session}
                              :fulcro/state-map @state-atom})
        => {:a     "A"
            :field "F"}))

    (component "Updating via actor paths"
      (sp/update! data-model processing-env {:ops [(ops/assign [:actor/thing :thing/field] "baz")]})

      (assertions
        "Writes to the targeted actor"
        (get-in @state-atom [:thing/id 1 :thing/field]) => "baz"))

    (component "Updating via plain paths"
      (sp/update! data-model processing-env {:ops [(ops/assign [:x] 1)
                                                   (ops/assign :y 2)]})

      (assertions
        "Affects the local data model"
        (get-in @state-atom (scf/local-data-path ::session :x)) => 1
        (get-in @state-atom (scf/local-data-path ::session :y)) => 2))

    (component "Updating via `fop/assoc-alias`"
      (sp/update! data-model processing-env {:ops [(fop/assoc-alias :a "A2" :field "F2")]})

      (assertions
        "Writes to the targeted alias path"
        (get-in @state-atom [:a]) => "A2"
        (get-in @state-atom [:thing/id 1 :thing/field]) => "F2"))

    (component "Current state"
      (assertions
        "of the state machine's session"
        (scf/current-configuration app ::session) => #{:A})

      (sp/send! event-queue env {:target ::session
                                 :event  :ping
                                 :data   {}})
      (process-events env)

      (assertions
        "Moves to the next state"
        (scf/current-configuration app ::session) => #{:B}))))

