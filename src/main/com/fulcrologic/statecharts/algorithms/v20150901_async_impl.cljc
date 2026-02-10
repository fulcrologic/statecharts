(ns com.fulcrologic.statecharts.algorithms.v20150901-async-impl
  "An async-capable implementation of the W3C SCXML Recommended Spec from 2015-09-01.

   This is a parallel implementation to v20150901-impl that supports expressions returning
   promesa promises. When an expression returns a plain value, processing continues synchronously.
   When a promise is returned, the algorithm parks until it resolves, then continues.

   The algorithm logic is identical to the sync variant — same transition selection, entry/exit
   order, conflict resolution, and history handling. Only the control flow is adapted for
   async support using promesa."
  #?(:cljs (:require-macros [com.fulcrologic.statecharts.algorithms.v20150901-async-impl
                             :refer [in-state-context]]))
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :as elements]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.events :as evts :refer [new-event]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.malli-specs]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.util :refer [genid new-uuid]]
    [promesa.core :as p]
    [taoensso.timbre :as log]))

;; =============================================================================
;; Macros — identical to sync impl
;; =============================================================================

#?(:clj
   (defmacro in-state-context
     "Change the `env` context to the given state element (you can supply id or element itself). Runs the `body` with
      the updated env, and returns the last expression of the body. `env` changes to context
      are not visible outside of this block."
     [env-sym state-or-id & body]
     `(let [m#  (get ~env-sym ::sc/statechart)
            id# (chart/element-id m# ~state-or-id)
            ~env-sym (assoc ~env-sym ::sc/context-element-id id#)]
        ~@body)))

(s/fdef in-state-context
  :ret any?
  :args (s/cat :s symbol? :sid any? :body (s/* any?)))

;; =============================================================================
;; Async utility: maybe-then
;; =============================================================================

(defn- maybe-then
  "If `v` is a promise, chains `f` on it. Otherwise calls `(f v)` directly.
   This avoids wrapping synchronous values in promises for the common case."
  [v f]
  (if (p/promise? v)
    (p/then v f)
    (f v)))

(defn- maybe-chain
  "Chains a sequence of possibly-async operations. Each `step-fn` receives the result
   of the previous step. If any step returns a promise, the chain becomes async from
   that point forward."
  [initial & step-fns]
  (reduce (fn [acc f]
            (maybe-then acc f))
    initial
    step-fns))

(defn- do-sequence
  "Execute `(f item)` for each item in `coll` sequentially. If any call returns a promise,
   subsequent calls wait for it to resolve. Returns the result of the last call (or nil for empty)."
  [coll f]
  (if (empty? coll)
    nil
    (loop [remaining coll
           last-result nil]
      (if (empty? remaining)
        last-result
        (let [result (f (first remaining))]
          (if (p/promise? result)
            ;; Switch to async mode for the rest
            (p/let [_ result]
              (let [rest-items (rest remaining)]
                (if (empty? rest-items)
                  nil
                  (do-sequence rest-items f))))
            (recur (rest remaining) result)))))))

;; =============================================================================
;; Core helper functions — adapted for async
;; =============================================================================

(defn !?
  "Returns `value` if not nil, or runs `expr` and returns that as the value. Returns nil if both are nil.
   The result may be a promise if the expression is async."
  [{::sc/keys [execution-model] :as env} value expr]
  (let [raw-env (assoc env ::sc/raw-result? true)]
    (cond
      (fn? value) (sp/run-expression! execution-model raw-env value)
      (not (nil? value)) value
      (not (nil? expr)) (sp/run-expression! execution-model raw-env expr)
      :else nil)))

(defn- named-data
  "Convert a element namelist arg into a map of data from the data model."
  [{::sc/keys [data-model] :as env} namelist]
  (if (map? namelist)
    (reduce-kv
      (fn [acc k location]
        (let [v (sp/get-at data-model env location)]
          (if (nil? v)
            acc
            (assoc acc k v))))
      {}
      namelist)
    {}))

(defn- run-expression!
  "Run an expression through the execution model. If the expression returns a promise that rejects,
   sends an error.execution event. The result may be a promise."
  [{::sc/keys [event-queue execution-model] :as env} expr]
  (let [result (try
                 (log/debug "Running expression" expr)
                 (sp/run-expression! execution-model env expr)
                 (catch #?(:clj Throwable :cljs :default) e
                   (log/error e "Expression failure (sync throw)")
                   (let [session-id (env/session-id env)]
                     (log/debug "Sending event" :error.execution "for exception" (ex-message e))
                     (sp/send! event-queue env {:event             :error.execution
                                                :send-id           session-id
                                                :data              {:error e}
                                                :source-session-id session-id}))
                   nil))]
    (if (p/promise? result)
      (p/catch result
        (fn [e]
          (log/error e "Expression failure (promise rejection)")
          (let [session-id (env/session-id env)]
            (log/debug "Sending event" :error.execution "for rejection" (ex-message e))
            (sp/send! event-queue env {:event             :error.execution
                                       :send-id           session-id
                                       :data              {:error e}
                                       :source-session-id session-id}))
          nil))
      (do
        (log/spy :debug result)
        result))))

(defn condition-match
  "Evaluate the condition on `element-or-id`. Returns a boolean (possibly wrapped in a promise
   if the condition expression is async)."
  [{::sc/keys [statechart] :as env} element-or-id]
  (let [{:keys [cond]} (chart/element statechart element-or-id)]
    (if (nil? cond)
      true
      (do
        (log/debug "evaluating condition" cond)
        (let [result (run-expression! (assoc env ::sc/raw-result? true) cond)]
          (maybe-then result (fn [v] (boolean v))))))))

(defn session-id
  "Returns the unique session id from an initialized `env`."
  [{::sc/keys [vwmem]}]
  (::sc/session-id @vwmem))

(defn in-final-state?
  "Returns true if `non-atomic-state` is completely done."
  [{::sc/keys [statechart vwmem] :as env} non-atomic-state]
  (boolean
    (cond
      (chart/compound-state? statechart non-atomic-state)
      (some
        (fn [s] (and (chart/final-state? statechart s) (contains? (::sc/configuration @vwmem) s)))
        (chart/child-states statechart non-atomic-state))

      (chart/parallel-state? statechart non-atomic-state)
      (every?
        (fn [s] (in-final-state? env s))
        (chart/child-states statechart non-atomic-state))

      :else false)))

(declare add-descendant-states-to-enter!)

(defn raise
  "Add an event to the internal (working memory) event queue."
  [{::sc/keys [vwmem]} event]
  (vswap! vwmem update ::sc/internal-queue conj (evts/new-event event))
  nil)

;; =============================================================================
;; State entry/exit computation — these are pure/sync, no expressions evaluated
;; =============================================================================

(defn add-ancestor-states-to-enter!
  [{::sc/keys [statechart] :as env} state ancestor
   states-to-enter states-for-default-entry default-history-content]
  (doseq [anc (chart/get-proper-ancestors statechart state ancestor)]
    (vswap! states-to-enter conj (chart/element-id statechart anc))
    (when (chart/parallel-state? statechart anc)
      (doseq [child (chart/child-states statechart anc)]
        (when-not (some (fn [s] (chart/descendant? statechart s child)) @states-to-enter)
          (add-descendant-states-to-enter! env child states-to-enter
            states-for-default-entry default-history-content)))))
  nil)

(defn add-descendant-states-to-enter!
  [{::sc/keys [statechart vwmem] :as env}
   state states-to-enter states-for-default-entry default-history-content]
  (letfn [(add-elements! [target parent]
            (doseq [s target]
              (add-descendant-states-to-enter! env s states-to-enter
                states-for-default-entry default-history-content))
            (doseq [s target]
              (add-ancestor-states-to-enter! env s parent states-to-enter
                states-for-default-entry default-history-content)))]
    (let [{:keys [id parent] :as state} (chart/element statechart state)]
      (if (chart/history-element? statechart state)
        (if-let [previously-active-states (get (::sc/history-value @vwmem) id)]
          (add-elements! previously-active-states parent)
          (let [{:keys [children target]} (chart/transition-element statechart state)]
            (when (seq children) (vswap! default-history-content assoc parent children))
            (add-elements! target parent)))
        (do
          (vswap! states-to-enter conj (chart/element-id statechart state))
          (if (chart/compound-state? statechart state)
            (let [target (->> (chart/initial-element statechart state) (chart/transition-element statechart) :target)]
              (vswap! states-for-default-entry conj (chart/element-id statechart state))
              (add-elements! target state))
            (when (chart/parallel-state? statechart state)
              (doseq [child (chart/child-states statechart state)]
                (when-not (some (fn [s] (chart/descendant? statechart s child)) @states-to-enter)
                  (add-descendant-states-to-enter! env child states-to-enter
                    states-for-default-entry default-history-content))))))))
    nil))

(defn get-effective-target-states
  [{::sc/keys [statechart vwmem] :as env} t]
  (let [{::sc/keys [history-value]} @vwmem]
    (reduce
      (fn [targets s]
        (log/spy :debug "target" s)
        (let [{:keys [id] :as s} (chart/element statechart s)]
          (log/spy :debug "target-element" id)
          (cond
            (and
              (chart/history-element? statechart s)
              (contains? history-value id))
            (set/union targets (get history-value id))

            (chart/history-element? statechart s)
            (let [default-transition (first (chart/transitions statechart s))]
              (set/union targets (get-effective-target-states env default-transition)))

            :else (conj targets id))))
      (chart/document-ordered-set statechart)
      (log/spy :debug "target(s)"
        (:target (chart/element statechart t))))))

(defn get-transition-domain
  [{::sc/keys [statechart] :as env} t]
  (let [tstates (log/spy :debug (get-effective-target-states env t))
        tsource (log/spy :debug (chart/nearest-ancestor-state statechart t))]
    (cond
      (empty? tstates) nil
      (and
        (= :internal (:type t))
        (chart/compound-state? statechart tsource)
        (every? (fn [s] (chart/descendant? statechart s tsource)) tstates)) tsource
      :else (log/spy :debug (:id (chart/find-least-common-compound-ancestor statechart (into (if tsource [tsource] []) tstates)))))))

(defn compute-entry-set!
  "Returns [states-to-enter states-for-default-entry default-history-content]."
  [{::sc/keys [vwmem statechart] :as env}]
  (let [states-to-enter          (volatile! #{})
        states-for-default-entry (volatile! #{})
        default-history-content  (volatile! {})
        transitions              (mapv (fn [t]
                                         (or (chart/element statechart t)
                                           (elements/transition {:target t})))
                                   (::sc/enabled-transitions @vwmem))]
    (doseq [{:keys [target] :as t} transitions]
      (let [ancestor (chart/element-id statechart (get-transition-domain env t))]
        (doseq [s target]
          (add-descendant-states-to-enter! env s states-to-enter
            states-for-default-entry default-history-content))
        (doseq [s (get-effective-target-states env t)]
          (add-ancestor-states-to-enter! env s ancestor states-to-enter
            states-for-default-entry default-history-content))))
    [@states-to-enter @states-for-default-entry @default-history-content]))

;; =============================================================================
;; Data model initialization — may be async
;; =============================================================================

(defn initialize-data-model!
  "Initialize the data models in volatile working memory for the given states, if necessary.
   May return a promise if expressions are async."
  [{::sc/keys [statechart data-model] :as env} state]
  (log/debug "Initializing data model for" state)
  (let [dm-eles (chart/get-children statechart state :data-model)
        {:keys [src expr]} (chart/element statechart (first dm-eles))]
    (when (> (count dm-eles) 1)
      (log/warn "Too many data elements on" state))
    (in-state-context env state
      (cond
        src (sp/load-data data-model env src)
        (fn? expr) (let [result (run-expression! env expr)]
                     (maybe-then result
                       (fn [ops]
                         (when (vector? ops)
                           (sp/update! data-model env {:ops (log/spy :debug ops)})))))
        (map? expr) (sp/update! data-model env {:ops (log/spy :debug (ops/set-map-ops expr))})
        :else (sp/update! data-model env {:ops (ops/set-map-ops {})})))
    nil))

;; =============================================================================
;; Executable content — multimethods, async-aware
;; =============================================================================

(declare execute!)

(defmulti execute-element-content!
  "Multimethod. Extensible mechanism for running the content of elements on the state machine.
   Dispatch by :node-type of the element itself. May return a promise."
  (fn [_env element] (:node-type element)))

(defmethod execute-element-content! :default [env {:keys [children expr] :as element}]
  (if (and (nil? expr) (empty? children))
    (log/warn "There was no custom implementation to run content of " element)
    (run-expression! env expr)))

(defmethod execute-element-content! :on-entry [env {:keys [id] :as element}]
  (log/debug "on-entry " id)
  (execute! env element))

(defmethod execute-element-content! :on-exit [env {:keys [id] :as element}]
  (log/debug "on-exit " id)
  (execute! env element))

(defmethod execute-element-content! :log [env {:keys [label expr level]}]
  (let [raw-env (assoc env ::sc/raw-result? true)
        result  (run-expression! raw-env expr)]
    (maybe-then result
      (fn [v]
        (case level
          :error (log/error (or label "LOG") v)
          :warn (log/warn (or label "LOG") v)
          :info (log/info (or label "LOG") v)
          (log/debug (or label "LOG") v))))))

(defmethod execute-element-content! :raise [env {:keys [event]}]
  (log/debug "Raise " event)
  (raise env event))

(defmethod execute-element-content! :assign [env {:keys [location expr]}]
  (let [result (run-expression! (assoc env ::sc/raw-result? true) expr)]
    (maybe-then result
      (fn [v]
        (log/debug "Assign" location " = " v)
        (env/assign! env location v)))))

(letfn [(send! [{::sc/keys [event-queue
                             data-model] :as env}
                {:keys [id
                        idlocation
                        delay
                        delayexpr
                        namelist
                        content
                        event
                        eventexpr
                        target
                        targetexpr
                        type
                        typeexpr] :as _send-element}]
          (let [event-name-v (!? env event eventexpr)
                id-v         (if idlocation (genid "send") id)
                target-v     (!? env target targetexpr)
                type-v       (!? env type typeexpr)
                delay-v      (!? env delay delayexpr)
                content-v    (!? env nil content)]
            ;; Any of these may be promises. Chain them together.
            (maybe-then event-name-v
              (fn [event-name]
                (maybe-then target-v
                  (fn [target]
                    (maybe-then type-v
                      (fn [type]
                        (maybe-then delay-v
                          (fn [delay]
                            (maybe-then content-v
                              (fn [content]
                                (let [target-is-parent? (= target (env/parent-session-id env))
                                      data              (log/spy :debug
                                                          "Computed send event data"
                                                          (merge
                                                            (named-data env namelist)
                                                            content))]
                                  (when idlocation (sp/update! data-model env {:ops [(ops/assign idlocation id-v)]}))
                                  (sp/send! event-queue env (cond-> {:send-id           id-v
                                                                      :source-session-id (env/session-id env)
                                                                      :event             event-name
                                                                      :data              data
                                                                      :target            target
                                                                      :type              (or type ::sc/chart)
                                                                      :delay             (or delay 0)}
                                                              target-is-parent? (assoc :invoke-id (env/invoke-id env)))))))))))))))))]
  (defmethod execute-element-content! :send [env element]
    (log/debug "Send event" element)
    (let [result (send! env element)]
      (maybe-then result
        (fn [sent?]
          (when-not sent?
            (raise env (evts/new-event {:name :error.execution
                                        :data {:type    :send
                                               :element element}}))))))))

(defmethod execute-element-content! :cancel [{::sc/keys [event-queue] :as env}
                                             {:keys [sendid sendidexpr] :as element}]
  (log/debug "Cancel event" element)
  (let [result (!? env sendid sendidexpr)]
    (maybe-then result
      (fn [id]
        (sp/cancel! event-queue env (env/session-id env) id)))))

(defmethod execute-element-content! :if [env {:keys [cond children] :as element}]
  (log/debug "Evaluating if" element)
  (let [{::sc/keys [statechart]} env
        ;; Split children into then-branch and else-branches
        [then-branch else-branches]
        (loop [remaining children
               then []
               elses []]
          (if (empty? remaining)
            [then elses]
            (let [child-id (first remaining)
                  {:keys [node-type]} (chart/element statechart child-id)]
              (if (#{:else-if :else} node-type)
                (recur (rest remaining) then (conj elses child-id))
                (recur (rest remaining) (conj then child-id) elses)))))
        cond-result (condition-match env element)]
    (maybe-then cond-result
      (fn [cond-match?]
        (if cond-match?
          ;; Execute then-branch sequentially
          (do-sequence then-branch
            (fn [child-id]
              (execute-element-content! env (chart/element statechart child-id))))
          ;; Check else-if/else branches sequentially
          ((fn check-branches [branches]
             (if (empty? branches)
               nil
               (let [branch-id (first branches)
                     branch-ele (chart/element statechart branch-id)
                     {:keys [node-type]} branch-ele]
                 (cond
                   (= node-type :else-if)
                   (let [branch-cond (condition-match env branch-ele)]
                     (maybe-then branch-cond
                       (fn [matches?]
                         (if matches?
                           (execute-element-content! env branch-ele)
                           (check-branches (rest branches))))))

                   (= node-type :else)
                   (execute-element-content! env branch-ele)

                   :else
                   (check-branches (rest branches))))))
           else-branches))))))

(defmethod execute-element-content! :else-if [env {:keys [children] :as element}]
  (log/debug "Executing else-if branch" element)
  (let [{::sc/keys [statechart]} env]
    (do-sequence children
      (fn [child-id]
        (execute-element-content! env (chart/element statechart child-id))))))

(defmethod execute-element-content! :else [env {:keys [children] :as element}]
  (log/debug "Executing else branch" element)
  (let [{::sc/keys [statechart]} env]
    (do-sequence children
      (fn [child-id]
        (execute-element-content! env (chart/element statechart child-id))))))

(defmethod execute-element-content! :for-each [{::sc/keys [data-model statechart] :as env}
                                                {:keys [array item index children] :as element}]
  (log/debug "Executing for-each" element)
  (let [coll-result (run-expression! (assoc env ::sc/raw-result? true) array)]
    (maybe-then coll-result
      (fn [coll]
        (do-sequence (map-indexed vector coll)
          (fn [[idx value]]
            (when item
              (sp/update! data-model env {:ops [(ops/assign item value)]}))
            (when index
              (sp/update! data-model env {:ops [(ops/assign index idx)]}))
            (do-sequence children
              (fn [child-id]
                (execute-element-content! env (chart/element statechart child-id))))))))))

(defn execute!
  "Run the executable content (immediate children) of `s`. May return a promise."
  [{::sc/keys [statechart] :as env} s]
  (log/debug "Execute content of" s)
  (let [{:keys [children]} (chart/element statechart s)]
    (do-sequence children
      (fn [n]
        (try
          (let [ele (chart/element statechart n)]
            (execute-element-content! env ele))
          (catch #?(:clj Throwable :cljs :default) t
            ;; TODO: Proper error event for execution problem
            (log/error t "Unexpected exception in content")
            nil))))))

(defn compute-done-data!
  [{::sc/keys [statechart] :as env} final-state]
  (let [done-element (some->> (chart/get-children statechart final-state :done-data)
                       first
                       (chart/element statechart))]
    (if done-element
      (let [result (execute-element-content! env done-element)]
        (maybe-then result (fn [v] (log/spy :debug "computed done data" v))))
      {})))

;; =============================================================================
;; State entry — async-aware
;; =============================================================================

(defn enter-states!
  "Enters states, triggers actions, tracks long-running invocations. May return a promise."
  [{::sc/keys [statechart vwmem] :as env}]
  (let [[states-to-enter
         states-for-default-entry
         default-history-content] (log/spy :debug "entry set"
                                    (compute-entry-set! env))]
    (do-sequence (chart/in-entry-order statechart states-to-enter)
      (fn [s]
        (let [state-id (chart/element-id statechart s)]
          (log/debug "Enter" state-id)
          (in-state-context env s
            (vswap! vwmem update ::sc/configuration conj s)
            (vswap! vwmem update ::sc/states-to-invoke conj s)
            (let [late-init (when (and (= :late (:binding statechart))
                                    (not (contains? (::sc/initialized-states @vwmem) state-id)))
                              (let [r (initialize-data-model! env s)]
                                (vswap! vwmem update ::sc/initialized-states (fnil conj #{}) state-id)
                                r))]
              (maybe-then late-init
                (fn [_]
                  ;; Run entry handlers sequentially
                  (let [entry-result (do-sequence (chart/entry-handlers statechart s)
                                      (fn [entry] (execute! env entry)))]
                    (maybe-then entry-result
                      (fn [_]
                        ;; Default entry transition content
                        (let [default-entry-result
                              (when-let [t (and (contains? states-for-default-entry s)
                                             (some->> s (chart/initial-element statechart) (chart/transition-element statechart)))]
                                (execute! env t))]
                          (maybe-then default-entry-result
                            (fn [_]
                              ;; History content
                              (let [history-result
                                    (when-let [content (get default-history-content (chart/element-id statechart s))]
                                      (execute-element-content! env (chart/element statechart content)))]
                                (maybe-then history-result
                                  (fn [_]
                                    ;; Final state handling (no async needed — pure data operations)
                                    (when (log/spy :debug (chart/final-state? statechart s))
                                      (if (= :ROOT (chart/get-parent statechart s))
                                        (vswap! vwmem assoc ::sc/running? false)
                                        (let [parent      (chart/get-parent statechart s)
                                              grandparent (chart/get-parent statechart parent)
                                              done-data-result (compute-done-data! env s)]
                                          (maybe-then done-data-result
                                            (fn [done-data]
                                              (vswap! vwmem update ::sc/internal-queue conj
                                                (new-event {:sendid (chart/element-id statechart s)
                                                            :type   :internal
                                                            :data   done-data
                                                            :name   (keyword (str "done.state." (name (chart/element-id statechart parent))))}))
                                              (when (and (chart/parallel-state? statechart grandparent)
                                                      (every? (fn [s] (in-final-state? env s)) (chart/child-states statechart grandparent)))
                                                (vswap! vwmem update ::sc/internal-queue conj
                                                  (new-event {:sendid (chart/element-id statechart s)
                                                              :type   :internal
                                                              :data   done-data
                                                              :name   (keyword (str "done.state." (name (chart/element-id statechart grandparent))))})))))))))))))))))))))))))
  (log/spy :debug "after enter states: " (::sc/configuration @vwmem))
  nil)

;; =============================================================================
;; Transition content execution — async-aware
;; =============================================================================

(defn execute-transition-content!
  "Execute the content of enabled transitions. May return a promise."
  [{::sc/keys [vwmem] :as env}]
  (do-sequence (vec (::sc/enabled-transitions @vwmem))
    (fn [t] (execute! env t))))

;; =============================================================================
;; Exit set computation — sync (no expressions)
;; =============================================================================

(defn compute-exit-set
  [{::sc/keys [statechart vwmem] :as env} transitions]
  (let [states-to-exit (volatile! (chart/document-ordered-set statechart))]
    (doseq [t (mapv #(chart/element statechart %) transitions)]
      (when (contains? t :target)
        (let [domain (log/spy :debug (get-transition-domain env t))]
          (doseq [s (::sc/configuration @vwmem)]
            (when (chart/descendant? statechart s domain)
              (vswap! states-to-exit conj s))))))
    @states-to-exit))

;; =============================================================================
;; Transition conflict resolution — sync (no expressions)
;; =============================================================================

(defn remove-conflicting-transitions
  "Updates working-mem so that enabled-transitions no longer includes any conflicting ones."
  [{::sc/keys [statechart] :as env} enabled-transitions]
  (log/spy :debug "conflicting?" enabled-transitions)
  (let [filtered-transitions (volatile! (chart/document-ordered-set statechart))]
    (doseq [t1 enabled-transitions
            :let [to-remove  (volatile! (chart/document-ordered-set statechart))
                  preempted? (volatile! false)]]
      (doseq [t2 @filtered-transitions
              :while (not @preempted?)]
        (when (seq (set/intersection
                     (log/spy :debug (compute-exit-set env [t1]))
                     (log/spy :debug (compute-exit-set env [t2]))))
          (if (chart/descendant? statechart (chart/source statechart t1) (chart/source statechart t2))
            (vswap! to-remove conj t2)
            (vreset! preempted? true))))
      (when (not @preempted?)
        (doseq [t3 @to-remove]
          (vswap! filtered-transitions disj t3))
        (vswap! filtered-transitions conj t1)))
    @filtered-transitions))

;; =============================================================================
;; Transition selection — async because conditions may be async
;; =============================================================================

(defn- find-first-matching-transition
  "Find the first transition in `transitions` that matches `predicate`.
   Returns the transition's element-id or nil. Handles async predicates."
  [machine transitions predicate]
  (if (empty? transitions)
    nil
    (let [t     (first transitions)
          match (predicate t)]
      (maybe-then match
        (fn [matches?]
          (if matches?
            (chart/element-id machine t)
            (find-first-matching-transition machine (rest transitions) predicate)))))))

(defn- find-enabled-transition-for-state
  "Find the first enabled transition for `state` by checking the state and its ancestors.
   Returns the matching transition ID or nil. May return a promise."
  [machine state predicate]
  (let [ancestors (into [state] (chart/get-proper-ancestors machine state))]
    (if (empty? ancestors)
      nil
      ((fn check-ancestor [ancs]
         (if (empty? ancs)
           nil
           (let [s           (first ancs)
                 transitions (mapv #(chart/element machine %)
                               (chart/in-document-order machine (chart/transitions machine s)))
                 result      (find-first-matching-transition machine transitions predicate)]
             (maybe-then result
               (fn [tid]
                 (if tid
                   tid
                   (check-ancestor (rest ancs))))))))
       ancestors))))

(defn select-transitions*
  "Select enabled transitions for the given `configuration` using `predicate`.
   The predicate may return a promise (for async condition evaluation).
   Returns the set of enabled transitions (possibly wrapped in a promise)."
  [machine configuration predicate]
  (let [enabled-transitions (volatile! (chart/document-ordered-set machine))
        atomic-states       (chart/in-document-order machine (filterv #(chart/atomic-state? machine %) configuration))]
    (if (empty? atomic-states)
      @enabled-transitions
      ((fn process-states [states]
         (if (empty? states)
           @enabled-transitions
           (let [state  (first states)
                 result (find-enabled-transition-for-state machine state predicate)]
             (maybe-then result
               (fn [tid]
                 (when tid
                   (vswap! enabled-transitions conj tid))
                 (process-states (rest states)))))))
       (seq atomic-states)))))

(defn select-eventless-transitions!
  "Populate ::sc/enabled-transitions in working memory with eventless transitions.
   May return a promise if condition evaluation is async."
  [{::sc/keys [statechart vwmem] :as env}]
  (let [raw-transitions (select-transitions* statechart (::sc/configuration @vwmem)
                          (fn [t]
                            (if (:event t)
                              false
                              ;; condition-match may return a promise, which is fine —
                              ;; find-first-matching-transition handles it via maybe-then
                              (condition-match env t))))]
    (maybe-then raw-transitions
      (fn [tns]
        (let [filtered (remove-conflicting-transitions env tns)]
          (vswap! vwmem assoc ::sc/enabled-transitions filtered))))))

(defn select-transitions!
  "Populate ::sc/enabled-transitions in working memory for the given `event`.
   May return a promise if condition evaluation is async."
  [{::sc/keys [statechart vwmem] :as env} event]
  (let [raw-transitions (select-transitions* statechart (::sc/configuration @vwmem)
                          (fn [t]
                            (if (and (contains? t :event)
                                  (evts/name-match? (:event t) event))
                              ;; condition-match may return a promise — handled by maybe-then
                              (condition-match env t)
                              false)))]
    (maybe-then raw-transitions
      (fn [tns]
        (let [filtered (log/spy :debug "enabled transitions"
                         (remove-conflicting-transitions env tns))]
          (vswap! vwmem assoc ::sc/enabled-transitions filtered))))))

;; =============================================================================
;; Invocations — mostly sync, param evaluation may be async
;; =============================================================================

(defn- invocation-details
  [{::sc/keys [statechart
               invocation-processors] :as env} invocation]
  (let [{:keys [type typeexpr src srcexpr] :as invocation} (chart/element statechart invocation)
        type-v (!? env type typeexpr)
        src-v  (or src (!? env nil srcexpr))]
    ;; type and src may be promises
    (maybe-then type-v
      (fn [type]
        (let [type (or type :statechart)]
          (maybe-then src-v
            (fn [src]
              (assoc invocation
                :type type
                :src src
                :processor (first (filterv #(sp/supports-invocation-type? % type) invocation-processors))))))))))

(defn- start-invocation!
  [{::sc/keys [statechart
               data-model
               execution-model
               event-queue] :as env} invocation]
  (let [details (invocation-details env invocation)]
    (maybe-then details
      (fn [{:keys [type src
                   id idlocation
                   explicit-id?
                   namelist params
                   processor] :as invocation}]
        (let [parent-state-id (chart/nearest-ancestor-state statechart invocation)]
          (if processor
            (let [param-result (if (map? params)
                                 (let [entries (vec params)
                                       results (volatile! {})]
                                   (let [seq-result (do-sequence entries
                                                      (fn [[k expr]]
                                                        (let [v (sp/run-expression! execution-model env expr)]
                                                          (maybe-then v (fn [resolved] (vswap! results assoc k resolved))))))]
                                     (maybe-then seq-result (fn [_] @results))))
                                 (sp/run-expression! execution-model env params))]
              (maybe-then param-result
                (fn [param-map]
                  (let [invokeid (if explicit-id?
                                   id
                                   (str parent-state-id "." (new-uuid)))
                        params   (merge
                                   (named-data env namelist)
                                   param-map)]
                    (when idlocation
                      (sp/update! data-model env {:ops [(ops/assign idlocation invokeid)]}))
                    (log/debugf "Starting invocation id = %s, type = %s, src = %s, params = %s"
                      (str invokeid) (str type) (str src) (str params))
                    (sp/start-invocation! processor env {:invokeid invokeid
                                                         :src      src
                                                         :type     type
                                                         :params   params})))))
            (do
              (log/error "Cannot start invocation. No processor for " invocation)
              (sp/send! event-queue env {:event             :error.execution
                                         :data              {:invocation-type type
                                                             :reason          "Not found"}
                                         :send-id           :invocation-failure
                                         :source-session-id (session-id env)
                                         :target            (session-id env)}))))))))

(defn run-invocations!
  "Start any pending invocations. May return a promise."
  [{::sc/keys [statechart vwmem] :as env}]
  (let [{::sc/keys [states-to-invoke]} @vwmem
        result (do-sequence (vec (chart/in-entry-order statechart states-to-invoke))
                 (fn [state-to-invoke]
                   (in-state-context env state-to-invoke
                     (do-sequence (vec (chart/in-document-order statechart (chart/invocations statechart state-to-invoke)))
                       (fn [i] (start-invocation! env i))))))]
    (maybe-then result
      (fn [_]
        (vswap! vwmem assoc ::sc/states-to-invoke (chart/document-ordered-set statechart))
        nil))))

(defn run-many!
  "Run the code associated with the given nodes. Does NOT set context id of the nodes run."
  [{::sc/keys [statechart] :as env} nodes]
  (do-sequence (vec nodes)
    (fn [n]
      (try
        (execute-element-content! env (chart/element statechart n))
        (catch #?(:clj Throwable :cljs :default) e
          (raise env (evts/new-event {:name :error.execution
                                      :data {:node      n
                                             :exception e}}))
          (log/error e "Unexpected execution error")
          nil)))))

;; =============================================================================
;; Invocation stop & forwarding — sync (no expressions involved)
;; =============================================================================

(letfn [(stop-invocation!
          [{::sc/keys [data-model] :as env} invocation]
          (let [details (invocation-details env invocation)]
            (maybe-then details
              (fn [{:keys [type processor id idlocation]}]
                (when processor
                  (let [invokeid (if idlocation
                                   (sp/get-at data-model env idlocation)
                                   id)]
                    (log/debug "Stopping invocation" invokeid)
                    (sp/stop-invocation! processor env {:invokeid invokeid
                                                        :type     type})))))))]
  (defn cancel-active-invocations!
    [{::sc/keys [statechart] :as env} state]
    (log/spy :debug "Stopping invocations for " state)
    (do-sequence (vec (chart/invocations statechart state))
      (fn [i] (stop-invocation! env i)))))

;; =============================================================================
;; Exit states — async-aware (exit handlers may be async)
;; =============================================================================

(defn exit-states!
  "Does all of the processing for exiting states. May return a promise."
  [{::sc/keys [statechart vwmem] :as env}]
  (let [{::sc/keys [enabled-transitions
                    states-to-invoke
                    configuration]} @vwmem
        states-to-exit   (chart/in-exit-order statechart (compute-exit-set env enabled-transitions))
        states-to-invoke (set/difference states-to-invoke (set states-to-exit))]
    (vswap! vwmem assoc ::sc/states-to-invoke states-to-invoke)
    ;; Record history (sync — no expressions)
    (doseq [s states-to-exit]
      (doseq [{:keys [id type] :as h} (chart/history-elements statechart s)]
        (let [f (if (= :deep type)
                  (fn [s0] (and
                             (chart/atomic-state? statechart s0)
                             (chart/descendant? statechart s0 s)))
                  (fn [s0]
                    (= s (chart/element-id statechart (chart/get-parent statechart s0)))))]
          (vswap! vwmem assoc-in [::sc/history-value id] (into #{} (filter f configuration))))))
    ;; Exit states (may be async — exit handlers)
    (do-sequence (vec states-to-exit)
      (fn [s]
        (log/debug "Leaving state " s)
        (in-state-context env s
          (let [to-exit (chart/exit-handlers statechart s)
                result  (run-many! env to-exit)]
            (maybe-then result
              (fn [_]
                (let [cancel-result (cancel-active-invocations! env s)]
                  (maybe-then cancel-result
                    (fn [_]
                      (vswap! vwmem update ::sc/configuration disj s))))))))))))

;; =============================================================================
;; Microstep — async chain of exit → transition content → enter
;; =============================================================================

(defn microstep!
  "Perform a single microstep. May return a promise."
  [env]
  (let [exit-result (exit-states! env)]
    (maybe-then exit-result
      (fn [_]
        (let [trans-result (execute-transition-content! env)]
          (maybe-then trans-result
            (fn [_]
              (enter-states! env))))))))

;; =============================================================================
;; Exit handler helpers
;; =============================================================================

(defn run-exit-handlers!
  "Run the exit handlers of `state`. May return a promise."
  [{::sc/keys [statechart] :as env} state]
  (in-state-context env state
    (let [nodes (chart/in-document-order statechart (chart/exit-handlers statechart state))]
      (run-many! env nodes))))

(defn send-done-event!
  [env state]
  (in-state-context env state
    (let [{::sc/keys [vwmem event-queue]} env
          {:org.w3.scxml.event/keys [invokeid]
           ::sc/keys                [parent-session-id]} @vwmem]
      (when (and invokeid parent-session-id)
        (let [session-id  (env/session-id env)
              done-result (compute-done-data! env state)]
          (maybe-then done-result
            (fn [done-data]
              (log/debug "Sending done event from" session-id "to" parent-session-id "for" invokeid)
              (sp/send! event-queue env {:target            parent-session-id
                                         :sendid            session-id
                                         :source-session-id session-id
                                         :invoke-id         invokeid
                                         :data              done-data
                                         :event             (evts/invoke-done-event invokeid)}))))))))

;; =============================================================================
;; Eventless transitions — the critical loop, async-aware
;; =============================================================================

(defn handle-eventless-transitions!
  "Work through eventless transitions. May return a promise."
  [{::sc/keys [vwmem] :as env}]
  ((fn step []
     (if-not (::sc/running? @vwmem)
       nil
       (let [sel-result (select-eventless-transitions! env)]
         (maybe-then sel-result
           (fn [_]
             (let [{::sc/keys [enabled-transitions
                               internal-queue]} @vwmem]
               (if (empty? enabled-transitions)
                 (if (empty? internal-queue)
                   nil ;; macrostep done
                   (let [internal-event (first internal-queue)]
                     (log/spy :debug internal-event)
                     (vswap! vwmem update ::sc/internal-queue pop)
                     (env/assign! env [:ROOT :_event] internal-event)
                     (let [sel2 (select-transitions! env internal-event)]
                       (maybe-then sel2
                         (fn [_]
                           (if (seq (::sc/enabled-transitions @vwmem))
                             (let [ms (microstep! env)]
                               (maybe-then ms (fn [_] (step))))
                             (step)))))))
                 (let [ms (microstep! env)]
                   (maybe-then ms (fn [_] (step)))))))))))))

;; =============================================================================
;; Finalization & external invocation handling
;; =============================================================================

(defn finalize!
  "Run the finalize executable content for an event from an external invocation.
   May return a promise."
  [{::sc/keys [statechart] :as env} invocation event]
  (let [parent (or
                 (chart/nearest-ancestor-state statechart invocation)
                 statechart)]
    (in-state-context env parent
      (env/assign! env [:ROOT :_event] event)
      (let [result (when-let [finalize (log/spy :debug "finalizers" (chart/get-children statechart invocation :finalize))]
                     (do-sequence (vec finalize)
                       (fn [f]
                         (execute! (assoc env :_event event) f))))]
        (maybe-then result
          (fn [_]
            (env/delete! env [:ROOT :_event])))))))

(letfn [(forward-event!
          [{::sc/keys [data-model] :as env} invocation event]
          (let [details (invocation-details env invocation)]
            (maybe-then details
              (fn [{:keys [type processor id idlocation]}]
                (when processor
                  (let [invokeid (if idlocation (sp/get-at data-model env idlocation) id)]
                    (sp/forward-event! processor env {:invokeid invokeid
                                                      :type     type
                                                      :event    event})))))))]

  (defn handle-external-invocations!
    [{::sc/keys [statechart vwmem data-model] :as env}
     {:keys [invokeid] :as external-event}]
    (do-sequence (vec (::sc/configuration @vwmem))
      (fn [s]
        (do-sequence (vec (mapv (partial chart/element statechart) (chart/invocations statechart s)))
          (fn [{:keys [id idlocation id-location auto-forward? autoforward] :as inv}]
            (let [id (if-let [loc (or idlocation id-location)]
                       (sp/get-at data-model env loc)
                       id)]
              (let [finalize-result (when (log/spy :debug "event from invocation?" (= invokeid id))
                                     (finalize! env inv external-event))
                    forward-result (when (or (true? autoforward) (true? auto-forward?))
                                    (forward-event! env inv external-event))]
                (maybe-then finalize-result
                  (fn [_] forward-result))))))))))

;; =============================================================================
;; Exit interpreter
;; =============================================================================

(defn exit-interpreter!
  "Exit the interpreter, running exit handlers for all active states.
   May return a promise."
  ([{::sc/keys [statechart vwmem] :as env} skip-done-event?]
   (let [states-to-exit (log/spy :debug (chart/in-exit-order statechart (::sc/configuration @vwmem)))]
     (do-sequence (vec states-to-exit)
       (fn [state]
         (let [exit-result (run-exit-handlers! env state)]
           (maybe-then exit-result
             (fn [_]
               (let [cancel-result (cancel-active-invocations! env state)]
                 (maybe-then cancel-result
                   (fn [_]
                     (vswap! vwmem update ::sc/configuration disj state)
                     (when (and (not skip-done-event?) (chart/final-state? statechart state) (= :ROOT (chart/get-parent statechart state)))
                       (send-done-event! env state))))))))))))
  ([{::sc/keys [statechart vwmem] :as env}]
   (exit-interpreter! env false)))

;; =============================================================================
;; Before-event loop — async-aware
;; =============================================================================

(defn before-event!
  "Steps that are run before processing the next event. May return a promise."
  [{::sc/keys [statechart vwmem] :as env}]
  (let [{::sc/keys [running?]} @vwmem]
    (if running?
      ((fn step []
         (vswap! vwmem assoc ::sc/enabled-transitions (chart/document-ordered-set statechart) ::sc/macrostep-done? false)
         (let [het-result (handle-eventless-transitions! env)]
           (maybe-then het-result
             (fn [_]
               (if (-> vwmem deref ::sc/running?)
                 (let [inv-result (run-invocations! env)]
                   (maybe-then inv-result
                     (fn [_]
                       (if (seq (::sc/internal-queue @vwmem))
                         (step)
                         nil))))
                 (exit-interpreter! env)))))))
      (exit-interpreter! env))))

;; =============================================================================
;; Cancel detection
;; =============================================================================

(defn cancel? [event] (= :com.fulcrologic.statecharts.events/cancel (:name event)))

;; =============================================================================
;; Processing env setup — identical to sync
;; =============================================================================

(defn processing-env
  "Set up `env` to track live data that is needed during the algorithm."
  [{::sc/keys [statechart-registry] :as env} statechart-src {::sc/keys                [session-id
                                                                                        parent-session-id]
                                                             :org.w3.scxml.event/keys [invokeid] :as wmem}]
  (if-let [statechart (sp/get-statechart statechart-registry statechart-src)]
    (do
      (log/spy :debug "Processing event on statechart" statechart-src)
      (assoc env
        ::sc/context-element-id :ROOT
        ::sc/statechart statechart
        ::sc/vwmem (volatile! (merge
                                {::sc/session-id         (or session-id (genid "session"))
                                 ::sc/statechart-src     statechart-src
                                 ::sc/configuration      #{}
                                 ::sc/initialized-states #{}
                                 ::sc/history-value      {}
                                 ::sc/running?           true}
                                wmem))))
    (throw (ex-info "Statechart not found" {:src statechart-src}))))

;; =============================================================================
;; Main entry points — async-aware
;; =============================================================================

(defn process-event!
  "Process the given `external-event`. Returns working memory, possibly wrapped in a promise
   if any expressions during processing were async."
  [env external-event]
  (log/spy :debug external-event)
  (let [event (new-event external-event)
        vwmem (get env ::sc/vwmem)
        statechart (::sc/statechart env)]
    ;; Set up processing context
    (vswap! vwmem (fn [m] (merge
                            {::sc/enabled-transitions (chart/document-ordered-set statechart)
                             ::sc/states-to-invoke    (chart/document-ordered-set statechart)
                             ::sc/internal-queue      (com.fulcrologic.statecharts.util/queue)}
                            m)))
    (let [body-result (if (cancel? event)
                        (exit-interpreter! env)
                        (do
                          (env/assign! env [:ROOT :_event] event)
                          (let [sel (select-transitions! env event)]
                            (maybe-then sel
                              (fn [_]
                                (let [inv (handle-external-invocations! env event)]
                                  (maybe-then inv
                                    (fn [_]
                                      (let [ms (microstep! env)]
                                        (maybe-then ms
                                          (fn [_]
                                            (before-event! env))))))))))))]
      (maybe-then body-result
        (fn [_]
          ;; Cleanup processing context
          (vswap! vwmem dissoc ::sc/enabled-transitions ::sc/states-to-invoke ::sc/internal-queue)
          (env/assign! env [:ROOT :_event] nil)
          @vwmem)))))

(defn initialize!
  "Initializes the state machine and creates initial working memory.
   Returns working memory, possibly wrapped in a promise."
  [{::sc/keys [statechart data-model parent-session-id vwmem] :as env}
   {::sc/keys                [invocation-data statechart-src]
    :org.w3.scxml.event/keys [invokeid]}]
  (let [{:keys [binding script]} statechart
        early? (not= binding :late)
        t      (some->> statechart
                 (chart/initial-element statechart)
                 (chart/transition-element statechart)
                 (chart/element-id statechart))]
    (vswap! vwmem (fn [wm]
                    (cond-> (assoc wm
                              ::sc/statechart-src statechart-src
                              ::sc/enabled-transitions (if t
                                                         (chart/document-ordered-set statechart t)
                                                         (chart/document-ordered-set statechart)))
                      parent-session-id (assoc ::sc/parent-session-id parent-session-id)
                      invokeid (assoc :org.w3.scxml.event/invokeid invokeid))))
    ;; Set up processing context
    (vswap! vwmem (fn [m] (merge
                            {::sc/enabled-transitions (chart/document-ordered-set statechart)
                             ::sc/states-to-invoke    (chart/document-ordered-set statechart)
                             ::sc/internal-queue      (com.fulcrologic.statecharts.util/queue)}
                            m)))
    (let [init-result
          (let [early-init (when early?
                             (let [all-data-model-nodes (filterv #(= :data-model (:node-type %)) (vals (::sc/elements-by-id statechart)))]
                               (do-sequence all-data-model-nodes
                                 (fn [n]
                                   (in-state-context env n
                                     (initialize-data-model! env (chart/get-parent statechart n)))))))]
            (maybe-then early-init
              (fn [_]
                (when (map? invocation-data)
                  (sp/update! data-model env {:ops (ops/set-map-ops invocation-data)}))
                (let [enter-result (enter-states! env)]
                  (maybe-then enter-result
                    (fn [_]
                      (let [before-result (before-event! env)]
                        (maybe-then before-result
                          (fn [_]
                            (when script
                              (execute! env script)))))))))))]
      (maybe-then init-result
        (fn [_]
          ;; Cleanup processing context
          (vswap! vwmem dissoc ::sc/enabled-transitions ::sc/states-to-invoke ::sc/internal-queue)
          @vwmem)))))

;; =============================================================================
;; Configuration diagnostics — identical to sync
;; =============================================================================

(defn configuration-problems
  "Returns a list of problems with the current machine's working-memory configuration (active states), if there are
   any."
  [machine working-memory]
  (let [configuration          (::sc/configuration working-memory)
        top-states             (set (chart/child-states machine machine))
        active-top-states      (set/intersection top-states configuration)
        atomic-states          (filter #(chart/atomic-state? machine %) configuration)
        necessary-ancestors    (into #{} (mapcat (fn [s] (chart/get-proper-ancestors machine s)) atomic-states))
        compound-states        (filter #(chart/compound-state? machine %) configuration)
        broken-compound-states (set
                                 (for [cs compound-states
                                       :let [cs-children (set (chart/child-states machine cs))]
                                       :when (not= 1 (count (set/intersection configuration cs-children)))]
                                   cs))
        active-parallel-states (set (filter #(chart/parallel-state? machine %) configuration))
        broken-parallel-states (for [cs active-parallel-states
                                     :let [cs-children (set (chart/child-states machine cs))]
                                     :when (not= cs-children (set/intersection configuration cs-children))]
                                 cs)]
    (cond-> []
      (not= 1 (count active-top-states)) (conj "The number of top-level active states != 1")
      (zero? (count atomic-states)) (conj "There are zero active atomic states")
      (not= (set/intersection configuration necessary-ancestors) necessary-ancestors) (conj (str "Some active states are missing their necessary ancestors "
                                                                                              necessary-ancestors " should all be in " configuration))
      (seq broken-compound-states) (conj (str "The compound states " broken-compound-states " should have exactly one child active (each) in " configuration))
      (seq broken-parallel-states) (conj (str "The parallel states " broken-parallel-states " should have all of their children in " configuration)))))
