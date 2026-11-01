(ns com.fulcrologic.statecharts.integration.fulcro-impl-spec
  "Tests for the master application chart that the Fulcro integration runs as the parent of every
   session started with `scf/start!`. It removes a session's working memory and local data from the
   Fulcro state map when that session reaches a final state."
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :refer [defsc]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [final on-entry script state transition]]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro-impl :as impl]
    [fulcro-spec.core :refer [=> assertions component specification]]
    [taoensso.timbre :as log]))

(defsc Root [_ _]
  {:query         [:ui/placeholder]
   :initial-state {}})

(def finishing-chart
  (chart/statechart {:initial :running}
    (state {:id :running}
      (on-entry {}
        (script {:expr (fn [_env _data & _] [(ops/assign :started? true)])}))
      (transition {:event :finish :target :done}))
    (final {:id :done})))

(defn- error-logs-during
  "Runs `f` and returns a vector of the timbre `:error` (and above) entries logged while it ran. Each
   entry has the `:level`, the `:message`, and the `:error-message` of any attached exception."
  [f]
  (let [logs (atom [])]
    (log/with-merged-config
      {:min-level :error
       :appenders {::capture {:enabled? true
                              :fn       (fn [{:keys [level msg_ ?err]}]
                                          (swap! logs conj {:level         level
                                                            :message       (force msg_)
                                                            :error-message (some-> ?err ex-message)}))}}}
      (f))
    @logs))

(specification {:covers {`impl/application-chart "PLACEHOLDER"}} "application-chart"
  (component "when a session started under the Fulcro integration reaches a final state"
    (let [a          (doto (app/fulcro-app)
                       (app/set-root! Root {:initialize-state? true})
                       (scf/install-fulcro-statecharts! {:event-loop? :immediate}))
          _          (scf/register-statechart! a ::finishing finishing-chart)
          session-id (scf/start! a {:machine ::finishing :session-id ::child})
          before     (app/current-state a)
          errors     (error-logs-during #(scf/send! a session-id :finish))
          after      (app/current-state a)]
      (assertions
        "the running session has working memory in the state map"
        (contains? (::sc/session-id before) session-id) => true
        "the running session has local data in the state map"
        (get-in before [::sc/local-data session-id :started?]) => true
        "the master chart handles the session's done event without logging an error"
        errors => []
        "the master chart removes the finished session's working memory from the state map"
        (contains? (::sc/session-id after) session-id) => false
        "the master chart removes the finished session's local data from the state map"
        (contains? (::sc/local-data after) session-id) => false
        "the master chart session stays running"
        (scf/current-configuration a impl/master-chart-id) => #{:state/initial}))))
