(ns com.fulcrologic.statecharts.state-machine-spec
  (:require
    [com.fulcrologic.statecharts.elements :refer [state parallel
                                                  history final initial
                                                  on-enter on-exit invoke
                                                  data-model
                                                  transition]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.util :refer [queue]]
    [com.fulcrologic.statecharts.state-machine :as sm]
    [fulcro-spec.core :refer [specification assertions =>]]
    [taoensso.timbre :as log])
  #?(:clj
     (:import (clojure.lang PersistentQueue))))

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
                   (initial {:id :s1.1}
                     (state {:id :s1.1.1})))
                 (state {:id :s2}
                   (parallel {:id :p}
                     (state {:id :p1}
                       (final {:id :f2}))
                     (state {:id :p2})
                     (state {:id :p3})))]
      m         (apply sm/machine {} substates)]
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
        (:children (sm/element m :s0)) => [:t1 :t2 :t3 :I1 :s0.0])))

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
      (sm/get-children m m :state) => [:I :s0 :s1 :s2]
      "Can find the immediate children of a node"
      (sm/get-children m (sm/element m :s0) :state) => [:I1 :s0.0]
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
    #_(assertions
        "Returns the IDs of children that are any kind of state"
        (sm/child-states m :s2) => [:p]
        (sm/child-states m :p) => [:p1 :p2 :p3]
        (sm/child-states m :p1) => [:f2]
        (sm/child-states m m) => [:I :s0 :s1 :s2]))

  (specification "descendant?"
    #_(assertions
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
    (let [mb (apply sm/machine {::sc/document-order :breadth-first} substates)]
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

  (specification "initial-element"
    (assertions
      "can find the element of the machine"
      (map? (sm/initial-element m m)) => true
      (:id (sm/initial-element m m)) => :I
      "can find the initial element of a substate"
      (:id (sm/initial-element m :s1)) => :s1.1))

  (specification "transition-element"
    (assertions
      "can find the transition element (first) of a state"
      (:id (sm/transition-element m (sm/initial-element m m))) => :it)))

(specification "with-working-mem"
  (assertions
    "Returns the working memory with only the internal additions."
    (sm/with-working-memory [m {}]
      (assertions
        "Adds required processing initial items to memory"
        (::sc/enabled-transitions m) => #{}
        (::sc/states-to-invoke m) => #{}
        (::sc/internal-queue m) => (queue))
      (assoc m :x 1))
    => {:x 1}))

(def equipment
  (sm/machine {}
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
  (let [machine (sm/machine {}
                  (state {:id :A}
                    (transition {:event  :trigger
                                 :target :B}))
                  (state {:id :B}
                    (transition {:event  :trigger
                                 :target :A}
                      )
                    (state {:id :C}
                      (state {:id :C1}))
                    (state {:id :D})))
        wmem    (sm/initialize machine)]
    (assertions
      "Finds the machine if there is no other alternative"
      (sm/find-least-common-compound-ancestor machine #{:A :B}) => machine
      "Finds the common compound ancestor"
      (sm/find-least-common-compound-ancestor machine #{:C1 :D}) => (sm/element machine :B)
      (sm/find-least-common-compound-ancestor machine #{:C1 :A}) => machine
      (sm/find-least-common-compound-ancestor machine #{:C1}) => (sm/element machine :C)
      (sm/find-least-common-compound-ancestor machine #{:D}) => (sm/element machine :B)
      (sm/find-least-common-compound-ancestor machine #{:D :C}) => (sm/element machine :B))))

(specification "in-final-state?"
  (let [machine (sm/machine {}
                  (state {:id :A}
                    (transition {:event  :trigger
                                 :target :B})
                    (parallel {:id :P}
                      (state {:id :P1}
                        (initial {})
                        (final {:id :p1e}))
                      (state {:id :P2}
                        (initial {})
                        (final {:id :p2e}))
                      (state {:id :P3}
                        (initial {})
                        (final {:id :p3e}))))
                  (state {:id :B}
                    (transition {:event  :trigger
                                 :target :A})
                    (final {:id :END})
                    (state {:id :C}
                      (state {:id :C1}))
                    (state {:id :D})))
        wmem    (sm/initialize machine)
        config  (fn [c] (assoc wmem ::sc/configuration c))]
    (assertions
      "Returns true if the given compound state is in it's final state "
      (sm/in-final-state? machine (config #{}) :A) => false
      (sm/in-final-state? machine (config #{:END}) :B) => true
      (sm/in-final-state? machine (config #{:C1}) :B) => false
      "Returns true if a parallel state has ALL children in a final state"
      (sm/in-final-state? machine (config #{}) :P) => false
      (sm/in-final-state? machine (config #{:p1e :p2e}) :P) => false
      (sm/in-final-state? machine (config #{:p1e :p2e :p3e}) :P) => true)))

(specification "compute-entry-set"
  #_(let [machine (machine {}
                    (initial {}
                      (transition {:id     :I
                                   :target :A}))
                    (state {:id :A}
                      (initial {:target :P})
                      (transition {:id     :t1
                                   :event  :trigger
                                   :target :B})
                      (parallel {:id :P}
                        (state {:id :P1}
                          (state {:id :p1i})
                          (final {:id :p1e}))
                        (state {:id :P2}
                          (state {:id :p2i})
                          (final {:id :p2e}))
                        (state {:id :P3}
                          (state {:id :p3i})
                          (final {:id :p3e}))))
                    (state {:id :B}
                      (transition {:event  :trigger
                                   :target :A})
                      (final {:id :END})
                      (state {:id :C}
                        (state {:id :C1}))
                      (state {:id :D})))
          config  (fn [c ts] {::sc/enabled-transitions ts
                              ::sc/configuration       c})]
      (assertions
        "An initialized machine's computed entry state is in the first state"
        (sm/compute-entry-set machine (config #{} #{:I})) => [#{:A :P :P1 :P2 :P3} #{:A} {}]
        (sm/compute-entry-set machine (config #{:A :P :P1 :P2 :P3} #{:t1}))
        => [#{:B} #{:B} {}])))

(specification "Root parallel state" :focus
  (let [wmem (sm/initialize equipment)
        c    (fn [mem] (::sc/configuration mem))]
    (log/info "********************************************************************************")
    (assertions
      "Enters proper states from initial"
      (c wmem) => #{:led/off :motor/off :Eq :motor :LED}
      "Transitions on events"
      (c (sm/process-event equipment wmem :start)) => #{:led/on :motor/on :Eq :motor :LED}
      "Transitions on additional events"
      (c (as-> wmem $
           (sm/process-event equipment $ :start)
           (sm/process-event equipment $ :stop))) => #{:led/off :motor/off :Eq :motor :LED})))

(specification "Basic machine"
  (let [machine (sm/machine {}
                  #_(initial {}
                      (transition {:target :A}))
                  (state {:id :A}
                    (transition {:event  :trigger
                                 :target :B}))
                  (state {:id :B}
                    (transition {:event  :trigger
                                 :target :A})))
        wmem    (sm/initialize machine)
        c       (fn [mem] (::sc/configuration mem))]
    (assertions
      "Enters the first state if there is no initial"
      (c wmem) => #{:A}
      "Transitions on events"
      (c (sm/process-event machine wmem :trigger)) => #{:B}
      (c (as-> wmem $
           (sm/process-event machine $ :trigger)
           (sm/process-event machine $ :trigger))) => #{:A})))
