(ns com.fulcrologic.statecharts.routing-demo2.ui
  "Fulcro UI components for the routing2 demo.
   Each screen is a proper `defsc` component with ident and query, rendered
   via the `ui-routes` routing system with the simplified (no session-id) API."
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as sroute]
    [com.fulcrologic.statecharts.integration.fulcro.routing-options :as sfro]
    [com.fulcrologic.statecharts.routing-demo2.admin-chart :as admin-chart]
    [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn nav-buttons
  "Renders the main navigation buttons."
  [this]
  (dom/div {:style {:display "flex" :gap "8px" :flexWrap "wrap" :marginBottom "12px"}}
    (dom/button {:onClick #(sroute/route-to! this ::Dashboard)} "Dashboard")
    (dom/button {:onClick #(sroute/route-to! this ::ProjectList)} "Projects")
    (dom/button {:onClick #(sroute/route-to! this ::BusyForm)} "Busy Form")
    (dom/button {:onClick #(sroute/route-to! this ::AdminUsersList)} "Admin Users")
    (dom/button {:onClick #(sroute/route-to! this ::AdminSettings)} "Admin Settings")
    (dom/button {:onClick #(scf/send! this sroute/session-id :auth/logout {})
                 :style   {:marginLeft "8px"}}
      "Logout")))

(defn state-inspector
  "Shows the current statechart configuration for debugging."
  [this]
  (let [cfg (scf/current-configuration this sroute/session-id)]
    (dom/div {:style {:marginTop "24px" :padding "12px" :background "#f5f5f5"
                      :borderRadius "4px" :fontSize "13px" :fontFamily "monospace"}}
      (dom/div {:style {:fontWeight "bold" :marginBottom "8px"}} "State Inspector")
      (dom/div {} (str "Active: " (pr-str (sort cfg)))))))

;; ---------------------------------------------------------------------------
;; Leaf components (entities with data-driven idents)
;; ---------------------------------------------------------------------------

(defsc ProjectListItem [_this {:project/keys [id name description]}]
  {:query [:project/id :project/name :project/description]
   :ident :project/id}
  (dom/li {:key id}
    (dom/strong {} name)
    (dom/span {:style {:marginLeft "8px" :color "#666"}} description)))

(def ui-project-list-item (comp/factory ProjectListItem {:keyfn :project/id}))

(defsc AdminUserItem [_this {:user/keys [id username name role]}]
  {:query [:user/id :user/username :user/name :user/role]
   :ident :user/id}
  (dom/li {:key id}
    (dom/strong {} name)
    (dom/span {:style {:marginLeft "8px" :color "#666"}}
      (str "(" (when role (cljs.core/name role)) ")"))))

(def ui-admin-user-item (comp/factory AdminUserItem {:keyfn :user/id}))

;; ---------------------------------------------------------------------------
;; Route screens
;; ---------------------------------------------------------------------------

(defsc LoginScreen [this {:ui/keys [error-message] :as _props}]
  {:query         [:ui/error-message]
   :ident         (fn [] [:component/id ::LoginScreen])
   :initial-state {:ui/error-message nil}}
  (let [username (or (comp/get-state this :username) "")
        password (or (comp/get-state this :password) "")]
    (dom/div {}
      (dom/h2 {} "Login")
      (dom/div {:style {:marginBottom "12px"}}
        (dom/label {:style {:display "block" :marginBottom "4px"}} "Username")
        (dom/input {:type     "text"
                    :value    username
                    :onChange #(comp/set-state! this {:username (.. % -target -value)})}))
      (dom/div {:style {:marginBottom "12px"}}
        (dom/label {:style {:display "block" :marginBottom "4px"}} "Password")
        (dom/input {:type     "password"
                    :value    password
                    :onChange #(comp/set-state! this {:password (.. % -target -value)})
                    :onKeyDown (fn [e]
                                 (when (= "Enter" (.-key e))
                                   (scf/send! this sroute/session-id :auth/attempt-login
                                     {:username username :password password})))}))
      (dom/button {:onClick #(scf/send! this sroute/session-id :auth/attempt-login
                               {:username username :password password})}
        "Log In")
      (when error-message
        (dom/p {:style {:color "red" :marginTop "8px"}} error-message))
      (dom/div {:style {:marginTop "12px" :padding "8px" :background "#e6f3ff"
                        :borderRadius "4px" :fontSize "12px"}}
        (dom/strong {} "Demo Credentials:")
        (dom/ul {:style {:margin "4px 0 0 20px" :padding 0}}
          (dom/li {} "Admin: " (dom/code {} "admin") " / " (dom/code {} "DemoPass123!"))
          (dom/li {} "User: " (dom/code {} "user1") " / " (dom/code {} "UserPass456!")))))))

(defsc Dashboard [this {:dashboard/keys [greeting] :as _props}]
  {:query         [:dashboard/greeting]
   :ident         (fn [] [:component/id ::Dashboard])
   :initial-state {:dashboard/greeting "Welcome!"}}
  (dom/div {}
    (dom/h2 {} "Dashboard")
    (dom/p {} (or greeting "Welcome!"))
    (nav-buttons this)
    (state-inspector this)))

(defsc ProjectDetail [_this {:project/keys [id name description]}]
  {:query [:project/id :project/name :project/description]
   :ident :project/id}
  (dom/div {}
    (dom/h2 {} (or name "Loading project..."))
    (when name
      (dom/div {}
        (dom/p {} description)
        (dom/p {:style {:color "#666" :fontSize "13px"}}
          (str "Project ID: " id))))))

(defsc ProjectList [this {:project/keys [all] :as _props}]
  {:query         [{:project/all (comp/get-query ProjectListItem)}]
   :ident         (fn [] [:component/id ::ProjectList])
   :initial-state {:project/all []}}
  (dom/div {}
    (dom/h2 {} "Projects")
    (if (seq all)
      (dom/ul {}
        (mapv (fn [project]
                (dom/li {:key (:project/id project)}
                  (dom/button {:onClick #(sroute/route-to! this ::ProjectDetail
                                           {:project-id (:project/id project)})}
                    (:project/name project))
                  (dom/span {:style {:marginLeft "8px" :color "#666"}}
                    (:project/description project))))
          all))
      (dom/p {} "Loading projects..."))
    (nav-buttons this)
    (state-inspector this)))

;; ---------------------------------------------------------------------------
;; Busy form — demonstrates route guard (dirty form blocks navigation)
;; ---------------------------------------------------------------------------

(defsc BusyForm [this {:ui/keys [notes]}]
  {:query         [:ui/notes]
   :ident         (fn [] [:component/id ::BusyForm])
   :initial-state {:ui/notes ""}
   sfro/busy?       (fn [_app {:ui/keys [notes]}]
                      (and (string? notes) (pos? (count notes))))}
  (dom/div {}
    (dom/h2 {} "Busy Form (Route Guard Demo)")
    (dom/p {} "Type something below. While the field is non-empty, navigation will be blocked by the route guard.")
    (dom/div {:style {:marginBottom "12px"}}
      (dom/label {:style {:display "block" :marginBottom "4px"}} "Notes (non-empty = busy)")
      (dom/input {:type     "text"
                  :value    (or notes "")
                  :onChange #(m/set-string!! this :ui/notes :event %)}))
    (when (and (string? notes) (pos? (count notes)))
      (dom/p {:style {:color "#e53e3e" :fontWeight "bold" :marginBottom "8px"}}
        "Form is BUSY — navigation will be guarded!"))
    (dom/button {:onClick #(m/set-value!! this :ui/notes "")
                 :style   {:marginRight "8px"}}
      "Clear (un-busy)")
    (nav-buttons this)
    (state-inspector this)))

;; ---------------------------------------------------------------------------
;; Admin screens
;; ---------------------------------------------------------------------------

(defsc AdminUserDetail [this {:user/keys [id username name role]}]
  {:query [:user/id :user/username :user/name :user/role]
   :ident :user/id}
  (dom/div {}
    (dom/h2 {} (or name "Loading user..."))
    (when name
      (dom/div {}
        (dom/p {} (str "Username: " username))
        (dom/p {} (str "Role: " (when role (cljs.core/name role))))
        (dom/p {:style {:color "#666" :fontSize "13px"}}
          (str "User ID: " id))))
    (dom/button {:onClick #(sroute/route-to! this ::AdminUsersList)
                 :style   {:marginTop "12px"}}
      "← Back to Users List")))

(defsc AdminUsersList [this {:user/keys [all] :as _props}]
  {:query         [{:user/all (comp/get-query AdminUserItem)}]
   :ident         (fn [] [:component/id ::AdminUsersList])
   :initial-state {:user/all []}}
  (dom/div {}
    (dom/h2 {} "Admin - Users")
    (if (seq all)
      (dom/ul {}
        (mapv (fn [user]
                (dom/li {:key (:user/id user)}
                  (dom/button {:onClick #(sroute/route-to! this ::AdminUserDetail
                                           {:user-id (:user/id user)})}
                    (:user/name user))
                  (dom/span {:style {:marginLeft "8px" :color "#666"}}
                    (str "(" (when (:user/role user) (cljs.core/name (:user/role user))) ")"))))
          all))
      (dom/p {} "Loading users..."))
    (dom/div {:style {:marginTop "12px" :display "flex" :gap "8px"}}
      (dom/button {:onClick #(sroute/route-to! this ::AdminSettings)} "Settings")
      (dom/button {:onClick #(sroute/route-to! this ::Dashboard)} "Back to Main"))
    (state-inspector this)))

(defsc AdminSettings [this {:settings/keys [theme notifications-enabled? language]}]
  {:query         [:settings/theme :settings/notifications-enabled? :settings/language]
   :ident         (fn [] [:component/id ::AdminSettings])
   :initial-state {:settings/theme                "light"
                   :settings/notifications-enabled? true
                   :settings/language             "en"}}
  (dom/div {}
    (dom/h2 {} "Admin - Settings")
    (dom/div {}
      (dom/p {} (str "Theme: " theme))
      (dom/p {} (str "Notifications: " (if notifications-enabled? "Enabled" "Disabled")))
      (dom/p {} (str "Language: " language)))
    (dom/div {:style {:marginTop "12px" :display "flex" :gap "8px"}}
      (dom/button {:onClick #(sroute/route-to! this ::AdminUsersList)} "Users")
      (dom/button {:onClick #(sroute/route-to! this ::Dashboard)} "Back to Main"))
    (state-inspector this)))

(defsc AdminPanel [this {:ui/keys [current-route] :as _props}]
  {:query                   [:ui/current-route]
   :ident                   (fn [] [:component/id ::AdminPanel])
   :preserve-dynamic-query? true
   sfro/statechart             admin-chart/admin-chart
   :initial-state           {:ui/current-route {}}}
  (dom/div {}
    (dom/div {:style {:marginBottom "12px"}}
      (dom/h3 {} "Admin Area")
      (dom/div {:style {:display "flex" :gap "8px" :marginBottom "8px"}}
        (dom/button {:onClick #(sroute/route-to! this ::AdminUsersList)} "Users List")
        (dom/button {:onClick #(sroute/route-to! this ::AdminSettings)} "Settings")))
    (sroute/ui-current-subroute this comp/factory)))

;; ---------------------------------------------------------------------------
;; Routing root
;; ---------------------------------------------------------------------------

(defsc RoutingRoot [this {:ui/keys [current-route] :as _props}]
  {:query                   [:ui/current-route]
   :ident                   (fn [] [:component/id ::RoutingRoot])
   :preserve-dynamic-query? true
   :initial-state           {:ui/current-route {}}}
  (dom/div {}
    (sroute/ui-current-subroute this comp/factory)))

;; ---------------------------------------------------------------------------
;; Application root
;; ---------------------------------------------------------------------------

(defsc Root [this {:ui/keys [routing-root] :as _props}]
  {:query                   [{:ui/routing-root (comp/get-query RoutingRoot)}]
   :initial-state           {:ui/routing-root {}}
   :preserve-dynamic-query? true}
  (let [cfg (scf/current-configuration this sroute/session-id)
        current-url (sroute/route-current-url this)
        history-idx (sroute/route-history-index this)]
    (dom/div {:style {:maxWidth "800px" :margin "20px auto" :fontFamily "sans-serif"}}
      (dom/h1 {} "Routing Demo 2 - Statechart UI Routing")
      ;; URL History Debug Bar
      (dom/div {:style {:background "#f0f0f0" :padding "8px" :marginBottom "12px"
                        :borderRadius "4px" :fontSize "12px"}}
        (dom/div {} (dom/strong {} "URL: ") (dom/code {} (or current-url "/")))
        (when history-idx
          (dom/div {} (dom/strong {} "History Index: ") history-idx))
        (dom/div {:style {:marginTop "4px" :display "flex" :gap "4px"}}
          (dom/button {:onClick #(sroute/route-back! this)
                       :style   {:padding "2px 8px" :fontSize "11px"}
                       :disabled (not (sroute/url-sync-installed? this))}
            "← Back")
          (dom/button {:onClick #(sroute/route-forward! this)
                       :style   {:padding "2px 8px" :fontSize "11px"}
                       :disabled (not (sroute/url-sync-installed? this))}
            "Forward →")))
      (cond
        ;; Auth states handled outside routing system
        (contains? cfg :state/initializing)
        (dom/div {} (dom/p {} "Checking session..."))

        (contains? cfg ::LoginScreen)
        (let [LoginScreen* (comp/registry-key->class ::LoginScreen)]
          ((comp/factory LoginScreen*) {}))

        ;; Routing system active
        (contains? cfg :region/routing-info)
        (dom/div {}
          ;; Route denied modal
          (when (sroute/route-denied? this)
            (dom/div {:style {:background  "#fff3cd" :padding "12px"
                              :marginBottom "12px" :borderRadius "4px"}}
              (dom/p {} "Route blocked: a form or component is busy.")
              (dom/button {:onClick #(sroute/force-continue-routing! this)
                           :style   {:marginRight "8px"}}
                "Continue Anyway")
              (dom/button {:onClick #(sroute/abandon-route-change! this)}
                "Cancel")))
          ;; Main routing root
          ((comp/factory RoutingRoot) routing-root))

        :else
        (dom/div {} (dom/p {} "Loading...")))

      ;; State inspector always visible
      (state-inspector this))))
