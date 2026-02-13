(ns com.fulcrologic.statecharts.integration.fulcro-impl
  (:require
    [clojure.set :as set]
    [com.fulcrologic.devtools.common.protocols :as dp]
    [com.fulcrologic.devtools.common.resolvers :as dres]
    [com.fulcrologic.devtools.common.utils :refer [strip-lambdas]]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.inspect.devtool-api :as dapi]
    [com.fulcrologic.fulcro.inspect.inspect-client :refer [app-uuid app-uuid-key]]
    [com.fulcrologic.fulcro.inspect.target-impl :as timpl]
    [com.fulcrologic.fulcro.inspect.tools :as fit]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as op]
    [com.fulcrologic.statecharts.elements :as ele]
    [com.fulcrologic.statecharts.environment :as senv]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.wsscode.pathom.connect :as pc]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]))

(dapi/remote-mutations statechart-event)

(defn runtime-atom [app] (:com.fulcrologic.fulcro.application/runtime-atom app))

(dres/defresolver statechart-definition-resolver [env input]
  {::pc/output [{:statechart/definitions [:statechart/registry-key
                                          :statechart/chart]}]}
  (let [params   (:query-params env)
        app-uuid (app-uuid params)
        app      (get @timpl/apps* app-uuid)]
    (when app
      (let [runtime-env          (some-> (runtime-atom app) deref :com.fulcrologic.statecharts/env)
            chart-id->definition (some-> runtime-env :com.fulcrologic.statecharts/statechart-registry :charts deref strip-lambdas)
            definitions          (mapv (fn [[k v]]
                                         {:statechart/registry-key k
                                          :statechart/chart        v})
                                   chart-id->definition)]
        {:statechart/definitions definitions}))))

(dres/defresolver statechart-session-resolver [env input]
  {::pc/output [{:statechart/available-sessions [:com.fulcrologic.statecharts/session-id
                                                 :com.fulcrologic.statecharts/history-value
                                                 :com.fulcrologic.statecharts/parent-session-id
                                                 :com.fulcrologic.statecharts/statechart-src
                                                 :com.fulcrologic.statecharts/configuration
                                                 :com.fulcrologic.statecharts/statechart]}]}
  (let [params   (:query-params env)
        app-uuid (app-uuid params)
        app      (get @timpl/apps* app-uuid)]
    (when (boolean app)
      (let [{session-id->session :com.fulcrologic.statecharts/session-id :as state-map} (rapp/current-state app)
            runtime-env          (some-> (runtime-atom app) deref :com.fulcrologic.statecharts/env)
            chart-id->definition (some-> runtime-env :com.fulcrologic.statecharts/statechart-registry :charts deref strip-lambdas)
            available-sessions   (mapv
                                   (fn [session]
                                     (let [src-id (:com.fulcrologic.statecharts/statechart-src session)]
                                       (-> session
                                         (select-keys [:com.fulcrologic.statecharts/session-id
                                                       :com.fulcrologic.statecharts/history-value
                                                       :com.fulcrologic.statecharts/parent-session-id
                                                       :com.fulcrologic.statecharts/statechart-src
                                                       :com.fulcrologic.statecharts/configuration])
                                         (assoc :com.fulcrologic.statecharts/statechart {:statechart/registry-key src-id
                                                                                         :statechart/chart        (chart-id->definition src-id)}))))
                                   (vals session-id->session))]
        {:statechart/available-sessions available-sessions}))))

(defmethod timpl/handle-inspect-event `statechart-event [tconn app event]
  (let [app-uuid (app-uuid app)]
    (dp/transmit! tconn app-uuid [(statechart-event event)])))

(defn statechart-event! [app session-id event data new-config]
  #?(:cljs
     (let [app-uuid (app-uuid app)]
       (fit/notify! app `statechart-event {app-uuid-key                               app-uuid
                                           :com.fulcrologic.statecharts/session-id    session-id
                                           :event                                     event
                                           :data                                      data
                                           :com.fulcrologic.statecharts/configuration new-config}))))

(defn local-data-path
  "Returns the Fulcro app state ident location of the local data of a specific instance of a running state machine."
  [session-id & ks]
  (into [::sc/local-data session-id] ks))

(defn statechart-session-ident
  "Returns the Fulcro app state ident location of the working memory of a specific statechart session."
  [session-id]
  [::sc/session-id session-id])

