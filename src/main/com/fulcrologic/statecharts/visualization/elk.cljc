(ns com.fulcrologic.statecharts.visualization.elk
  (:require
    #?@(:cljs [[com.fulcrologic.fulcro.dom :as dom]
               ["elkjs/lib/elk.bundled.js" :as elk]]
        :clj  [[com.fulcrologic.fulcro.dom-server :as dom]
               [clojure.data.json :as json]])
    [com.fulcrologic.fulcro.components :as comp]
    [clojure.string :as str]
    [clojure.core.async :as async]
    [taoensso.timbre :as log])
  #?(:clj
     (:import
       (java.io StringWriter)
       [org.eclipse.elk.graph ElkBendPoint ElkEdge ElkEdgeSection ElkNode]
       [org.eclipse.elk.core RecursiveGraphLayoutEngine]
       [org.eclipse.elk.core.util BasicProgressMonitor]
       [org.eclipse.elk.graph.json ElkGraphJson]
       [org.eclipse.elk.alg.layered LayeredLayoutProvider]
       [org.eclipse.elk.core.options CoreOptions])))

#?(:clj
   (defn elk-edges->edn [node]
     (let [edges (.getOutgoingEdges node)]
       (mapcat
         (fn [e]
           (let [sections (.getSections e)]
             (for [^ElkEdgeSection s sections]
               {:startPoint {:x (.getStartX s)
                             :y (.getStartY s)}
                :endPoint   {:x (.getEndX s)
                             :y (.getEndY s)}
                :sources    [(some-> (.getIncomingShape s) (.getIdentifier))]
                :targets    [(some-> (.getOutgoingShape s) (.getIdentifier))]
                :bendPoints (mapv
                              (fn [^ElkBendPoint p] {:x (.getX p) :y (.getY p)})
                              (.getBendPoints s))})))
         edges))))

#?(:clj
   (defn elk-node->edn [^ElkNode node]
     (let [children    (mapv
                         elk-node->edn
                         (.getChildren node))
           child-edges (mapcat :nested-edges children)]
       {:x            (.getX node)
        :id           (.getIdentifier node)
        :y            (.getY node)
        :width        (.getWidth node)
        :height       (.getHeight node)
        :nested-edges (elk-edges->edn node)
        :edges        child-edges
        :children     children})))

(defn layout!
  "Returns an async channel on which the resulting layout will be returned"
  [graph]
  #?(:cljs
     (let [e     (new elk)
           c     (async/chan)
           graph (clj->js graph)]
       (-> e
         (.layout graph)
         (.then (fn [result]
                  (async/go
                    (async/>! c (js->clj result :keywordize-keys true))))))
       c)
     :clj
     (let [sw      (StringWriter.)
           _       (json/write graph sw)
           json    (.toString sw)
           graph   (.toElk (ElkGraphJson/forGraph json))
           c       (async/chan)
           monitor (proxy [BasicProgressMonitor] []
                     (doDone [top levels]
                       (async/go
                         (async/>! c (elk-node->edn graph)))))]
       (.layout (RecursiveGraphLayoutEngine.) graph monitor)
       c)))

(defn render-node [{:keys [id x y width height children] :as props}]
  (dom/g {}
    (dom/rect
      (assoc (select-keys props [:x :y :width :height])
        :key id
        :fill "transparent"
        :stroke "black"))
    (mapv
      (fn [{cx :x cy :y :as child}]
        (render-node (assoc child :x (+ x cx) :y (+ y cy))))
      children)))

(defn render-edge [{:keys [id sections] :as props}]
  (let [paths (mapv
                (fn [{:keys [startPoint endPoint bendPoints]}]
                  (str/join " "
                    (flatten
                      [(str "M " (:x startPoint) " " (:y startPoint))
                       (mapv
                         (fn [{:keys [x y]}]
                           (str "L " x " " y))
                         bendPoints)
                       (str "L " (:x endPoint) " " (:y endPoint))])))
                sections)]
    (dom/g {:key id}
      (mapv
        (fn [p] (dom/path {:key         id
                           :fill        "transparent"
                           :markerEnd   "url(#arrowhead)"
                           :strokeWidth "2"
                           :stroke      "black"
                           :d           p}))
        paths))))

(defn render-edges [{:keys [width height edges]}]
  (dom/svg {:viewBox (str "0 0 " width " " height) :width "100%" :height "100%" :xmlns "http://www.w3.org/2000/svg"}
    (dom/defs
      (dom/marker
        {:id "arrowhead", :markerWidth "6", :markerHeight "7", :refX "5", :refY "3.5", :orient "auto"}
        (dom/polygon {:points "0 0, 6 3.5, 0 7", :fill "black"})))
    (mapv render-edge edges)))

(comment
  #?(:clj
     (dom/render-to-str (render-layout {}))))
