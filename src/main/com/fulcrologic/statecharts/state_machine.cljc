(ns com.fulcrologic.statecharts.state-machine
  "Implementation using a close approximation of https://www.w3.org/TR/2015/REC-scxml-20150901, including
   the suggested algorithm in that document, translated as closely as possible to CLJC. Future versions of
   the specification will appear as new namespaces to maintain compatibility through time.

   ::sc/k in the docstrings of this namespace assumes the alias `[com.fulcrologic.statecharts :as sc]`, which
   can be generated as only an alias, though an empty namespace of that name does exist."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.util :refer [queue]]
    [com.fulcrologic.statecharts.tracing :refer [trace]]
    [clojure.set :as set]))

(declare invalid-history-elements configuration-problems)

(defn- ids-in-document-order
  "Returns the IDs of the states in the given node, in document order (not including the node itself).
   You can specify `::sc/document-order :breadth-first` on the top-level machine definition to get a
   depth-first interpretation vs. breadth."
  ([machine]
   (ids-in-document-order machine machine))
  ([{desired-order ::sc/document-order :as machine} {:keys [id] :as node}]
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

(defn- assign-parents
  [{parent-id :id :as parent} nodes]
  (mapv
    (fn [n]
      (let [{:keys [children]} n]
        (cond-> n
          parent (assoc :parent parent-id)
          children (update :children #(assign-parents n %)))))
    nodes))

(defn machine
  "Create a new state machine definition that mimics the structure and semantics of SCXML."
  [{:keys [initial name script] :as attrs} & children]
  (let [node         (assoc attrs
                       :node-type :machine
                       :children (assign-parents nil children))
        ids-in-order (ids-in-document-order node)
        result       (assoc node
                       :children (mapv :id children)
                       ::sc/elements-by-id (into {}
                                             (keep (fn [{:keys [id children] :as n}] (when id [id (cond-> n
                                                                                                    (seq children) (assoc :children
                                                                                                                     (mapv :id children)))])))
                                             (tree-seq :children :children node))
                       ::sc/ids-in-document-order ids-in-order)
        problems     (concat
                       (invalid-history-elements result))]
    (when (seq problems)
      (throw (ex-info "Invalid machine specification" {:problems problems})))
    result))

(defn element
  "Find the node in the machine that has the given ID (of any type)"
  [machine node-or-id]
  (cond
    (and (map? node-or-id)
      (contains? (::sc/elements-by-id machine) (:id node-or-id)))
    (get-in machine [::sc/elements-by-id (:id node-or-id)])

    (map? node-or-id)
    node-or-id

    :else
    (get-in machine [::sc/elements-by-id node-or-id])))

(defn dispatch
  "Use the machine definition to figure out how to run the given code sym."
  [machine sym working-memory]
  (let [f (::sc/dispatcher machine)]
    (if f
      (update working-memory ::sc/data-model (fn [m] (f sym m)))
      working-memory)))

(defn get-parent
  "Get the immediate parent (id) of the given node-or-id"
  [machine node-or-id]
  (:parent (element machine node-or-id)))

(defn get-children
  "Returns the ID of the child nodes of the given `node-or-id` which
  have the given type."
  [machine node-or-id type]
  (filterv #(= (:node-type (element machine %)) type)
    (:children (element machine node-or-id))))

(defn invocations
  "Returns the IDs of the nodes that are invocations within `node-or-id-or-id`"
  [machine node-or-id] (get-children machine node-or-id :invoke))
(defn transitions [machine node-or-id] (get-children machine node-or-id :transition))
(defn exit-handlers [machine node-or-id] (get-children machine node-or-id :on-exit))
(defn entry-handlers [machine node-or-id] (get-children machine node-or-id :on-entry))
(defn history-elements [machine node-or-id] (get-children machine node-or-id :history))
(defn history-element? [machine node-or-id] (= :history (:node-type (element machine node-or-id))))
(defn state? [machine node-or-id]
  (let [n (element machine node-or-id)]
    (boolean
      (and
        (map? n)
        (#{:final :state :parallel} (:node-type n))))))

(defn child-states
  "Find all of the immediate children (IDs) of `node-or-id` that are states
   (final, node-or-id, or parallel)"
  [machine node-or-id]
  (into []
    (concat
      (get-children machine node-or-id :final)
      (get-children machine node-or-id :state)
      (get-children machine node-or-id :parallel))))

(defn atomic-state? [machine node-or-id]
  (and
    (state? machine node-or-id)
    (empty? (child-states machine node-or-id))))

(defn parallel-state? [machine node-or-id]
  (= :parallel (:node-type (element machine node-or-id))))

(defn compound-state?
  "Returns true if the given state contains other states."
  [machine node-or-id]
  (and
    (not (parallel-state? machine node-or-id))
    (not (atomic-state? machine node-or-id))))

(defn nearest-ancestor-state
  "Returns the ID of the state (if any) that encloses the given node-or-id"
  [machine node-or-id]
  (let [p (get-parent machine node-or-id)]
    (cond
      (state? machine p) p
      (nil? p) nil
      :else (nearest-ancestor-state machine p))))

(defn all-descendants
  "Returns a set of IDs of the (recursive) descendants (children) of s"
  [machine s]
  (let [immediate-children (:children (element machine s))]
    (into (set immediate-children)
      (mapcat #(all-descendants machine %) immediate-children))))

(defn descendant? [machine s1 s2]
  (let [s1-id (if (map? s1) (:id s1) s1)]
    (boolean
      (contains? (all-descendants machine s2) s1-id))))

(defn in-document-order
  "Given a set/sequence of actual nodes-or-ids (as maps), returns a vector of those nodes-or-ids, but in document order."
  [machine nodes-or-ids]
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

(defn in-exit-order
  "The reverse of in-document-order."
  [machine nodes]
  (into [] (reverse (in-document-order machine nodes))))

(defn initialize [{:keys [script] :as machine}]
  (let [state {::sc/configuration       #{}
               ::sc/states-to-invoke    #{}
               ::sc/enabled-transitions #{}
               ::sc/internal-queue      (queue)
               ::sc/history-value       {}
               ::sc/data-model          {}
               ::sc/running?            true}]
    (cond-> state
      script (script))))

(defn get-proper-ancestors
  "Returns the node ids from `machine` that are proper ancestors of `node-or-id` (an id or actual node-or-id). If `stopping-node-or-id-or-id`
   is included, then that will stop the retrieval (not including the stopping node-or-id). The results are
   in the ancestry order (i.e. deepest node-or-id first)."
  ([machine node-or-id] (get-proper-ancestors machine node-or-id nil))
  ([machine node-or-id stopping-node-or-id]
   (let [stop-id (:id (element machine stopping-node-or-id))]
     (loop [n      node-or-id
            result []]
       (let [parent-id (get-parent machine n)]
         (if (or (nil? parent-id) (= parent-id stop-id))
           result
           (recur parent-id (conj result parent-id))))))))

;; TODO: implement
(defn condition-match [machine working-memory node] true)
(defn find-least-common-compound-ancestor [machine states] nil)
(defn enter-states [machine states] nil)
(defn execute-transition-content [machine states] nil)

(defn get-effective-target-states [machine {::sc/keys [history-value] :as working-memory} t]
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
          :else (conj targets s))))
    #{}
    (:target t)))


(defn get-transition-domain [machine working-memory t]
  (let [tstates (get-effective-target-states machine working-memory t)
        tsource (nearest-ancestor-state machine t)]
    (cond
      (empty? tstates) nil
      (and
        (= :internal (:type t))
        (compound-state? machine tsource)
        (every? (fn [s] (descendant? machine s tsource)) tstates)) tsource
      :else (find-least-common-compound-ancestor machine (into [tsource] tstates)))))

(defn compute-exit-set [machine {::sc/keys [configuration] :as working-mem} transitions]
  (reduce
    (fn [acc t]
      (if (contains? t :target)
        (let [domain (get-transition-domain machine working-mem t)]
          (into acc
            (filter #(descendant? machine % domain))
            configuration))
        acc))
    #{}
    transitions))

(defn remove-conflicting-transitions
  "Updates working-mem so that enabled-transitions no longer includes any conflicting ones."
  [machine {::sc/keys [enabled-transitions] :as working-mem}]
  (let [final-transitions
        (loop [t1        (first enabled-transitions)
               remaining (rest enabled-transitions)
               filtered  #{}]
          (let [{:keys [to-remove
                        preempted?]} (reduce
                                       (fn [{:keys [to-remove] :as acc} t2]
                                         (let [exit-set-1 (compute-exit-set machine working-mem [t1])
                                               exit-set-2 (compute-exit-set machine working-mem [t2])
                                               common     (set/intersection exit-set-1 exit-set-2)]
                                           (if (empty? common)
                                             acc
                                             (if (descendant? machine (nearest-ancestor-state machine t1) (nearest-ancestor-state machine t2))
                                               (update acc :to-remove conj t2)
                                               (reduced (assoc acc :preempted? true))))))
                                       {:to-remove  #{}
                                        :preempted? false}
                                       filtered)
                filtered (if (not preempted?)
                           (conj (set/difference filtered to-remove) t1)
                           filtered)]
            (when (seq remaining)
              (recur (first remaining) (rest remaining) filtered))))]
    (assoc working-mem ::sc/enabled-transitions final-transitions)))

(defn select-eventless-transitions
  "Returns a new version of working memory with ::sc/enabled-transitions populated."
  [machine {::sc/keys [configuration] :as working-memory}]
  (let [working-memory (assoc working-memory ::sc/enabled-transitions #{})
        atomic-states  (in-document-order machine (filterv atomic-state? configuration))]
    (remove-conflicting-transitions machine
      (reduce
        (fn [wmem atomic-state]
          (let [states-to-scan    (into [atomic-state] (get-proper-ancestors machine atomic-state))
                transition-to-add (first
                                    (for [s states-to-scan
                                          t (in-document-order machine (transitions machine s))
                                          :when (and
                                                  (not (contains? t :event))
                                                  (condition-match machine working-memory t))]
                                      (:id t)))]
            (if transition-to-add
              (update wmem ::sc/enabled-transitions conj transition-to-add)
              wmem)))
        working-memory
        atomic-states))))

(defn select-transitions
  "Returns a new version of working memory with ::sc/enabled-transitions populated."
  [machine {::sc/keys [configuration] :as working-memory} event]
  (let [working-memory (assoc working-memory ::sc/enabled-transitions #{})
        atomic-states  (in-document-order machine (filterv atomic-state? configuration))]
    (remove-conflicting-transitions machine
      (reduce
        (fn [wmem atomic-state]
          (let [states-to-scan    (into [atomic-state] (get-proper-ancestors machine atomic-state))
                transition-to-add (first
                                    (for [s states-to-scan
                                          t (in-document-order machine (transitions machine s))
                                          :when (and
                                                  (contains? t :event)
                                                  (= (:event t) (:name event))
                                                  (condition-match machine working-memory t))]
                                      (:id t)))]
            (if transition-to-add
              (update wmem ::sc/enabled-transitions conj transition-to-add)
              wmem)))
        working-memory
        atomic-states))))


(comment
  (let [machine (machine {::sc/document-order :breadth-first}
                  (state {:id :A}
                    (transition {:event  :e/a
                                 :id     :t1
                                 :target :A/a})
                    (transition {:event  :e/a
                                 :id     :t2
                                 :target :A/a})
                    (state {:id :A/a}
                      (state {:id :A.a/a})))
                  (state {:id :B}))
        A       (element machine :A)
        Aaa     (element machine :A.a/a)
        Aa      (element machine :A/a)]
    (get-proper-ancestors machine :A.a/a)))

(defn invoke! [machine working-memory invocation])

(defn- run-invocations [machine working-memory]
  (let [{::sc/keys [states-to-invoke]} working-memory]
    (reduce
      (fn [wmem state-to-invoke]
        (reduce
          (partial invoke! machine)
          wmem
          (invocations machine state-to-invoke)))
      (assoc working-memory ::sc/states-to-invoke #{})
      states-to-invoke)))

(defn- run-many
  "Run the code associated with the given nodes. Returns a new working-memory with an update data model (context)."
  [machine working-memory nodes]
  (reduce
    (fn [{:keys [function]}]
      (cond
        (ifn? function) (update working-memory ::sc/data-model function)
        (symbol? function) (dispatch machine function working-memory)
        :else working-memory))
    working-memory
    nodes))

(defn- cancel-invoke [i]
  ;; TODO
  )

(defn- cancel-active-invocations [working-memory state]
  (doseq [i (invocations machine state)] (cancel-invoke i))
  working-memory)


(defn exit-states
  "Does all of the processing for exiting states. Returns new working memory."
  [machine {::sc/keys [enabled-transitions
                       states-to-invoke
                       history-value
                       configuration] :as working-memory}]
  (let [states-to-exit   (in-exit-order machine (compute-exit-set machine working-memory enabled-transitions))
        states-to-invoke (set/difference states-to-invoke (set states-to-exit))
        history-nodes    (into {}
                           (map (fn [s] [s (history-elements machine (get-parent machine s))]))
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

(defn- microstep [machine working-memory]
  (->> working-memory
    (exit-states machine)
    (execute-transition-content machine)
    (enter-states machine)))

(defn- handle-eventless-transitions
  "Work through eventless transitions, returning the updated working memory"
  [machine working-memory]
  (loop [wmem working-memory]
    (let [{::sc/keys [enabled-transitions
                      internal-queue] :as wmem} (select-eventless-transitions machine wmem)
          wmem (if (empty? enabled-transitions)
                 (if (empty? internal-queue)
                   wmem
                   (let [internal-event (first internal-queue)]
                     (-> wmem
                       (update ::sc/internal-queue pop)
                       (assoc-in [::sc/data-model :_event] internal-event)
                       (assoc ::sc/enabled-transitions (select-transitions machine wmem internal-event))))))
          {::sc/keys [running? macrostep-done?]
           :as       wmem} (if (seq (::sc/enabled-transitions wmem))
                             (microstep machine wmem))]
      (if (and running? (not macrostep-done?))
        (recur wmem)
        wmem))))

(defn- run-exit-handlers [working-memory state]
  (reduce
    (fn [wmem handler] (update wmem ::sc/data-model handler))
    working-memory
    (exit-handlers machine state)))

(defn- exit-interpreter [machine {::sc/keys [configuration] :as working-memory}]
  (let [states-to-exit (in-exit-order machine configuration)]
    (reduce (fn [wmem state]
              (let [result (-> wmem
                             (run-exit-handlers state)
                             (cancel-active-invocations state)
                             (update ::sc/configuration disj state))]
                ;; TODO: Sending events back to the machine that started this one
                #_(when (and (final-state? state) (nil? (:parent state)))
                    (send-done-event! wmem (get-done-data state)))
                result))
      working-memory
      states-to-exit)))

(defn before-event
  "Steps that are run before processing the next event."
  [machine working-memory]
  (loop [step-memory working-memory]
    (let [working-memory (assoc step-memory
                           ::sc/enabled-transitions nil
                           ::sc/macrostep-done? false)
          {::sc/keys [states-to-invoke running?]
           :as       working-memory2} (handle-eventless-transitions machine working-memory)]
      (if running?
        (let [final-mem (run-invocations machine working-memory2)]
          (if (seq (::sc/internal-queue final-mem))
            (recur final-mem)
            final-mem))
        (exit-interpreter machine working-memory2)))))

(defn- cancel? [event] false)
(defn- handle-external-invocations [machine working-memory]
  ;; TODO
  working-memory)

(defn process-event
  "Process the given `external-event` given a state `machine` with the `working-memory` as its current status/state."
  [machine working-memory external-event]
  (if (cancel? external-event)
    (exit-interpreter machine working-memory)
    (as-> working-memory $
      (assoc $ ::sc/enabled-transitions (select-transitions machine $ external-event))
      (handle-external-invocations machine $)
      (microstep machine $)
      (before-event machine $))))

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
                {:keys [target event cond] :as transition} (first transitions)
                immediate-children (set (child-states machine parent))
                possible-problem   (cond-> (assoc hn :msgs [])
                                     (= 1 (count transitions)) (e "A history node MUST have exactly one transition")
                                     (and (nil? event) (nil? cond)) (e "A history transition MUST NOT have cond/event.")
                                     (and (not deep?) (not= 1 (count target))) (e "Exactly ONE transition target is required for shallow history.")
                                     ;; TODO: Validate deep history
                                     (or deep? (= 1 (count immediate-children))) (e "Exactly ONE transition target for shallow. If many, then deep history is required."))]
          :when (pos-int? (count (:msgs possible-problem)))]
      possible-problem)))
