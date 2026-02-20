(ns com.fulcrologic.statecharts.routing-demo2.chart-test
  "Headless tests for the routing-demo2 async routing statechart.

   Uses the async processor + flat data model directly (no Fulcro)
   to verify statechart behavior: auth guards, bookmark replay,
   login/logout, and route navigation.

   Fulcro-dependent features (afop/load, invoke-remote) are not testable
   headlessly; those are covered by the full-stack demo."
  (:require
    #?@(:cljs [[com.fulcrologic.statecharts.routing-demo2.ui :as ui]]
        :clj  [[com.fulcrologic.statecharts.routing-demo2.ui :as-alias ui]])
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.algorithms.v20150901-async :as async-alg]
    [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.execution-model.lambda-async :as lambda-async]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as sroute]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.registry.local-memory-registry :as lmr]
    [com.fulcrologic.statecharts.routing-demo2.chart :as demo-chart]
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
  "Registers and starts the demo chart. Returns working memory (possibly a promise).
   `initial-dm` is an optional map of initial data model values (flat keys)."
  ([env]
   (start-chart! env nil))
  ([env initial-dm]
   (let [{::sc/keys [processor statechart-registry]} env]
     (sp/register-statechart! statechart-registry chart-key demo-chart/routing-chart)
     (sp/start! processor env chart-key
       (cond-> {::sc/session-id ::test-session}
         initial-dm (assoc ::wmdm/data-model initial-dm))))))

(defn- send-event!
  "Sends an event and returns the resulting working memory (possibly a promise)."
  [env wmem event-name event-data]
  (let [{::sc/keys [processor]} env]
    (sp/process-event! processor env wmem
      (evts/new-event {:name event-name :data event-data}))))

(defn- resolve-promise
  "Resolves a value that may or may not be a promise."
  [v]
  (if (p/promise? v)
    #?(:clj  @v
       :cljs (p/extract v))
    v))

(defn- config
  "Gets the current statechart configuration from working memory."
  [wmem]
  (::sc/configuration wmem))

(defn- dm-val
  "Gets a value from the flat data model in working memory."
  [wmem k]
  (get-in wmem [::wmdm/data-model k]))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(specification "Session resume - no token"
  (let [env  (async-env)
        wmem (resolve-promise (start-chart! env))]
    (assertions
      "transitions to LoginScreen when no token is present"
      (contains? (config wmem) ::ui/LoginScreen) => true
      "remains inside the unauthenticated wrapper"
      (contains? (config wmem) :state/unauthenticated) => true
      "is not in the initializing state"
      (contains? (config wmem) :state/initializing) => false
      "routing-info region is idle"
      (contains? (config wmem) :routing-info/idle) => true)))

(specification "Session resume - valid token"
  (let [env  (async-env)
        wmem (resolve-promise (start-chart! env {:auth/token "pre-seeded-token"}))]
    (assertions
      "transitions to Dashboard"
      (contains? (config wmem) ::ui/Dashboard) => true
      "exits the unauthenticated wrapper"
      (contains? (config wmem) :state/unauthenticated) => false
      "is not in the initializing state"
      (contains? (config wmem) :state/initializing) => false
      "routing-info region is idle"
      (contains? (config wmem) :routing-info/idle) => true)))

(specification "Login success"
  (let [env  (async-env)
        wmem (resolve-promise (start-chart! env))
        _    (assertions "starts at LoginScreen"
               (contains? (config wmem) ::ui/LoginScreen) => true)
        wmem (resolve-promise
               (send-event! env wmem :auth/login-success
                 {:token "test-token"
                  :user  {:user/id 1 :user/name "Test User"}}))]
    (assertions
      "transitions to Dashboard"
      (contains? (config wmem) ::ui/Dashboard) => true
      "exits the unauthenticated wrapper"
      (contains? (config wmem) :state/unauthenticated) => false
      "stores the auth token"
      (dm-val wmem :auth/token) => "test-token"
      "stores user data"
      (:user/name (dm-val wmem :auth/user)) => "Test User"
      "marks as authenticated"
      (dm-val wmem :auth/authenticated?) => true)))

