(ns com.fulcrologic.statecharts.chart-spec
  (:require
    [com.fulcrologic.statecharts.elements :refer [state initial parallel final transition]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [fulcro-spec.core :refer [specification assertions =>]]))


(let [substates [(initial {:id :I}
                   (transition {:id     :it
                                :target :s0}))
                 (state {:id :s0}
                   (transition {:id :t1 :target :s1})
                   (transition {:id :t2 :target :s1})
                   (transition {:id :t3 :target :s1})
                   (initial {:id :I1}
                     (transition {:target :s0.0}))
                   (state {:id :s0.0}))
                 (state {:id :s1}
                   (initial {:id :s1i} (transition {:id :t1i :target :s1.1}))
                   (state {:id :s1.1}
                     (initial {:id :s1.1i} (transition {:id :t1.1i :target :s1.1.1}))
                     (state {:id :s1.1.1})
                     (state {:id :s1.1.2})))
                 (state {:id :s2}
                   (parallel {:id :p}
                     (state {:id :p1}
                       (final {:id :f2}))
                     (state {:id :p2})
                     (state {:id :p3})))]
      m         (apply chart/statechart {} substates)]
  (specification "element"
    (let [n (fn [id] (= id (:id (chart/element m id))))]
      (assertions
        "returns the machine if passed the machine as a node"
        (chart/element m m) => m
        "returns the machine if passed the special key :ROOT as a node"
        (chart/element m :ROOT) => m
        "Can find any node by ID"
        (n :s0) => true
        (n :s0.0) => true
        (n :s1) => true
        (n :s2) => true
        "Returns the node unchanged if it isn't in the machine"
        (chart/element m (state {:id :s99})) => (state {:id :s99})
        "Allows a node with ID to be used, but returns the real element"
        (chart/element m (state {:id :s0})) => (chart/element m :s0)
        "Returns a node whose children are normalized into a vector of IDs in doc order"
        (vector? (:children (chart/element m :s0))) => true
        (:children (chart/element m :s0)) => [:t1 :t2 :t3 :I1 :s0.0])))

  (specification "get-parent"
    (assertions
      "Can find the parent of any node"
      (chart/get-parent m :s1.1.1) => :s1.1
      (chart/get-parent m :s1.1) => :s1
      (chart/get-parent m :s0.0) => :s0
      "A node without a parent returns :ROOT"
      (chart/get-parent m :s0) => :ROOT))

  (specification "all-descendants"
    (let [all-ids (set (keys (::sc/elements-by-id m)))]
      (assertions
        "Can find the descendants of the entire machine"
        (chart/all-descendants m :ROOT) => all-ids
        "Can find the descendants of any node"
        (chart/all-descendants m :s1) => #{:s1.1.2 :s1.1 :s1.1.1 :t1i :s1.1i :s1i :t1.1i})))

  (specification "get-children"
    (assertions
      "Finds child node ids of a given type"
      (chart/get-children m :s0 :transition) => [:t1 :t2 :t3]
      "Can find the immediate children of a machine"
      (chart/get-children m m :state) => [:I :s0 :s1 :s2]
      "Can find the immediate children of a node"
      (chart/get-children m (chart/element m :s0) :state) => [:I1 :s0.0]
      "returns the children in document order"
      (chart/get-children m :s0 :transition) => [:t1 :t2 :t3]))

  (specification "state?"
    (assertions
      "Is true for final, state, and parallel nodes."
      (chart/state? m (state {})) => true
      (chart/state? m (final {})) => true
      (chart/state? m (parallel {})) => true
      (chart/state? m :s0) => true
      (chart/state? m :t1) => false))

  (specification "nearest-ancestor-state"
    (assertions
      "Returns the ID of the nearest ancestor state"
      (chart/nearest-ancestor-state m :t1) => :s0
      (chart/nearest-ancestor-state m :p1) => :p
      (chart/nearest-ancestor-state m :s1.1.1) => :s1.1))

  (specification "atomic-state?"
    (assertions
      "Returns true iff the node is a state, and has no substates"
      (chart/atomic-state? m :s0.0) => true
      (chart/atomic-state? m :s1.1.1) => true
      (chart/atomic-state? m :p2) => true
      (chart/atomic-state? m :p3) => true
      (chart/atomic-state? m :f2) => true)
    (assertions
      "Returns false on non-atomic states"
      (chart/atomic-state? m :s0) => false
      (chart/atomic-state? m :s1) => false
      (chart/atomic-state? m :s1.1) => false
      (chart/atomic-state? m :s2) => false
      (chart/atomic-state? m :p) => false
      (chart/atomic-state? m :p1) => false)
    (assertions
      "Returns false on non-state nodes (even with no children)"
      (chart/atomic-state? m :t1) => false
      (chart/atomic-state? m :t2) => false
      (chart/atomic-state? m :t3) => false))

  (specification "in-document-order"
    (let [mb (apply chart/statechart {::sc/document-order :breadth-first} substates)]
      (assertions
        "Returns a vector of the state IDs in (depth-first) document order"
        (chart/in-document-order m #{:s2 :t1 :s1.1.1 :s1.1 :s0}) => [:s0
                                                                  :t1
                                                                  :s1.1
                                                                  :s1.1.1
                                                                  :s2]
        "Can be forced to use breadth-first"
        (chart/in-document-order mb #{:s2 :t1 :s1.1.1 :s1.1 :s0}) => [:s0 :s2 :t1 :s1.1 :s1.1.1])))

  (specification "get-proper-ancestors"
    (assertions
      "Returns :ROOT if there are no ancestors"
      (chart/get-proper-ancestors m :s2) => [:ROOT]
      "Returns a vector of node IDs that are ancestors of the specified ID, in ancestry order"
      (chart/get-proper-ancestors m :s1.1.1) => [:s1.1 :s1 :ROOT]
      (chart/get-proper-ancestors m :t2) => [:s0 :ROOT]
      "Can stop at (and not include) a given node"
      (chart/get-proper-ancestors m :s1.1.1 :s1) => [:s1.1]))

  (specification "compound-state?"
    (assertions
      "Is true only for non-parallel states that contain other states"
      (chart/compound-state? m :s0) => true
      (chart/compound-state? m :s1.1) => true
      (chart/compound-state? m :s1.1.1) => false
      (chart/compound-state? m :p) => false
      (chart/compound-state? m :p1) => true))

  (specification "parallel-state?"
    (assertions
      "Is true only for parallel states"
      (chart/parallel-state? m :s1) => false
      (chart/parallel-state? m :t1) => false
      (chart/parallel-state? m :p) => true
      (chart/parallel-state? m :p1) => false))

  (specification "initial-element"
    (assertions
      "can find the element of the machine"
      (map? (chart/initial-element m m)) => true
      (:id (chart/initial-element m m)) => :I
      "can find the initial element of a substate"
      (:id (chart/initial-element m :s1)) => :s1i))

  (specification "transition-element"
    (assertions
      "can find the transition element (first) of a state"
      (:id (chart/transition-element m (chart/initial-element m m))) => :it)))

(def equipment
  (chart/statechart {}
    (initial {}
      (transition {:target :Eq}))

    (parallel {:id :Eq}
      (state {:id :motor}
        (initial {}
          (transition {:target :motor/off}))
        (state {:id :motor/off}
          (transition {:event :start :target :motor/on}))
        (state {:id :motor/on}
          (transition {:event :stop :target :motor/off})))
      (state {:id :LED}
        (initial {}
          (transition {:target :led/off}))
        (state {:id :led/on}
          (transition {:event :stop :target :led/off}))
        (state {:id :led/off}
          (transition {:event :start :target :led/on}))))))

(specification "find-least-common-compound-ancestor"
  (let [machine (chart/statechart {}
                  (state {:id :A}
                    (transition {:event  :trigger
                                 :target :B}))
                  (state {:id :B}
                    (transition {:event  :trigger
                                 :target :A})
                    (state {:id :C}
                      (state {:id :C1}))
                    (state {:id :D})))]
    (assertions
      "Finds the machine if there is no other alternative"
      (chart/find-least-common-compound-ancestor machine #{:A :B}) => machine
      "Finds the common compound ancestor"
      (chart/find-least-common-compound-ancestor machine #{:C1 :D}) => (chart/element machine :B)
      (chart/find-least-common-compound-ancestor machine #{:C1 :A}) => machine
      (chart/find-least-common-compound-ancestor machine #{:C1}) => (chart/element machine :C)
      (chart/find-least-common-compound-ancestor machine #{:D}) => (chart/element machine :B)
      (chart/find-least-common-compound-ancestor machine #{:D :C}) => (chart/element machine :B))))
