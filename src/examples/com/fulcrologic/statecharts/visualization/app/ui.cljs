(ns com.fulcrologic.statecharts.visualization.app.ui
  (:require
    [cljs.reader :as reader]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.visualization.simulator :as sim]
    [com.fulcrologic.statecharts.visualization.visualizer :as viz]
    [taoensso.timbre :as log]))

;; =============================================================================
;; Simulator instance storage (non-serializable — kept outside Fulcro state)
;; =============================================================================

;; Map of chart-id -> simulator state map (from sim/start-simulation!)
(defonce simulators (atom {}))

;; =============================================================================
;; Forward declarations
;; =============================================================================

(declare ChartViewer)

;; =============================================================================
;; Session State (connected mode)
;; =============================================================================

(defsc SessionState [this {session-id     ::sc/session-id
                           configuration  ::sc/configuration
                           working-memory ::sc/working-memory}]
  {:query [::sc/session-id ::sc/configuration ::sc/working-memory]
   :ident ::sc/session-id}
  nil)

;; =============================================================================
;; Mutations
;; =============================================================================

(defmutation select-chart
  "Select a chart to display in the visualizer."
  [{:keys [chart-id]}]
  (action [{:keys [state app]}]
    (when chart-id
      (fns/swap!-> state
        (merge/merge-component viz/Visualizer {:ui/chart-id          chart-id
                                                :ui/node-id->position {}}
          :replace [::sc/id chart-id :ui/visualizer])
        (assoc-in [:component/id :chart-selector :ui/selected-chart-id] chart-id))
      (df/load! app [::sc/id chart-id] ChartViewer
        {:target [:component/id :chart-selector :ui/selected-chart]}))))

(defmutation update-session-config
  "Update the current configuration from loaded session state."
  [{:keys [chart-id]}]
  (action [{:keys [state]}]
    (let [session-ident  (get-in @state [::sc/id chart-id :ui/session-state])
          session-config (when session-ident
                           (get-in @state (conj session-ident ::sc/configuration)))]
      (when session-config
        (swap! state assoc-in [::sc/id chart-id :ui/current-configuration] session-config)))))

