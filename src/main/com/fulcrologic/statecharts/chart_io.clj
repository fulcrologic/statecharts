(ns com.fulcrologic.statecharts.chart-io
  "Functions to read/write an approximation of SCXML to/from the statecharts of this
   library.

   VERY ALPHA.

   The input routines can be useful if you have a SCXML document as a starting point. The
   output uses plantuml because it was the best format for free tooling I could find."
  (:require
    [com.fulcrologic.statecharts.state-machine :as sm]
    [com.fulcrologic.statecharts :as sc]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str]
    [hickory.core :as hc]
    [taoensso.timbre :as log]))

(def coercions {:id     keyword
                :event  keyword
                :name   keyword
                :target (fn [t]
                          (let [targets (str/split t #" ")]
                            (if (= 1 (count targets))
                              (keyword (first targets))
                              (mapv keyword targets))))})

(defn- element->call
  ([elem]
   (element->call elem {}))
  ([elem {:keys [ns-alias] :as options}]
   (cond
     (and (string? elem)
       (let [elem (str/trim elem)]
         (or
           (= "" elem)
           (and
             (str/starts-with? elem "<!--")
             (str/ends-with? elem "-->"))
           (re-matches #"^[ \n]*$" elem)))) nil
     (string? elem) (str/trim elem)
     (vector? elem) (let [tag               (symbol (name (first elem)))
                          raw-props         (second elem)
                          attrs             (reduce-kv
                                              (fn [acc k v]
                                                (let [v (if (contains? coercions k)
                                                          ((get coercions k) v)
                                                          v)]
                                                  (assoc acc k v)))
                                              {}
                                              raw-props)
                          children          (keep (fn [c] (element->call c options)) (drop 2 elem))
                          expanded-children (reduce
                                              (fn [acc c]
                                                (if (vector? c)
                                                  (into [] (concat acc c))
                                                  (conj acc c)))
                                              []
                                              children)]
                      (concat (list) (keep identity
                                       [(if (seq ns-alias)
                                          (symbol ns-alias tag)
                                          (symbol tag))
                                        attrs]) expanded-children))
     :otherwise "")))

(defn scxml->cljc
  "Convert an SCXML fragment (containing just one top-level XML element) into a corresponding statecharts
   CLJC code fragment.

  Options is a map that can contain:

  - `ns-alias`: The primary element namespace alias to use.  If not set, the calls will not be namespaced.
  "
  ([^String scxml-fragment options]
   (let [hiccup-list (map hc/as-hiccup (hc/parse-fragment scxml-fragment))]
     (let [result (keep (fn [e] (element->call e options)) hiccup-list)]
       (if (< 1 (count result))
         (vec result)
         (first result)))))
  ([^String html-fragment]
   (scxml->cljc html-fragment {:ns-alias "ele"})))

(defmulti attribute-value (fn [node k v] k))
(defmethod attribute-value :default [node k v] v)
(defmethod attribute-value :children [node k v] nil)
(defmethod attribute-value :parent [node k v] nil)
(defmethod attribute-value :node-type [node k v] nil)
(defmethod attribute-value :binding [node k v] (some-> v name))
(defmethod attribute-value :initial [node k v] (some-> v name))
(defmethod attribute-value :diagram/label [node k v] nil)
(defmethod attribute-value ::sc/elements-by-id [node k v] nil)
(defmethod attribute-value ::sc/ids-in-document-order [node k v] nil)
(defmethod attribute-value :id [node k v] (some-> v (name)))
(defmethod attribute-value :type [node k v] (some-> v (name)))
(defmethod attribute-value :target [node k v] (when (seq v)
                                                (str/join " " (map name v))))
(defmethod attribute-value :expr [{:diagram/keys [label] :as node} k v] label)
(defmethod attribute-value :event [{:diagram/keys [label] :as node} k v] (some-> v (name)))

(def attr-name->scxml
  {:initial? :initial})

(defn attributes [element]
  (str/join " "
    (reduce-kv
      (fn attr-accum* [acc k v]
        (if-let [v (attribute-value element k v)]
          (conj acc (str (name (get attr-name->scxml k k)) "=\"" v \"))
          acc))
      []
      element)))

(def node-type->tag {:machine    :scxml
                     :on-exit    :onexit
                     :data-model :datamodel
                     :on-entry   :onentry})

(defn indent [level] (str/join (repeat level "  ")))

(declare write-element)
(defn to-plantuml [chart element nesting-level]
  (write-element chart (sm/element chart element) nesting-level))

(defn plantuml-transition [chart source-name {:diagram/keys [condition]
                                              :keys         [target event cond] :as transition} nesting-level]
  (mapv
    (fn [target]
      (let [{:keys [id deep?] :as target-element} (sm/element chart target)
            history?        (sm/history-element? chart target)
            ;; History nodes are different in UML...
            uml-target      (if history? (sm/get-parent chart target) target)
            condition-label (and cond (or condition "???"))
            labeled?        (or event condition-label)]
        (str (indent nesting-level) source-name " --> " (some-> uml-target name)
          (clojure.core/cond deep? "[H*]" history? "[H]" :else "")
          (when labeled? " : ")
          (when event (str (name event)))
          (when condition-label (str " [" condition-label "]"))
          "\n")))
    target))

(defn plantuml-transitions [chart state nesting-level]
  (let [transition-ids (sm/transitions chart state)
        id             (sm/element-id chart state)
        tsource        (name id)]
    (str/join ""
      (vec
        (mapcat
          (fn [tid]
            (let [transition (sm/element chart tid)]
              (plantuml-transition chart tsource transition nesting-level)))
          transition-ids)))))

(defmulti write-element
  (fn dispatch* [chart {:keys [id node-type initial?] :as element} nesting-level]
    (cond
      initial? :initial
      (sm/atomic-state? chart element) :atomic-state
      :else node-type)))

(defn uml-priority [chart a b]
  (cond
    (sm/initial? chart a) -1
    (and
      (sm/atomic-state? chart a)
      (sm/atomic-state? chart b)) 0
    (sm/atomic-state? chart a) -1
    (= (:node-type a) (:node-type b)) 0
    :else 1))

(defn render-children [chart children nesting-level]
  (map #(to-plantuml chart % nesting-level)
    (sort-by (fn [id] (sm/element chart id)) (partial uml-priority chart) children)))

(defmethod write-element :initial [chart {:keys [name node-type children] :as element} nesting-level]
  (let [transition (sm/element chart (first (sm/transitions chart element)))]
    (str/join ""
      (plantuml-transition chart "[*]" transition nesting-level))))

(defn executable-content [chart element]

  )

(defmethod write-element :state [chart {:keys [id children] :as element} nesting-level]
  (let [choice-nodes (into [] (filter (partial sm/condition-node? chart)) children)
        child-level  (inc nesting-level)]
    (str
      (indent nesting-level) "state " (name id) " {\n"
      (executable-content chart element)
      ;; Have to pre-declare styles
      (str/join "\n"
        (map
          (fn [cid]
            (let [{:keys [id]} (sm/element chart cid)]
              (str (indent child-level) "state " (some-> id name) " <<choice>>\n")))
          choice-nodes))
      (str/join "" (render-children chart children child-level))
      (plantuml-transitions chart element child-level)
      (indent nesting-level) "}\n")))

(defmethod write-element :parallel [chart {:keys [id children] :as element} nesting-level]
  (str (indent nesting-level) "state " (name id) " {\n"
    (str/join (str (indent (inc nesting-level)) "--\n")
      (render-children chart children (inc nesting-level)))
    (indent nesting-level) "}\n"))

(defmethod write-element :atomic-state [chart {:keys [id node-type children] :as element} nesting-level]
  (let [state-decl (str (indent nesting-level) "state " (some-> id name) "\n")]
    (str
      state-decl
      (executable-content chart element)
      (plantuml-transitions chart element (inc nesting-level)))))

(defmethod write-element :machine [chart {:keys [name node-type children] :as element} nesting-level]
  (str
    "state " (or name "StateChart") " {\n"
    (str/join "" (render-children chart children (inc nesting-level)))
    "}\n"))

(defmethod write-element :history [chart {:keys [node-type deep? children] :as element} nesting-level]
  (let [p                  (->> element (sm/get-parent chart) name)
        label              (str p (if deep? "[H*]" "[H]"))
        default-transition (some->> element
                             (sm/transitions chart)
                             (first)
                             (sm/element chart))]
    (str/join "" (plantuml-transition chart label default-transition nesting-level))))

(defmethod write-element :default [chart {:keys [node-type children] :as element} nesting-level]
  (log/warn "No renderer for node" element))

(defn statechart->plantuml [chart]
  (str
    "\n[plantuml]\n"
    "-----\n"
    (to-plantuml chart chart 0)
    "-----\n"))
