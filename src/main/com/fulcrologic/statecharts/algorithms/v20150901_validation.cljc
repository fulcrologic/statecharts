(ns com.fulcrologic.statecharts.algorithms.v20150901-validation
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.elements :as elements :refer [state transition parallel]]
    [com.fulcrologic.statecharts.events :as evts :refer [new-event]]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    com.fulcrologic.statecharts.specs
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.state-machine :as sm]
    [com.fulcrologic.statecharts.util :refer [queue genid]]
    [taoensso.timbre :as log]
    [com.fulcrologic.statecharts.environment :as env]))

(declare problems)

(defn warning [element msg] {:level :warn :element element :message msg})
(defn error [element msg] {:level :error :element element :message msg})
(defmulti element-problems
  "Extensible validation multimethod. You want `problems` if you're checking a statechart."
  (fn [chart element] (:node-type (sm/element chart element))))

(defmethod element-problems :default [chart {:keys [children] :as ele}]
  (cond-> []
    (seq children) (into (mapcat #(problems chart %) children))))

(defmethod element-problems :parallel [chart {:keys [id children] :as ele}]
  (let [substate-ids (sm/child-states chart ele)]
    (cond-> []
      (< (count substate-ids) 2) (conj (warning ele (str "Parallel state " id " has fewer than 2 substates.")))
      (seq children) (into (mapcat #(problems chart %) children)))))

(defmethod element-problems :state [chart {:keys [id target event cond children] :as ele}]
  (let [tids           (sm/transitions chart ele)
        transitions    (map #(sm/element chart %) tids)
        by-event       (group-by (fn [e] [(:event e) (:cond e)]) transitions)
        event-problems (for [k (keys by-event)
                             :when (> (count (get by-event k)) 1)]
                         (error ele (str "More than one transition in state " id
                                      " has the exact same event and condition: " (get by-event k))))]
    (cond-> []
      (seq event-problems) (into event-problems)
      (seq children) (into (mapcat #(problems chart %) children)))))

(defmethod element-problems :transition [chart {:keys [target event cond children] :as ele}]
  (cond-> []
    (and target
      (some #(nil? (sm/element chart %)) target)) (conj (error ele
                                                          (str "Transition in state " (sm/get-parent chart ele)
                                                            " uses a target that is not defined: " target)))
    (seq children) (into (mapcat #(problems chart %) children))))

(defmethod element-problems :machine [chart {:keys [name version initial children]}]
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
   (element-problems statechart (sm/element statechart item)))
  ([statechart]
   (problems statechart statechart)))

(def m (sm/machine {}
         (parallel {:id :X})
         (state {:id :A}
           (transition {:event :X :cond 42 :target :B})
           (transition {:event :X :target :B})
           )
         (state {:id :B}
           (transition {:event  :X
                        :target :A}))))

(comment
  (problems m))
