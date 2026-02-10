(ns com.fulcrologic.statecharts.chart
  "Main mechanism to define and navigate a statechart definition. The processing algorithm for such
   a statechart is protocol-based. The v20150901 implementation is the default.

   ::sc/k in the docstrings of this namespace assumes the alias `[com.fulcrologic.statecharts :as sc]`, which
   can be generated as only an alias, though an empty namespace of that name does exist."
  (:require
    [clojure.set :as set]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn >defn- ?]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.elements :as elements]
    [com.fulcrologic.statecharts.malli-specs]
    [taoensso.timbre :as log]))

;; I did try to translate all that imperative code to something more functional...got really tiring, and generated
;; subtle bugs divergent from spec.
;; So, some internals are in an imperative style, which does make them easier to compare to the spec.
;; The main event loop from the spec is split into `before-event` and `process-event` so it can be
;; used in a non-imperative way.

(>defn ids-in-document-order
  "Returns the IDs of the states in the given node, in document order (not including the node itself).
   You can specify `::sc/document-order :breadth-first` on the top-level chart definition to get a
   depth-first interpretation vs. breadth."
  ([chart]
   [::sc/statechart => [:vector :keyword]]
   (ids-in-document-order chart chart))
  ([{desired-order ::sc/document-order :as chart} {:keys [id] :as node}]
   [::sc/statechart ::sc/element => [:vector keyword?]]
   (if (= :breadth-first desired-order)
     (let [states     (:children node)
           base-order (mapv :id states)]
       (into base-order
         (mapcat #(ids-in-document-order chart %))
         states))
     (let [children (:children node)]
       (cond-> []
         id (conj id)
         (seq children) (into (mapcat #(ids-in-document-order chart %) children)))))))

(>defn- with-default-initial-state
  "Scans children for an initial state. If no such state is found then it creates one and sets the target to the
   first child that is a state."
  [{:keys [id initial] :as parent} children]
  [(? ::sc/element) [:sequential ::sc/element] => [:sequential ::sc/element]]
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
  [::sc/element [:sequential ::sc/element] => [:vector ::sc/element]]
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

(>defn statechart
  "Create a new state chart definition that mimics the structure and semantics of SCXML.

  Attributes:

  ::sc/document-order - :breadth-first or :depth-first (default). See Conformance.adoc.
  :initial - ID(s) of initial state(s) of the chart. Default is the top-most `initial` element,
             or the first element in document order.
  :name - Optional name
  :binding - :late or :early (default is :early)
  "
  [{:keys [initial name binding] :as attrs} & children]
  [[:map
    [:initial {:optional true} [:or ::sc/id ::sc/ids]]
    [:name {:optional true} [:or ::sc/id ::sc/ids]]
    [:binding {:optional true} [:enum :late :early]]]
   [:* ::sc/element] => ::sc/statechart]
  (let [node             (assoc attrs
                           :id :ROOT
                           :binding (or binding :early)
                           :node-type :statechart)
        children         (assign-parents node children)
        node             (assoc node :children (vec children))
        ids-in-order     (ids-in-document-order node)
        id-ordinals      (zipmap ids-in-order (range))
        node             (assoc node
                           :children (mapv :id children)
                           ::sc/elements-by-id (reduce
                                                 (fn [acc {:keys [id children] :as n}]
                                                   (let [n (cond-> n
                                                             (seq children) (assoc :children
                                                                                   (mapv :id children)))]
                                                     (cond
                                                       (= :ROOT id) acc
                                                       (and id (contains? acc id))
                                                       (throw (ex-info (str "Duplicate element ID on chart: " id) {}))
                                                       id (assoc acc id n)
                                                       :else acc)))
                                                 {}
                                                 (tree-seq :children :children node))
                           :id :ROOT
                           ::sc/id-ordinals id-ordinals
                           ::sc/ids-in-document-order ids-in-order)
        node-types       (into #{}
                           (map :node-type)
                           children)
        legal-node-types #{:state :parallel :final :data-model :script}
        bad-nodes        (set/difference node-types legal-node-types)]
    (when (seq bad-nodes)
      (throw (ex-info (str "Illegal top-level node. Root node cannot have: " bad-nodes " elements.") {})))
    node))

(def scxml "Alias for `statechart`." statechart)

(>defn element
  "Find the node in the chart that has the given ID (of any type)"
  [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => (? ::sc/element)]
  (cond
    (= element-or-id :ROOT) chart
    (and (map? element-or-id)
      (contains? (::sc/elements-by-id chart) (:id element-or-id)))
    (get-in chart [::sc/elements-by-id (:id element-or-id)])

    (map? element-or-id)
    element-or-id

    :else
    (get-in chart [::sc/elements-by-id element-or-id])))

(>defn element-id
  [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => (? ::sc/id)]
  (:id (element chart element-or-id)))

(>defn get-parent
  "Get the immediate parent (id) of the given element-or-id. Returns :ROOT if the parent is the root,
   and nil if the element queried is already the root."
  [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => (? ::sc/id)]
  (cond
    (nil? element-or-id) nil
    (= :ROOT (element-id chart element-or-id)) nil
    :else (or
            (:parent (element chart element-or-id))
            :ROOT)))

(>defn get-children
  "Returns the ID of the child nodes of the given `element-or-id` which
  have the given type."
  [chart element-or-id type]
  [::sc/statechart (? ::sc/element-or-id) ::sc/node-type => [:vector ::sc/id]]
  (filterv #(= (:node-type (element chart %)) type)
    (:children (element chart element-or-id))))

(>defn invocations
  "Returns the IDs of the nodes that are invocations within `element-or-id`"
  [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => [:vector ::sc/id]]
  (get-children chart element-or-id :invoke))

(>defn transitions [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => [:vector ::sc/id]]
  (get-children chart element-or-id :transition))

(>defn transition-element
  "Returns the element that represents the first transition of element-or-id.
   This should only be used on nodes that have a single required transition, such as
   <initial> and <history> nodes."
  [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => (? ::sc/transition-element)]
  (log/spy :trace "transition element"
    (some->> (transitions chart element-or-id) first (element chart))))

(>defn exit-handlers
  "Returns the immediate child elements that are on-exit."
  [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => [:vector ::sc/on-exit-element]]
  (mapv (partial element chart) (get-children chart element-or-id :on-exit)))

(>defn entry-handlers [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => [:vector ::sc/on-entry-element]]
  (mapv (partial element chart) (get-children chart element-or-id :on-entry)))

(>defn history-elements [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => [:vector ::sc/history-element]]
  (mapv #(element chart %) (get-children chart element-or-id :history)))

(>defn history-element? [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => boolean?]
  (boolean
    (= :history (:node-type (element chart element-or-id)))))

(>defn final-state? [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => boolean?]
  (boolean
    (= :final (:node-type (element chart element-or-id)))))

(>defn state? [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => boolean?]
  (let [n (element chart element-or-id)]
    (boolean
      (and (map? n) (#{:final :state :parallel} (:node-type n))))))

(>defn child-states
  "Find all of the immediate children (IDs) of `element-or-id` that are states
   (final, element-or-id, or parallel)"
  [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => [:vector ::sc/id]]
  (into []
    (concat
      (get-children chart element-or-id :final)
      (get-children chart element-or-id :state)
      (get-children chart element-or-id :parallel))))

(>defn initial-element
  "Returns the element that represents the <initial> element of a compound state element-or-id.
   Returns nil if the element isn't a compound state."
  [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => (? ::sc/initial-element)]
  (log/spy :trace "initial element"
    (->>
      (child-states chart element-or-id)
      (map #(element chart %))
      (filter :initial?)
      first)))

(>defn atomic-state?
  [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => boolean?]
  (boolean
    (and
      (state? chart element-or-id)
      (empty? (child-states chart element-or-id)))))

(>defn initial?
  [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => boolean?]
  (let [{:keys [initial initial?]} (element chart element-or-id)]
    (boolean
      (or initial? initial))))

(>defn condition-node?
  "Returns true if the given element is ALL of:

  * An atomic state
  * Has more than one transition
  * NONE of the transitions require an event
  "
  [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => boolean?]
  (let [tids (transitions chart element-or-id)]
    (boolean
      (and
        (atomic-state? chart element-or-id)
        (> (count tids) 1)
        (every? #(nil? (:event (element chart %))) tids)))))

(>defn parallel-state? [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => boolean?]
  (boolean
    (= :parallel (:node-type (element chart element-or-id)))))

(>defn compound-state?
  "Returns true if the given state contains other states."
  [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => boolean?]
  (and
    (not (parallel-state? chart element-or-id))
    (not (atomic-state? chart element-or-id))))

(>defn nearest-ancestor-state
  "Returns the ID of the state (if any) that encloses the given element-or-id, or nil if there is no
   ancestor state."
  [chart element-or-id]
  [::sc/statechart (? ::sc/element-or-id) => (? ::sc/id)]
  (let [p (get-parent chart element-or-id)]
    (cond
      (state? chart p) p
      (nil? p) nil
      :else (nearest-ancestor-state chart p))))

(def get-parent-state
  "[chart element-or-id]

   Alias for `nearest-ancestor-state`."
  nearest-ancestor-state)

(def source "[chart element-or-id]
   Returns the source (nearest ancestor that is a state element) of an element (meant to be used for transitions)."
  nearest-ancestor-state)

(>defn all-descendants
  "Returns a set of IDs of the (recursive) descendants (children) of s"
  [chart s]
  [::sc/statechart (? ::sc/element-or-id) => [:set ::sc/id]]
  (if-let [immediate-children (:children (element chart s))]
    (into (set immediate-children)
      (mapcat #(all-descendants chart %) immediate-children))
    #{}))

(>defn descendant?
  [chart s1 s2]
  [::sc/statechart ::sc/element-or-id (? ::sc/element-or-id) => boolean?]
  (let [s1-id (element-id chart s1)]
    (boolean
      (contains? (all-descendants chart s2) s1-id))))

(>defn in-document-order
  "Given a set/sequence of actual nodes-or-ids (as maps), returns a vector of those nodes-or-ids, but in document order."
  [chart nodes-or-ids]
  [::sc/statechart [:every ::sc/element-or-id] => [:vector ::sc/element-or-id]]
  (let [ordered-ids (::sc/ids-in-document-order chart)
        ids         (set (map #(if (map? %) (:id %) %) nodes-or-ids))]
    (vec
      (keep
        (fn [id] (when (ids id) id))
        ordered-ids))))

(defn compare-in-document-order
  "Comparator function returning -1, 0, or 1 to establish the relative order of two element IDs"
  [{::sc/keys [id-ordinals] :as chart} a b]
  ; [::sc/statechart ::sc/element-or-id ::sc/element-or-id => [:enum -1 0 1]]
  (let [a (element-id chart a)
        b (element-id chart b)]
    (compare (get id-ordinals a) (get id-ordinals b))))

(>defn document-ordered-set
  "Returns a set that keeps the IDs in document order"
  [chart & initial-elements]
  [::sc/statechart [:* ::sc/id] => set?]
  (into
    (sorted-set-by (partial compare-in-document-order chart))
    initial-elements))

(def in-entry-order
  "[chart nodes]

   Same as in-document-order."
  in-document-order)

(>defn in-exit-order
  "The reverse of in-document-order."
  [chart nodes]
  [::sc/statechart [:every ::sc/element-or-id] => [:vector ::sc/element-or-id]]
  (into [] (reverse (in-document-order chart nodes))))

(>defn get-proper-ancestors
  "Returns the node ids from `chart` that are proper ancestors of `element-or-id` (an id or actual element-or-id). If `stopping-element-or-id-or-id`
   is included, then that will stop the retrieval (not including the stopping element-or-id). The results are
   in the ancestry order (i.e. deepest element-or-id first)."
  ([chart element-or-id]
   [::sc/statechart ::sc/element-or-id => [:vector ::sc/id]]
   (get-proper-ancestors chart element-or-id nil))
  ([chart element-or-id stopping-element-or-id]
   [::sc/statechart ::sc/element-or-id (? ::sc/element-or-id) => [:vector ::sc/id]]
   (let [stop-id (:id (element chart stopping-element-or-id))]
     (loop [n      element-or-id
            result []]
       (let [parent-id (get-parent chart n)]
         (if (or (nil? parent-id) (= parent-id stop-id))
           result
           (recur parent-id (conj result parent-id))))))))

(>defn find-least-common-compound-ancestor
  "Returns the ELEMENT that is the common compound ancestor of all the `states`. NOTE: This may be
   the state chart itself. The compound state returned will be the one closest to all of the states."
  [chart states]
  [::sc/statechart [:every ::sc/element-or-id] => ::sc/element]
  (let [possible-ancestors (conj
                             (into []
                               (comp
                                 (filter #(or
                                            (= :ROOT %)
                                            (compound-state? chart %)))
                                 (map (partial element-id chart)))
                               (get-proper-ancestors chart (first states)))
                             chart)
        other-states       (rest states)]
    (element chart
      (first
        (keep
          (fn [anc] (when (every? (fn [s] (descendant? chart s anc)) other-states) anc))
          possible-ancestors)))))

(defn invalid-history-elements
  "Returns a sequence of history elements from `chart` that have errors. Each node will contain a `:msgs` key
   with the problem descriptions. This is a static check.

   Validates per W3C SCXML Section 3.10:
   - History must have exactly one transition child
   - History transition must not have event or cond attributes
   - History must be child of a compound state
   - Shallow history requires exactly one target
   - Deep history target should be a proper descendant of the parent state"
  [chart]
  (let [history-nodes (filter #(history-element? chart %) (vals (::sc/elements-by-id chart)))
        e             (fn [n msg] (update n :msgs conj msg))]
    (for [{:keys [parent deep? type] :as hn} history-nodes  ; Section 3.10 of spec
          :let [transitions        (transitions chart hn)
                transition-element (element chart (first transitions))
                {:keys [target event cond]} transition-element
                parent-descendants (all-descendants chart parent)
                is-compound?       (compound-state? chart parent)
                targets-are-proper-descendants? (every? #(contains? parent-descendants %) target)
                possible-problem   (cond-> (assoc hn :msgs [])
                                     (not= 1 (count transitions)) (e "A history node MUST have exactly one transition")
                                     (or (some? event) (some? cond)) (e "A history transition MUST NOT have cond/event.")
                                     (not is-compound?) (e "A history node MUST be a child of a compound state.")
                                     (and (not deep?) (not= 1 (count target))) (e "Exactly ONE transition target is required for shallow history.")
                                     (and deep? (seq target) (not targets-are-proper-descendants?)) (e "Deep history transition target should be a proper descendant of the parent state."))]
          :when (pos-int? (count (:msgs possible-problem)))]
      possible-problem)))
