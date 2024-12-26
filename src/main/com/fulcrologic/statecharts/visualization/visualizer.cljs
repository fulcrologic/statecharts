(ns com.fulcrologic.statecharts.visualization.visualizer
  (:require
    ["react" :as react]
    [clojure.core.async :as async]
    [clojure.edn :as edn]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.visualization.elk :as elk]))

(defn element-label [{:keys [id diagram/label]}]
  (or
    label
    (str (name id))))

(defn chart->elk-tree [{::sc/keys [elements-by-id] :as chart} node-id->size]
  (let [node->tree (fn node->tree* [id->element {:keys [id node-type children] :as node}]
                     (when (#{:initial :final :parallel :state :statechart} node-type)
                       (cond-> (-> node
                                 (dissoc ::sc/elements-by-id ::sc/id-ordinals ::sc/ids-in-document-order)
                                 (merge {:id            (pr-str id)
                                         :layoutOptions {"elk.nodeLabels.placement" "INSIDE"
                                                         "elk.nodeLabels.padding"   80}
                                         :diagram/label (element-label node)}
                                   (get node-id->size id {:width 40 :height 40})))
                         (seq children) (assoc :children
                                               (vec
                                                 (keep (fn [k] (node->tree* id->element (id->element k)))
                                                   children))))))
        all-edges  (vec
                     (keep
                       (fn [{:keys [id node-type parent target]}]
                         (when (= node-type :transition)
                           {:id      (pr-str id)
                            :sources [(pr-str parent)]
                            :targets (if target
                                       (mapv pr-str target)
                                       [(pr-str parent)])}))
                       (vals elements-by-id)))]
    (assoc (node->tree elements-by-id chart)
      :edges all-edges
      :layoutOptions {"elk.hierarchyHandling"                     "INCLUDE_CHILDREN",
                      "elk.algorithm"                             "layered",
                      "elk.algorithm.depth"                       20,
                      "elk.layered.considerModelOrder"            "NODES_AND_EDGES",
                      "elk.layered.wrapping.strategy"             "MULTI_EDGE"
                      "elk.direction"                             "RIGHT"
                      "elk.spacing.nodeNode"                      50
                      "elk.layered.spacing.nodeNodeBetweenLayers" 100})))

(defn use-chart-elements [this chart-or-id]
  (hooks/use-effect
    (fn []
      (let [{::sc/keys [elements-by-id]} (if (map? chart-or-id)
                                           chart-or-id
                                           (scf/lookup-statechart this chart-or-id))
            state?      (fn [k] (boolean (#{:initial :final :state :parallel} (get-in elements-by-id [k :node-type]))))
            states      (vec
                          (keep
                            (fn [{:keys [node-type children] :as node}]
                              (when (#{:initial :final :state :parallel} node-type)
                                (cond-> (with-meta node {:ref (react/createRef)})
                                  (some state? children) (assoc :compound? true)
                                  )))
                            (vals elements-by-id)))
            transitions (vec
                          (filter
                            (fn [{:keys [node-type]}] (= :transition node-type))
                            (vals elements-by-id)))]
        (m/set-value!! this :ui/states states)
        (m/set-value!! this :ui/transitions transitions)
        (m/set-value!! this :ui/node-id->size {})
        (m/set-value!! this :ui/node-id->position {}))
      js/undefined)
    [(hash chart-or-id)]))

(defn have-dom-nodes? [states] (every? (fn [s] (some? (.-current (:ref (meta s))))) states))

(defn use-state-sizes [this states]
  (let [has-dom-nodes? (have-dom-nodes? states)]
    (hooks/use-effect
      (fn []
        (when has-dom-nodes?
          (let [id->sz (into {}
                         (map
                           (fn [{:keys [id] :as node}]
                             (let [dom-node (some-> node (meta) (:ref) (.-current))
                                   size     (.getBoundingClientRect dom-node)]
                               [id {:width  (.-width size)
                                    :height (.-height size)}])))
                         states)]
            (m/set-value! this :ui/node-id->size id->sz)))
        js/undefined)
      [has-dom-nodes?])))


(defn flatten-nodes
  [node parent-x parent-y]
  (let [x-offset     (+ (:x node) parent-x)
        y-offset     (+ (:y node) parent-y)
        updated-node (assoc node :x x-offset :y y-offset)]
    (if (:children node)
      (into [updated-node]
        (mapcat #(flatten-nodes % x-offset y-offset) (:children node)))
      [updated-node])))

(defn to-layout-map [layout]
  (let [absolute-nodes (flatten-nodes layout 0 0)]
    (reduce
      (fn [acc {:keys [id] :as node}]
        (assoc acc (edn/read-string id) (dissoc node :children)))
      {}
      absolute-nodes)))

(defn update-edge-points
  [edges node-id->layout]
  (mapv #(let [container-node-id (edn/read-string (:container %))
               {:keys [x y]} (node-id->layout container-node-id)
               node-points       {:x x :y y}]
           (update % :sections (fn [section]
                                 (mapv
                                   (fn [s]
                                     (assoc s
                                       :startPoint (merge-with + (:startPoint s) node-points)
                                       :endPoint (merge-with + (:endPoint s) node-points)
                                       :bendPoints (mapv
                                                     (fn [b] (merge-with + b node-points))
                                                     (:bendPoints s))))
                                   section))))
    edges))

(defn use-elk-layout [this chart-or-id node-id->size]
  (let [[layout set-layout!] (hooks/use-state nil)]
    (hooks/use-effect
      (fn []
        (when (seq node-id->size)
          (let [chart     (assoc (if (map? chart-or-id)
                                   chart-or-id
                                   (scf/lookup-statechart this chart-or-id))
                            :width 2000
                            :height 2000)
                elk-input (chart->elk-tree chart node-id->size)]
            (async/go
              (let [layout          (async/<! (elk/layout! elk-input))
                    node-id->layout (to-layout-map layout)
                    layout          (update layout :edges update-edge-points node-id->layout)]
                (set-layout! {:node-id->layout node-id->layout
                              :layout          layout})))))
        js/undefined)
      [(hash node-id->size) chart-or-id])
    layout))

(defsc Visualizer [this {:ui/keys [chart-id layout states transitions node-id->size node-id->position] :as props}
                   {:keys [session-id chart current-configuration]}]
  {:query         [:ui/chart-id
                   :ui/chart
                   :ui/layout
                   :ui/states
                   :ui/transitions
                   :ui/node-id->size
                   :ui/node-id->position
                   [::sc/session-id '_]]
   :initial-state {:ui/chart-id          :param/chart-id
                   :ui/node-id->size     {}
                   :ui/node-id->position {}}
   :ident         (fn [_] [:component/id ::Visualizer])
   :use-hooks?    true}
  (use-chart-elements this (or chart chart-id))
  (use-state-sizes this states)
  (let [{:keys [layout node-id->layout]} (use-elk-layout this (or chart chart-id) node-id->size)
        active? (if session-id
                  (scf/current-configuration this session-id)
                  (or current-configuration #{}))]
    (dom/div {:style {:position        :relative
                      :width           (str (get-in (or node-id->layout node-id->size) [:ROOT :width]) "px")
                      :height          (str (get-in (or node-id->layout node-id->size) [:ROOT :height]) "px")
                      :top             (get-in (or node-id->layout node-id->position) [:ROOT :y] 0)
                      :left            (get-in (or node-id->layout node-id->position) [:ROOT :x] 0)
                      :backgroundColor "white"}}
      (when layout (elk/render-edges layout))
      (mapv
        (fn [{:keys [id initial? compound? node-type children] :as node}]
          (if initial?
            (dom/div {:key   (pr-str id)
                      :style {:position        :absolute
                              :zIndex          1
                              :backgroundColor "black"
                              :borderRadius    "15px"
                              :width           "15px"
                              :height          "15px"
                              :top             (get-in (or node-id->layout node-id->position) [id :y] 0)
                              :left            (get-in (or node-id->layout node-id->position) [id :x] 0)}
                      :ref   (:ref (meta node))})
            (dom/div {:key   (pr-str id)
                      :style {:position     :absolute
                              :zIndex       (if compound? 0 1)
                              :border       (str "2px solid " (if (active? id) "red" "black"))
                              :borderRadius "10px"
                              ;; NOTE: We have to subtract off the padding/boder from width/height to get alignment
                              :padding      "10px"
                              :width        (str (- (get-in (or node-id->layout node-id->size) [id :width])
                                                   24) "px")
                              :height       (str (- (get-in (or node-id->layout node-id->size) [id :height])
                                                   24) "px")
                              :minHeight    "80px"
                              :top          (get-in (or node-id->layout node-id->position) [id :y] 0)
                              :left         (get-in (or node-id->layout node-id->position) [id :x] 0)}
                      :ref   (:ref (meta node))}
              (dom/div (if compound?
                         {:style {:position        "absolute"
                                  :borderRadius    "10px"
                                  :backgroundColor "black"
                                  :color           "white"
                                  :padding         "1px 6px"
                                  :fontSize        "11px"
                                  :lineHeight      "15px"
                                  :top             "-10px"}}
                         {})
                (element-label node)))))
        states))))

(def ui-visualizer
  "[props {:keys [session-id]}]

   Render the visualizer. If you include a session ID then the current configuration will be highlighted."
  (comp/computed-factory Visualizer))
