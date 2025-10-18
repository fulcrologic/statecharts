(ns com.fulcrologic.statecharts.visualization.elk
  (:require
    [clojure.core.async :as async]
    ["elkjs/lib/elk.bundled.js" :as elk]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.dom :as dom]))

(defn layout!
  "Returns an async channel on which the resulting layout will be returned"
  [graph]
  (let [^js e (new elk)
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

(defn render-edge [{:keys [id sections labels] :as props}]
  (let [paths (mapv
                (fn [{:keys [startPoint endPoint bendPoints]}]
                  (if (seq bendPoints)
                    ;; Path with bend points
                    (let [all-points (concat [startPoint] bendPoints [endPoint])
                          path-parts (map-indexed
                                       (fn [idx point]
                                         (cond
                                           (= idx 0) (str "M " (:x point) " " (:y point))
                                           :else (str "L " (:x point) " " (:y point))))
                                       all-points)]
                      (str/join " " path-parts))
                    ;; Straight line if no bend points
                    (str "M " (:x startPoint) " " (:y startPoint)
                      " L " (:x endPoint) " " (:y endPoint))))
                sections)]
    (dom/g {:key id}
      ;; Render edge paths with unique keys for each path
      (map-indexed
        (fn [idx p]
          (dom/path {:key            (str id "-path-" idx)
                     :fill           "transparent"
                     :markerEnd      "url(#arrowhead)"
                     :strokeWidth    "2"
                     :stroke         "#616161"
                     :strokeLinecap  "round"
                     :strokeLinejoin "round"
                     :d              p}))
        paths)
      ;; Render labels using ELK's calculated positions with unique keys
      (when (seq labels)
        (map-indexed
          (fn [idx {:keys [text x y width height] :as label}]
            (when (and text x y)
              (dom/g {:key (str id "-label-" idx)}
                ;; Background rectangle for better readability
                (dom/rect {:x           x
                           :y           y
                           :width       width
                           :height      height
                           :fill        "white"
                           :stroke      "#9e9e9e"
                           :strokeWidth "1"
                           :rx          "4"
                           :opacity     "0.95"})
                ;; Label text - centered in the rect
                (dom/text {:x                (+ x (/ width 2))
                           :y                (+ y (/ height 2))
                           :textAnchor       "middle"
                           :dominantBaseline "middle"
                           :fill             "#424242"
                           :fontSize         "12px"
                           :fontWeight       "500"
                           :fontFamily       "system-ui, -apple-system, sans-serif"}
                  text))))
          labels)))))

(defn render-edges [{:keys [width height edges]}]
  (dom/svg {:viewBox (str "0 0 " width " " height) :width "100%" :height "100%" :xmlns "http://www.w3.org/2000/svg"
            :style   {:position :absolute :top 0 :left 0 :pointerEvents "none" :zIndex 100}}
    (dom/defs
      (dom/marker
        {:id           "arrowhead"
         :markerWidth  "7"
         :markerHeight "7"
         :refX         "6"
         :refY         "2"
         :orient       "auto"
         :markerUnits  "strokeWidth"}
        (dom/path {:d "M0,0 L0,4 L6,2 z" :fill "#616161"})))
    (mapv render-edge edges)))