(defn statechart-env
  "Returns the installed statechart env."
  [app-ish]
  (some-> (rc/any->app app-ish) :com.fulcrologic.fulcro.application/runtime-atom deref ::sc/env))

(defn resolve-alias-path
  ([state-map session-id alias]
   (let [local-data (get-in state-map (local-data-path session-id))]
     (resolve-alias-path local-data alias)))
  ([local-data alias]
   (let [actors     (:fulcro/actors local-data)
         alias-path (get-in local-data [:fulcro/aliases alias])]
     (vec
       (mapcat
         (fn [path-key]
           (cond
             (and (qualified-keyword? path-key) (= "actor" (namespace path-key)) (not (contains? actors path-key)))
             (throw (ex-info "Invalid alias. No such actor." {:alias alias
                                                              :actor path-key}))

             (and (qualified-keyword? path-key) (= "actor" (namespace path-key)) (contains? actors path-key))
             (get-in local-data [:fulcro/actors path-key :ident])

             (#{:fulcro/state :fulcro/state-map} path-key)
             []

             :else
             [path-key]))
         alias-path)))))

(defn resolve-aliases [{:keys [_event fulcro/app fulcro/state-map] :as env-or-data}]
  (let [session-id (or (:target _event) (senv/session-id env-or-data))
        state-map  (or state-map (rapp/current-state app))
        local-data (get-in state-map (local-data-path session-id))
        aliases    (get local-data :fulcro/aliases)]
    (reduce
      (fn [acc alias]
        (let [expanded-path (resolve-alias-path local-data alias)]
          (assoc acc alias (get-in state-map expanded-path))))
      {}
      (keys aliases))))


(defmulti run-fulcro-data-op! (fn [_app _penv {:keys [op]}] op))
(defmethod run-fulcro-data-op! :default [_app _penv op]
  (log/warn "Operation not understood" op))

(defn state-atom [{:com.fulcrologic.fulcro.application/keys [state-atom]}] state-atom)

(defn dissoc-in [m ks]
  (cond
    (empty? ks) m
    (= 1 (count ks)) (dissoc m (first ks))
    (contains? (get-in m (butlast ks)) (last ks)) (update-in m (butlast ks) dissoc (last ks))
    :else m))

(defn resolve-actors-in-path
  "For any :actor/xxx keywords in `path`: splices the ident of that actor (if known)."
  ([state-map session-id path]
   (let [local-data (get-in state-map (local-data-path session-id))]
     (resolve-actors-in-path local-data path)))
  ([local-data path]
   (let [actors (:fulcro/actors local-data)]
     (or
       (when (vector? path)
         (let [k (first path)]
           (when (and
                   (qualified-keyword? k)
                   (= "actor" (namespace k))
                   (contains? actors k))
             (into [:fulcro/state] (concat (get-in actors [k :ident]) (rest path))))))
       path))))

(defmethod run-fulcro-data-op! :assign [app processing-env {:keys [data]}]
  (let [session-id (senv/session-id processing-env)
        local-data (get-in @(state-atom app) (local-data-path session-id))
        aliases    (get local-data :fulcro/aliases)]
    (swap! (state-atom app)
      (fn [all-data]
        (reduce-kv
          (fn assign* [acc path value]
            (let [path (resolve-actors-in-path local-data path)]
              (cond
                (= :ROOT path) (assoc-in acc (local-data-path session-id) value)
                (and (sequential? path) (#{:fulcro/state :fulcro/state-map} (first path))) (assoc-in acc (rest path) value)
                (and (keyword? path) (contains? aliases path)) (assign* acc (get aliases path) value)
                (keyword? path) (assoc-in acc (local-data-path session-id path) value)
                (and (vector? path) (= :ROOT (first path))) (assoc-in acc (into (local-data-path session-id) (rest path)) value)
                (vector? path) (assoc-in acc (into (local-data-path session-id) path) value)
                :else acc)))
          all-data
          data)))))

(defmethod run-fulcro-data-op! :delete [app processing-env {:keys [paths]}]
  (let [session-id (senv/session-id processing-env)]
    (swap! (state-atom app)
      (fn [all-data]
        (reduce
          (fn [M path]
            (cond
              (= :ROOT path) (update M ::sc/local-data dissoc session-id)
              (and (vector? path) (= :fulcro/state-map (first path))) (dissoc-in M (rest path))
              (keyword? path) (update-in M (local-data-path session-id) dissoc path)
              (and (vector? path) (= :ROOT (first path))) (dissoc-in M (apply local-data-path session-id (rest path)))
              (vector? path) (dissoc-in M (apply local-data-path session-id path))
              :else M))
          all-data
          paths)))))

(deftype FulcroDataModel [fulcro-app]
  sp/DataModel
  (load-data [_provider processing-env src]
    (let [session-id (senv/session-id processing-env)]
      (when (and session-id src)
        (log/trace "Initializing (overwrite) local statechart data with" src)
        (swap! (state-atom fulcro-app) assoc-in [::sc/local-data session-id] src))
      (rapp/schedule-render! fulcro-app)))
  (current-data [_provider processing-env]
    (let [session-id (senv/session-id processing-env)
          state-map  (rapp/current-state fulcro-app)
          local-data (get-in state-map [::sc/local-data session-id])
          aliases    (resolve-aliases {:_event           {:target session-id}
                                       :fulcro/state-map state-map})]
      #?(:cljs
         (when goog.DEBUG
           (let [lks (set (keys local-data))
                 aks (set (keys aliases))]
             (when (seq (set/intersection lks aks))
               (log/warn
                 "The fulcro alias names overlap your statechart's local data. The current-data will contain the alias value, not the local one.")))))
      (assoc (merge local-data aliases) :fulcro/state-map state-map)))
  (get-at [_provider processing-env path]
    (let [state-map  (rapp/current-state fulcro-app)
          session-id (senv/session-id processing-env)
          local-data (get-in state-map [::sc/local-data session-id])]
      (cond
        (= :ROOT path) local-data
        (keyword? path) (get local-data path)
        (and (vector? path) (= :ROOT (first path))) (get-in local-data (rest path))
        (and (vector? path) (= :fulcro/state-map (first path))) (get-in state-map (rest path))
        (vector? path) (get-in local-data path)
        :else nil)))
  (update! [_provider processing-env {:keys [ops] :as args}]
    (when-not (map? args)
      (log/error "You forgot to wrap your operations in a map!" args))
    (doseq [op ops]
      (run-fulcro-data-op! fulcro-app processing-env op))
    (rapp/schedule-render! fulcro-app)))

(defn new-fulcro-data-model
  "Creates a data model where a Fulcro application's state is used."
  [app]
  (FulcroDataModel. app))

(deftype FulcroWorkingMemoryStore [fulcro-app on-save on-delete]
  sp/WorkingMemoryStore
  (get-working-memory [_this _env session-id]
    (get-in (rapp/current-state fulcro-app) (statechart-session-ident session-id)))
  (save-working-memory! [_this _env session-id wmem]
    (swap! (state-atom fulcro-app) assoc-in (statechart-session-ident session-id) wmem)
    (when (fn? on-save)
      (on-save session-id wmem)))
  (delete-working-memory! [_this _env session-id]
    (swap! (state-atom fulcro-app) update ::sc/session-id dissoc session-id)
    (swap! (state-atom fulcro-app) update ::sc/local-data dissoc session-id)
    (when (fn? on-delete)
      (on-delete session-id))))

(def remote-mutation-delegate (m/->Mutation `remote-mutation-delegate))

(defmethod run-fulcro-data-op! :fulcro/assoc-alias [app processing-env {:keys [data]}]
  (let [session-id (senv/session-id processing-env)
        state-map  (rapp/current-state app)
        local-data (get-in state-map (local-data-path session-id))]
    (swap! (state-atom app)
      (fn [M]
        (reduce-kv
          (fn [state-map k v]
            (let [expanded-path (resolve-alias-path local-data k)]
              (assoc-in state-map expanded-path v)))
          M
          data)))))

(let [mtrigger! (fn mutation-trigger* [{:keys [app result]} session-id event data]
                  (let [env        (statechart-env app)
                        event-data (assoc data :fulcro/mutation-result result)]
                    (sp/send! (::sc/event-queue env) env {:event  event
                                                          :data   event-data
                                                          :target session-id})))]
  (defmethod m/mutate `remote-mutation-delegate [{:keys [state ast app] :as env}]
    ;; mutation can be run for figuring out remote
    (let [{:keys [txn target returning session-id ok-event error-event
                  ok-data error-data mutation-remote]} (:params ast)
          ast       (eql/query->ast1 txn)
          state-map @state
          target    (cond->> target
                      (keyword? target) (resolve-alias-path state-map session-id)
                      (vector? target) (resolve-actors-in-path state-map session-id))
          returning (cond
                      (keyword? returning) (some->
                                             (get-in state-map (local-data-path session-id :fulcro/actors returning :component))
                                             (rc/registry-key->class))
                      :else returning)]
      {(or mutation-remote :remote) (fn [env]
                                      (let [env (assoc env :ast ast)]
                                        (cond-> env
                                          returning (m/returning returning)
                                          target (m/with-target target))))
       :result-action               m/default-result-action!
       :ok-action                   (fn [env]
                                      (when ok-event
                                        (let [tid->rid (tempid/result->tempid->realid (:body (:result env)))
                                              ok-data  (tempid/resolve-tempids ok-data tid->rid)
                                              asm-id   (tempid/resolve-tempids session-id tid->rid)]
                                          (mtrigger! env asm-id ok-event ok-data))))
       :error-action                (fn [env]
                                      (when error-event
                                        (let [tid->rid   (tempid/result->tempid->realid (:body (:result env)))
                                              error-data (tempid/resolve-tempids error-data tid->rid)
                                              asm-id     (tempid/resolve-tempids session-id tid->rid)]
                                          (mtrigger! env asm-id error-event error-data))))})))

(defmethod run-fulcro-data-op! :fulcro/invoke-remote [app processing-env operation]
  (let [session-id (senv/session-id processing-env)
        {:keys [tx-options]} operation]
    (rc/transact! app [(remote-mutation-delegate (assoc operation :session-id session-id))]
      (or tx-options {}))))


(defmethod run-fulcro-data-op! :fulcro/load [app processing-env {:keys [query-root component-or-actor options]}]
  (let [session-id   (senv/session-id processing-env)
        state-map    (rapp/current-state app)
        local-data   (get-in state-map (local-data-path session-id))
        {::sc/keys [ok-event ok-data error-event error-data target-alias]} options
        event-queue  (::sc/event-queue processing-env)
        ok-action    (when ok-event
                       (fn [{:keys [load-params] :as env}]
                         (df/finish-load! env (dissoc load-params :ok-action))
                         (sp/send! event-queue processing-env {:event  ok-event
                                                               :data   (or ok-data {})
                                                               :target session-id})))
        error-action (when error-event
                       (fn [{:keys [load-params] :as env}]
                         (df/load-failed! env (dissoc load-params :error-action))
                         (sp/send! event-queue processing-env {:event  error-event
                                                               :data   (or error-data {})
                                                               :target session-id})))
        component    (if-let [component-name (and
                                               (keyword? component-or-actor)
                                               (get-in local-data [:fulcro/actors component-or-actor :component]))]
                       (rc/registry-key->class component-name)
                       (rc/registry-key->class component-or-actor))
        target       (when target-alias (resolve-alias-path local-data target-alias))
        params       (cond-> (dissoc options ::sc/ok-event ::sc/error-event ::sc/error-data ::sc/ok-data ::sc/target-alias)
                       ok-action (assoc :ok-action ok-action)
                       error-action (assoc :error-action error-action)
                       target (assoc :target target))]
    (df/load! app query-root component params)))


(defmutation do-apply-action [{:keys [f args]}]
  (action [{:keys [state]}]
    (try
      (swap! state (fn [s] (apply f s args)))
      (catch #?(:cljs :default :clj Throwable) t
        (log/error "Apply action failed:" {:f f :args args})))))

(defmethod run-fulcro-data-op! :fulcro/apply-action [app _processing-env {:keys [f args]}]
  (try
    (swap! (state-atom app) (fn [s] (apply f s args)))
    (catch #?(:cljs :default :clj Throwable) t
      (log/error "Apply action failed:" {:f f :args args}))))

(def master-chart-id :com.fulcrologic.fulcro/master-statechart)

(def application-chart
  (chart/statechart {:initial :state/initial}
    (ele/state {:id :state/initial}
      (ele/transition {:event :done.invoke.*}
        (ele/script {:expr (fn [_env {:keys [_event]}]
                             (let [{::sc/keys [source-session-id]} _event]
                               [(op/delete [:fulcro/state-map ::sc/session-id source-session-id])
                                (op/delete [:fulcro/state-map ::sc/local-data source-session-id])]))})))))
