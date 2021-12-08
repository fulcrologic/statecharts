(ns com.fulcrologic.statecharts.state-machine
  (:require
    [clojure.walk :as walk]
    [com.fulcrologic.statecharts.util :refer [queue]]
    [clojure.spec.alpha :as s]
    [clojure.set :as set])
  #?(:clj (:import (java.util UUID))))

(declare assign-parents ids-in-document-order)

(defn- genid
  "Generate a unique ID with a base prefix. Like `gensym` but returns a keyword."
  [s] (keyword (str (gensym s))))

(defn get-node
  "Find the node in the machine that has the given ID (of any type)"
  [machine id]
  (get-in machine [::node-by-id id]))


(defn machine
  "Create a new state machine definition that mimics the structure and semantics of SCXML."
  [{:keys [initial name script] :as attrs} & children]
  (let [node         (assoc attrs
                       :node-type :machine
                       :children (assign-parents nil children))
        ids-in-order (ids-in-document-order node)]
    (assoc node
      ::node-by-id (into {}
                     (keep (fn [{:keys [id] :as n}] (when id [id n])))
                     (tree-seq :children :children node))
      ::ids-in-document-order ids-in-order)))

(defn dispatch
  "Use the machine definition to figure out how to run the given code sym."
  [machine sym working-memory]
  (let [f (::dispatcher machine)]
    (if f
      (update working-memory ::data-model (fn [m] (f sym m)))
      working-memory)))

(defn state [{:keys [id initial?] :as attrs} & children]
  (merge {:id (genid "state")} attrs {:node-type :state
                                      :children  (vec children)}))

(defn history
  "Create a history node. Set `deep?` true for deep history."
  [{:keys [deep? id] :as attrs}]
  (merge {:id (genid "history")}
    attrs
    {:node-type :history}))

(defn final [{:keys [id] :as attrs} & children]
  (merge {:id (genid "final")}
    attrs
    {:node-type :final
     :children  (vec children)}))

(defn invoke [f] {:id        (genid "invoke")
                  :node-type :invoke
                  :function  f})

(defn initial "Alias for `(state {:initial? true ...} ...)`."
  [attrs & children]
  (apply state (assoc attrs :initial? true)
    children))

(defn on-enter [attrs f]
  (merge
    {:id (genid "enter")}
    attrs
    {:node-type :on-entry
     :function  f}))

(defn on-exit [attrs f]
  (merge
    {:id (genid "exit")}
    attrs
    {:node-type :on-exit
     :function  f}))

(defn parallel [attrs & children]
  (merge
    {:id (genid "parallel")}
    attrs
    {:node-type :parallel
     :children  (vec children)}))

(defn transition
  ([{:keys [event cond target type] :as attrs}]
   (transition attrs nil))
  ([{:keys [event cond target node-type] :as attrs} action]
   (merge
     {:id   (genid "transition")
      :type :external}
     attrs
     (cond-> {:node-type :transition}
       action (assoc :action action)))))

(defn- get-parent [node] (:parent node))
(defn- get-children [node type] (filterv #(= (:node-type %) type) (:children node)))
(defn- invocations [node] (get-children node :invoke))
(defn- transitions [node] (get-children node :transition))
(defn- exit-handlers [node] (get-children node :on-exit))
(defn- entry-handlers [node] (get-children node :on-entry))
(defn- history-nodes [node] (get-children node :history))
(defn- state? [s]
  (boolean
    (and
      (map? s)
      (#{:final :state :parallel} (:node-type s)))))
(defn- source
  "Returns the source state of a node"
  [machine node]
  (let [p (get-parent node)]
    (cond
      (state? p) p
      (nil? p) nil
      :else (source machine p))))

(defn- child-states
  "Find all of the immediate children of `state` that are also state nodes (final, state, or parallel)"
  [state] (into []
            (concat
              (get-children state :final)
              (get-children state :state)
              (get-children state :parallel))))

(defn- descendant? [s1 s2]
  (let [children (set (child-states s2))]
    (contains? children s1)))

(defn- ids-in-document-order
  "Returns the IDs of the states in the given node, in document order (not including the node itself).
   You can specify `::document-order :breadth-first` on the top-level machine definition to get a
   depth-first interpretation vs. breadth."
  ([machine]
   (ids-in-document-order machine machine))
  ([{desired-order ::document-order :as machine} {:keys [id] :as node}]
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

(defn atomic-state? [node]
  (and
    (state? node)
    (empty? (child-states node))))



(defn in-document-order
  "Given a set/sequence of actual nodes (as maps), returns a vector of those nodes, but in document order."
  [machine nodes]
  (let [ordered-ids (::ids-in-document-order machine)
        all-states  (zipmap (map :id nodes) nodes)]
    (reduce
      (fn [acc id]
        (if (contains? all-states id)
          (conj acc (get all-states id))
          acc))
      []
      ordered-ids)))

(def in-entry-order
  "[machine nodes]

   Same as in-document-order."
  in-document-order)

(defn in-exit-order
  "The reverse of in-document-order."
  [machine nodes]
  (into []
    (reverse (in-document-order machine nodes))))

(defn initialize [{:keys [script] :as machine}]
  (let [state {::configuration       #{}
               ::states-to-invoke    #{}
               ::enabled-transitions #{}
               ::internal-queue      (queue)
               ::history-value       {}
               ::data-model          {}
               ::running?            true}]
    (cond-> state
      script (script))))

(defn get-proper-ancestors
  "Returns the nodes from `machine` that are proper ancestors of `node` (an id or actual node). If `stopping-node`
   is included, then that will stop the retrieval (not including the stopping node). Returned in the ancestry order
   (i.e. deepest node first)."
  ([machine node] (get-proper-ancestors machine node nil))
  ([machine node stopping-node]
   (loop [n      (if (keyword? node) (get-node machine node) node)
          result []]
     (let [parent-id (get-parent n)
           parent    (get-node machine parent-id)]
       (if (or (nil? parent)
             (= parent-id stopping-node)
             (= parent stopping-node))
         result
         (recur parent (conj result parent)))))))

;; TODO: implement
(defn condition-match [maching working-memory node] true)

(defn get-transition-domain [machine t]
  (let [tstates (get-effective-target-states machine t)
        tsource (source machine t)]
    (cond
      (empty? tstates) nil
      (and (= :internal (:type t))
        (compound-state? tsource)
        (every? (fn [s] (descendant? s tsource)) tstates)) tsource
      :else (find-LCCA (into [tsource] tstates)))))

(defn compute-exit-set [machine {::keys [configuration] :as working-mem} transitions]
  (reduce
    (fn [acc t]
      (if (contains? t :target)
        (let [domain (get-transition-domain machine t)]
          (into acc
            (filter #(descendant? % domain))
            configuration))
        acc))
    #{}
    transitions))

(defn remove-conflicting-transitions
  "Updates working-mem so that enabled-transitions no longer includes any conflicting ones."
  [machine {::keys [enabled-transitions] :as working-mem}]
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
                                             (if (descendant? (source machine t1) (source machine t2))
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
    (assoc working-mem ::enabled-transitions final-transitions)))

(defn select-eventless-transitions
  "Returns a new version of working memory with ::enabled-transitions populated."
  [machine {::keys [configuration] :as working-memory}]
  (let [working-memory (assoc working-memory ::enabled-transitions #{})
        atomic-states  (in-document-order machine (filterv atomic-state? configuration))]
    (remove-conflicting-transitions machine
      (reduce
        (fn [wmem atomic-state]
          (let [states-to-scan    (into [atomic-state] (get-proper-ancestors machine atomic-state))
                transition-to-add (first
                                    (for [s states-to-scan
                                          t (in-document-order machine (transitions s))
                                          :when (and
                                                  (not (contains? t :event))
                                                  (condition-match machine working-memory t))]
                                      (:id t)))]
            (if transition-to-add
              (update wmem ::enabled-transitions conj transition-to-add)
              wmem)))
        working-memory
        atomic-states))))

(defn select-transitions
  "Returns a new version of working memory with ::enabled-transitions populated."
  [machine {::keys [configuration] :as working-memory} event]
  (let [working-memory (assoc working-memory ::enabled-transitions #{})
        atomic-states  (in-document-order machine (filterv atomic-state? configuration))]
    (remove-conflicting-transitions machine
      (reduce
        (fn [wmem atomic-state]
          (let [states-to-scan    (into [atomic-state] (get-proper-ancestors machine atomic-state))
                transition-to-add (first
                                    (for [s states-to-scan
                                          t (in-document-order machine (transitions s))
                                          :when (and
                                                  (contains? t :event)
                                                  (= (:event t) (:name event))
                                                  (condition-match machine working-memory t))]
                                      (:id t)))]
            (if transition-to-add
              (update wmem ::enabled-transitions conj transition-to-add)
              wmem)))
        working-memory
        atomic-states))))


(comment
  (let [machine (machine {::document-order :breadth-first}
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
        A       (get-node machine :A)
        Aaa     (get-node machine :A.a/a)
        Aa      (get-node machine :A/a)]
    (get-proper-ancestors machine :A.a/a)))

(defn invoke! [machine working-memory invocation])

(defn- run-invocations [machine working-memory]
  (let [{::keys [states-to-invoke]} working-memory]
    (reduce
      (fn [wmem state-to-invoke]
        (reduce
          (partial invoke! machine)
          wmem
          (invocations state-to-invoke)))
      (assoc working-memory ::states-to-invoke #{})
      states-to-invoke)))

(defn- run-many
  "Run the code associated with the given nodes. Returns a new working-memory with an update data model (context)."
  [machine working-memory nodes]
  (reduce
    (fn [{:keys [function]}]
      (cond
        (ifn? function) (update working-memory ::data-model function)
        (symbol? function) (dispatch machine function working-memory)
        :else working-memory))
    working-memory
    nodes))

(defn- cancel-invoke [i]
  ;; TODO
  )

(defn- cancel-active-invocations [working-memory state]
  (doseq [i (invocations state)] (cancel-invoke i))
  working-memory)


(defn exit-states
  "Does all of the processing for exiting states. Returns new working memory."
  [machine {::keys [enabled-transitions
                    states-to-invoke
                    history-value
                    configuration] :as working-memory}]
  (let [states-to-exit   (in-exit-order machine (compute-exit-set machine working-memory enabled-transitions))
        states-to-invoke (set/difference states-to-invoke (set states-to-exit))
        history-nodes    (into {}
                           (map (fn [s] [s (history-nodes (get-parent s))]))
                           states-to-exit)
        history-value    (reduce-kv
                           (fn [acc s {:keys [id deep?] :as hn}]
                             (let [f (if deep?
                                       (fn [s0] (and (atomic-state? s0) (descendant? s0 s)))
                                       (fn [s0] (= s (get-parent s0))))]
                               (assoc acc id (into #{} (filter f configuration)))))
                           history-value
                           history-nodes)
        working-memory   (assoc working-memory
                           ::states-to-invoke states-to-invoke
                           ::history-value history-value)]
    (reduce
      (fn [wmem s]
        (let [to-exit (exit-handlers s)
              run     (partial run-many machine)]
          (-> wmem
            (run to-exit)
            (cancel-active-invocations s)
            (update ::configuration disj s))))
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
    (let [{::keys [enabled-transitions
                   internal-queue] :as wmem} (select-eventless-transitions machine wmem)
          wmem (if (empty? enabled-transitions)
                 (if (empty? internal-queue)
                   wmem
                   (let [internal-event (first internal-queue)]
                     (-> wmem
                       (update ::internal-queue pop)
                       (assoc-in [::data-model :_event] internal-event)
                       (assoc ::enabled-transitions (select-transitions machine wmem internal-event))))))
          {::keys [running? macrostep-done?]
           :as    wmem} (if (seq (::enabled-transitions wmem))
                          (microstep machine wmem))]
      (if (and running? (not macrostep-done?))
        (recur wmem)
        wmem))))

(defn- run-exit-handlers [working-memory state]
  (reduce
    (fn [wmem handler] (update wmem ::data-model handler))
    working-memory
    (exit-handlers state)))

(defn- exit-interpreter [machine {::keys [configuration] :as working-memory}]
  (let [states-to-exit (exit-order machine configuration)]
    (reduce (fn [wmem state]
              (let [result (-> wmem
                             (run-exit-handlers state)
                             (cancel-active-invocations state)
                             (update ::configuration disj state))]
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
                           ::enabled-transitions nil
                           ::macrostep-done? false)
          {::keys [states-to-invoke running?]
           :as    working-memory2} (handle-eventless-transitions machine working-memory)]
      (if running?
        (let [final-mem (run-invocations machine working-memory2)]
          (if (seq (::internal-queue final-mem))
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
      (assoc $ ::enabled-transitions (select-transitions machine $ external-event))
      (handle-external-invocations machine $)
      (microstep machine $)
      (before-event machine $))))


