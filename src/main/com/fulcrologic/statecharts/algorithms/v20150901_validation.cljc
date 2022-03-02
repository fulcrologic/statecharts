(ns com.fulcrologic.statecharts.algorithms.v20150901-validation
  (:require
    [com.fulcrologic.statecharts.chart :as chart]))

(declare problems)

(defn warning [element msg] {:level :warn :element element :message msg})
(defn error [element msg] {:level :error :element element :message msg})
(defmulti element-problems
  "Extensible validation multimethod. You want `problems` if you're checking a statechart."
  (fn [chart element] (:node-type (chart/element chart element))))

(defmethod element-problems :default [chart {:keys [children] :as ele}]
  (cond-> []
    (seq children) (into (mapcat #(problems chart %) children))))

(defmethod element-problems :parallel [chart {:keys [id children] :as ele}]
  (let [substate-ids (chart/child-states chart ele)]
    (cond-> []
      (< (count substate-ids) 2) (conj (warning ele (str "Parallel state " id " has fewer than 2 substates.")))
      (seq children) (into (mapcat #(problems chart %) children)))))


(defmethod element-problems :state [chart {:keys [id target event cond children] :as ele}]
  (let [tids           (chart/transitions chart ele)
        transitions    (map #(chart/element chart %) tids)
        by-event       (group-by (fn [e] [(:event e) (:cond e)]) transitions)
        ;; This case seems to be valid (at least it's supported in SCION), and document order should
        ;; take care of selecting the correct transition
        event-problems (for [k (keys by-event)
                             :when (> (count (get by-event k)) 1)]
                         (warning ele (str "More than one transition in state " id
                                        " has the exact same event and condition: " (get by-event k))))]
    (cond-> []
      (seq event-problems) (into event-problems)
      (seq children) (into (mapcat #(problems chart %) children)))))

(defmethod element-problems :transition [chart {:keys [target event cond children] :as ele}]
  (cond-> []
    (and target
      (some #(nil? (chart/element chart %)) target)) (conj (error ele
                                                             (str "Transition in state " (chart/get-parent chart ele)
                                                               " uses a target that is not defined: " target)))
    (seq children) (into (mapcat #(problems chart %) children))))

(defmethod element-problems :statechart [chart {:keys [name version initial children]}]
  (let []
    (cond-> []
      ;(nil? version) (conj (warning "Machine doesn't specify a version"))
      ;(nil? name) (conj (warning "Machine doesn't specify a name"))
      (seq children) (into (mapcat #(problems chart %) children)))))


(defn problems
  "Returns a sequence of problems with the given statechart. Each problem is a map with:

   * :level - :warn or :error
   * :element - The element in question (except for top-level)
   * :message - A user-readable description of the problem."
  ([statechart item]
   (element-problems statechart (chart/element statechart item)))
  ([statechart]
   (problems statechart statechart)))
