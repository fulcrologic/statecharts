(ns com.fulcrologic.statecharts.visualization.elk
  (:require
    [clojure.core.async :as async]
    ["elkjs/lib/elk.bundled.js" :as elk]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.dom :as dom])
  )

(defn layout!
  "Returns an async channel on which the resulting layout will be returned"
  [graph]
  (let [e     (new elk)
        c     (async/chan)
        graph (clj->js graph)]
    (-> e
      (.layout graph)
      (.then (fn [result]
               (async/go
                 (async/>! c (js->clj result :keywordize-keys true))))))
    c))

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
