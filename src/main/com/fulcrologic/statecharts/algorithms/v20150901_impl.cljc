(ns com.fulcrologic.statecharts.algorithms.v20150901-impl
  "An implementation of the W3C SCXML Recommended Spec from 2015-09-01.

   Uses an imperative style (internally) to match the pseudocode in the standard for easier translation,
   verification, and avoidance of subtle differences in implementation."
  #?(:cljs (:require-macros [com.fulcrologic.statecharts.algorithms.v20150901 :refer [in-context]]))
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.elements :as elements]
    [com.fulcrologic.statecharts.events :as evts :refer [new-event]]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    com.fulcrologic.statecharts.specs
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.state-machine :as sm]
    [com.fulcrologic.statecharts.util :refer [queue genid]]
    [taoensso.timbre :as log])
  #?(:clj (:import (java.util UUID))))

(defmacro in-context
  "Change the `env` context to the given state element (you can supply id or element itself). Runs the `body` with
   the update env, and returns the last expression of the body. `env` changes to context
   are not visible outside of this block."
  [env-sym state-or-id & body]
  `(let [m#  (get ~env-sym ::sc/machine)
         id# (sm/element-id m# ~state-or-id)
         ~env-sym (assoc ~env-sym ::sc/context-element-id id#)]
     ~@body))

(s/fdef in-context
  :ret any?
  :args (s/cat :s symbol? :sid any? :body (s/* any?)))

(>defn condition-match
  [{::sc/keys [machine execution-model] :as env} element-or-id]
  [::sc/env ::sc/element-or-id => boolean?]
  (let [{:keys [cond]} (sm/element machine element-or-id)]
    (if (nil? cond)
      true
      (boolean (sp/run-expression! execution-model env cond)))))

(>defn session-id
  "Returns the unique session id from an initialized `env`."
  [{::sc/keys [vwmem] :as env}]
  [::sc/env => ::sc/session-id]
  (::sc/session-id @vwmem))

(>defn in-final-state?
  "Returns true if `non-atomic-state` is completely done."
  [{::sc/keys [machine vwmem] :as env} non-atomic-state]
  [::sc/env (? ::sc/element-or-id) => boolean?]
  (boolean
    (cond
      (sm/compound-state? machine non-atomic-state) (some
                                                      (fn [s] (and (sm/final-state? machine s) (contains? (::sc/configuration @vwmem) s)))
                                                      (sm/child-states machine non-atomic-state))
      (sm/parallel-state? machine non-atomic-state) (every?
                                                      (fn [s] (in-final-state? env s))
                                                      (sm/child-states machine non-atomic-state))
      :else false)))

(declare add-descendant-states-to-enter!)

(>defn add-ancestor-states-to-enter! [{::sc/keys [machine] :as env} state ancestor
                                      states-to-enter states-for-default-entry default-history-content]
  [::sc/env ::sc/element-or-id ::sc/element-or-id volatile? volatile? volatile? => nil?]
  (doseq [anc (sm/get-proper-ancestors machine state ancestor)]
    (vswap! states-to-enter conj (sm/element-id machine anc))
    (when (sm/parallel-state? machine anc)
      (doseq [child (sm/child-states machine anc)]
        (when-not (some (fn [s] (sm/descendant? machine s child)) @states-to-enter)
          (add-descendant-states-to-enter! env child states-to-enter
            states-for-default-entry default-history-content)))))
  nil)

(>defn add-descendant-states-to-enter! [{::sc/keys [machine vwmem] :as env}
                                        state states-to-enter states-for-default-entry default-history-content]
  [::sc/env ::sc/element-or-id volatile? volatile? volatile? => nil?]
  (letfn [(add-elements! [target parent]
            (doseq [s target]
              (add-descendant-states-to-enter! env s states-to-enter
                states-for-default-entry default-history-content))
            (doseq [s target]
              (add-ancestor-states-to-enter! env s parent states-to-enter
                states-for-default-entry default-history-content)))]
    (let [{:keys [id parent] :as state} (sm/element machine state)]
      (if (sm/history-element? machine state)
        (if-let [previously-active-states (get (::sc/history-value @vwmem) id)]
          (add-elements! previously-active-states parent)
          (let [{:keys [content target]} (sm/transition-element machine state)]
            (when content (vswap! default-history-content assoc parent content))
            (add-elements! target parent)))
        ; not a history element
        (do
          (vswap! states-to-enter conj (sm/element-id machine state))
          (if (sm/compound-state? machine state)
            (let [target (->> (sm/initial-element machine state) (sm/transition-element machine) :target)]
              (vswap! states-for-default-entry conj (sm/element-id machine state))
              (add-elements! target state))
            (if (sm/parallel-state? machine state)
              (doseq [child (sm/child-states machine state)]
                (when-not (some (fn [s] (sm/descendant? machine s child)) @states-to-enter)
                  (add-descendant-states-to-enter! env child states-to-enter
                    states-for-default-entry default-history-content))))))))
    nil))

(>defn get-effective-target-states
  [{::sc/keys [machine vwmem] :as env} t]
  [::sc/machine ::sc/element-or-id => (s/every ::sc/element-or-id :kind set?)]
  (let [{::sc/keys [history-value] :as working-memory} @vwmem]
    (reduce
      (fn [targets s]
        (let [{:keys [id] :as s} (sm/element machine s)]
          (cond
            (and
              (sm/history-element? machine s)
              (contains? history-value id))
            #_=> (set/union targets (get history-value id))
            (sm/history-element? machine s)
            #_=> (let [default-transition (first (sm/transitions machine s))] ; spec + validation. There will be exactly one
                   (set/union targets (get-effective-target-states env default-transition)))
            :else (conj targets id))))
      #{}
      (:target (sm/element machine t)))))

(>defn get-transition-domain
  [{::sc/keys [machine] :as env} t]
  [::sc/env ::sc/element-or-id => (? ::sc/id)]
  (let [tstates (get-effective-target-states env t)
        tsource (sm/nearest-ancestor-state machine t)]
    (cond
      (empty? tstates) nil
      (and
        (= :internal (:type t))
        (sm/compound-state? machine tsource)
        (every? (fn [s] (sm/descendant? machine s tsource)) tstates)) tsource
      :else (:id (sm/find-least-common-compound-ancestor machine (into [tsource] tstates))))))

(>defn compute-entry-set!
  "Returns [states-to-enter states-for-default-entry default-history-content]."
  [{::sc/keys [vwmem machine] :as env}]
  [::sc/env => (s/tuple set? set? map?)]
  (let [states-to-enter          (volatile! #{})
        states-for-default-entry (volatile! #{})
        default-history-content  (volatile! {})
        transitions              (mapv #(or (sm/element machine %)
                                          (elements/transition {:target %})) (::sc/enabled-transitions @vwmem))]
    (doseq [{:keys [target] :as t} transitions]
      (log/trace "Entry set target(s): " target)
      (let [ancestor (sm/element-id machine (get-transition-domain env t))]
        (doseq [s target]
          (add-descendant-states-to-enter! env s states-to-enter
            states-for-default-entry default-history-content))
        (doseq [s (get-effective-target-states env t)]
          (add-ancestor-states-to-enter! env s ancestor states-to-enter
            states-for-default-entry default-history-content))))
    [@states-to-enter @states-for-default-entry @default-history-content]))

(>defn initialize-data-model!
  "Initialize the data models in volatile working memory `wmem` for the given states, if necessary."
  [{:sc/keys [machine data-model execution-model] :as env} state]
  [::sc/env ::sc/element-or-id => nil?]
  (log/info "Initializing data model for" state)
  (let [dm-eles (sm/get-children machine state :data-model)
        {:keys [src expr]} (sm/element machine (first dm-eles))]
    (when (> (count dm-eles) 1)
      (log/warn "Too many data elements on" state))
    (in-context env state
      (cond
        src (sp/load-data data-model env src)
        (fn? expr) (let [txn (sp/run-expression! execution-model env expr)]
                     (if (vector? txn)
                       (sp/transact! data-model env {:txn txn})))
        (map? expr) (sp/transact! data-model env {:txn (ops/set-map-txn expr)})
        :else (sp/transact! data-model env {:txn (ops/set-map-txn {})})))
    nil))

(defmulti execute-element-content!
  "Multimethod. Extensible mechanism for running the content of elements on the state machine. Dispatch by :node-type
   of the element itself."
  (fn [env element] (:node-type element)))

(defmethod execute-element-content! :default [{:keys [execution-model] :as env} {:keys [node-type expr] :as element}]
  (if (nil? expr)
    (log/warn "No implementation to run content of " element)
    (sp/run-expression! execution-model env expr)))

(>defn execute!
  "Run the executable content (immediate children) of s. Returns an updated wmem."
  [{::sc/keys [machine] :as env} s]
  [::sc/env ::sc/element-or-id => nil?]
  (log/info "Execute content of" s)
  (let [{:keys [children]} (sm/element machine s)]
    (doseq [n children]
      (try
        (let [ele (sm/element machine n)]
          (in-context env ele
            (execute-element-content! env ele)))
        (catch #?(:clj Throwable :cljs :default) t
          (log/error t "Unexpected exception in content")))))
  nil)

(>defn enter-states!
  "Enters states, triggers actions, tracks long-running invocations, and
   returns updated working memory."
  [{::sc/keys [machine vwmem] :as env}]
  [::sc/env => nil?]
  (log/info "enter-states")
  (let [[states-to-enter
         states-for-default-entry
         default-history-content] (compute-entry-set! env)]
    (doseq [s (sm/in-entry-order machine states-to-enter)
            :let [state-id (sm/element-id machine s)]]
      (in-context env s
        (vswap! vwmem update ::sc/configuration conj s)
        (vswap! vwmem update ::sc/states-to-invoke conj s)
        (when (and (= :late (:binding machine)) (not (contains? (::sc/initialized-states @vwmem) state-id)))
          (initialize-data-model! env s)
          (vswap! vwmem update ::sc/initialized-states (fnil conj #{}) state-id))
        (doseq [entry (sm/entry-handlers machine s)]
          (execute! env entry))
        (when-let [t (and (contains? states-for-default-entry s)
                       (some->> s (sm/initial-element machine) (sm/transition-element machine)))]
          (execute! env t))
        (when-let [content (get default-history-content (sm/element-id machine s))]
          (execute! env content))
        (when (sm/final-state? machine s)
          (if (= :ROOT (sm/get-parent machine s))
            (vswap! vwmem assoc ::sc/running? false)
            (let [parent      (sm/get-parent machine s)
                  grandparent (sm/get-parent machine parent)
                  done-data   {}]                           ;; TASK: done-data
              (vswap! vwmem update ::sc/internal-queue conj
                (new-event {:sendid (sm/element-id machine s)
                            :type   :internal
                            :data   done-data
                            :name   (keyword (str "done.state." (name (sm/element-id machine parent))))}))
              (when (and (sm/parallel-state? machine grandparent)
                      (every? (fn [s] (in-final-state? env s)) (sm/child-states machine grandparent)))
                (vswap! vwmem update ::sc/internal-queue conj
                  (new-event {:sendid (sm/element-id machine s)
                              :type   :internal
                              :data   done-data
                              :name   (keyword (str "done.state." (name (sm/element-id machine grandparent))))})))))))))
  nil)

(>defn execute-transition-content!
  [{::sc/keys [vwmem] :as env}]
  [::sc/env => nil?]
  (doseq [t (::sc/enabled-transitions @vwmem)]
    (execute! env t))
  nil)

(>defn compute-exit-set
  [{::sc/keys [machine vwmem] :as env} transitions]
  [::sc/machine (s/every ::sc/element-or-id) => (s/every ::sc/id :kind set?)]
  (reduce
    (fn [acc t]
      (if (contains? (sm/element machine t) :target)
        (let [domain (get-transition-domain env t)]
          (into acc
            (filter #(sm/descendant? machine % domain))
            (::sc/configuration @vwmem)))
        acc))
    #{}
    transitions))

(>defn remove-conflicting-transitions
  "Updates working-mem so that enabled-transitions no longer includes any conflicting ones."
  [{::sc/keys [machine] :as env} enabled-transitions]
  [::sc/machine (s/every ::sc/id) => (s/every ::sc/id)]
  (let [filtered-transitions (volatile! #{})]
    (doseq [t1 enabled-transitions
            :let [to-remove  (volatile! #{})
                  preempted? (volatile! false)]]
      (doseq [t2 @filtered-transitions
              :while (not preempted?)]
        (when (seq (set/intersection
                     (compute-exit-set env [t1])
                     (compute-exit-set env [t2])))
          (if (sm/descendant? machine (sm/source t1) (sm/source t2))
            (vswap! to-remove conj t2)
            (vreset! preempted? true))))
      (when (not @preempted?)
        (do
          (doseq [t3 @to-remove]
            (vswap! filtered-transitions disj t3))
          (vswap! filtered-transitions conj t1))))
    @filtered-transitions))

(>defn select-transitions* [machine configuration predicate]
  [::sc/machine ::sc/configuration ifn? => ::sc/enabled-transitions]
  (let [enabled-transitions (volatile! #{})
        looping?            (volatile! true)
        start-loop!         #(vreset! looping? true)
        break!              #(vreset! looping? false)
        atomic-states       (sm/in-document-order machine (filterv #(sm/atomic-state? machine %) configuration))]
    (doseq [state atomic-states]
      (start-loop!)
      (doseq [s (into [state] (sm/get-proper-ancestors machine state))
              :when @looping?]
        (doseq [t (map #(sm/element machine %) (sm/in-document-order machine (sm/transitions machine s)))
                :when (and @looping? (predicate t))]
          (vswap! enabled-transitions conj (sm/element-id machine t))
          (break!))))
    @enabled-transitions))

(>defn select-eventless-transitions!
  "Returns a new version of working memory with ::sc/enabled-transitions populated."
  [{::sc/keys [machine vwmem] :as env}]
  [::sc/env => nil?]
  (let [tns (remove-conflicting-transitions env
              (select-transitions* machine (::sc/configuration @vwmem)
                (fn [t] (and (not (:event t)) (condition-match env t)))))]
    (vswap! vwmem assoc ::sc/enabled-transitions tns)))

(>defn select-transitions!
  "Returns a new version of working memory with ::sc/enabled-transitions populated."
  [{::sc/keys [machine vwmem] :as env} event]
  [::sc/env ::sc/event-or-name => ::sc/active-working-memory]
  (let [tns (remove-conflicting-transitions env
              (select-transitions* machine (::sc/configuration @vwmem)
                (fn [t] (and
                          (contains? t :event)
                          (evts/name-match? (:event t) event)
                          (condition-match env t)))))]
    (vswap! vwmem assoc ::sc/enabled-transitions tns)))

(>defn invoke! [env invocation]
  [::sc/env ::sc/element-or-id => nil?]
  ;; TASK: Implement this
  (log/warn "Invoke not implemented" invocation)
  nil)

(>defn run-invocations! [{::sc/keys [machine vwmem] :as env}]
  [::sc/env => nil?]
  (let [{::sc/keys [states-to-invoke]} @vwmem]
    (doseq [state-to-invoke states-to-invoke]
      (in-context env state-to-invoke
        (doseq [i (sm/invocations machine state-to-invoke)]
          (invoke! env i)))))
  nil)

(>defn run-many!
  "Run the code associated with the given nodes. Does NOT set context id of the nodes run."
  [env nodes]
  [::sc/env (s/every ::sc/element-or-id) => nil?]
  (doseq [n nodes]
    (execute! env n))
  nil)

(defn cancel-invoke! [i]
  ;; TASK
  (log/warn "Cancel not implemented" i))

(>defn cancel-active-invocations!
  [{::sc/keys [machine] :as env} state]
  [::sc/env ::sc/element-or-id => nil?]
  (doseq [i (sm/invocations machine state)]
    (cancel-invoke! i))
  nil)

(>defn exit-states!
  "Does all of the processing for exiting states. Returns new working memory."
  [{::sc/keys [machine vwmem] :as env}]
  [::sc/env => nil?]
  (try
    (let [{::sc/keys [enabled-transitions
                      configuration
                      history-value]} @vwmem
          states-to-exit   (sm/in-exit-order machine (compute-exit-set env enabled-transitions))
          states-to-invoke (set/difference (::sc/states-to-invoke @vwmem) (set states-to-exit))
          history-nodes    (into {}
                             (keep (fn [s]
                                     (let [eles (sm/history-elements machine (sm/get-parent machine s))]
                                       (when (seq eles)
                                         [(sm/element-id machine s) eles]))))
                             states-to-exit)
          history-value    (reduce-kv
                             (fn [acc s {:keys [id deep?] :as hn}]
                               (let [f (if deep?
                                         (fn [s0] (and (sm/atomic-state? machine s0) (sm/descendant? machine s0 s)))
                                         (fn [s0] (= s (sm/get-parent machine s0))))]
                                 (assoc acc id (into #{} (filter f configuration)))))
                             history-value
                             history-nodes)]
      (vswap! vwmem assoc
        ::sc/states-to-invoke states-to-invoke
        ::sc/history-value history-value)
      (doseq [s states-to-exit]
        (in-context env s
          (let [to-exit (sm/exit-handlers machine s)]
            (run-many! env to-exit)
            (cancel-active-invocations! env s)
            (vswap! vwmem update ::sc/configuration disj s)))))
    (catch #?(:clj Throwable :cljs :default) t
      (log/error t "Unexpected error in enter states")))
  nil)

(>defn microstep!
  [env]
  [::sc/env => nil?]
  (exit-states! env)
  (execute-transition-content! env)
  (enter-states! env)
  nil)

(>defn handle-eventless-transitions!
  "Work through eventless transitions, returning the updated working memory"
  [{::sc/keys [vwmem data-model] :as env}]
  [::sc/env => nil?]
  (let [macrostep-done? (volatile! false)]
    (while (and (::sc/running? @vwmem) (not @macrostep-done?))
      (select-eventless-transitions! env)
      (let [{::sc/keys [enabled-transitions
                        internal-queue]} @vwmem]
        (when (empty? enabled-transitions)
          (if (empty? internal-queue)
            (vreset! macrostep-done? true)
            (let [internal-event (first internal-queue)]
              (vswap! vwmem update ::sc/internal-queue pop)
              (sp/transact! data-model env [(ops/assign :_event internal-event)])
              (select-transitions! env internal-event))))
        (when (seq (::sc/enabled-transitions @vwmem))
          (microstep! env)))))
  nil)

(>defn run-exit-handlers!
  "Run the exit handlers of `state`."
  [{::sc/keys [machine] :as env} state]
  [::sc/env ::sc/element-or-id => nil?]
  (in-context env state
    (let [nodes (sm/in-document-order machine (sm/exit-handlers machine state))]
      (run-many! env nodes)))
  nil)

;; TASK: Sending events back to the machine that started this one, if there is one
(>defn send-done-event! [env state]
  [::sc/env ::sc/element-or-id => nil?]
  (in-context env state
    (log/warn "done event not implements" state))
  nil)

(>defn exit-interpreter!
  [{::sc/keys [machine vwmem] :as env}]
  [::sc/env => ::sc/working-memory]
  (let [states-to-exit (sm/in-exit-order machine (::sc/configuration @vwmem))]
    (doseq [state states-to-exit]
      (run-exit-handlers! env state)
      (cancel-active-invocations! env state)
      (vswap! vwmem update ::sc/configuration disj state)
      (when (and (sm/final-state? machine state) (nil? (:parent (sm/element machine state))))
        (send-done-event! env state)))))

(>defn before-event!
  "Steps that are run before processing the next event."
  [{::sc/keys [machine vwmem] :as env}]
  [::sc/env => nil?]
  (let [{::sc/keys [running?]} @vwmem]
    (when running?
      (loop []
        (vswap! vwmem assoc ::sc/enabled-transitions #{} ::sc/macrostep-done? false)
        (handle-eventless-transitions! env)
        (if running?
          (do
            (run-invocations! env)
            (when (seq (::sc/internal-queue @vwmem))
              (recur)))
          (exit-interpreter! machine))))))

(defn cancel? [event] (= :EXIT (:node-type event)))

(>defn handle-external-invocations! [env]
  [::sc/env => nil?]
  ;; TASK
  (log/warn "External invocations not implemented")
  nil)

(>defn runtime-env
  "Set up `env` to track live data that is needed during the algorithm."
  [env wmem]
  [::sc/env ::sc/working-memory => ::sc/env]
  (assoc env
    ::sc/context-element-id :ROOT
    ::sc/vwmem (volatile! (assoc
                            wmem
                            ::sc/enabled-transitions #{}
                            ::sc/states-to-invoke #{}
                            ::sc/macrostep-done? false))))



(>defn process-event!
  "Process the given `external-event` given a state `machine` with the `working-memory` as its current status/state.
   Returns the new version of working memory which you should save to pass into the next call.  Typically this function
   is called by an overall processing system instead of directly."
  [{::sc/keys [data-model vwmem] :as env} external-event]
  [::sc/env ::sc/event-or-name => ::sc/working-memory]
  (if (cancel? external-event)
    (exit-interpreter! env)
    (do
      (select-transitions! env external-event)
      (sp/transact! data-model env [(ops/assign :_event external-event)])
      (handle-external-invocations! env)
      (microstep! env)
      (before-event! env)))
  (dissoc (some-> env ::sc/vwmem deref)
    ::sc/states-to-invoke
    ::sc/enabled-transitions
    ::sc/internal-queue))

(>defn initialize!
  "Initializes the state machine and creates an initial working memory for a new machine env.
   Auto-assigns a unique UUID for session ID.

   This function processes the initial transition."
  [{:keys [machine vwmem] :as env}]
  [::sc/env => nil?]
  (let [{:keys [binding name initial script]} machine
        early? (= binding :early)
        t      (some->> machine (sm/initial-element machine) (sm/transition-element machine) (sm/element-id machine))
        wmem   (runtime-env
                 env
                 {::sc/session-id          #?(:clj (UUID/randomUUID) :cljs (random-uuid))
                  ::sc/configuration       #{}              ; currently active states
                  ::sc/initialized-states  #{}              ; states that have been entered (initialized data model) before
                  ::sc/enabled-transitions (if t #{t} #{})
                  ::sc/history-value       {}
                  ::sc/running?            true})]
    (vreset! vwmem wmem)
    (when early?
      (let [all-data-model-nodes (filter #(= :data-model (:node-type %)) (vals (::sc/elements-by-id machine)))]
        (doseq [n all-data-model-nodes]
          (initialize-data-model! env (:parent n)))))
    (enter-states! env)
    (before-event! env)
    (when script
      (execute! env script))))


(defn configuration-problems
  "Returns a list of problems with the current machine's working-memory configuration (active states), if there are
   any."
  [{::sc/keys [machine vwmem]}]
  (let [configuration          (::sc/configuration @vwmem)
        top-states             (set (sm/child-states machine machine))
        active-top-states      (set/intersection top-states configuration)
        atomic-states          (filter #(sm/atomic-state? machine %) configuration)
        necessary-ancestors    (into #{} (mapcat (fn [s] (sm/get-proper-ancestors machine s)) atomic-states))
        compound-states        (filter #(sm/compound-state? machine %) configuration)
        broken-compound-states (for [cs compound-states
                                     :let [cs-children (sm/child-states machine cs)]
                                     :when (not= 1 (count (set/intersection configuration cs-children)))]
                                 cs)
        active-parallel-states (filter #(sm/parallel-state? machine %) configuration)
        broken-parallel-states (for [cs active-parallel-states
                                     :let [cs-children (sm/child-states machine cs)]
                                     :when (not= cs-children (set/intersection configuration cs-children))]
                                 cs)]
    (cond-> []
      (not= 1 (count active-top-states)) (conj "The number of top-level active states != 1")
      (zero? (count atomic-states)) (conj "There are zero active atomic states")
      (not= (set/intersection configuration necessary-ancestors) necessary-ancestors) (conj (str "Some active states are missing their necessary ancestors"
                                                                                              necessary-ancestors " should all be in " configuration))
      (seq broken-compound-states) (conj (str "The compound states " broken-compound-states " should have exactly one child active (each) in " configuration))
      (seq broken-parallel-states) (conj (str "The parallel states " broken-parallel-states " should have all of their children in " configuration)))))
