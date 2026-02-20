(ns com.fulcrologic.statecharts.routing-demo2.admin-chart
  "Admin panel sub-chart for routing-demo2.
   Invoked by the main chart via istate when navigating to admin routes.
   Uses routing APIs: routes, rstate."
  (:require
    [com.fulcrologic.statecharts.routing-demo2.ui :as-alias ui]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [on-entry script]]
    [com.fulcrologic.statecharts.integration.fulcro.async-operations :as afop]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as sroute]
    [taoensso.timbre :as log]))

(def admin-chart
  "Admin panel statechart. Invoked from the main chart via istate.

   Structure:
   - routes with AdminPanel as routing root (unguarded — parent chart owns the guard)
     - rstate AdminUsersList: loads all users via afop/load
     - rstate AdminUserDetail: loads single user by id via afop/load
     - rstate AdminSettings: loads settings via afop/load

   The ::pending-child-route mechanism is handled automatically by routes on-entry.
   No manual forwarding logic needed."
  (chart/statechart {:initial :admin/routes}
    (sroute/routes {:id :admin/routes :routing/root ::ui/AdminPanel}

        ;; Admin users list: load all users on entry
        (sroute/rstate {:route/target ::ui/AdminUsersList}
          (on-entry {}
            (script {:expr (fn [_env _data & _]
                             (log/info "Admin: loading users")
                             [(afop/load :user/all ::ui/AdminUserItem
                                {:target [:component/id ::ui/AdminUsersList :user/all]})])})))

        ;; Admin user detail: load single user by id on entry
        (sroute/rstate {:route/target ::ui/AdminUserDetail :route/params #{:user-id}}
          (on-entry {}
            (script {:expr (fn [_env _data _event-name event-data & _]
                             (let [user-id (:user-id event-data)]
                               (log/info "Admin: loading user" user-id)
                               [(afop/load [:user/id user-id] ::ui/AdminUserDetail
                                  {:target [:component/id ::ui/AdminPanel :ui/current-route]})]))})))

        ;; Admin settings: load settings on entry
        (sroute/rstate {:route/target ::ui/AdminSettings}
          (on-entry {}
            (script {:expr (fn [_env _data & _]
                             (log/info "Admin: loading settings")
                             [])}))))))
