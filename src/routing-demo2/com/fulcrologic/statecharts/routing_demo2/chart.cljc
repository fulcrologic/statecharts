(ns com.fulcrologic.statecharts.routing-demo2.chart
  "Main routing statechart for routing-demo2.
   Demonstrates routing APIs: routing-regions, routes, rstate, istate."
  (:require
    #?@(:cljs [[com.fulcrologic.statecharts.routing-demo2.ui :as ui]]
        :clj  [[com.fulcrologic.statecharts.routing-demo2.ui :as-alias ui]])
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [on-entry script state transition]]
    [com.fulcrologic.statecharts.environment :as senv]
    [com.fulcrologic.statecharts.integration.fulcro.async-operations :as afop]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as sroute]
    [com.fulcrologic.statecharts.routing-demo2.mock-server :as ms]
    [taoensso.timbre :as log]))

(defn- save-bookmark
  "Saves the triggering route event as a bookmark for replay after login.
   Skips saving if the event is already for LoginScreen (avoids loops)."
  [_env _data event-name event-data]
  (let [login-event (sroute/route-to-event-name ::ui/LoginScreen)]
    (if (= event-name login-event)
      nil
      (do (log/debug "Saving bookmark:" event-name event-data)
          [(ops/assign ::bookmark {:event-name event-name :event-data event-data})]))))

(defn- replay-bookmark!
  "On-entry for Dashboard. Replays saved bookmark if present after login."
  [env data & _]
  (let [bookmark (get data ::bookmark)]
    (when bookmark
      (log/debug "Replaying bookmark:" (:event-name bookmark) (:event-data bookmark))
      (senv/raise env (:event-name bookmark) (:event-data bookmark)))
    ;; Clear bookmark after use (or no-op if nil); let raised event handle navigation
    [(ops/assign ::bookmark nil)]))

(defn- handle-login-success
  "Stores auth data from a successful login event."
  [_env _data _event-name event-data]
  [(ops/assign :auth/token (:token event-data))
   (ops/assign :auth/user (:user event-data))
   (ops/assign :auth/authenticated? true)])

(defn- clear-auth
  "Clears auth state on logout."
  [_env _data & _]
  [(ops/assign :auth/token nil)
   (ops/assign :auth/user nil)
   (ops/assign :auth/authenticated? false)])

(defn- check-session!
  "Checks if a session token exists in the data model.
   Raises :auth/session-valid or :auth/session-invalid."
  [env data & _]
  (let [token (get data :auth/token)]
    (if token
      (senv/raise env :auth/session-valid {})
      (senv/raise env :auth/session-invalid {}))
    nil))

(def routing-chart
  "Main routing statechart. Uses routing simplified API throughout.

   Demonstrates:
   1. Basic routing with rstate (Dashboard, ProjectList, ProjectDetail)
   2. Guarded transitions via BusyForm (ro/busy? blocks navigation)
   3. Cross-chart routing via istate with auto-derived reachable targets (AdminPanel)
   4. URL sync via install-url-sync! (wired in app.cljs)"
  (chart/statechart {:initial :state/route-root}
    (sroute/routing-regions
      (sroute/routes {:id :region/main :routing/root ::ui/RoutingRoot}

        ;; Auth guard: plain state (NOT rstate/istate) — intercepts route-to.* when unauthenticated
        (state {:id :state/unauthenticated :initial :state/initializing}
          (transition {:event :route-to.* :target ::ui/LoginScreen}
            (script {:expr save-bookmark}))
          (state {:id :state/initializing}
            (on-entry {} (script {:expr check-session!}))
            (transition {:event :auth/session-valid :target ::ui/Dashboard})
            (transition {:event :auth/session-invalid :target ::ui/LoginScreen}))
          (sroute/rstate {:route/target `ui/LoginScreen}
            (transition {:event :auth/attempt-login}
              (script {:expr (fn [_env _data _event-name event-data & _]
                               [(afop/invoke-remote
                                  `[(ms/login ~event-data)]
                                  {:ok-event :auth/login-success :error-event :auth/login-failed})])}))
            (transition {:event :auth/login-success :target ::ui/Dashboard}
              (script {:expr handle-login-success}))
            (transition {:event :auth/login-failed}
              (script {:expr (fn [_env _data _event-name _event-data & _]
                               [(ops/assign :ui/error-message "Login failed. Please try again.")])}))))

        ;; Logout transition at routes level (fires from any authenticated route)
        (transition {:event :auth/logout :target ::ui/LoginScreen}
          (script {:expr clear-auth}))

        ;; Dashboard: replay bookmark on entry
        (sroute/rstate {:route/target ::ui/Dashboard}
          (on-entry {} (script {:expr replay-bookmark!})))

        ;; Project list: custom URL segment "projects" instead of "ProjectList"
        (sroute/rstate {:route/target ::ui/ProjectList :route/segment "projects"}
          (on-entry {}
            (script {:expr (fn [_env _data & _]
                             [(afop/load :project/all ::ui/ProjectListItem
                                {:target [:component/id ::ui/ProjectList :project/all]})])})))

        ;; Project detail: load single project by id on entry
        (sroute/rstate {:route/target ::ui/ProjectDetail :route/params #{:project-id}}
          (on-entry {}
            (script {:expr (fn [_env _data _event-name event-data & _]
                             (let [project-id (:project-id event-data)]
                               [(afop/load [:project/id project-id] ::ui/ProjectDetail
                                  {:target [:component/id ::ui/RoutingRoot :ui/current-route]})]))})))

        ;; Busy form: demonstrates route guard via ro/busy? on the component
        (sroute/rstate {:route/target ::ui/BusyForm})

        ;; Admin panel istate: cross-chart routing
        ;; Explicit :route/reachable needed for CLJ headless tests where the component class isn't registered
        (sroute/istate {:route/target    `ui/AdminPanel
                        :route/reachable #{::ui/AdminUsersList
                                           ::ui/AdminUserDetail
                                           ::ui/AdminSettings}})))))
