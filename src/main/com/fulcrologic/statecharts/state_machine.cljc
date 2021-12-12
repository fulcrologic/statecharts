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
    [com.fulcrologic.statecharts.events :as evts :refer [new-event]]
    [com.fulcrologic.statecharts.util :refer [queue genid]]
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [taoensso.timbre :as log]))

;; I did try to translate all that imperative code to something more functional...got really tiring, and generated
;; subtle bugs divergent from spec.
;; So, some internals are in an imperative style, which does make them easier to compare to the spec.
;; The main event loop from the spec is split into `before-event` and `process-event` so it can be
;; used in a non-imperative way.

(declare execute enter-states invalid-history-elements configuration-problems get-transition-domain before-event
  initial-element transition-element child-states)

(>defn- ids-in-document-order
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
  [children]
  [(s/every ::sc/element) => (s/every ::sc/element)]
  (let [states (filter #(#{:state :parallel} (:node-type %)) children)]
    (cond
      (some :initial? states) children
      (empty? states) children
      :else (conj children (elements/initial {}
                             (elements/transition {:target (:id (first states))}))))))

(>defn- assign-parents
  [{parent-id :id :as parent} nodes]
  [(? ::sc/element) (s/every ::sc/element) => (s/every ::sc/element)]
  (if nodes
    (let [nodes (if (or (nil? parent) (= :state (:node-type parent)))
                  (with-default-initial-state nodes)
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
  "Create a new state machine definition that mimics the structure and semantics of SCXML."
  [{:keys [initial name script] :as attrs} & children]
  [map? (s/* ::sc/element) => ::sc/machine]
  (let [children     (assign-parents nil children)
        node         (assoc attrs
                       :id :ROOT
                       :node-type :machine
                       :children children)
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
                       ::sc/ids-in-document-order ids-in-order)
        problems     (concat (invalid-history-elements node))]
    (when (seq problems)
      (throw (ex-info "Invalid machine specification" {:problems problems})))
    node))

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

(>defn dispatch
  "Use the machine definition to figure out how to run the given code sym."
  [machine sym working-memory]
  [::sc/machine symbol? ::sc/working-memory => ::sc/working-memory]
  ;; FIXME: Use formal data model, (allow to use, but not require to use, working memory)
  (if-let [f (::sc/dispatcher machine)]
    (if f
      (update working-memory ::sc/data-model (fn [m] (f sym m)))
      working-memory)
    working-memory))

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

(>defn exit-handlers [machine element-or-id]
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
      (and
        (map? (log/spy :trace n))
        (#{:final :state :parallel} (log/spy :trace (:node-type n)))))))

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
    (child-states machine element-or-id)
    (map #(element machine %))
    (filter :initial?)
    first))

(>defn atomic-state?
  [machine element-or-id]
  [::sc/machine (? ::sc/element-or-id) => boolean?]
  (boolean
    (and
      (log/spy :trace (state? machine (log/spy :trace element-or-id)))
      (log/spy :trace (empty? (child-states machine element-or-id))))))

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

(def source "[machine element-or-id]" nearest-ancestor-state)

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
  [::sc/machine ::sc/element-or-id ::sc/element-or-id => boolean?]
  (let [s1-id (element-id machine s1)]
    (boolean
      (contains? (log/spy :trace (all-descendants machine s2)) (log/spy :trace s1-id)))))

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

#?(:clj
   (defmacro with-working-memory [binding & body]
     (let [[sym expr] binding]
       `(let [~sym (merge {::sc/enabled-transitions #{}
                           ::sc/states-to-invoke    #{}
                           ::sc/internal-queue      (com.fulcrologic.statecharts.util/queue)}
                     ~expr)
              next-mem# (do
                          ~@body)]
          (dissoc next-mem# ::sc/enabled-transitions ::sc/states-to-invoke ::sc/internal-queue)))))

#?(:clj
   (s/fdef with-working-memory
     :args (s/cat :binding (s/tuple symbol? any?) :body (s/* any?))))

(>defn initialize
  "Create working memory for a new machine. Auto-assigns a session ID unless you supply one."
  [{:keys [id name script] :as machine}]
  [::sc/machine => ::sc/working-memory]
  (let [t    (log/spy :trace "initial transition" (some->> machine (initial-element machine) (transition-element machine) (element-id machine)))
        wmem {::sc/session-id          #?(:clj (java.util.UUID/randomUUID) :cljs (random-uuid))
              ::sc/configuration       #{}
              ::sc/initialized-states  #{}                  ; states that have been entered (initialized data model) before
              ::sc/enabled-transitions (if t #{t} #{})
              ::sc/history-value       {}
              ;; FIXME: Should be defined by external thing that has an entry/exit when processing
              ::sc/data-model          {:_name         (or name (genid "name"))
                                        :_x            {}   ; system variables
                                        :_ioprocessors {}
                                        :_sessionid    (or id (genid "workingmemory"))}
              ::sc/running?            true}]
    (with-working-memory [wmem wmem]
      (as-> wmem $
        (enter-states machine $)
        (before-event machine $)
        (cond-> $
          script (as-> $ (execute machine $ script)))))))

(>defn session-id
  "Returns the unique session id of the machine instance with `working-memory`"
  [working-memory]
  [::sc/working-memory => ::sc/session-id]
  (::sc/session-id working-memory))

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
       (let [parent-id (log/spy :trace (get-parent machine (log/spy :trace n)))]
         (if (or (nil? parent-id) (= parent-id stop-id))
           result
           (recur parent-id (conj result parent-id))))))))

;; TODO: implement
(>defn condition-match
  [machine working-memory element-or-id]
  [::sc/machine ::sc/working-memory ::sc/element-or-id => boolean?]
  true)

(>defn find-least-common-compound-ancestor
  "Returns the ELEMENT that is the common compound ancestor of all the `states`. NOTE: This may be
   the state machine itself. The compound state returned will be the one closest to all of the states."
  [machine states]
  [::sc/machine (s/every ::sc/element-or-id) => ::sc/element]
  (let [possible-ancestors (log/spy :trace "possible" (conj
                                                        (into []
                                                          (comp
                                                            (filter #(or
                                                                       (= :ROOT %)
                                                                       (compound-state? machine %)))
                                                            (map (partial element-id machine)))
                                                          (log/spy :trace "PA" (get-proper-ancestors machine (log/spy :trace (first states)))))
                                                        machine))
        other-states       (log/spy :trace "other states" (rest states))]
    (element machine
      (log/spy :trace "LCCA"
        (first
          (keep
            (fn [anc] (when (log/spy :trace (every? (fn [s] (descendant? machine s anc)) other-states)) anc))
            possible-ancestors))))))

(>defn in-final-state?
  "Returns true if `non-atomic-state` is completely done."
  [machine {::sc/keys [configuration] :as wmem} non-atomic-state]
  [::sc/machine ::sc/working-memory (? ::sc/element-or-id) => boolean?]
  (boolean
    (cond
      (compound-state? machine non-atomic-state) (some
                                                   (fn [s] (and (final-state? machine s) (contains? configuration s)))
                                                   (child-states machine non-atomic-state))
      (parallel-state? machine non-atomic-state) (every?
                                                   (fn [s] (in-final-state? machine wmem s))
                                                   (child-states machine non-atomic-state))
      :else false)))

(declare add-descendant-states-to-enter!)

(>defn add-ancestor-states-to-enter! [machine wmem state ancestor
                                      states-to-enter states-for-default-entry default-history-content]
  [::sc/machine ::sc/active-working-memory ::sc/element-or-id ::sc/element-or-id volatile? volatile? volatile? => nil?]
  (log/spy :trace [state ancestor])
  (doseq [anc (log/spy :trace "add proper-ancestors" (get-proper-ancestors machine state ancestor))]
    (vswap! states-to-enter conj (element-id machine anc))
    (when (log/spy :trace "parallel anc?" (parallel-state? machine anc))
      (doseq [child (child-states machine anc)]
        (when-not (some (fn [s] (descendant? machine s child)) @states-to-enter)
          (add-descendant-states-to-enter! machine wmem child states-to-enter
            states-for-default-entry default-history-content)))))
  nil)

(>defn add-descendant-states-to-enter! [machine {::sc/keys [history-value] :as wmem}
                                        state states-to-enter states-for-default-entry default-history-content]
  [::sc/machine ::sc/active-working-memory ::sc/element-or-id volatile? volatile? volatile? => nil?]
  (letfn [(add-elements! [target parent]
            (doseq [s target]
              (add-descendant-states-to-enter! machine wmem s states-to-enter
                states-for-default-entry default-history-content))
            (doseq [s target]
              (add-ancestor-states-to-enter! machine wmem s parent states-to-enter
                states-for-default-entry default-history-content)))]
    (let [{:keys [id parent] :as state} (element machine state)]
      (if (history-element? machine state)
        (if-let [previously-active-states (get history-value id)]
          (add-elements! previously-active-states parent)
          (let [{:keys [content target]} (transition-element machine state)]
            (when content (vswap! default-history-content assoc parent content))
            (add-elements! target parent)))
        ; not a history element
        (do
          (vswap! states-to-enter conj (element-id machine state))
          (if (compound-state? machine state)
            (let [target (->> (initial-element machine state) (transition-element machine) :target)]
              (vswap! states-for-default-entry conj (element-id machine state))
              (add-elements! target state))
            (if (parallel-state? machine state)
              (doseq [child (child-states machine state)]
                (when-not (some (fn [s] (descendant? machine s child)) @states-to-enter)
                  (add-descendant-states-to-enter! machine wmem child states-to-enter
                    states-for-default-entry default-history-content))))))))
    nil))

(>defn get-effective-target-states
  [machine {::sc/keys [history-value] :as working-memory} t]
  [::sc/machine ::sc/active-working-memory ::sc/element-or-id => (s/every ::sc/element-or-id :kind set?)]
  (reduce
    (fn [targets s]
      (let [{:keys [id] :as s} (element machine s)]
        (cond
          (and
            (history-element? machine s)
            (contains? history-value id))
          #_=> (set/union targets (get history-value id))
          (history-element? machine s)
          #_=> (let [default-transition (first (transitions machine s))] ; spec + validation. There will be exactly one
                 (set/union targets (get-effective-target-states machine working-memory default-transition)))
          :else (conj targets id))))
    #{}
    (:target (element machine t))))

(>defn compute-entry-set
  "Returns [states-to-enter states-for-default-entry default-history-content]."
  [machine {::sc/keys [enabled-transitions] :as working-memory}]
  [::sc/machine ::sc/active-working-memory => (s/tuple set? set? map?)]
  (let [states-to-enter          (volatile! #{})
        states-for-default-entry (volatile! #{})
        default-history-content  (volatile! {})
        transitions              (mapv #(or (element machine %)
                                          (elements/transition {:target %})) enabled-transitions)]
    (doseq [{:keys [target] :as t} transitions]
      (let [ancestor (log/spy :trace "ancestor" (element-id machine (get-transition-domain machine working-memory t)))]
        (doseq [s target]
          (add-descendant-states-to-enter! machine working-memory (log/spy :trace s) states-to-enter
            states-for-default-entry default-history-content))
        (doseq [s (get-effective-target-states machine working-memory t)]
          (add-ancestor-states-to-enter! machine working-memory (log/spy :trace s) (log/spy :trace ancestor) states-to-enter
            states-for-default-entry default-history-content))))
    [@states-to-enter @states-for-default-entry @default-history-content]))

;; TODO:
(defn- initialize-data-model!
  "Initialize the data models in volatile working memory `wmem` for the given states, if necessary."
  [machine wmem states])

(>defn- execute
  "Run the content/action of s. Returns an updated wmem."
  [machine wmem s]
  [::sc/machine ::sc/active-working-memory ::sc/element-or-id => ::sc/working-memory]
  wmem)

(>defn enter-states
  "Enters states, triggers actions, tracks long-running invocations, and
   returns updated working memory."
  [machine wmem]
  [::sc/machine ::sc/active-working-memory => ::sc/working-memory]
  (let [[states-to-enter
         states-for-default-entry
         default-history-content] (log/spy :trace (compute-entry-set machine wmem))
        ma (volatile! wmem)]
    (doseq [s (in-entry-order machine states-to-enter)]
      (vswap! ma update ::sc/configuration conj s)
      (vswap! ma update ::sc/states-to-invoke conj s)
      (initialize-data-model! machine ma s)
      (doseq [entry (entry-handlers machine s)]
        (vreset! ma (execute machine @ma entry)))
      (when-let [t (and (contains? states-for-default-entry s)
                     (some->> s (initial-element machine) (transition-element machine)))]
        (vreset! ma (execute machine @ma t)))
      (when-let [content (get default-history-content (element-id machine s))]
        (vreset! ma (execute machine @ma content)))
      (when (final-state? machine s)
        (if (= :ROOT (get-parent machine s))
          (vswap! ma assoc ::sc/running? false)
          (let [parent      (get-parent machine s)
                grandparent (get-parent machine parent)
                done-data   {}]                             ;; TODO: done-data
            (vswap! ma update ::sc/internal-queue conj
              (new-event {:sendid (element-id machine s)
                          :type   :internal
                          :name   (keyword (str "done.state." (element-id machine parent)))} done-data))
            (when (and (parallel-state? machine grandparent)
                    (every? (fn [s] (in-final-state? machine @ma s)) (child-states machine grandparent)))
              (vswap! ma update ::sc/internal-queue conj
                (new-event {:sendid (element-id machine s)
                            :type   :internal
                            :name   (keyword (str "done.state." (element-id machine grandparent)))} done-data)))))))
    @ma))

(>defn execute-transition-content
  [machine {::sc/keys [enabled-transitions] :as wmem}]
  [::sc/machine ::sc/active-working-memory => ::sc/working-memory]
  (reduce
    (fn [wmem t] (execute machine wmem t))
    wmem
    enabled-transitions))

(>defn get-transition-domain
  [machine working-memory t]
  [::sc/machine ::sc/working-memory ::sc/element-or-id => (? ::sc/id)]
  (let [tstates (log/spy :trace (get-effective-target-states machine working-memory t))
        tsource (log/spy :trace (nearest-ancestor-state machine t))]
    (cond
      (empty? tstates) nil
      (and
        (= :internal (:type t))
        (compound-state? machine tsource)
        (every? (fn [s] (descendant? machine s tsource)) tstates)) tsource
      :else (:id (find-least-common-compound-ancestor machine (into [tsource] tstates))))))

(>defn compute-exit-set
  [machine {::sc/keys [configuration] :as working-mem} transitions]
  [::sc/machine ::sc/active-working-memory (s/every ::sc/element-or-id) => (s/every ::sc/id :kind set?)]
  (reduce
    (fn [acc t]
      (if (log/spy :trace (contains? (element machine t) :target))
        (let [domain (log/spy :trace (get-transition-domain machine working-mem t))]
          (into acc
            (filter #(descendant? machine % domain))
            configuration))
        acc))
    #{}
    transitions))

(>defn remove-conflicting-transitions
  "Updates working-mem so that enabled-transitions no longer includes any conflicting ones."
  [machine {::sc/keys [configuration enabled-transitions] :as wmem}]
  [::sc/machine ::sc/active-working-memory => ::sc/active-working-memory]
  (log/spy :trace enabled-transitions)
  (let [filtered-transitions (volatile! #{})]
    (doseq [t1 enabled-transitions
            :let [to-remove  (volatile! #{})
                  preempted? (volatile! false)]]
      (doseq [t2 @filtered-transitions
              :while (not preempted?)]
        (when (seq (set/intersection
                     (compute-exit-set machine wmem [t1])
                     (compute-exit-set machine wmem [t2])))
          (if (descendant? machine (source t1) (source t2))
            (vswap! to-remove conj t2)
            (vreset! preempted? true))))
      (when (not @preempted?)
        (do
          (doseq [t3 @to-remove]
            (vswap! filtered-transitions disj t3))
          (vswap! filtered-transitions conj t1))))
    (assoc wmem ::sc/enabled-transitions (log/spy :trace @filtered-transitions))))

(>defn- select-transitions* [machine configuration predicate]
  [::sc/machine ::sc/configuration ifn? => ::sc/enabled-transitions]
  (let [enabled-transitions (volatile! #{})
        looping?            (volatile! true)
        start-loop!         #(vreset! looping? true)
        break!              #(vreset! looping? false)
        atomic-states       (in-document-order machine (log/spy :trace (filterv #(atomic-state? machine %) configuration)))]
    (doseq [state (log/spy :trace atomic-states)]
      (start-loop!)
      (doseq [s (into [state] (get-proper-ancestors machine state))
              :when @looping?]
        (doseq [t (map #(element machine %) (in-document-order machine (transitions machine s)))
                :when (and @looping? (predicate t))]
          (vswap! enabled-transitions conj (element-id machine t))
          (break!))))
    (log/spy :trace @enabled-transitions)))

(>defn select-eventless-transitions
  "Returns a new version of working memory with ::sc/enabled-transitions populated."
  [machine {::sc/keys [configuration] :as working-memory}]
  [::sc/machine ::sc/active-working-memory => ::sc/active-working-memory]
  (remove-conflicting-transitions machine
    (assoc working-memory ::sc/enabled-transitions (select-transitions* machine configuration
                                                     (fn [t]
                                                       (and
                                                         (not (:event t))
                                                         (condition-match machine working-memory t)))))))

(>defn select-transitions
  "Returns a new version of working memory with ::sc/enabled-transitions populated."
  [machine {::sc/keys [configuration] :as working-memory} event]
  [::sc/machine ::sc/active-working-memory ::sc/event-or-name => ::sc/active-working-memory]
  (remove-conflicting-transitions machine
    (assoc working-memory ::sc/enabled-transitions (select-transitions* machine configuration
                                                     (fn [t]
                                                       (log/spy :trace "pred t" t)
                                                       (and
                                                         (log/spy :trace (contains? t :event))
                                                         (log/spy :trace (evts/name-match?
                                                                           (log/spy :trace (:event t))
                                                                           (log/spy :trace event)))
                                                         (condition-match machine working-memory t)))))))

(>defn invoke! [machine working-memory invocation]
  [::sc/machine ::sc/active-working-memory ::sc/element-or-id => ::sc/active-working-memory]
  (log/spy :trace "invoke (NOT IMPLEMENTED)" invocation)
  working-memory)

(>defn- run-invocations [machine working-memory]
  [::sc/machine ::sc/active-working-memory => ::sc/active-working-memory]
  (let [{::sc/keys [states-to-invoke]} working-memory]
    (reduce
      (fn [wmem state-to-invoke]
        (reduce
          (partial invoke! machine)
          wmem
          (invocations machine state-to-invoke)))
      (assoc working-memory ::sc/states-to-invoke #{})
      states-to-invoke)))

(>defn- run-many
  "Run the code associated with the given nodes. Returns a new working-memory with an update data model (context)."
  [machine working-memory nodes]
  [::sc/machine ::sc/active-working-memory (s/every ::sc/element-or-id) => ::sc/active-working-memory]
  (reduce
    (fn [wmem {:keys [function]}]
      (cond
        (ifn? function) (update wmem ::sc/data-model function)
        (symbol? function) (dispatch machine function wmem)
        :else wmem))
    working-memory
    nodes))

(defn- cancel-invoke [i]
  ;; TODO
  )

(defn- cancel-active-invocations [working-memory state]
  #_(doseq [i (invocations machine state)] (cancel-invoke i))
  working-memory)

(>defn exit-states
  "Does all of the processing for exiting states. Returns new working memory."
  [machine {::sc/keys [enabled-transitions
                       states-to-invoke
                       history-value
                       configuration] :as working-memory}]
  [::sc/machine ::sc/active-working-memory => ::sc/active-working-memory]
  (let [states-to-exit   (in-exit-order machine (compute-exit-set machine working-memory enabled-transitions))
        states-to-invoke (set/difference states-to-invoke (set states-to-exit))
        history-nodes    (into {}
                           (keep (fn [s]
                                   (let [eles (history-elements machine (get-parent machine s))]
                                     (when (seq eles)
                                       [(element-id machine s) eles]))))
                           states-to-exit)
        history-value    (reduce-kv
                           (fn [acc s {:keys [id deep?] :as hn}]
                             (let [f (if deep?
                                       (fn [s0] (and (atomic-state? machine s0) (descendant? machine s0 s)))
                                       (fn [s0] (= s (get-parent machine s0))))]
                               (assoc acc id (into #{} (filter f configuration)))))
                           history-value
                           history-nodes)
        working-memory   (assoc working-memory
                           ::sc/states-to-invoke states-to-invoke
                           ::sc/history-value history-value)]
    (reduce
      (fn [wmem s]
        (let [to-exit (exit-handlers machine s)
              run     (partial run-many machine)]
          (-> wmem
            (run to-exit)
            (cancel-active-invocations s)
            (update ::sc/configuration disj s))))
      working-memory
      states-to-exit)))

(>defn- microstep
  [machine working-memory]
  [::sc/machine ::sc/active-working-memory => ::sc/active-working-memory]
  (->> working-memory
    (exit-states machine)
    (execute-transition-content machine)
    (enter-states machine)))

(>defn- handle-eventless-transitions
  "Work through eventless transitions, returning the updated working memory"
  [machine working-memory]
  [::sc/machine ::sc/active-working-memory => ::sc/active-working-memory]
  {:post [(map? %)]}
  (let [macrostep-done? (volatile! false)]
    (loop [wmem working-memory]
      (let [{::sc/keys [enabled-transitions
                        internal-queue] :as wmem} (select-eventless-transitions machine wmem)
            wmem (if (empty? enabled-transitions)
                   (if (empty? (log/spy :trace internal-queue))
                     (do
                       (vreset! macrostep-done? true)
                       wmem)
                     (let [internal-event (first internal-queue)]
                       (as-> wmem $
                         (update $ ::sc/internal-queue pop)
                         (assoc-in $ [::sc/data-model :_event] internal-event)
                         (select-transitions machine $ internal-event))))
                   wmem)
            {::sc/keys [running?]
             :as       wmem} (cond->> wmem
                               (seq (::sc/enabled-transitions wmem)) (microstep machine))]
        (if (and running? (not @macrostep-done?))
          (recur wmem)
          wmem)))))

(>defn- run-exit-handlers [machine working-memory state]
  [::sc/machine ::sc/active-working-memory ::sc/element-or-id => ::sc/active-working-memory]
  (reduce
    (fn [wmem handler] (update wmem ::sc/data-model handler))
    working-memory
    (exit-handlers machine state)))

;; TODO: Sending events back to the machine that started this one, if there is one
(>defn- send-done-event! [machine wmem state]
  [::sc/machine ::sc/active-working-memory ::sc/element-or-id => nil?]
  nil)

(>defn- exit-interpreter
  [machine {::sc/keys [configuration] :as working-memory}]
  [::sc/machine ::sc/active-working-memory => ::sc/working-memory]
  (let [states-to-exit (in-exit-order machine configuration)]
    (reduce (fn [wmem state]
              (let [result (as-> wmem $
                             (run-exit-handlers machine $ state)
                             (cancel-active-invocations $ state)
                             (update $ ::sc/configuration disj state))]
                (when (and (final-state? machine state) (nil? (:parent (element machine state))))
                  (send-done-event! machine wmem state))
                result))
      working-memory
      states-to-exit)))

(>defn- before-event
  "Steps that are run before processing the next event."
  [machine {::sc/keys [running?] :as working-memory}]
  [::sc/machine ::sc/active-working-memory => ::sc/working-memory]
  {:post [(map? %)]}
  (if running?
    (with-working-memory [working-memory working-memory]
      (loop [step-memory working-memory]
        (let [working-memory (assoc step-memory
                               ::sc/enabled-transitions #{}
                               ::sc/macrostep-done? false)
              {::sc/keys [running?]
               :as       working-memory2} (handle-eventless-transitions machine working-memory)]
          (if (log/spy :trace running?)
            (let [final-mem (run-invocations machine working-memory2)]
              (if (seq (::sc/internal-queue final-mem))
                (recur final-mem)
                final-mem))
            (exit-interpreter machine working-memory2)))))
    working-memory))

(defn- cancel? [event] false)
(>defn- handle-external-invocations [machine working-memory]
  [::sc/machine ::sc/active-working-memory => ::sc/active-working-memory]
  ;; TODO
  working-memory)

(>defn process-event
  "Process the given `external-event` given a state `machine` with the `working-memory` as its current status/state.
   Returns the new version of working memory."
  [machine working-memory external-event]
  [::sc/machine ::sc/working-memory ::sc/event-or-name => ::sc/working-memory]
  (with-working-memory [working-memory working-memory]
    (if (cancel? external-event)
      (exit-interpreter machine working-memory)
      (as-> working-memory $
        (select-transitions machine $ external-event)
        (log/spy :trace $)
        (handle-external-invocations machine $)
        (microstep machine $)
        (before-event machine $)))))

;; VALIDATION HELPERS

(defn configuration-problems
  "Returns a list of problems with the current machine's working-memory configuration (active states)."
  [m {::sc/keys [configuration] :as working-memory}]
  (let [top-states             (set (child-states m m))
        active-top-states      (set/intersection top-states configuration)
        atomic-states          (filter #(atomic-state? m %) configuration)
        necessary-ancestors    (into #{} (mapcat (fn [s] (get-proper-ancestors m s)) atomic-states))
        compound-states        (filter #(compound-state? m %) configuration)
        broken-compound-states (for [cs compound-states
                                     :let [cs-children (child-states m cs)]
                                     :when (not= 1 (count (set/intersection configuration cs-children)))]
                                 cs)
        active-parallel-states (filter #(parallel-state? m %) configuration)
        broken-parallel-states (for [cs active-parallel-states
                                     :let [cs-children (child-states m cs)]
                                     :when (not= cs-children (set/intersection configuration cs-children))]
                                 cs)]
    (cond-> []
      (not= 1 (count active-top-states)) (conj "The number of top-level active states != 1")
      (zero? (count atomic-states)) (conj "There are zero active atomic states")
      (not= (set/intersection configuration necessary-ancestors) necessary-ancestors) (conj (str "Some active states are missing their necessary ancestors"
                                                                                              necessary-ancestors " should all be in " configuration))
      (seq broken-compound-states) (conj (str "The compound states " broken-compound-states " should have exactly one child active (each) in " configuration))
      (seq broken-parallel-states) (conj (str "The parallel states " broken-parallel-states " should have all of their children in " configuration)))))

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
                                     ;; TODO: Validate deep history
                                     (or deep? (= 1 (count immediate-children))) (e "Exactly ONE transition target for shallow. If many, then deep history is required."))]
          :when (pos-int? (count (:msgs possible-problem)))]
      possible-problem)))
