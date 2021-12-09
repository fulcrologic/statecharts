(ns com.fulcrologic.statecharts.state-machine-spec
  (:require
    [com.fulcrologic.statecharts.elements :refer [state parallel
                                                  history final initial
                                                  on-enter on-exit invoke
                                                  data-model
                                                  transition]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.util :refer [queue]]
    [com.fulcrologic.statecharts.state-machine :as sm :refer [machine]]
    [fulcro-spec.core :refer [specification assertions =>]])
  #?(:clj
     (:import (clojure.lang PersistentQueue))))

(let [substates [(state {:id :s0}
                   (transition {:id :t1})
                   (transition {:id :t2})
                   (transition {:id :t3})
                   (state {:id :s0.0}))
                 (state {:id :s1}
                   (state {:id :s1.1}
                     (state {:id :s1.1.1})))
                 (state {:id :s2}
                   (parallel {:id :p}
                     (state {:id :p1}
                       (final {:id :f2}))
                     (state {:id :p2})
                     (state {:id :p3})))]
      m         (apply machine {} substates)]
  (specification "element"
    (let [n (fn [id] (= id (:id (sm/element m id))))]
      (assertions
        "returns the machine if passed the machine as a node"
        (sm/element m m) => m
        "Can find any node by ID"
        (n :s0) => true
        (n :s0.0) => true
        (n :s1) => true
        (n :s2) => true
        "Returns the node unchanged if it isn't in the machine"
        (sm/element m (state {:id :s99})) => (state {:id :s99})
        "Allows a node with ID to be used, but returns the real element"
        (sm/element m (state {:id :s0})) => (sm/element m :s0)
        "Returns a node whose children are normalized into a vector of IDs in doc order"
        (vector? (:children (sm/element m :s0))) => true
        (:children (sm/element m :s0)) => [:t1 :t2 :t3 :s0.0])))

  (specification "get-parent"
    (assertions
      "Can find the parent of any node"
      (sm/get-parent m :s1.1.1) => :s1.1
      (sm/get-parent m :s1.1) => :s1
      (sm/get-parent m :s0.0) => :s0
      (sm/get-parent m :s0) => nil))

  (specification "get-children"
    (assertions
      "Finds child node ids of a given type"
      (sm/get-children m :s0 :transition) => [:t1 :t2 :t3]
      "Can find the immediate children of a machine"
      (sm/get-children m m :state) => [:s0 :s1 :s2]
      "Can find the immediate children of a node"
      (sm/get-children m (sm/element m :s0) :state) => [:s0.0]
      "returns the children in document order"
      (sm/get-children m :s0 :transition) => [:t1 :t2 :t3]))

  (specification "state?"
    (assertions
      "Is true for final, state, and parallel nodes."
      (sm/state? m (state {})) => true
      (sm/state? m (final {})) => true
      (sm/state? m (parallel {})) => true
      (sm/state? m :s0) => true
      (sm/state? m :t1) => false))

  (specification "nearest-ancestor-state"
    (assertions
      "Returns the ID of the nearest ancestor state"
      (sm/nearest-ancestor-state m :t1) => :s0
      (sm/nearest-ancestor-state m :p1) => :p
      (sm/nearest-ancestor-state m :s1.1.1) => :s1.1))

  (specification "child-states"
    (assertions
      "Returns the IDs of children that are any kind of state"
      (sm/child-states m :s2) => [:p]
      (sm/child-states m :p) => [:p1 :p2 :p3]
      (sm/child-states m :p1) => [:f2]
      (sm/child-states m m) => [:s0 :s1 :s2]))

  (specification "descendant?"
    (assertions
      (sm/all-descendants m :s2) => #{:p :p1 :p2 :p3 :f2}
      "Returns true if s1 is a descendant of s2"
      (sm/descendant? m :p3 :p) => true
      (sm/descendant? m :p3 :s2) => true
      (sm/descendant? m :s1.1.1 :s1.1) => true
      (sm/descendant? m :s1.1 :s1) => true
      (sm/descendant? m :s1.1.1 :s1) => true
      (sm/descendant? m :s1 :s1.1.1) => false
      (sm/descendant? m :s2 :s1.1.1) => false
      (sm/descendant? m :p :s1) => false))

  (specification "atomic-state?"
    (assertions
      "Returns true iff the node is a state, and has no substates"
      (sm/atomic-state? m :s0.0) => true
      (sm/atomic-state? m :s1.1.1) => true
      (sm/atomic-state? m :p2) => true
      (sm/atomic-state? m :p3) => true
      (sm/atomic-state? m :f2) => true)
    (assertions
      "Returns false on non-atomic states"
      (sm/atomic-state? m :s0) => false
      (sm/atomic-state? m :s1) => false
      (sm/atomic-state? m :s1.1) => false
      (sm/atomic-state? m :s2) => false
      (sm/atomic-state? m :p) => false
      (sm/atomic-state? m :p1) => false)
    (assertions
      "Returns false on non-state nodes (even with no children)"
      (sm/atomic-state? m :t1) => false
      (sm/atomic-state? m :t2) => false
      (sm/atomic-state? m :t3) => false))

  (specification "in-document-order"
    (let [mb (apply machine {::sc/document-order :breadth-first} substates)]
      (assertions
        "Returns a vector of the state IDs in (depth-first) document order"
        (sm/in-document-order m #{:s2 :t1 :s1.1.1 :s1.1 :s0}) => [:s0
                                                                  :t1
                                                                  :s1.1
                                                                  :s1.1.1
                                                                  :s2]
        "Can be forced to use breadth-first"
        (sm/in-document-order mb #{:s2 :t1 :s1.1.1 :s1.1 :s0}) => [:s0 :s2 :t1 :s1.1 :s1.1.1])))

  (specification "get-proper-ancestors"
    (assertions
      "Returns an empty list if there are no ancestors"
      (sm/get-proper-ancestors m :s2) => []
      "Returns a vector of node IDs that are ancestors of the specified ID, in ancestry order"
      (sm/get-proper-ancestors m :s1.1.1) => [:s1.1 :s1]
      (sm/get-proper-ancestors m :t2) => [:s0]
      "Can stop at (and not include) a given node"
      (sm/get-proper-ancestors m :s1.1.1 :s1) => [:s1.1]))

  (specification "compound-state?"
    (assertions
      "Is true only for non-parallel states that contain other states"
      (sm/compound-state? m :s0) => true
      (sm/compound-state? m :s1.1) => true
      (sm/compound-state? m :s1.1.1) => false
      (sm/compound-state? m :p) => false
      (sm/compound-state? m :p1) => true))

  (specification "parallel-state?"
    (assertions
      "Is true only for parallel states"
      (sm/parallel-state? m :s1) => false
      (sm/parallel-state? m :t1) => false
      (sm/parallel-state? m :p) => true
      (sm/parallel-state? m :p1) => false))

  (specification "")
  )



