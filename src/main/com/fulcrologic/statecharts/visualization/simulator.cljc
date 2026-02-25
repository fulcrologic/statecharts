(ns com.fulcrologic.statecharts.visualization.simulator
  "A browser-only simulator that lets users step through a statechart interactively.
   Uses the real processor but with a custom ExecutionModel where guard predicates
   are user-toggleable booleans and other executable content (scripts, assigns, sends)
   is a noop.

   Creates a self-contained env via `simple-env` pattern — no Fulcro dependency."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.algorithms.v20150901 :as alg]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.registry.local-memory-registry :as lmr]
    [com.fulcrologic.statecharts.simple :as simple]
    [com.fulcrologic.statecharts.working-memory-store.local-memory-store :as lms]
    [com.fulcrologic.statecharts.util :refer [new-uuid]]))

;; =============================================================================
;; SimulatorExecutionModel
;; =============================================================================

(deftype SimulatorExecutionModel [guard-values-atom]
  sp/ExecutionModel
  (run-expression! [_model env expr]
    (if (::sc/raw-result? env)
      ;; Guard evaluation — look up fn ref in atom, default to true (permissive)
      (let [guard-map @guard-values-atom]
        (get guard-map expr true))
      ;; Script/assign/send — noop
      nil)))

(defn new-simulator-execution-model
  "Creates an execution model for simulation. Guards look up their fn ref in
   `guard-values-atom` (defaulting to `true`). All other expressions are noop."
  [guard-values-atom]
  (->SimulatorExecutionModel guard-values-atom))

;; =============================================================================
;; Guard Extraction
;; =============================================================================

(defn extract-guards
  "Walks `::sc/elements-by-id` in `chart`, finds all transitions with a `:cond`,
   and returns a map of `{fn-ref {:label str :transition-id keyword :default true}}`.

   Uses `:diagram/condition` for the label when present."
  [chart]
  (let [elements-by-id (::sc/elements-by-id chart)]
    (reduce-kv
      (fn [acc id element]
        (if (and (= :transition (:node-type element))
                 (contains? element :cond))
          (let [cond-fn (:cond element)]
            (if (contains? acc cond-fn)
              acc
              (assoc acc cond-fn {:label         (or (:diagram/condition element) (str id))
                                  :transition-id id
                                  :default       true})))
          acc))
      {}
      elements-by-id)))

;; =============================================================================
;; Available Events
;; =============================================================================

(defn available-events
  "Returns the set of event keywords from transitions on states in `configuration`."
  [chart configuration]
  (let [elements-by-id (::sc/elements-by-id chart)]
    (into #{}
      (comp
        (mapcat (fn [state-id]
                  (let [state-el (get elements-by-id state-id)]
                    ;; Get transitions from this state and all ancestors
                    (loop [sid  state-id
                           tids []]
                      (if (or (nil? sid) (= :ROOT sid))
                        tids
                        (let [el (if (= sid state-id) state-el (get elements-by-id sid))]
                          (recur (chart/get-parent chart sid)
                            (into tids (chart/transitions chart el)))))))))
        (keep (fn [tid]
                (:event (get elements-by-id tid)))))
      configuration)))

;; =============================================================================
;; Simulator State & Lifecycle
;; =============================================================================

(defn start-simulation!
  "Registers `chart`, starts a session, and returns a simulator state map containing:

   - `:env` — the statechart env
   - `:session-id` — the session identifier
   - `:chart` — the statechart definition
   - `:chart-key` — the registry key
   - `:guard-values` — atom of `{fn-ref boolean}`"
  [chart]
  (let [guard-info    (extract-guards chart)
        guard-values  (atom (reduce-kv (fn [acc k v] (assoc acc k (:default v))) {} guard-info))
        dm            (wmdm/new-flat-model)
        q             (mpq/new-queue)
        ex            (new-simulator-execution-model guard-values)
        registry      (lmr/new-registry)
        wmstore       (lms/new-store)
        env           {::sc/statechart-registry   registry
                       ::sc/data-model            dm
                       ::sc/event-queue           q
                       ::sc/working-memory-store  wmstore
                       ::sc/processor             (alg/new-processor)
                       ::sc/invocation-processors []
                       ::sc/execution-model       ex}
        chart-key     ::simulator-chart
        session-id    (new-uuid)]
    (simple/register! env chart-key chart)
    (let [processor (::sc/processor env)
          wmem      (sp/start! processor env chart-key {::sc/session-id session-id})]
      (sp/save-working-memory! wmstore env session-id wmem)
      {:env          env
       :session-id   session-id
       :chart        chart
       :chart-key    chart-key
       :guard-values guard-values})))

(defn current-configuration
  "Returns the set of active state IDs for the simulation."
  [{:keys [env session-id]}]
  (let [wmstore (::sc/working-memory-store env)
        wmem    (sp/get-working-memory wmstore env session-id)]
    (::sc/configuration wmem)))

(defn send-event!
  "Processes `event-name` (with optional `event-data`) through the simulator.
   Returns the new configuration."
  ([sim event-name] (send-event! sim event-name {}))
  ([{:keys [env session-id] :as sim} event-name event-data]
   (let [processor (::sc/processor env)
         wmstore   (::sc/working-memory-store env)
         wmem      (sp/get-working-memory wmstore env session-id)
         event     (evts/new-event {:name event-name :data (or event-data {})})
         wmem'     (sp/process-event! processor env wmem event)]
     (sp/save-working-memory! wmstore env session-id wmem')
     (current-configuration sim))))

(defn toggle-guard!
  "Sets the value of `guard-fn` to `new-value` in the simulator's guard atom."
  [{:keys [guard-values]} guard-fn new-value]
  (swap! guard-values assoc guard-fn new-value)
  nil)

(defn reset-simulation!
  "Restarts the simulation from the initial configuration. Returns the new configuration."
  [{:keys [env session-id chart chart-key] :as sim}]
  (let [processor (::sc/processor env)
        wmstore   (::sc/working-memory-store env)
        wmem      (sp/start! processor env chart-key {::sc/session-id session-id})]
    (sp/save-working-memory! wmstore env session-id wmem)
    (current-configuration sim)))
