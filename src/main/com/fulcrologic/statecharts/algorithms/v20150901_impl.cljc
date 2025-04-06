(ns com.fulcrologic.statecharts.algorithms.v20150901-impl
  "An implementation of the W3C SCXML Recommended Spec from 2015-09-01.

   Uses an imperative style (internally) to match the pseudocode in the standard for easier translation,
   verification, and avoidance of subtle differences in implementation."
  #?(:cljs (:require-macros [com.fulcrologic.statecharts.algorithms.v20150901-impl
                             :refer [in-state-context
                                     with-processing-context]]))
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.malli.core :refer [>defn >defn- => ?]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :as elements]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.events :as evts :refer [new-event]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.malli-specs]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.util :refer [genid new-uuid]]
    [taoensso.timbre :as log]))

#?(:clj
   (defmacro with-processing-context [env & body]
     `(let [vwmem# (get ~env ::sc/vwmem)]
        (vswap! vwmem# (fn [m#] (merge
                                  {::sc/enabled-transitions (chart/document-ordered-set (::sc/statechart ~env))
                                   ::sc/states-to-invoke    (chart/document-ordered-set (::sc/statechart ~env))
                                   ;; This belongs elsewhere, I think
                                   ::sc/internal-queue      (com.fulcrologic.statecharts.util/queue)}
                                  m#)))
        (do ~@body)
        (vswap! vwmem# dissoc ::sc/enabled-transitions ::sc/states-to-invoke ::sc/internal-queue))))

#?(:clj
   (s/fdef with-processing-context
     :args (s/cat :env symbol? :body (s/* any?))))

#?(:clj
   (defmacro in-state-context
     "Change the `env` context to the given state element (you can supply id or element itself). Runs the `body` with
      the update env, and returns the last expression of the body. `env` changes to context
      are not visible outside of this block."
     [env-sym state-or-id & body]
     `(let [m#  (get ~env-sym ::sc/statechart)
            id# (chart/element-id m# ~state-or-id)
            ~env-sym (assoc ~env-sym ::sc/context-element-id id#)]
        ~@body)))

(s/fdef in-state-context
  :ret any?
  :args (s/cat :s symbol? :sid any? :body (s/* any?)))

(defn !?
  "Returns `value` if not nil, or runs `expr` and returns that as the value. Returns nil if both are nil."
  [{::sc/keys [execution-model] :as env} value expr]
  (cond
    (fn? value) (sp/run-expression! execution-model env value)
    (not (nil? value)) value
    (not (nil? expr)) (sp/run-expression! execution-model env expr)
    :else nil))

(>defn- named-data
  "Convert a element namelist arg into a map of data from the data model."
  [{::sc/keys [data-model] :as env} namelist]
  [[:map ::sc/data-model] (? map?) => map?]
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

(defn- run-expression! [{::sc/keys [event-queue execution-model] :as env} expr]
  (try
    (log/debug "Running expression" expr)
    (log/spy :debug
      (sp/run-expression! execution-model env expr))
    (catch #?(:clj Throwable :cljs :default) e
      (log/error e "Expression failure")
      (let [session-id (env/session-id env)]
        (log/debug "Sending event" :error.execution "for exception" (ex-message e))
        (sp/send! event-queue env {:event             :error.execution
                                   :send-id           session-id
                                   :data              {:error e}
                                   :source-session-id session-id})
        ; Expression failed to run
        nil))))

(>defn condition-match
  [{::sc/keys [statechart] :as env} element-or-id]
  [::sc/processing-env ::sc/element-or-id => boolean?]
  (let [{:keys [cond]} (chart/element statechart element-or-id)]
    (if (nil? cond)
      true
      (do
        (log/debug "evaluating condition" cond)
        (log/spy :debug
          (boolean (run-expression! env cond)))))))

(>defn session-id
  "Returns the unique session id from an initialized `env`."
  [{::sc/keys [vwmem]}]
  [::sc/processing-env => ::sc/session-id]
  (::sc/session-id @vwmem))

(>defn in-final-state?
  "Returns true if `non-atomic-state` is completely done."
  [{::sc/keys [statechart vwmem] :as env} non-atomic-state]
  [::sc/processing-env (? ::sc/element-or-id) => boolean?]
  (boolean
    (cond
      (chart/compound-state? statechart non-atomic-state) (some
                                                            (fn [s] (and (chart/final-state? statechart s) (contains? (::sc/configuration @vwmem) s)))
                                                            (chart/child-states statechart non-atomic-state))
      (chart/parallel-state? statechart non-atomic-state) (every?
                                                            (fn [s] (in-final-state? env s))
                                                            (chart/child-states statechart non-atomic-state))
      :else false)))

(declare add-descendant-states-to-enter!)

(>defn raise
  "Add an event to the internal (working memory) event queue."
  [{::sc/keys [vwmem]} event]
  [::sc/processing-env ::sc/event-or-name => nil?]
  (vswap! vwmem update ::sc/internal-queue conj (evts/new-event event))
  nil)

(>defn add-ancestor-states-to-enter! [{::sc/keys [statechart] :as env} state ancestor
                                      states-to-enter states-for-default-entry default-history-content]
  [::sc/processing-env ::sc/element-or-id ::sc/element-or-id [:fn volatile?] [:fn volatile?] [:fn volatile?] => nil?]
  (doseq [anc (chart/get-proper-ancestors statechart state ancestor)]
    (vswap! states-to-enter conj (chart/element-id statechart anc))
    (when (chart/parallel-state? statechart anc)
      (doseq [child (chart/child-states statechart anc)]
        (when-not (some (fn [s] (chart/descendant? statechart s child)) @states-to-enter)
          (add-descendant-states-to-enter! env child states-to-enter
            states-for-default-entry default-history-content)))))
  nil)

(>defn add-descendant-states-to-enter! [{::sc/keys [statechart vwmem] :as env}
                                        state states-to-enter states-for-default-entry default-history-content]
  [::sc/processing-env ::sc/element-or-id [:fn volatile?] [:fn volatile?] [:fn volatile?] => nil?]
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
        ; not a history element
        (do
          (vswap! states-to-enter conj (chart/element-id statechart state))
          (if (chart/compound-state? statechart state)
            (let [target (->> (chart/initial-element statechart state) (chart/transition-element statechart) :target)]
              (vswap! states-for-default-entry conj (chart/element-id statechart state))
              (add-elements! target state))
            (if (chart/parallel-state? statechart state)
              (doseq [child (chart/child-states statechart state)]
                (when-not (some (fn [s] (chart/descendant? statechart s child)) @states-to-enter)
                  (add-descendant-states-to-enter! env child states-to-enter
                    states-for-default-entry default-history-content))))))))
    nil))

(>defn get-effective-target-states
  [{::sc/keys [statechart vwmem] :as env} t]
  [::sc/processing-env ::sc/element-or-id => [:set ::sc/element-or-id]]
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
            (let [default-transition (first (chart/transitions statechart s))] ; spec + validation. There will be exactly one
              (set/union targets (get-effective-target-states env default-transition)))

            :else (conj targets id))))
      (chart/document-ordered-set statechart)
      (log/spy :debug "target(s)"
        (:target (chart/element statechart t))))))

(>defn get-transition-domain
  [{::sc/keys [statechart] :as env} t]
  [::sc/processing-env ::sc/element-or-id => (? ::sc/id)]
  (let [tstates (log/spy :debug (get-effective-target-states env t))
        tsource (log/spy :debug (chart/nearest-ancestor-state statechart t))]
    (cond
      (empty? tstates) nil
      (and
        (= :internal (:type t))
        (chart/compound-state? statechart tsource)
        (every? (fn [s] (chart/descendant? statechart s tsource)) tstates)) tsource
      :else (log/spy :debug (:id (chart/find-least-common-compound-ancestor statechart (into (if tsource [tsource] []) tstates)))))))

(>defn compute-entry-set!
  "Returns [states-to-enter states-for-default-entry default-history-content]."
  [{::sc/keys [vwmem statechart] :as env}]
  [::sc/processing-env => [:tuple set? set? map?]]
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

(>defn initialize-data-model!
  "Initialize the data models in volatile working memory `wmem` for the given states, if necessary."
  [{::sc/keys [statechart data-model] :as env} state]
  [::sc/processing-env ::sc/element-or-id => nil?]
  (log/debug "Initializing data model for" state)
  (let [dm-eles (chart/get-children statechart state :data-model)
        {:keys [src expr]} (chart/element statechart (first dm-eles))]
    (when (> (count dm-eles) 1)
      (log/warn "Too many data elements on" state))
    (in-state-context env state
      (cond
        src (sp/load-data data-model env src)
        (fn? expr) (let [ops (run-expression! env expr)]
                     (when (vector? ops)
                       (sp/update! data-model env {:ops (log/spy :debug ops)})))
        (map? expr) (sp/update! data-model env {:ops (log/spy :debug (ops/set-map-ops expr))})
        :else (sp/update! data-model env {:ops (ops/set-map-ops {})})))
    nil))

(declare execute!)

(defmulti execute-element-content!
  "Multimethod. Extensible mechanism for running the content of elements on the state machine. Dispatch by :node-type
   of the element itself."
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
  (case level
    :error (log/error (or label "LOG") (run-expression! env expr))
    :warn (log/warn (or label "LOG") (run-expression! env expr))
    :info (log/info (or label "LOG") (run-expression! env expr))
    (log/debug (or label "LOG") (run-expression! env expr))))

(defmethod execute-element-content! :raise [env {:keys [event]}]
  (log/debug "Raise " event)
  (raise env event))

(defmethod execute-element-content! :assign [env {:keys [location expr]}]
  (let [v (run-expression! env expr)]
    (log/debug "Assign" location " = " v)
    (env/assign! env location v)))

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
          (let [event-name        (!? env event eventexpr)
                id                (if idlocation (genid "send") id)
                target            (!? env target targetexpr)
                target-is-parent? (= target (env/parent-session-id env))
                data              (log/spy :debug
                                    "Computed send event data"
                                    (merge
                                      (named-data env namelist)
                                      (!? env nil content)))]
            (when idlocation (sp/update! data-model env {:ops [(ops/assign idlocation id)]}))
            (sp/send! event-queue env (cond-> {:send-id           id
                                               :source-session-id (env/session-id env)
                                               :event             event-name
                                               :data              data
                                               :target            target
                                               :type              (or (!? env type typeexpr) ::sc/chart)
                                               :delay             (or (!? env delay delayexpr) 0)}
                                        target-is-parent? (assoc :invoke-id (env/invoke-id env))))))]
  (defmethod execute-element-content! :send [env element]
    (log/debug "Send event" element)
    (when-not (send! env element)
      (raise env (evts/new-event {:name :error.execution
                                  :data {:type    :send
                                         :element element}})))))

(defmethod execute-element-content! :cancel [{::sc/keys [event-queue] :as env}
                                             {:keys [sendid sendidexpr] :as element}]
  (log/debug "Cancel event" element)
  (let [id (!? env sendid sendidexpr)]
    (sp/cancel! event-queue env (env/session-id env) id)))

(>defn execute!
  "Run the executable content (immediate children) of s."
  [{::sc/keys [statechart] :as env} s]
  [::sc/processing-env ::sc/element-or-id => nil?]
  (log/debug "Execute content of" s)
  (let [{:keys [children]} (chart/element statechart s)]
    (doseq [n children]
      (try
        (let [ele (chart/element statechart n)]
          (execute-element-content! env ele))
        (catch #?(:clj Throwable :cljs :default) t
          ;; TODO: Proper error event for execution problem
          (log/error t "Unexpected exception in content")))))
  nil)

(>defn compute-done-data! [{::sc/keys [statechart] :as env} final-state]
  [::sc/processing-env ::sc/element-or-id => any?]
  (let [done-element (some->> (chart/get-children statechart final-state :done-data)
                       first
                       (chart/element statechart))]
    (if done-element
      (log/spy :debug "computed done data" (execute-element-content! env done-element))
      {})))

(>defn enter-states!
  "Enters states, triggers actions, tracks long-running invocations, and
   returns updated working memory."
  [{::sc/keys [statechart vwmem] :as env}]
  [::sc/processing-env => nil?]
  (let [[states-to-enter
         ;; TODO: Verify states-for-default-entry is kept around correct amount of time!
         states-for-default-entry
         default-history-content] (log/spy :debug "entry set"
                                    (compute-entry-set! env))]
    (doseq [s (chart/in-entry-order statechart states-to-enter)
            :let [state-id (chart/element-id statechart s)]]
      (log/debug "Enter" state-id)
      (in-state-context env s
        (vswap! vwmem update ::sc/configuration conj s)
        (vswap! vwmem update ::sc/states-to-invoke conj s)
        (when (and (= :late (:binding statechart)) (not (contains? (::sc/initialized-states @vwmem) state-id)))
          (initialize-data-model! env s)
          (vswap! vwmem update ::sc/initialized-states (fnil conj #{}) state-id))
        (doseq [entry (chart/entry-handlers statechart s)]
          (execute! env entry))
        (when-let [t (and (contains? states-for-default-entry s)
                       (some->> s (chart/initial-element statechart) (chart/transition-element statechart)))]
          (execute! env t))
        (when-let [content (get default-history-content (chart/element-id statechart s))]
          (execute-element-content! env (chart/element statechart content)))
        (when (log/spy :debug (chart/final-state? statechart s))
          (if (= :ROOT (chart/get-parent statechart s))
            (vswap! vwmem assoc ::sc/running? false)
            (let [parent      (chart/get-parent statechart s)
                  grandparent (chart/get-parent statechart parent)
                  done-data   (compute-done-data! env s)]
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
                              :name   (keyword (str "done.state." (name (chart/element-id statechart grandparent))))})))))))))
  (log/spy :debug "after enter states: " (::sc/configuration @vwmem))
  nil)

(>defn execute-transition-content!
  [{::sc/keys [vwmem] :as env}]
  [::sc/processing-env => nil?]
  (doseq [t (::sc/enabled-transitions @vwmem)]
    (execute! env t))
  nil)

(>defn compute-exit-set
  [{::sc/keys [statechart vwmem] :as env} transitions]
  [::sc/processing-env [:or
                        [:set ::sc/element-or-id]
                        [:sequential ::sc/element-or-id]] => [:set ::sc/id]]
  (let [states-to-exit (volatile! (chart/document-ordered-set statechart))]
    (doseq [t (map #(chart/element statechart %) transitions)]
      (when (contains? t :target)
        (let [domain (log/spy :debug (get-transition-domain env t))]
          (doseq [s (::sc/configuration @vwmem)]
            (when (chart/descendant? statechart s domain)
              (vswap! states-to-exit conj s))))))
    @states-to-exit))

(>defn remove-conflicting-transitions
  "Updates working-mem so that enabled-transitions no longer includes any conflicting ones."
  [{::sc/keys [statechart] :as env} enabled-transitions]
  [::sc/processing-env [:or
                        [:sequential ::sc/id]
                        [:set ::sc/id]] => [:set ::sc/id]]
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
        (do
          (doseq [t3 @to-remove]
            (vswap! filtered-transitions disj t3))
          (vswap! filtered-transitions conj t1))))
    @filtered-transitions))

(>defn select-transitions* [machine configuration predicate]
  [::sc/statechart ::sc/configuration ifn? => ::sc/enabled-transitions]
  (let [enabled-transitions (volatile! (chart/document-ordered-set machine))
        looping?            (volatile! true)
        start-loop!         #(vreset! looping? true)
        break!              #(vreset! looping? false)
        atomic-states       (chart/in-document-order machine (filterv #(chart/atomic-state? machine %) configuration))]
    (doseq [state atomic-states]
      (start-loop!)
      (doseq [s (into [state] (chart/get-proper-ancestors machine state))
              :when @looping?]
        (doseq [t (map #(chart/element machine %) (chart/in-document-order machine (chart/transitions machine s)))
                :when (and @looping? (predicate t))]
          (vswap! enabled-transitions conj (chart/element-id machine t))
          (break!))))
    @enabled-transitions))

(>defn select-eventless-transitions!
  "Returns a new version of working memory with ::sc/enabled-transitions populated."
  [{::sc/keys [statechart vwmem] :as env}]
  [::sc/processing-env => nil?]
  (let [tns (remove-conflicting-transitions env
              (select-transitions* statechart (::sc/configuration @vwmem)
                (fn [t] (and (not (:event t)) (condition-match env t)))))]
    (vswap! vwmem assoc ::sc/enabled-transitions tns))
  nil)

(>defn select-transitions!
  "Returns a new version of working memory with ::sc/enabled-transitions populated."
  [{::sc/keys [statechart vwmem] :as env} event]
  [::sc/processing-env ::sc/event-or-name => ::sc/working-memory]
  (let [tns (log/spy :debug "enabled transitions"
              (remove-conflicting-transitions env
                (select-transitions* statechart (::sc/configuration @vwmem)
                  (fn [t] (and
                            (contains? t :event)
                            (evts/name-match? (:event t) event)
                            (condition-match env t))))))]
    (vswap! vwmem assoc ::sc/enabled-transitions tns)))

(defn- invocation-details
  [{::sc/keys [statechart
               invocation-processors] :as env} invocation]
  (let [{:keys [type typeexpr src srcexpr] :as invocation} (chart/element statechart invocation)
        type (or (!? env type typeexpr) :statechart)
        src  (or src (!? env nil srcexpr))]
    (assoc invocation
      :type type
      :src src
      :processor (first (filter #(sp/supports-invocation-type? % type) invocation-processors)))))

(letfn [(start-invocation!
          [{::sc/keys [statechart
                       data-model
                       execution-model
                       event-queue] :as env} invocation]
          (let [{:keys [type src
                        id idlocation
                        explicit-id?
                        namelist params
                        processor] :as invocation} (invocation-details env invocation)
                parent-state-id (chart/nearest-ancestor-state statechart invocation)]
            (if processor
              (let [param-map (if (map? params)
                                (reduce-kv
                                  (fn [acc k expr]
                                    (assoc acc k (sp/run-expression! execution-model env expr)))
                                  {}
                                  params)
                                (sp/run-expression! execution-model env params))
                    invokeid  (if explicit-id?
                                id
                                (str parent-state-id "." (new-uuid)))
                    params    (merge
                                (named-data env namelist)
                                param-map)]
                (when idlocation
                  (sp/update! data-model env {:ops [(ops/assign idlocation invokeid)]}))
                (log/debugf "Starting invocation id = %s, type = %s, src = %s, params = %s"
                  (str invokeid) (str type) (str src) (str params))
                (sp/start-invocation! processor env {:invokeid invokeid
                                                     :src      src
                                                     :type     type
                                                     :params   params}))
              (do
                (log/error "Cannot start invocation. No processor for " invocation)
                ;; Switch to internal event queue of working memory?
                (sp/send! event-queue env {:event             :error.execution
                                           :data              {:invocation-type type
                                                               :reason          "Not found"}
                                           :send-id           :invocation-failure
                                           :source-session-id (session-id env)
                                           :target            (session-id env)})))))]

  (>defn run-invocations! [{::sc/keys [statechart vwmem] :as env}]
    [::sc/processing-env => nil?]
    (let [{::sc/keys [states-to-invoke]} @vwmem]
      (doseq [state-to-invoke (chart/in-entry-order statechart states-to-invoke)]
        (in-state-context env state-to-invoke
          (doseq [i (chart/in-document-order statechart (chart/invocations statechart state-to-invoke))]
            (start-invocation! env i))))
      ;; Clear states to invoke
      (vswap! vwmem assoc ::sc/states-to-invoke (chart/document-ordered-set statechart)))
    nil))

(>defn run-many!
  "Run the code associated with the given nodes. Does NOT set context id of the nodes run."
  [{::sc/keys [statechart] :as env} nodes]
  [::sc/processing-env [:sequential ::sc/element-or-id] => nil?]
  (doseq [n nodes]
    (try
      (execute-element-content! env (chart/element statechart n))
      (catch #?(:clj Throwable :cljs :default) e
        (raise env (evts/new-event {:name :error.execution
                                    :data {:node      n
                                           :exception e}}))
        (log/error e "Unexpected execution error"))))
  nil)

(letfn [(stop-invocation!
          [{::sc/keys [data-model] :as env} invocation]
          (let [{:keys [type
                        processor
                        id idlocation]} (invocation-details env invocation)]
            (when processor
              (let [invokeid (if idlocation
                               (sp/get-at data-model env idlocation)
                               id)]
                (log/debug "Stopping invocation" invokeid)
                (sp/stop-invocation! processor env {:invokeid invokeid
                                                    :type     type})))))]
  (>defn cancel-active-invocations!
    [{::sc/keys [statechart] :as env} state]
    [::sc/processing-env ::sc/element-or-id => nil?]
    (log/spy :debug "Stopping invocations for " state)
    (doseq [i (chart/invocations statechart state)]
      (stop-invocation! env i))
    nil))

(>defn exit-states!
  "Does all of the processing for exiting states. Returns new working memory."
  [{::sc/keys [statechart vwmem] :as env}]
  [::sc/processing-env => nil?]
  (let [{::sc/keys [enabled-transitions
                    states-to-invoke
                    configuration]} @vwmem
        states-to-exit   (chart/in-exit-order statechart (compute-exit-set env enabled-transitions))
        states-to-invoke (set/difference states-to-invoke (set states-to-exit))]
    (vswap! vwmem assoc ::sc/states-to-invoke states-to-invoke)
    (doseq [s states-to-exit]
      (doseq [{:keys [id type] :as h} (chart/history-elements statechart s)]
        (let [f (if (= :deep type)
                  (fn [s0] (and
                             (chart/atomic-state? statechart s0)
                             (chart/descendant? statechart s0 s)))
                  (fn [s0]
                    (= s (chart/element-id statechart (chart/get-parent statechart s0)))))]
          (vswap! vwmem assoc-in [::sc/history-value id] (into #{} (filter f configuration))))))
    (doseq [s states-to-exit]
      (log/debug "Leaving state " s)
      (in-state-context env s
        (let [to-exit (chart/exit-handlers statechart s)]
          (run-many! env to-exit)
          (cancel-active-invocations! env s)
          (vswap! vwmem update ::sc/configuration disj s)))))
  nil)

(>defn microstep!
  [env]
  [::sc/processing-env => nil?]
  (exit-states! env)
  (execute-transition-content! env)
  (enter-states! env)
  nil)

(>defn handle-eventless-transitions!
  "Work through eventless transitions, returning the updated working memory"
  [{::sc/keys [vwmem] :as env}]
  [::sc/processing-env => nil?]
  (let [macrostep-done? (volatile! false)]
    (while (and (::sc/running? @vwmem) (not @macrostep-done?))
      (select-eventless-transitions! env)
      (let [{::sc/keys [enabled-transitions
                        internal-queue]} @vwmem]
        (when (empty? enabled-transitions)
          (if (empty? internal-queue)
            (vreset! macrostep-done? true)
            (let [internal-event (first internal-queue)]
              (log/spy :debug internal-event)
              (vswap! vwmem update ::sc/internal-queue pop)
              (env/assign! env [:ROOT :_event] internal-event)
              (select-transitions! env internal-event))))
        (when (seq (::sc/enabled-transitions @vwmem))
          (microstep! env)))))
  nil)

(>defn run-exit-handlers!
  "Run the exit handlers of `state`."
  [{::sc/keys [statechart] :as env} state]
  [::sc/processing-env ::sc/element-or-id => nil?]
  (in-state-context env state
    (let [nodes (chart/in-document-order statechart (chart/exit-handlers statechart state))]
      (run-many! env nodes)))
  nil)

(>defn send-done-event! [env state]
  [::sc/processing-env ::sc/element-or-id => nil?]
  (in-state-context env state
    (let [{::sc/keys [vwmem event-queue]} env
          {:org.w3.scxml.event/keys [invokeid]
           ::sc/keys                [parent-session-id]} @vwmem]
      (when (and invokeid parent-session-id)
        (let [session-id (env/session-id env)]
          (log/debug "Sending done event from" session-id "to" parent-session-id "for" invokeid)
          (sp/send! event-queue env {:target            parent-session-id
                                     :sendid            session-id
                                     :source-session-id session-id
                                     :invoke-id         invokeid
                                     :event             (keyword (str "done.invoke." invokeid))})))))
  nil)

(>defn exit-interpreter!
  ([{::sc/keys [statechart vwmem] :as env} skip-done-event?]
   [::sc/processing-env :boolean => nil?]
   (let [states-to-exit (log/spy :debug (chart/in-exit-order statechart (::sc/configuration @vwmem)))]
     (doseq [state states-to-exit]
       (run-exit-handlers! env state)
       (cancel-active-invocations! env state)
       (vswap! vwmem update ::sc/configuration disj state)
       (when (and (not skip-done-event?) (chart/final-state? statechart state) (= :ROOT (chart/get-parent statechart state)))
         (send-done-event! env state)))))
  ([{::sc/keys [statechart vwmem] :as env}]
   [::sc/processing-env => nil?]
   (exit-interpreter! env false)))

(>defn before-event!
  "Steps that are run before processing the next event."
  [{::sc/keys [statechart vwmem] :as env}]
  [::sc/processing-env => nil?]
  (let [{::sc/keys [running?]} @vwmem]
    (if running?
      (loop []
        (vswap! vwmem assoc ::sc/enabled-transitions (chart/document-ordered-set statechart) ::sc/macrostep-done? false)
        (handle-eventless-transitions! env)
        (if (-> vwmem deref ::sc/running?)
          (do
            (run-invocations! env)
            (when (seq (::sc/internal-queue @vwmem))
              (recur)))
          (exit-interpreter! env)))
      (exit-interpreter! env))))

(defn cancel? [event] (= :com.fulcrologic.statecharts.events/cancel (:name event)))

(defn finalize!
  "Run the finalize executable content for an event from an external invocation."
  [{::sc/keys [statechart] :as env} invocation event]
  (let [parent (or
                 (chart/nearest-ancestor-state statechart invocation)
                 statechart)]
    (in-state-context env parent
      (env/assign! env [:ROOT :_event] event)
      (when-let [finalize (log/spy :debug "finalizers" (chart/get-children statechart invocation :finalize))]
        (doseq [f finalize]
          (execute! (assoc env :_event event) f)))
      (env/delete! env [:ROOT :_event]))))

(letfn [(forward-event!
          [{::sc/keys [data-model] :as env} invocation event]
          (let [{:keys [type
                        processor
                        id idlocation]} (invocation-details env invocation)]
            (when processor
              (let [invokeid (if idlocation (sp/get-at data-model env idlocation) id)]
                (sp/forward-event! processor env {:invokeid invokeid
                                                  :type     type
                                                  :event    event})))))]

  (>defn handle-external-invocations! [{::sc/keys [statechart vwmem data-model] :as env}
                                       {:keys [invokeid] :as external-event}]
    [::sc/processing-env ::sc/event => nil?]
    (doseq [s (::sc/configuration @vwmem)]
      (doseq [{:keys [id idlocation id-location auto-forward? autoforward] :as inv} (map (partial chart/element statechart) (chart/invocations statechart s))
              :let [id (if-let [loc (or idlocation id-location)]
                         (sp/get-at data-model env loc)
                         id)]]
        (when (log/spy :debug "event from invocation?" (= invokeid id))
          (finalize! env inv external-event))
        (when (or (true? autoforward) (true? auto-forward?))
          (forward-event! env inv external-event))))
    nil))

(>defn processing-env
  "Set up `env` to track live data that is needed during the algorithm."
  [{::sc/keys [statechart-registry] :as env} statechart-src {::sc/keys                [session-id
                                                                                       parent-session-id]
                                                             :org.w3.scxml.event/keys [invokeid] :as wmem}]
  [::sc/env ::sc/statechart-src [:map
                                 [::sc/session-id {:optional true}]
                                 [::sc/parent-session-id {:optional true}]
                                 [:org.w3.scxml.event/invokeid {:optional true}]] => ::sc/processing-env]
  (if-let [statechart (sp/get-statechart statechart-registry statechart-src)]
    (do
      (log/spy :debug "Processing event on statechart" statechart-src)
      (assoc env
        ::sc/context-element-id :ROOT
        ::sc/statechart statechart
        ::sc/vwmem (volatile! (merge
                                {::sc/session-id         (or session-id (genid "session"))
                                 ::sc/statechart-src     statechart-src
                                 ::sc/configuration      #{} ; active states
                                 ::sc/initialized-states #{} ; states that have been entered (initialized data model) before
                                 ::sc/history-value      {}
                                 ::sc/running?           true}
                                wmem))))
    (throw (ex-info "Statechart not found" {:src statechart-src}))))

(>defn process-event!
  "Process the given `external-event` given a state `machine` with the `working-memory` as its current status/state.
   Returns the new version of working memory which you should save to pass into the next call.  Typically this function
   is called by an overall processing system instead of directly."
  [env external-event]
  [::sc/processing-env ::sc/event-or-name => ::sc/working-memory]
  (log/spy :debug external-event)
  (let [event (new-event external-event)]
    (with-processing-context env
      (if (cancel? event)
        (exit-interpreter! env)
        (do
          (env/assign! env [:ROOT :_event] event)
          (select-transitions! env event)
          (handle-external-invocations! env event)
          (microstep! env)
          (before-event! env)))))
  (env/assign! env [:ROOT :_event] nil)
  (some-> env ::sc/vwmem deref))

(>defn initialize!
  "Initializes the state machine and creates an initial working memory for a new machine env.
   Auto-assigns a unique UUID for session ID.

   This function processes the initial transition and returns the updated working memory from `env`."
  [{::sc/keys [statechart data-model parent-session-id vwmem] :as env}
   {::sc/keys                [invocation-data statechart-src]
    :org.w3.scxml.event/keys [invokeid]}]
  [::sc/processing-env [:map
                        [::sc/invocation-data {:optional true} map?]
                        [:org.w3.scxml.event/invokeid {:optional true}]] => ::sc/working-memory]
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
    (with-processing-context env
      (when early?
        (let [all-data-model-nodes (filter #(= :data-model (:node-type %)) (vals (::sc/elements-by-id statechart)))]
          (doseq [n all-data-model-nodes]
            (in-state-context env n
              (initialize-data-model! env (chart/get-parent statechart n))))))
      (when (map? invocation-data)
        (sp/update! data-model env {:ops (ops/set-map-ops invocation-data)}))
      (enter-states! env)
      (before-event! env)
      (when script
        (execute! env script)))
    @vwmem))

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
