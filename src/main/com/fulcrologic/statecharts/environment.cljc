(ns com.fulcrologic.statecharts.environment
  "Helper functions related to the environment used by models."
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    com.fulcrologic.statecharts.specs
    [com.fulcrologic.statecharts.state-machine :as sm]
    [com.fulcrologic.statecharts.util :refer [queue]]
    [taoensso.timbre :as log]))

(>defn new-env
  ([machine DM Q Ex addl]
   [::sc/machine ::sc/data-model ::sc/event-queue ::sc/execution-model map? => ::sc/env]
   (merge addl (new-env machine DM Q Ex)))
  ([machine DM Q Ex]
   [::sc/machine ::sc/data-model ::sc/event-queue ::sc/execution-model => ::sc/env]
   (cond-> {::sc/machine         machine
            ::sc/data-model      DM
            ::sc/event-queue     Q
            ::sc/execution-model Ex
            ::sc/pending-events  (volatile! (queue))})))

(>defn send-internal-event!
  "Put an event on the pending queue. Only usable from within the implementation of a model (a function
   that receives and env)."
  [env event]
  [::sc/env ::sc/event => nil?]
  (when event
    (vswap! (::sc/pending-events env) conj event))
  nil)

(>defn session-id
  "Returns the session ID from an env."
  [{::sc/keys [vwmem] :as env}]
  [::sc/env => (? ::sc/session-id)]
  (some-> vwmem deref ::sc/session-id))

(>defn context-element-id
  "Returns the ID of the context (state of interest for the current operation) from an env, if set."
  [env]
  [(s/keys :req [::sc/context-element-id]) => (? ::sc/id)]
  (::sc/context-element-id env))

(>defn send-error-event!
  "Put an error (typically an exception) on the pending internal queue. Only usable from within the implementation of a model (a function
   that receives and env)."
  [env event-name error extra-data]
  [::sc/env ::sc/event-name any? map? => nil?]
  (send-internal-event! env
    (evts/new-event {:name  event-name
                     :data  (merge extra-data {:context-element-id (context-element-id env)
                                               :session-id         (session-id env)})
                     :error error
                     :type  :platform}))
  nil)

(defn assign!
  "Side effect against the data model in `env`, with the given path-value pairs."
  [{::sc/keys [data-model] :as env} & {:as path-value-pairs}]
  (sp/update! data-model env {:ops (ops/set-map-ops path-value-pairs)})
  nil)

(defn delete!
  "Side effect against the data model in `env`, with the given keys/paths"
  [{::sc/keys [data-model] :as env} & ks]
  (sp/update! data-model env {:ops [(ops/delete ks)]})
  nil)

(defn- !?
  "Returns `value` if not nil, or runs `expr` and returns that as the value. Returns nil if both are nil."
  [{::sc/keys [execution-model] :as env} value expr]
  (cond
    (not (nil? value)) value
    (not (nil? expr)) (sp/run-expression! execution-model env expr)
    :else nil))

(defn- named-data [{::sc/keys [data-model] :as env} namelist]
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

(defn send!
  "Send an external event (from within the state machine) using `env`"
  [{::sc/keys [event-queue
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
           typeexpr] :as send-element}]
  (let [event-name (!? env event eventexpr)
        data       (merge
                     (named-data env namelist)
                     (!? env {} content))]
    (sp/send! event-queue {:send-id           (if idlocation
                                                (sp/get-at data-model env idlocation)
                                                id)
                           :source-session-id (session-id env)
                           :event             event-name
                           :data              data
                           :target            (!? env target targetexpr)
                           :type              (!? env type typeexpr)
                           :delay             (!? env delay delayexpr)})))

(defn cancel-event!
  "Attempt to cancel a (delayed) event."
  [{::sc/keys [event-queue] :as env} event-id]
  (sp/cancel! event-queue (session-id env) event-id))

(defn- invocation-details
  "Expands `invocation` if it is an ID, rewrites `:type` if there is a `typeexpr`, and adds :processor
   as a key to the instance of InvocationProcess that should handle interactions for it"
  [{::sc/keys [machine
               invocation-processors] :as env} invocation]
  (let [{:keys [type typeexpr] :as invocation} (sm/element machine invocation)
        type (!? env type typeexpr)]
    (assoc invocation
      :type type
      :processor (first (filter #(sp/supports-invocation-type? % type) invocation-processors)))))

(defn start-invocation!
  "Try to start the given invocation element"
  [{::sc/keys [machine
               data-model
               execution-model
               event-queue] :as env} invocation]
  (let [{:keys [type
                id idlocation
                namelist params
                processor] :as invocation} (invocation-details env invocation)
        parent-state-id (sm/nearest-ancestor-state machine invocation)]
    (if processor
      (let [param-map (reduce-kv
                        (fn [acc k expr]
                          (assoc acc k (sp/run-expression! execution-model env expr)))
                        {}
                        params)
            invokeid  (if idlocation
                        (str parent-state-id "." #?(:clj  (java.util.UUID/randomUUID)
                                                    :cljs (random-uuid)))
                        id)
            params    (merge
                        (named-data env namelist)
                        param-map)]
        (when (log/spy :info idlocation)
          (sp/update! data-model env {:ops [(ops/assign idlocation invokeid)]}))
        (sp/start-invocation! processor env {:invokeid invokeid
                                             :type     type
                                             :params   params}))
      (do
        (log/error "Cannot start invocation. No processor for " invocation)
        ;; Switch to internal event queue of working memory?
        (sp/send! event-queue {:event             :error.execution
                               :data              {:invocation-type type
                                                   :reason          "Not found"}
                               :send-id           :invocation-failure
                               :source-session-id (session-id env)
                               :target            (session-id env)})))))

(defn stop-invocation!
  "Stop an invocation"
  [{::sc/keys [data-model] :as env} invocation]
  (let [{:keys [type
                processor
                id idlocation]} (invocation-details env invocation)]
    (when (log/spy :info processor)
      (let [invokeid (if (log/spy :info idlocation)
                       (log/spy :info (sp/get-at data-model env idlocation))
                       (log/spy :info id))]
        (sp/stop-invocation! processor env {:invokeid invokeid
                                            :type     type})))))

(defn forward-event!
  "Forward an event to an invocation"
  [{::sc/keys [data-model] :as env} invocation event]
  (let [{:keys [type
                processor
                id idlocation]} (invocation-details env invocation)]
    (when processor
      (let [invokeid (if idlocation (sp/get-at data-model env idlocation) id)]
        (sp/forward-event! processor env {:invokeid invokeid
                                          :type     type
                                          :event    event})))))