(specification "Auth guard - route while unauthenticated"
  (let [env        (async-env)
        wmem       (resolve-promise (start-chart! env))
        _          (assertions "starts at LoginScreen"
                     (contains? (config wmem) ::ui/LoginScreen) => true)
        route-evt  (sroute/route-to-event-name ::ui/BusyForm)
        wmem       (resolve-promise
                     (send-event! env wmem route-evt {}))]
    (assertions
      "stays at LoginScreen when not authenticated"
      (contains? (config wmem) ::ui/LoginScreen) => true
      "remains inside unauthenticated wrapper"
      (contains? (config wmem) :state/unauthenticated) => true
      "does not enter the target route"
      (contains? (config wmem) ::ui/BusyForm) => false)))

(specification "Bookmark replay after login"
  (let [env       (async-env)
        wmem      (resolve-promise (start-chart! env))
        _         (assertions "starts at LoginScreen"
                    (contains? (config wmem) ::ui/LoginScreen) => true)

        ;; Send route event while unauthenticated — bookmark is saved
        route-evt (sroute/route-to-event-name ::ui/BusyForm)
        wmem      (resolve-promise
                    (send-event! env wmem route-evt {}))
        _         (assertions "still at LoginScreen"
                    (contains? (config wmem) ::ui/LoginScreen) => true)

        ;; Log in — Dashboard on-entry replays the bookmark
        wmem      (resolve-promise
                    (send-event! env wmem :auth/login-success
                      {:token "test-token"
                       :user  {:user/id 1 :user/name "Test User"}}))]
    (assertions
      "exits the unauthenticated wrapper after login"
      (contains? (config wmem) :state/unauthenticated) => false
      "bookmark replayed: enters BusyForm"
      (contains? (config wmem) ::ui/BusyForm) => true
      "no longer at Dashboard (navigated away via bookmark)"
      (contains? (config wmem) ::ui/Dashboard) => false)))

(specification "Logout"
  (let [env  (async-env)
        wmem (resolve-promise (start-chart! env {:auth/token "pre-seeded-token"}))
        _    (assertions "starts logged in at Dashboard"
               (contains? (config wmem) ::ui/Dashboard) => true)

        wmem (resolve-promise
               (send-event! env wmem :auth/logout {}))]
    (assertions
      "transitions to LoginScreen"
      (contains? (config wmem) ::ui/LoginScreen) => true
      "enters unauthenticated wrapper"
      (contains? (config wmem) :state/unauthenticated) => true
      "auth token cleared"
      (dm-val wmem :auth/token) => nil
      "auth user cleared"
      (dm-val wmem :auth/user) => nil
      "not authenticated"
      (dm-val wmem :auth/authenticated?) => false)))

(specification "Route navigation - Dashboard to BusyForm"
  (let [env       (async-env)
        wmem      (resolve-promise (start-chart! env {:auth/token "pre-seeded-token"}))
        _         (assertions "starts at Dashboard"
                    (contains? (config wmem) ::ui/Dashboard) => true)

        route-evt (sroute/route-to-event-name ::ui/BusyForm)
        wmem      (resolve-promise
                    (send-event! env wmem route-evt {}))]
    (assertions
      "enters BusyForm"
      (contains? (config wmem) ::ui/BusyForm) => true
      "no longer at Dashboard"
      (contains? (config wmem) ::ui/Dashboard) => false
      "still in routing-info idle"
      (contains? (config wmem) :routing-info/idle) => true)))

(specification "Route navigation - back to Dashboard"
  (let [env       (async-env)
        wmem      (resolve-promise (start-chart! env {:auth/token "pre-seeded-token"}))

        ;; Navigate to BusyForm first
        wmem      (resolve-promise
                    (send-event! env wmem (sroute/route-to-event-name ::ui/BusyForm) {}))
        _         (assertions "at BusyForm"
                    (contains? (config wmem) ::ui/BusyForm) => true)

        ;; Navigate back to Dashboard
        wmem      (resolve-promise
                    (send-event! env wmem (sroute/route-to-event-name ::ui/Dashboard) {}))]
    (assertions
      "returns to Dashboard"
      (contains? (config wmem) ::ui/Dashboard) => true
      "no longer at BusyForm"
      (contains? (config wmem) ::ui/BusyForm) => false)))
