(ns com.fulcrologic.statecharts.routing-demo.chart-test
  "Headless tests for the async routing statechart.

   Uses the async processor + flat data model directly (no Fulcro)
   to verify statechart behavior: happy path, error cases, and
   redirect to error state."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.algorithms.v20150901-async :as async-alg]
    [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.execution-model.lambda-async :as lambda-async]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.registry.local-memory-registry :as lmr]
    [com.fulcrologic.statecharts.routing-demo.chart :as demo-chart]
    [com.fulcrologic.statecharts.routing-demo.model :as model]
    [com.fulcrologic.statecharts.working-memory-store.local-memory-store :as lms]
    [fulcro-spec.core :refer [=> assertions behavior component specification]]
    [promesa.core :as p]))

(def ^:private chart-key ::demo)

(defn- async-env
  "Creates an async-capable statechart env with flat data model."
  []
  (let [dm       (wmdm/new-flat-model)
        q        (mpq/new-queue)
        ex       (lambda-async/new-execution-model dm q {:explode-event? true})
        registry (lmr/new-registry)
        wmstore  (lms/new-store)]
    {::sc/statechart-registry   registry
     ::sc/data-model            dm
     ::sc/event-queue           q
     ::sc/working-memory-store  wmstore
     ::sc/processor             (async-alg/new-processor)
     ::sc/invocation-processors []
     ::sc/execution-model       ex}))

(defn- start-chart!
  "Registers and starts the demo chart. Returns working memory (possibly a promise)."
  [env]
  (let [{::sc/keys [processor statechart-registry]} env]
    (sp/register-statechart! statechart-registry chart-key demo-chart/routing-chart)
    (sp/start! processor env chart-key {::sc/session-id ::test-session})))

(defn- send-event!
  "Sends an event and returns the resulting working memory (possibly a promise)."
  [env wmem event-name event-data]
  (let [{::sc/keys [processor]} env]
    (sp/process-event! processor env wmem
      (evts/new-event {:name event-name :data event-data}))))

(defn- resolve-promise
  "Resolves a value that may or may not be a promise.
   In CLJ, blocks until the promise is resolved.
   In CLJS, uses p/extract to get the value from an already-resolved promise.
   NOTE: For CLJS tests to pass, promises must be synchronously resolved
   (e.g., using p/resolved or 0ms delays that complete before extraction)."
  [v]
  (if (p/promise? v)
    #?(:clj  @v
       :cljs (p/extract v))
    v))

(defn- config
  "Gets the current statechart configuration from working memory."
  [wmem]
  (::sc/configuration wmem))

(defn- data-model-val
  "Gets a value from the flat data model in working memory."
  [wmem k]
  (get-in wmem [:com.fulcrologic.statecharts.data-model.working-memory-data-model/data-model k]))

(specification "Async routing statechart - initialization"
  (binding [model/*load-delay-ms* 0]
    (behavior "starts in landing state"
      (let [env  (async-env)
            wmem (resolve-promise (start-chart! env))]
        (assertions
          "configuration contains landing"
          (contains? (config wmem) :state/landing) => true)))))

(specification "Async routing statechart - happy path deep navigation"
  (binding [model/*load-delay-ms* 0]
    (behavior "navigates from landing to menu (deepest state)"
      (let [env  (async-env)
            wmem (resolve-promise (start-chart! env))
            wmem (resolve-promise
                   (send-event! env wmem :route/go-menu
                     {:event-id "1" :day-num "2"}))]
        (assertions
          "configuration includes menu state"
          (contains? (config wmem) :state/menu) => true
          "configuration includes parent states"
          (contains? (config wmem) :state/day) => true
          (contains? (config wmem) :state/event) => true
          (contains? (config wmem) :state/app) => true
          "loaded the event"
          (:event/name (data-model-val wmem :current-event)) => "Annual Tech Conference"
          "loaded the day"
          (:day/name (data-model-val wmem :current-day)) => "Day 2 - Workshops"
          "loaded the menu"
          (:menu/items (data-model-val wmem :current-menu)) => ["Continental Breakfast" "Workshop Snacks" "Dinner Banquet"])))

    (behavior "navigates to event overview"
      (let [env  (async-env)
            wmem (resolve-promise (start-chart! env))
            wmem (resolve-promise
                   (send-event! env wmem :route/go-event
                     {:event-id "2"}))]
        (assertions
          "configuration includes event-overview"
          (contains? (config wmem) :state/event-overview) => true
          "loaded the event"
          (:event/name (data-model-val wmem :current-event)) => "Community Meetup")))))

(specification "Async routing statechart - error: bad event ID"
  (binding [model/*load-delay-ms* 0]
    (behavior "redirects to error state when event not found"
      (let [env  (async-env)
            wmem (resolve-promise (start-chart! env))
            wmem (resolve-promise
                   (send-event! env wmem :route/go-menu
                     {:event-id "999" :day-num "1"}))]
        (assertions
          "lands in error state"
          (contains? (config wmem) :state/error) => true
          "NOT in menu state"
          (contains? (config wmem) :state/menu) => false
          "NOT in app state"
          (contains? (config wmem) :state/app) => false
          "has error message"
          (data-model-val wmem :error/message) => "Event 999 not found")))))

(specification "Async routing statechart - error: bad day number"
  (binding [model/*load-delay-ms* 0]
    (behavior "redirects to error state when day not found"
      (let [env  (async-env)
            wmem (resolve-promise (start-chart! env))
            wmem (resolve-promise
                   (send-event! env wmem :route/go-menu
                     {:event-id "1" :day-num "99"}))]
        (assertions
          "lands in error state"
          (contains? (config wmem) :state/error) => true
          "error message mentions day"
          (data-model-val wmem :error/message) => "Day 99 not found for event 1")))))

(specification "Async routing statechart - recovery from error"
  (binding [model/*load-delay-ms* 0]
    (behavior "can navigate from error back to landing"
      (let [env  (async-env)
            wmem (resolve-promise (start-chart! env))
            wmem (resolve-promise
                   (send-event! env wmem :route/go-menu
                     {:event-id "999" :day-num "1"}))
            _    (assertions "in error state first"
                   (contains? (config wmem) :state/error) => true)
            wmem (resolve-promise
                   (send-event! env wmem :route/go-home {}))]
        (assertions
          "back to landing"
          (contains? (config wmem) :state/landing) => true)))

    (behavior "can retry from error with valid params"
      (let [env  (async-env)
            wmem (resolve-promise (start-chart! env))
            wmem (resolve-promise
                   (send-event! env wmem :route/go-menu
                     {:event-id "999" :day-num "1"}))
            wmem (resolve-promise
                   (send-event! env wmem :route/go-menu
                     {:event-id "1" :day-num "2"}))]
        (assertions
          "now in menu state"
          (contains? (config wmem) :state/menu) => true
          "loaded correct event"
          (:event/name (data-model-val wmem :current-event)) => "Annual Tech Conference")))))
