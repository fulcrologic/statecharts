(ns com.fulcrologic.statecharts.visualization.app.ui
  (:require
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.visualization.visualizer :as viz]
    [taoensso.timbre :as log]))

(declare ChartViewer)

(defsc SessionState [this {session-id     ::sc/session-id
                           configuration  ::sc/configuration
                           working-memory ::sc/working-memory}]
  {:query [::sc/session-id ::sc/configuration ::sc/working-memory]
   :ident ::sc/session-id}
  nil)

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
    ;; Get the session ident from the chart's ui/session-state
    (let [session-ident  (get-in @state [::sc/id chart-id :ui/session-state])
          ;; Follow the ident to get the actual session data
          session-config (when session-ident
                           (get-in @state (conj session-ident ::sc/configuration)))]
      (when session-config
        (swap! state assoc-in [::sc/id chart-id :ui/current-configuration] session-config)))))

(defmutation set-session-id
  "Set the session ID for viewing live session state."
  [{:keys [chart-id session-id]}]
  (action [{:keys [state app]}]
    (when chart-id                                          ; Only proceed if we have a valid chart-id
      (fns/swap!-> state
        (assoc-in [::sc/id chart-id :ui/session-id] session-id))
      (when (and session-id (not (empty? session-id)))
        ;; Load session state directly with only the session attributes
        (df/load! app [::sc/session-id session-id] SessionState
          {:target               [::sc/id chart-id :ui/session-state]
           :post-mutation        `update-session-config
           :post-mutation-params {:chart-id chart-id}})))))

(defsc ChartListItem [this {chart-id   ::sc/id
                            chart-name :chart/name}]
  {:query [::sc/id :chart/name]
   :ident ::sc/id}
  (dom/option {:value (str chart-id)} chart-name))

(def ui-chart-list-item (comp/factory ChartListItem {:keyfn ::sc/id}))

(defsc ChartViewer [this {:ui/keys    [visualizer session-id current-configuration session-state]
                          ::sc/keys   [id elements-by-id id-ordinals ids-in-document-order]
                          :chart/keys [name]
                          :keys       [initial initial? node-type children] :as props}]
  {:query [{:ui/visualizer (comp/get-query viz/Visualizer)}
           :ui/session-id
           :ui/current-configuration
           {:ui/session-state (comp/get-query SessionState)}
           ::sc/id :chart/name ::sc/elements-by-id ::sc/id-ordinals ::sc/ids-in-document-order
           :id :node-type :children :initial :initial?]
   :ident ::sc/id}
  (js/console.log "ChartViewer render - id:" id "name:" name "has elements:" (boolean elements-by-id)
    "session-id:" session-id "config:" current-configuration)
  (dom/div {:style {:marginTop     "2rem"
                    :width         "100%"
                    :display       "flex"
                    :flexDirection "column"
                    :alignItems    "center"}}
    (dom/div {:style {:padding         "1rem"
                      :border          "1px solid #ddd"
                      :borderRadius    "8px"
                      :backgroundColor "#f9f9f9"
                      :width           "100%"}}
      (dom/h2 {:style {:fontSize     "1.5rem"
                       :fontWeight   "600"
                       :color        "#333"
                       :marginBottom "1rem"}}
        (str "Chart: " name))

      ;; Session ID input
      (dom/div {:style {:marginBottom  "1rem"
                        :display       "flex"
                        :flexDirection "column"
                        :gap           "0.5rem"}}
        (dom/label {:style {:fontSize   "0.875rem"
                            :fontWeight "500"
                            :color      "#555"}}
          "Session ID (optional):")
        (dom/div {:style {:display "flex"
                          :gap     "0.5rem"}}
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
                         :style   {:padding         "0.5rem 1rem"
                                   :fontSize        "0.875rem"
                                   :border          "1px solid #ddd"
                                   :borderRadius    "4px"
                                   :backgroundColor "#f5f5f5"
                                   :cursor          "pointer"}}
              "Clear"))))

      ;; Session state info
      (when (and session-id (not (empty? session-id)))
        (dom/div {:style {:marginBottom    "1rem"
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
              "Loading session state..."))))

      ;; Visualizer
      (dom/div {:style {:backgroundColor "white"
                        :padding         "2rem"
                        :borderRadius    "4px"
                        :minHeight       "400px"}}
        (log/info [visualizer (map? elements-by-id)])
        (if (and visualizer elements-by-id (map? elements-by-id))
          (viz/ui-visualizer visualizer {:chart                 props
                                         :current-configuration current-configuration})
          (dom/div {}
            (dom/p {:style {:color "red"}} "Chart definition not loaded or invalid")
            (dom/p {} (str "Elements-by-id value: " (pr-str elements-by-id)))
            (dom/p {} (str "Props keys: " (pr-str (keys props))))))))))

(def ui-chart-viewer (comp/factory ChartViewer))

(defsc ChartSelector [this {:ui/keys [selected-chart-id selected-chart all-charts]}]
  {:query         [:ui/selected-chart-id
                   {:ui/selected-chart (comp/get-query ChartViewer)}
                   {:ui/all-charts (comp/get-query ChartListItem)}]
   :ident         (fn [] [:component/id :chart-selector])
   :initial-state {:ui/selected-chart-id nil
                   :ui/selected-chart    {}
                   :ui/all-charts        []}}
  (dom/div {:style {:width         "100%"
                    :display       "flex"
                    :flexDirection "column"
                    :gap           "1rem"}}
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
                                   (comp/transact! this [(select-chart {:chart-id (cljs.reader/read-string value)})]))))
                   :style    {:padding         "0.5rem"
                              :fontSize        "1rem"
                              :border          "1px solid #ddd"
                              :borderRadius    "4px"
                              :backgroundColor "white"
                              :cursor          "pointer"}}
        (dom/option {:value ""} "-- Choose a chart --")
        (map ui-chart-list-item all-charts)))
    (when selected-chart-id
      (ui-chart-viewer selected-chart))))

(def ui-chart-selector (comp/factory ChartSelector))

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
      (ui-chart-selector chart-selector))))
