(ns com.fulcrologic.statecharts.routing-demo.ui
  "Fulcro UI components for the routing demo.
   Simple placeholder screens that show statechart state and loaded data."
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.routing-demo.data :as data]))

(def session-id ::routing-session)

(defn send!
  "Sends a routing event to the demo statechart."
  [this event-name data]
  (scf/send! this session-id event-name data))

;; --- Navigation helpers ---

(defn nav-buttons
  "Renders navigation buttons for testing different routes."
  [this]
  (dom/div {:className "nav"}
    (dom/button {:onClick #(send! this :route/go-home {})} "Home")
    (dom/button {:onClick #(send! this :route/go-events {})} "Event List")
    (dom/button {:className "primary"
                 :onClick   #(send! this :route/go-menu {:event-id "1" :day-num "2"})}
      "Event 1 / Day 2 / Menu")
    (dom/button {:className "primary"
                 :onClick   #(send! this :route/go-event {:event-id "2"})}
      "Event 2 Overview")
    (dom/button {:style   {:borderColor "#e53e3e" :color "#e53e3e"}
                 :onClick #(send! this :route/go-menu {:event-id "999" :day-num "1"})}
      "Event 999 (bad)")
    (dom/button {:style   {:borderColor "#e53e3e" :color "#e53e3e"}
                 :onClick #(send! this :route/go-menu {:event-id "1" :day-num "99"})}
      "Day 99 (bad)")))

(defn config-display
  "Shows the current statechart configuration."
  [cfg]
  (dom/div {:className "config"}
    (str "Active states: " (pr-str (sort cfg)))))

;; --- Screen Components ---

(defn landing-screen [this cfg]
  (dom/div {:className "screen"}
    (dom/h1 "Event Planner - Landing")
    (dom/p "Welcome! Choose a route below to test async URL restoration.")
    (dom/p "The statechart cascades async loads at each route level. Try an invalid route to see error handling.")
    (nav-buttons this)
    (config-display cfg)))

(defn error-screen [this cfg {:keys [error-message error-type]}]
  (dom/div {:className "screen error"}
    (dom/h1 "Route Error")
    (dom/p (or error-message "An unknown error occurred."))
    (when error-type
      (dom/p {:style {:color "#888" :fontSize "14px"}}
        (str "Error type: " error-type)))
    (dom/div {:className "nav" :style {:marginTop "16px"}}
      (dom/button {:className "primary"
                   :onClick   #(send! this :route/go-home {})}
        "Back to Landing")
      (dom/button {:onClick #(send! this :route/go-events {})}
        "Go to Event List"))
    (config-display cfg)))

(defn event-list-screen [this cfg]
  (dom/div {:className "screen"}
    (dom/h1 "Event List")
    (dom/p "Select an event to view:")
    (dom/ul
      (for [[id evt] (sort-by key data/events-db)]
        (dom/li {:key id}
          (dom/button {:onClick #(send! this :route/go-event {:event-id (str id)})}
            (:event/name evt)))))
    (nav-buttons this)
    (config-display cfg)))

(defn event-overview-screen [this cfg current-event]
  (dom/div {:className "screen"}
    (dom/div {:className "breadcrumb"} "Events > " (:event/name current-event))
    (dom/h1 (:event/name current-event))
    (dom/p (str "This event has " (count (:event/days current-event)) " day(s)."))
    (dom/h2 "Days:")
    (dom/ul
      (for [day-num (:event/days current-event)]
        (let [day (data/lookup-day (:event/id current-event) day-num)]
          (dom/li {:key day-num}
            (dom/button {:onClick #(send! this :route/go-day
                                     {:event-id (str (:event/id current-event))
                                      :day-num  (str day-num)})}
              (:day/name day))))))
    (nav-buttons this)
    (config-display cfg)))

(defn day-overview-screen [this cfg current-event current-day]
  (dom/div {:className "screen"}
    (dom/div {:className "breadcrumb"}
      "Events > " (:event/name current-event) " > " (:day/name current-day))
    (dom/h1 (:day/name current-day))
    (dom/p (str "Part of: " (:event/name current-event)))
    (dom/div {:className "nav" :style {:marginTop "12px"}}
      (dom/button {:className "primary"
                   :onClick   #(send! this :route/go-menu
                                 {:event-id (str (:event/id current-event))
                                  :day-num  (str (:day/num current-day))})}
        "View Menu"))
    (nav-buttons this)
    (config-display cfg)))

(defn menu-screen [this cfg current-event current-day current-menu]
  (dom/div {:className "screen"}
    (dom/div {:className "breadcrumb"}
      "Events > " (:event/name current-event)
      " > " (:day/name current-day)
      " > Menu")
    (dom/h1 (str "Menu for " (:day/name current-day)))
    (dom/h2 "Menu Items:")
    (dom/ul
      (for [item (:menu/items current-menu)]
        (dom/li {:key item} item)))
    (nav-buttons this)
    (config-display cfg)))

;; --- Root Component ---

(defsc Root [this props]
  {:query         []
   :initial-state {}}
  (let [state-map     (rapp/current-state this)
        cfg           (scf/current-configuration this session-id)
        local-data    (get-in state-map [::sc/local-data session-id])
        current-event (:current-event local-data)
        current-day   (:current-day local-data)
        current-menu  (:current-menu local-data)
        error-message (get local-data :error/message)
        error-type    (get local-data :error/type)]
    (dom/div
      (dom/h2 {:style {:marginBottom "16px" :color "#333"}}
        "Async Statechart Routing Demo")
      (cond
        (contains? cfg :state/error)
        (error-screen this cfg {:error-message error-message
                                :error-type    error-type})

        (contains? cfg :state/menu)
        (menu-screen this cfg current-event current-day current-menu)

        (contains? cfg :state/day-overview)
        (day-overview-screen this cfg current-event current-day)

        (contains? cfg :state/event-overview)
        (event-overview-screen this cfg current-event)

        (contains? cfg :state/event-list)
        (event-list-screen this cfg)

        :else
        (landing-screen this cfg)))))
