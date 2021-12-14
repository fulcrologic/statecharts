(ns com.fulcrologic.statecharts.state-machine
  "Implementation using a close approximation of https://www.w3.org/TR/2015/REC-scxml-20150901, including
   the suggested algorithm in that document, translated as closely as possible to CLJC. Future versions of
   the specification will appear as new namespaces to maintain compatibility through time.

   ::sc/k in the docstrings of this namespace assumes the alias `[com.fulcrologic.statecharts :as sc]`, which
   can be generated as only an alias, though an empty namespace of that name does exist."
  #?(:cljs (:require-macros [com.fulcrologic.statecharts.state-machine
                             :refer [with-working-memory]]))
  (:require
    com.fulcrologic.statecharts.specs
    [com.fulcrologic.guardrails.core :refer [>defn => ? >defn-]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.elements :as elements]
    [com.fulcrologic.statecharts.util :refer [queue genid]]
    [clojure.spec.alpha :as s]
    [taoensso.timbre :as log])
  #?(:clj (:import (java.util UUID))))

;; I did try to translate all that imperative code to something more functional...got really tiring, and generated
;; subtle bugs divergent from spec.
;; So, some internals are in an imperative style, which does make them easier to compare to the spec.
;; The main event loop from the spec is split into `before-event` and `process-event` so it can be
;; used in a non-imperative way.

