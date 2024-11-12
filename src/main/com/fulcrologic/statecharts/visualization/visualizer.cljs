(ns com.fulcrologic.statecharts.visualization.visualizer
  (:require
    ["react" :as react]
    [clojure.edn :as edn]
    [clojure.pprint :refer [pprint]]
    [clojure.core.async :as async]
    [clojure.walk :as walk]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.visualization.elk :as elk]
    [taoensso.timbre :as log]))

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
                      "elk.nodeLabels.padding"                    44
                      "elk.aspectRatio"                           "2"
                      "elk.direction"                             "RIGHT"
                      "elk.spacing.nodeNode"                      20
                      "elk.layered.spacing.nodeNodeBetweenLayers" 20})))

(defn use-chart-elements [this chart-id]
  (hooks/use-effect
    (fn []
      (let [{::sc/keys [elements-by-id]} (scf/lookup-statechart this chart-id)
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
    [(hash chart-id)]))

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

(defn use-elk-layout [this chart-id node-id->size]
  (let [[layout set-layout!] (hooks/use-state nil)]
    (hooks/use-effect
      (fn []
        (when (seq node-id->size)
          (let [chart     (assoc (scf/lookup-statechart this chart-id)
                            :width 1000
                            :height 500)
                elk-input (chart->elk-tree chart node-id->size)]
            (async/go
              (let [layout (async/<! (elk/layout! elk-input))]
                (set-layout! (to-layout-map layout))))))
        js/undefined)
      [(hash node-id->size)])
    layout))

(defsc Visualizer [this {:ui/keys [chart-id layout states transitions node-id->size node-id->position] :as props}
                   {:keys [session-id]}]
  {:query         [:ui/chart-id
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
  (use-chart-elements this chart-id)
  (use-state-sizes this states)
  (let [node-id->layout (use-elk-layout this chart-id node-id->size)
        active?         (if session-id
                          (scf/current-configuration this session-id)
                          #{})]
    (dom/div {:style {:position         :relative
                      :width            (str (get-in (or node-id->layout node-id->size) [:ROOT :width]) "px")
                      :height           (str (get-in (or node-id->layout node-id->size) [:ROOT :height]) "px")
                      :top              (get-in (or node-id->layout node-id->position) [:ROOT :y] 0)
                      :left             (get-in (or node-id->layout node-id->position) [:ROOT :x] 0)
                      :background-color "white"}}
      (mapv
        (fn [{:keys [id initial? compound? node-type children] :as node}]
          (if initial?
            (dom/div {:key   (pr-str id)
                      :style {:position         :absolute
                              :background-color "black"
                              :borderRadius     "15px"
                              :width            "15px"
                              :height           "15px"
                              :top              (get-in (or node-id->layout node-id->position) [id :y] 0)
                              :left             (get-in (or node-id->layout node-id->position) [id :x] 0)}
                      :ref   (:ref (meta node))})
            (dom/div {:key   (pr-str id)
                      :style {:position     :absolute
                              :border       (str "2px solid " (if (active? id) "red" "black"))
                              :borderRadius "10px"
                              :padding      "10px"
                              :width        (str (get-in (or node-id->layout node-id->size) [id :width]) "px")
                              :height       (str (get-in (or node-id->layout node-id->size) [id :height]) "px")
                              :top          (get-in (or node-id->layout node-id->position) [id :y] 0)
                              :left         (get-in (or node-id->layout node-id->position) [id :x] 0)}
                      :ref   (:ref (meta node))}
              (dom/div (if compound?
                         {:style {:position "absolute"
                                  :top      "-20px"}}
                         {})
                (element-label node)))))
        states))))

(def ui-visualizer
  "[props {:keys [session-id]}]

   Render the visualizer. If you include a session ID then the current configuration will be highlighted."
  (comp/computed-factory Visualizer))
