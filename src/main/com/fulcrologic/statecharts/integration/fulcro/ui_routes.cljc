(ns com.fulcrologic.statecharts.integration.fulcro.ui-routes
  "A composable statechart-driven UI routing system"
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :as ele :refer [state on-entry on-exit transition script]]
    [com.fulcrologic.statecharts.convenience :refer [choice send-after]]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.statecharts.integration.fulcro.operations :as fops]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.fulcro.components :as comp]))


(defmacro defchart-router [sym options]
  (let [nspc (if (:ns &env) (-> &env :ns :name str) (name (ns-name *ns*)))
        fqkw (keyword nspc (str sym))]
    `(comp/defsc ~sym [this# props#]
       {:query [:current-route]
        :ident (fn [] [:statechart-router/id ~fqkw])}

       )))

(defn router-state
  "A statechart node that can control a chart-router."
  [{:keys [router]} & children])

(defn entry-load
  "An on-entry handler that issues a load. Sends :event/loaded when the load succeeds, :error.load
   if the load fails."
  [k component-or-actor options]
  )

(defn activate-route [env data _ _])
(defn establish-route-ident [env data _ _])
(defn asynchronous-route? [env data _ _])
(defn invalid-ident? [env data _ _])
(defn bad-route-parameters? [env data _ _])

(def test-chart
  ; Invoke from highest state on the main chart, so it doesn't have to be diagrammed with the system
  ; operation?
  (chart/statechart {}
    ; ...

    (state {:id :router1}

      ;; Perhaps this is co-located on the target in question, and is just invoked by the main chart?
      ;; * Problem: that causes the route status to not be retained. No history possible
      ;; Instead of invocation, could be started with a session-id. That makes the lifecycle a little
      ;; harder, but perhaps main chart manages *that*.
      (state {:id      :routeA
              :initial :routeA/entry}

        (on-entry {}
          ;; Route params
          (script {:expr establish-route-ident}))

        (choice {:id :routeA/entry}
          bad-route-parameters? :routeA/failed
          invalid-ident? :routeA/failed
          asynchronous-route? :routeA/initializing
          :else :routeA/visible)

        (state {:id :routeA/initializing}
          ;; detect delays and hangs
          (send-after {:delay 1000
                       :event :route/delayed})
          (send-after {:delay 30000
                       :event :error.route/failed})

          ;; async initialization: derive from route target component
          (on-entry {}
            (script {:diagram/label (str "load " :foo)
                     :expr          (fn [{:fulcro/keys [app] :as env} _]
                                      ;; send failed of initialized event
                                      )}))
          (transition {:event  :error.routeA
                       :target :routeA/failed})
          (transition {:event  :initialized.routeA
                       :target :routeA/visible}))

        (state {:id :routeA/delayed}
          (transition {:event  :loaded.routeA
                       :target :routeA/visible})
          (transition {:event :route/cancel}
            (script {:expr (fn [{:fulcro/keys [app]}] (app/abort! app :routeA))})))

        (state {:id :routeA/visible}
          (on-entry {}
            (script {:expr activate-route})))
        (state {:id :routeA/failed}
          ;; An event that the parent composite state can handle?
          ;; As an invocation this would be parent
          ;; As a separate chart, the master chart would be the target, but composition? parent?
          (ele/raise :error.route.failed)))

      )))

;; OR, the routes as data, with a single simple routing state chart that handles NO I/O, just
;; state initialization and UI switching (immediate)
(defn route-not-permitted? [env data _ event-data] false)
(defn process-route-change! [env data _ event-data]

  )
(defn show-reason-route-denied! [env data _ event-data])
(defn integrated-routing-message?
  "Was the message for the user given in a way that does NOT require an explicit dismissal?"
  [env data _ event-data])
(defn save-denied-route! [env data _ event-data]
  [(ops/assign ::denied-route-info event-data)])
(defn denied-route [_ {::keys [denied-route-info]} _ _] denied-route-info)

(def global-routing
  (chart/statechart {}
    (state {:id :region/routing}
      (state {:id :state/routeable}
        (transition {:event  :event/change-route
                     :cond   route-not-permitted?
                     :target :state/routeable}
          (script {:expr save-denied-route!})
          (ele/send {:event   :event/permission-denied
                     :target  ::authorization
                     :content denied-route}))

        (transition {:event  :event/change-route
                     :type   :external
                     :target :state/routeable}
          (script {:expr process-route-change!})))

      (state {:id :state/route-locked}
        (transition {:event  :event/unlock
                     :target :state/routeable})

        (state {:id :state.route-locked/idle}
          (transition {:event  :event/force-change-route
                       :target :state/routeable}
            (script {:expr process-route-change!}))

          (transition {:event  :event/change-route
                       :target :state.route-locked/warn-user}))

        (state {:id :state.route-locked/warn-user}
          (on-entry {}
            (script {:expr show-reason-route-denied!}))

          (transition {:cond   integrated-routing-message?
                       :target :state.route-locked/idle})

          (transition {:event  :event.route-locked/dismiss
                       :target :state.route-locked/idle}))))))


;; The above has some problems:
;; 1. it flattens the routing tree. Can't really track nested route data effectively
;; 2. it concentrates on route control but makes integration harder with things like custom auth

;; So, perhaps it is best just to code the application as a chart, with the nested routes as nested
;; states, and use the transition conflict rules to handle route denials? Unfortunately, I *think*
;; that might be hard to code

(defn busy? [_ _ _ _] false)
(defn show-route-denied! [_ _ _ _] false)

(def application-chart
  (chart/statechart {}
    (state {:id :routeA}
      (transition {:event  :route-to.routeA.2.1
                   :target :routeA.2.1})
      (state {:id :routeA.1})
      (state {:id :routeA.2}
        (state {:id :routeA.2.1})
        (state {:id :routeA.2.2}
          ;; Does this prevent the route without re-triggering on-entry/exit?
          (transition {:event  :route-to.*
                       :cond   busy?
                       :type   :internal
                       :target :routeA.2.2/monitor}
            (script {:expr show-route-denied!})))))))
