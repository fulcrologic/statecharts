(ns com.fulcrologic.statecharts.visualization.visualizer
  (:require
    ["react" :as react]
    [clojure.core.async :as async]
    [clojure.edn :as edn]
    [clojure.string]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.visualization.elk :as elk]
    [taoensso.timbre :as log]))

(defn element-label
  "Returns a display label for a statechart element using chart/diagram-label."
  [element]
  (chart/diagram-label element))

(defn chart->elk-tree [{::sc/keys [elements-by-id] :as chart} node-id->size label-id->size]
  (let [node->tree (fn node->tree* [id->element {:keys [id node-type children] :as node}]
                     (when (#{:initial :final :parallel :state :statechart :history} node-type)
                       (cond-> (-> node
                                 (dissoc ::sc/elements-by-id ::sc/id-ordinals ::sc/ids-in-document-order)
                                 (merge {:id            (pr-str id)
                                         :layoutOptions {"elk.nodeLabels.placement" "INSIDE"
                                                         "elk.nodeLabels.padding"   "20.0"
                                                         "elk.padding"              "[top=40.0,left=20.0,bottom=20.0,right=20.0]"}
                                         :diagram/label (element-label node)}
                                   ;; Use measured sizes directly - they already include padding and borders
                                   (get node-id->size id {:width 120 :height 80})))
                         (seq children) (assoc :children
                                               (vec
                                                 (keep (fn [k] (node->tree* id->element (id->element k)))
                                                   children))))))
        all-edges  (vec
                     (keep
                       (fn [{:keys [id node-type parent target] :as node}]
                         (when (= node-type :transition)
                           (let [edge-label (chart/transition-label elements-by-id node)]
                             {:id      (pr-str id)
                              :sources [(pr-str parent)]
                              :targets (if target
                                         (mapv pr-str target)
                                         [(pr-str parent)])
                              :labels  (when edge-label
                                         [(merge
                                            {:text edge-label
                                             :id   (str (pr-str id) "-label")}
                                            (get label-id->size id))])})))
                       (vals elements-by-id)))]
    (assoc (node->tree elements-by-id chart)
      :edges all-edges
      :layoutOptions {"elk.hierarchyHandling"                            "INCLUDE_CHILDREN"
                      "elk.algorithm"                                    "layered"
                      "elk.algorithm.depth"                              20
                      "elk.layered.considerModelOrder"                   "NODES_AND_EDGES"
                      "elk.layered.wrapping.strategy"                    "MULTI_EDGE"
                      "elk.direction"                                    "RIGHT"
                      "elk.spacing.nodeNode"                             "100.0"
                      "elk.layered.spacing.nodeNodeBetweenLayers"        "220.0"
                      "elk.spacing.edgeNode"                             "80.0"
                      "elk.spacing.edgeEdge"                             "50.0"
                      "elk.padding"                                      "[top=60.0,left=60.0,bottom=60.0,right=60.0]"
                      ;; Edge label placement options to reduce overlapping
                      "elk.edgeLabels.placement"                         "CENTER"
                      "elk.edgeLabels.inline"                            "false"
                      "org.eclipse.elk.layered.edgeLabels.sideSelection" "SMART_DOWN"})))

(defn use-chart-elements [this chart-or-id]
  (let [resolved-chart (if (map? chart-or-id)
                         chart-or-id
                         (scf/lookup-statechart this chart-or-id))
        chart-key      (hash (sort (map :id (filter #(#{:state :parallel} (:node-type %)) (vals (::sc/elements-by-id resolved-chart))))))
        [states set-states!] (hooks/use-state [])
        [transitions set-transitions!] (hooks/use-state [])]
    (hooks/use-effect
      (fn []
        (log/debug "use-chart-elements running with chart-key:" chart-key)
        (let [{::sc/keys [elements-by-id]} resolved-chart
              ;; Calculate depth for each node
              id->depth   (loop [to-process [[resolved-chart 0]]
                                 depths     {}]
                            (if (empty? to-process)
                              depths
                              (let [[node depth] (first to-process)
                                    node-id     (:id node)
                                    children    (:children node)
                                    child-nodes (map #(get elements-by-id %) children)]
                                (recur (concat (rest to-process)
                                         (map #(vector % (inc depth)) child-nodes))
                                  (assoc depths node-id depth)))))
              state?      (fn [k] (boolean (#{:initial :final :state :parallel :history} (get-in elements-by-id [k :node-type]))))
              states      (vec
                            (keep
                              (fn [{:keys [node-type children id] :as node}]
                                (when (#{:initial :final :state :parallel :history} node-type)
                                  (cond-> (with-meta node {:ref (react/createRef)})
                                    (some state? children) (assoc :compound? true)
                                    true (assoc :depth (get id->depth id 0)))))
                              (vals elements-by-id)))
              transitions (vec
                            (keep
                              (fn [{:keys [node-type] :as node}]
                                (when (= :transition node-type)
                                  (let [edge-label (chart/transition-label elements-by-id node)]
                                    (cond-> node
                                      edge-label (assoc :label-text edge-label)
                                      edge-label (vary-meta assoc :ref (react/createRef))))))
                              (vals elements-by-id)))]
          (log/debug "Extracted states:" (count states) "states with IDs:" (mapv :id states))
          (log/debug "Extracted transitions:" (count transitions) "with labels:" (mapv :label-text transitions))
          (set-states! states)
          (set-transitions! transitions))
        js/undefined)
      [chart-key])
    [states transitions]))

(defn have-dom-nodes? [states] (every? (fn [s] (some? (.-current (:ref (meta s))))) states))

(defn use-state-sizes [states]
  (let [[node-id->size set-sizes!] (hooks/use-state {})]
    (hooks/use-effect
      (fn []
        ;; Reset sizes when states change
        (set-sizes! {})
        (when (seq states)
          ;; Schedule measurement after render completes and refs are attached
          (js/setTimeout
            (fn []
              (when (have-dom-nodes? states)
                (let [id->sz (into {}
                               (map
                                 (fn [{:keys [id] :as node}]
                                   (let [dom-node (some-> node (meta) (:ref) (.-current))
                                         size     (.getBoundingClientRect dom-node)]
                                     [id {:width  (.-width size)
                                          :height (.-height size)}])))
                               states)]
                  (set-sizes! id->sz))))
            0))
        js/undefined)
      [(hash (mapv :id states))])
    node-id->size))

(defn use-edge-label-sizes [transitions]
  (let [[label-id->size set-sizes!] (hooks/use-state {})
        ;; Create stable refs for each transition with label text
        transition-refs (hooks/use-memo
                          (fn []
                            (into {}
                              (map (fn [{:keys [id label-text]}]
                                     (when label-text
                                       [id (react/createRef)])))
                              transitions))
                          [(hash (mapv :id transitions))])]
    (hooks/use-effect
      (fn []
        ;; Reset sizes when transitions change
        (set-sizes! {})
        (when (seq transition-refs)
          ;; Schedule measurement after render completes and refs are attached
          (js/setTimeout
            (fn []
              (let [all-have-refs? (every? (fn [[_ ref]] (some? (.-current ref))) transition-refs)]
                (log/debug "use-edge-label-sizes: all-have-refs?" all-have-refs?
                  "transition-refs count:" (count transition-refs))
                (when all-have-refs?
                  (let [id->sz (into {}
                                 (map
                                   (fn [[id ref]]
                                     (let [dom-node (.-current ref)
                                           size     (.getBoundingClientRect dom-node)]
                                       [id {:width  (.-width size)
                                            :height (.-height size)}])))
                                 transition-refs)]
                    (log/debug "Measured label sizes:" (clj->js id->sz))
                    (set-sizes! id->sz)))))
            0))
        js/undefined)
      [(hash (keys transition-refs))])
    {:label-id->size  label-id->size
     :transition-refs transition-refs}))


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
               {:keys [x y]} (get node-id->layout container-node-id {:x 0 :y 0})
               node-points       {:x x :y y}]
           (-> %
             ;; Update edge section points
             (update :sections (fn [section]
                                 (mapv
                                   (fn [s]
                                     (assoc s
                                       :startPoint (merge-with + (:startPoint s) node-points)
                                       :endPoint (merge-with + (:endPoint s) node-points)
                                       :bendPoints (mapv
                                                     (fn [b] (merge-with + b node-points))
                                                     (:bendPoints s))))
                                   section)))
             ;; Update label positions
             (update :labels (fn [labels]
                               (when labels
                                 (mapv
                                   (fn [label]
                                     (-> label
                                       (update :x + x)
                                       (update :y + y)))
                                   labels))))))
    edges))

(defn use-elk-layout [this chart-or-id node-id->size label-id->size]
  (let [resolved-chart (if (map? chart-or-id)
                         chart-or-id
                         (scf/lookup-statechart this chart-or-id))
        chart-key      (hash (sort (map :id (filter #(#{:state :parallel} (:node-type %)) (vals (::sc/elements-by-id resolved-chart))))))
        [layout set-layout!] (hooks/use-state nil)]
    (hooks/use-effect
      (fn []
        (when (seq node-id->size)
          (let [chart     (assoc resolved-chart :width 2000 :height 2000)
                elk-input (chart->elk-tree chart node-id->size label-id->size)]
            (async/go
              (log/info "Running layout")
              (let [layout          (async/<! (elk/layout! elk-input))
                    node-id->layout (to-layout-map layout)
                    layout          (update layout :edges update-edge-points node-id->layout)]
                (set-layout! {:node-id->layout node-id->layout
                              :layout          layout})))))
        js/undefined)
      [(hash node-id->size) (hash label-id->size) chart-key])
    layout))

(defsc Visualizer [this _ {:keys [session-id chart current-configuration]}]
  {:query         [[::sc/session-id '_]]
   :initial-state {}
   :ident         (fn [] [:component/id ::Visualizer])
   :use-hooks?    true}
  (let [resolved-chart (if (map? chart) chart (scf/lookup-statechart this chart))
        elements-by-id (::sc/elements-by-id resolved-chart)
        [states transitions] (use-chart-elements this chart)
        node-id->size (use-state-sizes states)
        {:keys [label-id->size transition-refs]} (use-edge-label-sizes transitions)
        {:keys [layout node-id->layout]} (use-elk-layout this chart node-id->size label-id->size)
        active?       (if session-id
                        (scf/current-configuration this session-id)
                        (or current-configuration #{}))]

    (dom/div {:style {:position        :relative
                      :width           "100%"
                      :minHeight       "600px"
                      :padding         "20px"
                      :backgroundColor "#fafafa"
                      :border          "1px solid #e0e0e0"
                      :borderRadius    "8px"
                      :overflow        "auto"
                      :whiteSpace      "nowrap"}}
      ;; Hidden measurement divs for edge labels
      (dom/div {:style {:position   "absolute"
                        :visibility "hidden"
                        :top        -9999
                        :left       -9999}}
        (mapv
          (fn [{:keys [id label-text] :as transition}]
            (when-let [ref (get transition-refs id)]
              (dom/div {:key   (str "label-measure-" (pr-str id))
                        :ref   ref
                        :style {:display    "inline-block"
                                :padding    "4px 8px"
                                :fontSize   "12px"
                                :fontWeight "500"
                                :fontFamily "system-ui, -apple-system, sans-serif"
                                :whiteSpace "nowrap"}}
                label-text)))
          transitions))

      (dom/div {:style {:position :relative
                        :display  "inline-block"
                        :width    (str (get-in node-id->layout [:ROOT :width] 1000) "px")
                        :minWidth (str (get-in node-id->layout [:ROOT :width] 1000) "px")
                        :height   (str (get-in node-id->layout [:ROOT :height] 1200) "px")}}
        (when layout (elk/render-edges layout))
        (mapv
          (fn [{:keys [id initial? compound? node-type children final? depth] :as node}]
            (let [elk-width  (get-in (or node-id->layout node-id->size) [id :width])
                  elk-height (get-in (or node-id->layout node-id->size) [id :height])
                  elk-x      (get-in node-id->layout [id :x] 0)
                  elk-y      (get-in node-id->layout [id :y] 0)
                  ;; Calculate z-index based on depth: deeper nodes should be on top
                  ;; depth * 10 gives each level 10 z-index units
                  ;; +5 for leaf states ensures they're slightly above compound states at same depth
                  z-index    (+ (* (or depth 0) 10) (if compound? 0 5))]

              (cond
                ;; Initial state - small filled circle
                initial?
                (dom/div {:key   (pr-str id)
                          :style {:position        "absolute"
                                  :zIndex          z-index
                                  :backgroundColor "black"
                                  :borderRadius    "50%"
                                  :width           "20px"
                                  :height          "20px"
                                  :boxShadow       "0 2px 4px rgba(0,0,0,0.2)"
                                  :top             elk-y
                                  :left            elk-x}
                          :ref   (:ref (meta node))})

                ;; Final state - double circle
                (= node-type :final)
                (dom/div {:key   (pr-str id)
                          :style {:position        "absolute"
                                  :zIndex          z-index
                                  :border          "3px solid black"
                                  :borderRadius    "50%"
                                  :width           (str elk-width "px")
                                  :height          (str elk-height "px")
                                  :display         "flex"
                                  :alignItems      "center"
                                  :justifyContent  "center"
                                  :backgroundColor "white"
                                  :boxShadow       "0 2px 8px rgba(0,0,0,0.15)"
                                  :top             elk-y
                                  :left            elk-x}
                          :ref   (:ref (meta node))}
                  (dom/div {:style {:border       "3px solid black"
                                    :borderRadius "50%"
                                    :width        "60%"
                                    :height       "60%"}}))

                ;; History node - circle with H (shallow) or H* (deep) inside
                (= node-type :history)
                (dom/div {:key   (pr-str id)
                          :style {:position        "absolute"
                                  :zIndex          z-index
                                  :border          "3px solid #5c6bc0"
                                  :borderRadius    "50%"
                                  :width           (str elk-width "px")
                                  :height          (str elk-height "px")
                                  :display         "flex"
                                  :alignItems      "center"
                                  :justifyContent  "center"
                                  :backgroundColor "#f0f4ff"
                                  :boxShadow       "0 3px 10px rgba(92,107,192,0.3)"
                                  :fontSize        "24px"
                                  :fontWeight      "bold"
                                  :fontStyle       "italic"
                                  :color           "#5c6bc0"
                                  :top             elk-y
                                  :left            elk-x}
                          :ref   (:ref (meta node))}
                  (if (:deep? node) "H*" "H"))

                ;; Regular states (compound or simple) and parallel states
                :else
                (let [state-element (get elements-by-id id)
                      entry-labels  (when state-element (chart/state-entry-labels elements-by-id state-element))
                      exit-labels   (when state-element (chart/state-exit-labels elements-by-id state-element))
                      has-activities? (or (seq entry-labels) (seq exit-labels))]
                  (dom/div {:key   (pr-str id)
                            :style (cond-> {:position        "absolute"
                                            :zIndex          z-index
                                            :border          (str (if (= node-type :parallel) "3px "
                                                                                            (if compound? "3px " "2px "))
                                                               (if (= node-type :parallel) "dashed " "solid ")
                                                               (if (active? id) "#e53935"
                                                                                (if (= node-type :parallel) "#5c6bc0"
                                                                                                            (if compound? "#1976d2" "#424242"))))
                                            :borderRadius    "12px"
                                            :padding         "20px"
                                            :backgroundColor (cond
                                                               (active? id) "#ffebee"
                                                               (= node-type :parallel) "#f0f4ff"
                                                               compound? "#e3f2fd"
                                                               :else "white")
                                            :boxShadow       (cond
                                                               (= node-type :parallel) "0 4px 12px rgba(92,107,192,0.2)"
                                                               compound? "0 3px 10px rgba(25,118,210,0.15)"
                                                               :else "0 2px 4px rgba(0,0,0,0.12)")
                                            :boxSizing       "border-box"
                                            :minWidth        "160px"
                                            :width           (str elk-width "px")
                                            :height          (str elk-height "px")
                                            :top             elk-y
                                            :left            elk-x
                                            :display         "flex"
                                            :flexDirection   "column"})
                            :ref   (:ref (meta node))}
                    ;; Label
                    (dom/div {:style (if compound?
                                       {:position        "absolute"
                                        :top             "-12px"
                                        :left            "12px"
                                        :backgroundColor (cond
                                                           (active? id) "#e53935"
                                                           (= node-type :parallel) "#5c6bc0"
                                                           :else "#1976d2")
                                        :color           "white"
                                        :padding         "4px 12px"
                                        :borderRadius    "12px"
                                        :fontSize        "13px"
                                        :fontWeight      "600"
                                        :boxShadow       "0 2px 4px rgba(0,0,0,0.2)"
                                        :whiteSpace      "nowrap"}
                                       {:fontSize     "14px"
                                        :fontWeight   "500"
                                        :color        (if (active? id) "#c62828"
                                                                     (if (= node-type :parallel) "#5c6bc0" "#424242"))
                                        :marginBottom "4px"
                                        :whiteSpace   "nowrap"})}
                      (element-label node))
                    ;; Activity compartment (entry/exit labels)
                    (when has-activities?
                      (comp/fragment
                        ;; Divider line
                        (dom/hr {:style {:margin      "4px 0"
                                         :border      "none"
                                         :borderTop   "1px solid #ccc"
                                         :width       "100%"}})
                        ;; Activity lines
                        (dom/div {:style {:fontSize   "11px"
                                          :fontFamily "system-ui, -apple-system, sans-serif"
                                          :color      "#666"
                                          :lineHeight "1.4"}}
                          (mapv (fn [lbl]
                                  (dom/div {:key (str "entry-" lbl)} (str "entry / " lbl)))
                            entry-labels)
                          (mapv (fn [lbl]
                                  (dom/div {:key (str "exit-" lbl)} (str "exit / " lbl)))
                            exit-labels)))))))))
          states)))))

(def ui-visualizer
  "[props {:keys [session-id]}]

   Render the visualizer. If you include a session ID then the current configuration will be highlighted."
  (comp/computed-factory Visualizer))