(>defn ids-in-document-order
  "Returns the IDs of the states in the given node, in document order (not including the node itself).
   You can specify `::sc/document-order :breadth-first` on the top-level machine definition to get a
   depth-first interpretation vs. breadth."
  ([machine]
   [::sc/machine => (s/every keyword? :kind vector?)]
   (ids-in-document-order machine machine))
  ([{desired-order ::sc/document-order :as machine} {:keys [id] :as node}]
   [::sc/machine ::sc/element => (s/every keyword? :kind vector?)]
   (if (= :breadth-first desired-order)
     (let [states     (:children node)
           base-order (mapv :id states)]
       (into base-order
         (mapcat #(ids-in-document-order machine %))
         states))
     (let [children (:children node)]
       (cond-> []
         id (conj id)
         (seq children) (into (mapcat #(ids-in-document-order machine %) children)))))))

(>defn- with-default-initial-state
  "Scans children for an initial state. If no such state is found then it creates one and sets the target to the
   first child that is a state."
  [{:keys [id initial] :as parent} children]
  [(? ::sc/element) (s/every ::sc/element) => (s/every ::sc/element)]
  (let [states (filter #(#{:state :parallel} (:node-type %)) children)]
    (when (and initial (some :initial? states))
      (log/warn "You specified BOTH an :initial attribute and state in element. Preferring the element." id))
    (cond
      (some :initial? states) children
      (empty? states) children
      :else (conj children (elements/initial {}
                             (elements/transition {:target (or initial (:id (first states)))}))))))

(>defn- assign-parents
  [{parent-id :id
    initial   :initial
    :as       parent} nodes]
  [::sc/element (s/every ::sc/element) => (s/every ::sc/element)]
  (if nodes
    (let [nodes (if (or (nil? parent) (= :ROOT parent-id) (= :state (:node-type parent)))
                  (with-default-initial-state parent nodes)
                  nodes)]
      (mapv
        (fn [n]
          (let [{:keys [children]} n]
            (cond-> n
              parent (assoc :parent parent-id)
              children (update :children #(assign-parents n %)))))
        nodes))
    []))

(>defn machine
  "Create a new state machine definition that mimics the structure and semantics of SCXML.

  Attributes:

  :initial - ID(s) of initial state(s) of the machine. Default is the top-most `initial` element,
             or the first element in document order.
  :name - Optional name
  :binding - :late or :early (default is :early)
  "
  [{:keys [initial name binding] :as attrs} & children]
  [map? (s/* ::sc/element) => ::sc/machine]
  (let [node         (assoc attrs
                       :id :ROOT
                       :binding (or binding :early)
                       :node-type :machine)
        children     (assign-parents node children)
        node         (assoc node :children (vec children))
        ids-in-order (ids-in-document-order node)
        node         (assoc node
                       :children (mapv :id children)
                       ::sc/elements-by-id (reduce
                                             (fn [acc {:keys [id children] :as n}]
                                               (let [n (cond-> n
                                                         (seq children) (assoc :children
                                                                          (mapv :id children)))]
                                                 (cond
                                                   (= :ROOT id) acc
                                                   (and id (contains? acc id))
                                                   (throw (ex-info (str "Duplicate element ID on machine: " id) {}))
                                                   id (assoc acc id n)
                                                   :else acc)))
                                             {}
                                             (tree-seq :children :children node))
                       :id :ROOT
                       ::sc/ids-in-document-order ids-in-order)]
    node))

(def scxml
  "See `machine`. SCXML-compliant name for top-level element."
  machine)

(>defn element
  "Find the node in the machine that has the given ID (of any type)"
  [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => (? ::sc/element)]
  (cond
    (= element-or-id :ROOT) machine
    (and (map? element-or-id)
      (contains? (::sc/elements-by-id machine) (:id element-or-id)))
    (get-in machine [::sc/elements-by-id (:id element-or-id)])

    (map? element-or-id)
    element-or-id

    :else
    (get-in machine [::sc/elements-by-id element-or-id])))

(>defn element-id
  [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => (? ::sc/id)]
  (:id (element machine element-or-id)))

(>defn get-parent
  "Get the immediate parent (id) of the given element-or-id. Returns :ROOT if the parent is the root,
   and nil if the element queried is already the root."
  [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => (? ::sc/id)]
  (cond
    (nil? element-or-id) nil
    (= :ROOT (element-id machine element-or-id)) nil
    :else (or
            (:parent (element machine element-or-id))
            :ROOT)))

(>defn get-children
  "Returns the ID of the child nodes of the given `element-or-id` which
  have the given type."
  [machine element-or-id type]
  [::sc/machine (? ::sc/element-or-id) ::sc/node-type => (s/every ::sc/id :kind vector?)]
  (filterv #(= (:node-type (element machine %)) type)
    (:children (element machine element-or-id))))

(>defn invocations
  "Returns the IDs of the nodes that are invocations within `element-or-id`"
  [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => (s/every ::sc/id)]
  (get-children machine element-or-id :invoke))

(>defn transitions [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => (s/every ::sc/id)]
  (get-children machine element-or-id :transition))

(>defn transition-element
  "Returns the element that represents the first transition of element-or-id.
   This should only be used on nodes that have a single required transition, such as
   <initial> and <history> nodes."
  [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => (? ::sc/transition-element)]
  (some->> (transitions machine element-or-id) first (element machine)))

(>defn exit-handlers
  "Returns the immediate child elements that are on-exit."
  [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => (s/every ::sc/on-exit-element :kind vector?)]
  (mapv (partial element machine) (get-children machine element-or-id :on-exit)))

(>defn entry-handlers [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => (s/every ::sc/on-entry-element :kind vector?)]
  (mapv (partial element machine) (get-children machine element-or-id :on-entry)))

(>defn history-elements [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => (s/every ::sc/history-element :kind vector?)]
  (mapv #(element machine %) (get-children machine element-or-id :history)))

(>defn history-element? [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => boolean?]
  (boolean
    (= :history (:node-type (element machine element-or-id)))))

(>defn final-state? [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => boolean?]
  (boolean
    (= :final (:node-type (element machine element-or-id)))))

(>defn state? [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => boolean?]
  (let [n (element machine element-or-id)]
    (boolean
      (and (map? n) (#{:final :state :parallel} (:node-type n))))))

(>defn child-states
  "Find all of the immediate children (IDs) of `element-or-id` that are states
   (final, element-or-id, or parallel)"
  [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => (s/every ::sc/id :kind vector?)]
  (into []
    (concat
      (get-children machine element-or-id :final)
      (get-children machine element-or-id :state)
      (get-children machine element-or-id :parallel))))

(>defn initial-element
  "Returns the element that represents the <initial> element of a compound state element-or-id.
   Returns nil if the element isn't a compound state."
  [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => (? ::sc/initial-element)]
  (->>
    (log/spy :info "children of "(child-states machine element-or-id))
    (map #(element machine %))
    (filter :initial?)
    first))

(>defn atomic-state?
  [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => boolean?]
  (boolean
    (and
      (state? machine element-or-id)
      (empty? (child-states machine element-or-id)))))

(>defn parallel-state? [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => boolean?]
  (boolean
    (= :parallel (:node-type (element machine element-or-id)))))

(>defn compound-state?
  "Returns true if the given state contains other states."
  [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => boolean?]
  (and
    (not (parallel-state? machine element-or-id))
    (not (atomic-state? machine element-or-id))))

(>defn nearest-ancestor-state
  "Returns the ID of the state (if any) that encloses the given element-or-id, or nil if there is no
   ancestor state."
  [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => (? ::sc/id)]
  (let [p (get-parent machine element-or-id)]
    (cond
      (state? machine p) p
      (nil? p) nil
      :else (nearest-ancestor-state machine p))))

(def source "[machine element-or-id]
   Returns the source (nearest ancestor that is a state element) of an element (meant to be used for transitions)."
  nearest-ancestor-state)

(>defn all-descendants
  "Returns a set of IDs of the (recursive) descendants (children) of s"
  [machine s]
  [::sc/machine (? ::sc/element-or-id) => (s/every ::sc/id :kind set?)]
  (if-let [immediate-children (:children (element machine s))]
    (into (set immediate-children)
      (mapcat #(all-descendants machine %) immediate-children))
    #{}))

(>defn descendant?
  [machine s1 s2]
  [::sc/machine ::sc/element-or-id (? ::sc/element-or-id) => boolean?]
  (let [s1-id (element-id machine s1)]
    (boolean
      (contains? (all-descendants machine s2) s1-id))))

(>defn in-document-order
  "Given a set/sequence of actual nodes-or-ids (as maps), returns a vector of those nodes-or-ids, but in document order."
  [machine nodes-or-ids]
  [::sc/machine (s/every ::sc/element-or-id) => (s/every ::sc/element-or-id :kind vector?)]
  (let [ordered-ids (::sc/ids-in-document-order machine)
        ids         (set (map #(if (map? %) (:id %) %) nodes-or-ids))]
    (vec
      (keep
        (fn [id] (when (ids id) id))
        ordered-ids))))

(def in-entry-order
  "[machine nodes]

   Same as in-document-order."
  in-document-order)

(>defn in-exit-order
  "The reverse of in-document-order."
  [machine nodes]
  [::sc/machine (s/every ::sc/element-or-id) => (s/every ::sc/element-or-id :kind vector?)]
  (into [] (reverse (in-document-order machine nodes))))

(>defn get-proper-ancestors
  "Returns the node ids from `machine` that are proper ancestors of `element-or-id` (an id or actual element-or-id). If `stopping-element-or-id-or-id`
   is included, then that will stop the retrieval (not including the stopping element-or-id). The results are
   in the ancestry order (i.e. deepest element-or-id first)."
  ([machine element-or-id]
   [::sc/machine ::sc/element-or-id => (s/every ::sc/id :kind vector?)]
   (get-proper-ancestors machine element-or-id nil))
  ([machine element-or-id stopping-element-or-id]
   [::sc/machine ::sc/element-or-id (? ::sc/element-or-id) => (s/every ::sc/id :kind vector?)]
   (let [stop-id (:id (element machine stopping-element-or-id))]
     (loop [n      element-or-id
            result []]
       (let [parent-id (get-parent machine n)]
         (if (or (nil? parent-id) (= parent-id stop-id))
           result
           (recur parent-id (conj result parent-id))))))))

(>defn find-least-common-compound-ancestor
  "Returns the ELEMENT that is the common compound ancestor of all the `states`. NOTE: This may be
   the state machine itself. The compound state returned will be the one closest to all of the states."
  [machine states]
  [::sc/machine (s/every ::sc/element-or-id) => ::sc/element]
  (let [possible-ancestors (conj
                             (into []
                               (comp
                                 (filter #(or
                                            (= :ROOT %)
                                            (compound-state? machine %)))
                                 (map (partial element-id machine)))
                               (get-proper-ancestors machine (first states)))
                             machine)
        other-states       (rest states)]
    (element machine
      (first
        (keep
          (fn [anc] (when (every? (fn [s] (descendant? machine s anc)) other-states) anc))
          possible-ancestors)))))

(defn invalid-history-elements
  "Returns a sequence of history elements from `machine` that have errors. Each node will contain a `:msgs` key
   with the problem descriptions. This is a static check."
  [machine]
  (let [history-nodes (filter #(history-element? machine %) (vals (::sc/elements-by-id machine)))
        e             (fn [n msg] (update n :msgs conj msg))]
    (for [{:keys [parent deep?] :as hn} history-nodes       ; Section 3.10.2 of spec
          :let [transitions        (transitions machine hn)
                {:keys [target event cond]} (first transitions)
                immediate-children (set (child-states machine parent))
                possible-problem   (cond-> (assoc hn :msgs [])
                                     (= 1 (count transitions)) (e "A history node MUST have exactly one transition")
                                     (and (nil? event) (nil? cond)) (e "A history transition MUST NOT have cond/event.")
                                     (and (not deep?) (not= 1 (count target))) (e "Exactly ONE transition target is required for shallow history.")
                                     ;; TASK: Validate deep history
                                     (or deep? (= 1 (count immediate-children))) (e "Exactly ONE transition target for shallow. If many, then deep history is required."))]
          :when (pos-int? (count (:msgs possible-problem)))]
      possible-problem)))