(defmutation set-session-id
  "Set the session ID for viewing live session state."
  [{:keys [chart-id session-id]}]
  (action [{:keys [state app]}]
    (when chart-id
      (fns/swap!-> state
        (assoc-in [::sc/id chart-id :ui/session-id] session-id))
      (when (and session-id (not (empty? session-id)))
        (df/load! app [::sc/session-id session-id] SessionState
          {:target               [::sc/id chart-id :ui/session-state]
           :post-mutation        `update-session-config
           :post-mutation-params {:chart-id chart-id}})))))

(defmutation set-mode
  "Switch between :connected and :simulator mode."
  [{:keys [mode]}]
  (action [{:keys [state]}]
    (swap! state assoc-in [:component/id :chart-selector :ui/mode] mode)))

(defmutation set-sim-configuration
  "Store the simulator's current configuration in Fulcro state."
  [{:keys [chart-id configuration]}]
  (action [{:keys [state]}]
    (swap! state assoc-in [::sc/id chart-id :ui/sim-configuration] configuration)))

(defmutation set-sim-guard-values
  "Store the current guard values snapshot in Fulcro state for rendering."
  [{:keys [chart-id guard-values]}]
  (action [{:keys [state]}]
    (swap! state assoc-in [::sc/id chart-id :ui/sim-guard-values] guard-values)))

(defmutation set-selected-event
  "Set the currently selected event name in the event panel."
  [{:keys [chart-id event-name]}]
  (action [{:keys [state]}]
    (swap! state assoc-in [::sc/id chart-id :ui/selected-event] event-name)))

(defmutation set-event-data-text
  "Set the EDN text for event data."
  [{:keys [chart-id text]}]
  (action [{:keys [state]}]
    (swap! state assoc-in [::sc/id chart-id :ui/event-data-text] text)))

(defmutation set-zoom-level
  "Set the zoom level for the visualizer."
  [{:keys [chart-id zoom]}]
  (action [{:keys [state]}]
    (swap! state assoc-in [::sc/id chart-id :ui/zoom-level] zoom)))

;; =============================================================================
;; Simulator helpers
;; =============================================================================

(defn start-sim!
  "Starts a simulation for `chart-def` under `chart-id`. Stores the instance in
   the `simulators` atom and writes the initial configuration + guards to Fulcro state."
  [this chart-id chart-def]
  (let [sim    (sim/start-simulation! chart-def)
        config (sim/current-configuration sim)
        guards (sim/extract-guards chart-def)]
    (swap! simulators assoc chart-id sim)
    (comp/transact! this [(set-sim-configuration {:chart-id      chart-id
                                                   :configuration config})
                           (set-sim-guard-values {:chart-id     chart-id
                                                   :guard-values (into {}
                                                                   (map (fn [[k v]]
                                                                          [(str k) {:label   (:label v)
                                                                                    :value   (:default v)
                                                                                    :fn-ref  k}]))
                                                                   guards)})])))

(defn sim-send-event!
  "Sends an event through the simulator, updates Fulcro state with the new configuration."
  [this chart-id event-name event-data]
  (when-let [sim (get @simulators chart-id)]
    (let [config (sim/send-event! sim event-name event-data)]
      (comp/transact! this [(set-sim-configuration {:chart-id      chart-id
                                                     :configuration config})]))))

(defn sim-toggle-guard!
  "Toggles a guard in the simulator and updates Fulcro state."
  [this chart-id fn-ref new-value]
  (when-let [sim (get @simulators chart-id)]
    (sim/toggle-guard! sim fn-ref new-value)
    ;; Update guard values in Fulcro state
    (comp/transact! this [(set-sim-guard-values
                            {:chart-id     chart-id
                             :guard-values (into {}
                                             (map (fn [[k v]]
                                                    [(str k) {:label  (:label (get (sim/extract-guards (:chart sim)) k))
                                                              :value  v
                                                              :fn-ref k}]))
                                             @(:guard-values sim))})])))

(defn sim-reset!
  "Resets the simulator to its initial configuration."
  [this chart-id]
  (when-let [sim (get @simulators chart-id)]
    (let [config (sim/reset-simulation! sim)]
      (comp/transact! this [(set-sim-configuration {:chart-id      chart-id
                                                     :configuration config})]))))

;; =============================================================================
;; Styling constants
;; =============================================================================

(def ^:private btn-style
  {:padding         "0.5rem 1rem"
   :fontSize        "0.875rem"
   :border          "1px solid #ddd"
   :borderRadius    "4px"
   :backgroundColor "#f5f5f5"
   :cursor          "pointer"})

(def ^:private btn-primary-style
  (assoc btn-style
    :backgroundColor "#1976d2"
    :color "white"
    :border "1px solid #1565c0"
    :fontWeight "600"))

(def ^:private btn-danger-style
  (assoc btn-style
    :backgroundColor "#e53935"
    :color "white"
    :border "1px solid #c62828"))

(def ^:private panel-style
  {:padding         "1rem"
   :border          "1px solid #e0e0e0"
   :borderRadius    "8px"
   :backgroundColor "#fafafa"})

;; =============================================================================
;; Mode Selector
;; =============================================================================

(defn ui-mode-selector
  "Renders connected/simulator mode toggle."
  [this mode]
  (dom/div {:style {:display       "flex"
                    :gap           "0.5rem"
                    :marginBottom  "1rem"}}
    (dom/button {:onClick (fn [_] (comp/transact! this [(set-mode {:mode :connected})]))
                 :style   (if (= mode :connected)
                            (assoc btn-primary-style :boxShadow "0 2px 4px rgba(25,118,210,0.3)")
                            btn-style)}
      "Connected")
    (dom/button {:onClick (fn [_] (comp/transact! this [(set-mode {:mode :simulator})]))
                 :style   (if (= mode :simulator)
                            (assoc btn-primary-style :boxShadow "0 2px 4px rgba(25,118,210,0.3)")
                            btn-style)}
      "Simulator")))

;; =============================================================================
;; Event Panel
;; =============================================================================

(defn ui-event-panel
  "Renders available events, event data textarea, and Send button."
  [this {:keys [chart-id chart-def mode selected-event event-data-text configuration]}]
  (let [events (when (and chart-def configuration)
                 (sort (sim/available-events chart-def configuration)))]
    (dom/div {:style (assoc panel-style :marginBottom "1rem")}
      (dom/h3 {:style {:fontSize     "1rem"
                        :fontWeight   "600"
                        :color        "#333"
                        :marginBottom "0.75rem"
                        :marginTop    "0"}}
        "Events")
      (if (seq events)
        (comp/fragment
          ;; Event list
          (dom/div {:style {:display        "flex"
                            :flexWrap       "wrap"
                            :gap            "0.375rem"
                            :marginBottom   "0.75rem"}}
            (mapv (fn [evt]
                    (dom/button {:key     (str evt)
                                 :onClick (fn [_]
                                            (comp/transact! this [(set-selected-event {:chart-id   chart-id
                                                                                       :event-name evt})]))
                                 :style   (if (= evt selected-event)
                                            (assoc btn-primary-style :padding "0.25rem 0.75rem" :fontSize "0.8rem")
                                            (assoc btn-style :padding "0.25rem 0.75rem" :fontSize "0.8rem"))}
                      (str evt)))
              events))
          ;; Event data textarea
          (dom/div {:style {:marginBottom "0.5rem"}}
            (dom/label {:style {:display    "block"
                                :fontSize   "0.8rem"
                                :fontWeight "500"
                                :color      "#666"
                                :marginBottom "0.25rem"}}
              "Event Data (EDN):")
            (dom/textarea {:value       (or event-data-text "{}")
                           :onChange    (fn [e]
                                          (comp/transact! this [(set-event-data-text {:chart-id chart-id
                                                                                      :text     (.. e -target -value)})]))
                           :rows        3
                           :style       {:width           "100%"
                                         :padding         "0.5rem"
                                         :fontSize        "0.8rem"
                                         :fontFamily      "monospace"
                                         :border          "1px solid #ddd"
                                         :borderRadius    "4px"
                                         :backgroundColor "white"
                                         :resize          "vertical"
                                         :boxSizing       "border-box"}}))
          ;; Send button
          (dom/button {:onClick  (fn [_]
                                   (when selected-event
                                     (let [event-data (try (reader/read-string (or event-data-text "{}"))
                                                           (catch :default _ {}))]
                                       (if (= mode :simulator)
                                         (sim-send-event! this chart-id selected-event event-data)
                                         ;; Connected mode: send to live session via mutation
                                         (log/info "Connected mode send not yet implemented")))))
                       :disabled (nil? selected-event)
                       :style    (if selected-event
                                   btn-primary-style
                                   (assoc btn-style :opacity "0.5" :cursor "not-allowed"))}
            "Send Event"))
        (dom/div {:style {:fontSize "0.85rem" :color "#999" :fontStyle "italic"}}
          (if configuration
            "No events available from current states"
            "No configuration available"))))))

;; =============================================================================
;; Guard Panel
;; =============================================================================

(defn ui-guard-panel
  "Renders guard toggles for simulator mode."
  [this {:keys [chart-id guard-values]}]
  (dom/div {:style panel-style}
    (dom/h3 {:style {:fontSize     "1rem"
                      :fontWeight   "600"
                      :color        "#333"
                      :marginBottom "0.75rem"
                      :marginTop    "0"}}
      "Guards")
    (if (seq guard-values)
      (dom/div {:style {:display       "flex"
                        :flexDirection "column"
                        :gap           "0.5rem"}}
        (mapv (fn [[str-key {:keys [label value fn-ref]}]]
                (dom/div {:key   str-key
                          :style {:display        "flex"
                                  :alignItems     "center"
                                  :justifyContent "space-between"
                                  :padding        "0.5rem"
                                  :backgroundColor (if value "#e8f5e9" "#ffebee")
                                  :borderRadius  "4px"
                                  :border        (str "1px solid " (if value "#a5d6a7" "#ef9a9a"))}}
                  (dom/span {:style {:fontSize   "0.85rem"
                                     :fontWeight "500"
                                     :color      "#333"}}
                    (or label str-key))
                  (dom/button {:onClick (fn [_]
                                          (sim-toggle-guard! this chart-id fn-ref (not value)))
                               :style   (merge btn-style
                                          {:padding         "0.25rem 0.75rem"
                                           :fontSize        "0.75rem"
                                           :fontWeight      "600"
                                           :backgroundColor (if value "#4caf50" "#ef5350")
                                           :color           "white"
                                           :border          "none"
                                           :minWidth        "3.5rem"})}
                    (if value "true" "false"))))
          (sort-by first guard-values)))
      (dom/div {:style {:fontSize "0.85rem" :color "#999" :fontStyle "italic"}}
        "No guards in this chart"))))

;; =============================================================================
;; Chart Viewer — mode-aware
;; =============================================================================

(defsc ChartListItem [this {chart-id   ::sc/id
                            chart-name :chart/label}]
  {:query [::sc/id :chart/label]
   :ident ::sc/id}
  (dom/option {:value (str chart-id)} chart-name))

(def ui-chart-list-item (comp/factory ChartListItem {:keyfn ::sc/id}))

(defsc ChartViewer [this {:ui/keys    [visualizer session-id current-configuration session-state
                                       sim-configuration sim-guard-values
                                       selected-event event-data-text zoom-level]
                          ::sc/keys   [id elements-by-id id-ordinals ids-in-document-order]
                          :chart/keys [label]
                          :keys       [initial initial? node-type children] :as props}
                    {:keys [mode]}]
  {:query [{:ui/visualizer (comp/get-query viz/Visualizer)}
           :ui/session-id
           :ui/current-configuration
           {:ui/session-state (comp/get-query SessionState)}
           :ui/sim-configuration
           :ui/sim-guard-values
           :ui/selected-event
           :ui/event-data-text
           :ui/zoom-level
           ::sc/id :chart/label ::sc/elements-by-id ::sc/id-ordinals ::sc/ids-in-document-order
           :id :node-type :children :initial :initial?]
   :ident ::sc/id}
  (let [simulator-mode? (= mode :simulator)
        configuration   (if simulator-mode? sim-configuration current-configuration)
        zoom            (or zoom-level 1.0)]
    (dom/div {:style {:marginTop "1rem" :width "100%"}}
      ;; Chart title
      (dom/h2 {:style {:fontSize     "1.5rem"
                        :fontWeight   "600"
                        :color        "#333"
                        :marginBottom "1rem"}}
        (str "Chart: " label))

      ;; Layout: main area + sidebar
      (dom/div {:style {:display "flex" :gap "1rem" :width "100%"}}
        ;; Main area — visualizer
        (dom/div {:style {:flex      1
                          :minWidth  "0"
                          :overflow  "auto"}}
          ;; Connected mode: session ID input
          (when (not simulator-mode?)
            (dom/div {:style {:marginBottom  "1rem"
                              :padding       "1rem"
                              :border        "1px solid #e0e0e0"
                              :borderRadius  "8px"
                              :backgroundColor "#fafafa"}}
              (dom/label {:style {:fontSize   "0.875rem"
                                  :fontWeight "500"
                                  :color      "#555"
                                  :display    "block"
                                  :marginBottom "0.5rem"}}
                "Session ID (optional):")
              (dom/div {:style {:display "flex" :gap "0.5rem"}}
                (dom/input {:type        "text"
                            :value       (or session-id "")
                            :placeholder "Enter session ID to view live state"
                            :onChange    (fn [e]
                                           (let [value (.. e -target -value)]
                                             (comp/transact! this [(set-session-id {:chart-id   id
                                                                                    :session-id value})])))
                            :style       {:flex            1
                                          :padding         "0.5rem"
                                          :fontSize        "0.875rem"
                                          :border          "1px solid #ddd"
                                          :borderRadius    "4px"
                                          :backgroundColor "white"}})
                (when (and session-id (not (empty? session-id)))
                  (dom/button {:onClick (fn [_]
                                          (comp/transact! this [(set-session-id {:chart-id   id
                                                                                 :session-id ""})]))
                               :style   btn-style}
                    "Clear")))
              ;; Session state info
              (when (and session-id (not (empty? session-id)))
                (dom/div {:style {:marginTop       "0.75rem"
                                  :padding         "0.75rem"
                                  :backgroundColor "#e3f2fd"
                                  :borderRadius    "4px"
                                  :fontSize        "0.875rem"}}
                  (if current-configuration
                    (dom/div {}
                      (dom/div {:style {:fontWeight   "500"
                                        :color        "#1976d2"
                                        :marginBottom "0.25rem"}}
                        "Active States:")
                      (dom/div {:style {:color "#424242"}}
                        (str (pr-str current-configuration))))
                    (dom/div {:style {:color "#666"}}
                      "Loading session state..."))))))

          ;; Simulator mode: Reset button + config display
          (when simulator-mode?
            (dom/div {:style {:marginBottom  "1rem"
                              :padding       "0.75rem"
                              :border        "1px solid #e0e0e0"
                              :borderRadius  "8px"
                              :backgroundColor "#fafafa"
                              :display       "flex"
                              :alignItems    "center"
                              :gap           "1rem"}}
              (dom/button {:onClick (fn [_] (sim-reset! this id))
                           :style   btn-danger-style}
                "Reset")
              (when sim-configuration
                (dom/div {:style {:fontSize "0.85rem" :color "#424242"}}
                  (dom/span {:style {:fontWeight "500" :color "#1976d2" :marginRight "0.5rem"}}
                    "Active:")
                  (str (pr-str sim-configuration))))))

          ;; Zoom controls
          (dom/div {:style {:display         "flex"
                            :alignItems      "center"
                            :gap             "0.5rem"
                            :marginBottom    "0.5rem"
                            :padding         "0.5rem 0.75rem"
                            :backgroundColor "#f5f5f5"
                            :borderRadius    "4px"}}
            (dom/button {:onClick (fn [_]
                                    (comp/transact! this [(set-zoom-level {:chart-id id
                                                                           :zoom     (max 0.1 (- zoom 0.1))})]))
                          :style   (assoc btn-style :padding "0.25rem 0.75rem" :fontSize "0.8rem")}
              "\u2212")
            (dom/button {:onClick (fn [_]
                                    (comp/transact! this [(set-zoom-level {:chart-id id
                                                                           :zoom     (min 2.0 (+ zoom 0.1))})]))
                          :style   (assoc btn-style :padding "0.25rem 0.75rem" :fontSize "0.8rem")}
              "+")
            (dom/button {:onClick (fn [_]
                                    (comp/transact! this [(set-zoom-level {:chart-id id
                                                                           :zoom     1.0})]))
                          :style   (assoc btn-style :padding "0.25rem 0.75rem" :fontSize "0.8rem")}
              "100%")
            (dom/span {:style {:fontSize "0.8rem" :color "#666"}}
              (str (int (* zoom 100)) "%")))

          ;; Visualizer
          (dom/div {:style {:backgroundColor "white"
                            :borderRadius    "4px"
                            :minHeight       "400px"
                            :border          "1px solid #e0e0e0"
                            :overflow        "auto"}}
            (if (and visualizer elements-by-id (map? elements-by-id))
              (dom/div {:style {:display         "inline-block"
                                :minWidth        "100%"
                                :transformOrigin "top left"
                                :transform       (str "scale(" zoom ")")}}
                (viz/ui-visualizer visualizer {:chart                 props
                                               :current-configuration configuration}))
              (dom/div {}
                (dom/p {:style {:color "red"}} "Chart definition not loaded or invalid")
                (dom/p {} (str "Props keys: " (pr-str (keys props))))))))

        ;; Right sidebar — event panel + guard panel
        (dom/div {:style {:width         "320px"
                          :flexShrink    0
                          :display       "flex"
                          :flexDirection "column"
                          :gap           "1rem"}}
          ;; Event panel
          (ui-event-panel this {:chart-id       id
                                :chart-def      props
                                :mode           mode
                                :selected-event selected-event
                                :event-data-text event-data-text
                                :configuration  configuration})
          ;; Guard panel (simulator mode only)
          (when simulator-mode?
            (ui-guard-panel this {:chart-id     id
                                  :guard-values sim-guard-values})))))))

(def ui-chart-viewer (comp/computed-factory ChartViewer))

;; =============================================================================
;; Chart Selector — top-level with mode toggle
;; =============================================================================

(defsc ChartSelector [this {:ui/keys [selected-chart-id selected-chart all-charts mode]}]
  {:query         [:ui/selected-chart-id
                   {:ui/selected-chart (comp/get-query ChartViewer)}
                   {:ui/all-charts (comp/get-query ChartListItem)}
                   :ui/mode]
   :ident         (fn [] [:component/id :chart-selector])
   :initial-state {:ui/selected-chart-id nil
                   :ui/selected-chart    {}
                   :ui/all-charts        []
                   :ui/mode              :connected}}
  (let [mode (or mode :connected)]
    (dom/div {:style {:width         "100%"
                      :display       "flex"
                      :flexDirection "column"
                      :gap           "1rem"}}
      ;; Mode selector
      (ui-mode-selector this mode)

      ;; Chart picker
      (dom/div {:style {:display       "flex"
                        :flexDirection "column"
                        :gap           "0.5rem"}}
        (dom/label {:style {:fontSize   "1rem"
                            :fontWeight "500"
                            :color      "#333"}}
          "Select a Chart:")
        (dom/select {:value    (str selected-chart-id)
                     :onChange (fn [e]
                                 (let [value (.. e -target -value)]
                                   (when-not (empty? value)
                                     (let [chart-id (reader/read-string value)]
                                       (comp/transact! this [(select-chart {:chart-id chart-id})])
                                       ;; Start simulator if in simulator mode and chart loads
                                       (when (= mode :simulator)
                                         ;; We need to wait for the chart to load, so we do it via
                                         ;; a post-load action. For now, the user can re-select or
                                         ;; switch modes to trigger simulation start.
                                         nil)))))
                     :style    {:padding         "0.5rem"
                                :fontSize        "1rem"
                                :border          "1px solid #ddd"
                                :borderRadius    "4px"
                                :backgroundColor "white"
                                :cursor          "pointer"}}
          (dom/option {:value ""} "-- Choose a chart --")
          (map ui-chart-list-item all-charts)))

      ;; Start simulation button (simulator mode, chart selected, chart loaded)
      (when (and (= mode :simulator) selected-chart-id selected-chart)
        (let [chart-def selected-chart
              has-elements? (map? (::sc/elements-by-id chart-def))]
          (when has-elements?
            (dom/div {:style {:display "flex" :gap "0.5rem"}}
              (dom/button {:onClick (fn [_] (start-sim! this selected-chart-id chart-def))
                           :style   btn-primary-style}
                "Start / Restart Simulation")))))

      ;; Chart viewer
      (when selected-chart-id
        (ui-chart-viewer selected-chart {:mode mode})))))

(def ui-chart-selector (comp/factory ChartSelector))

;; =============================================================================
;; Root
;; =============================================================================

(defsc Root [this {:ui/keys [chart-selector]}]
  {:query         [{:ui/chart-selector (comp/get-query ChartSelector)}]
   :initial-state {:ui/chart-selector {}}}
  (comp/fragment
    (dom/div {:style {:padding         "2rem"
                      :display         "flex"
                      :flexDirection   "column"
                      :alignItems      "center"
                      :minHeight       "100vh"
                      :backgroundColor "#f5f5f5"}}
      (dom/h1 {:style {:fontSize     "2rem"
                        :fontWeight   "600"
                        :color        "#333"
                        :marginBottom "2rem"}}
        "Statechart Visualizer")
      (dom/div {:style {:width    "100%"
                        :maxWidth "1400px"}}
        (ui-chart-selector chart-selector)))))
