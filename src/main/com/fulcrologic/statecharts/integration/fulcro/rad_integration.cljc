(ns ^:deprecated com.fulcrologic.statecharts.integration.fulcro.rad-integration
  "DEPRECATED. Better RAD integration coming soon."
  (:require
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.algorithms.scheduling :as sched]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [entry-fn exit-fn]]
    [com.fulcrologic.statecharts.integration.fulcro.ui-routes :as uir]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]))

(defn report-state
  "Creates a state whose :route/target is a RAD report. The report will be started on entry, and the :route-params
   for the report will be a merge of the current statechart session data with the event data (which has precedence). If
   you set `report/param-keys` to a collection of keywords, then the route params will be selected from just those keys.

   See `rstate` for generate options for a routed state."
  [{:route/keys  [target path]
    :report/keys [param-keys] :as props}]
  (uir/rstate (merge {} props)
    (entry-fn [{:fulcro/keys [app]} data _ event-data]
      (log/debug "Starting report")
      (report/start-report! app (comp/registry-key->class (:route/target props)) {:route-params (cond-> (merge data event-data)
                                                                                                  (seq param-keys) (select-keys param-keys))})
      nil)))

(defn leave-form
  "Used by the custom RAD form state machine that is used by the rad integration. Discards all changes, and attempts to change route using ui-routing."
  [{::uism/keys [fulcro-app] :as uism-env}]
  (log/info "DONE")
  (let [Form           (uism/actor-class uism-env :actor/form)
        form-ident     (log/spy :info (uism/actor->ident uism-env :actor/form))
        state-map      (rapp/current-state fulcro-app)
        cancel-route   (?! (some-> Form comp/component-options ::form/cancel-route) fulcro-app (fns/ui->props state-map Form form-ident))
        {:keys [on-cancel]} (uism/retrieve uism-env :options)
        routing-action (fn []
                         (cond
                           (map? cancel-route) (let [{:keys [target params]} cancel-route
                                                     target (rc/registry-key->class target)]
                                                 (when (comp/component-class? target)
                                                   (uir/route-to! fulcro-app target (or params {}))))
                           (some-> cancel-route (rc/registry-key->class) (comp/component-class?)) (uir/route-to! fulcro-app (rc/registry-key->class cancel-route) {})))]
    (sched/defer routing-action 100)
    (-> uism-env
      (cond->
        on-cancel (uism/transact on-cancel))
      (uism/store :abandoned? true)
      (uism/apply-action fs/pristine->entity* form-ident))))


(defstatemachine uir-form-machine
  {::uism/actors
   #{:actor/form}

   ::uism/aliases
   {:server-errors [:actor/form ::form/errors]}

   ::uism/states
   {:initial
    {::uism/handler (fn [env]
                      (let [{::uism/keys [event-data]} env
                            {::keys [create?]} event-data
                            Form       (uism/actor-class env :actor/form)
                            form-ident (uism/actor->ident env :actor/form)
                            {{:keys [started]} ::form/triggers} (some-> Form (comp/component-options))]
                        (cond-> (uism/store env :options event-data)
                          create? (form/start-create event-data)
                          (not create?) (form/start-edit event-data)
                          (fn? started) (started form-ident))))}

    :state/loading
    {::uism/events
     (merge form/global-events
       {:event/loaded
        {::uism/handler
         (fn [{::uism/keys [state-map] :as env}]
           (log/debug "Loaded. Marking the form complete.")
           (let [FormClass  (uism/actor-class env :actor/form)
                 form-ident (uism/actor->ident env :actor/form)]
             (-> env
               (form/clear-server-errors)
               (form/auto-create-to-one)
               (form/handle-user-ui-props FormClass form-ident)
               (uism/apply-action fs/add-form-config* FormClass form-ident {:destructive? true})
               (uism/apply-action fs/mark-complete* form-ident)
               (uism/activate :state/editing))))}
        :event/failed
        {::uism/handler
         (fn [env]
           (uism/assoc-aliased env :server-errors [{:message "Load failed."}]))}})}

    :state/asking-to-discard-changes
    {::uism/events
     (merge
       form/global-events
       {:event/ok     {::uism/handler leave-form}
        :event/cancel {::uism/handler (fn [env] (uism/activate env :state/editing))}})}

    :state/saving
    {::uism/events
     (merge
       form/global-events
       {:event/save-failed
        {::uism/handler (fn [env]
                          (let [{:keys [on-save-failed]} (uism/retrieve env :options)
                                Form          (uism/actor-class env :actor/form)
                                {::form/keys           [save-mutation]
                                 {:keys [save-failed]} ::form/triggers} (comp/component-options Form)
                                save-mutation (or save-mutation form/*default-save-form-mutation*)
                                errors        (some-> env ::uism/event-data ::uism/mutation-result :body (get save-mutation) ::form/errors)
                                form-ident    (uism/actor->ident env :actor/form)]
                            (cond-> (uism/activate env :state/editing)
                              (seq errors) (uism/assoc-aliased :server-errors errors)
                              save-failed (save-failed form-ident)
                              on-save-failed (uism/transact on-save-failed))))}
        :event/saved
        {::uism/handler (fn [{::uism/keys [fulcro-app] :as env}]
                          (let [form-ident (uism/actor->ident env :actor/form)
                                Form       (uism/actor-class env :actor/form)
                                {{:keys [saved]} ::form/triggers} (some-> Form (comp/component-options))]
                            (-> env
                              (cond->
                                saved (saved form-ident))
                              (form/run-on-saved)
                              (uism/apply-action fs/entity->pristine* form-ident)
                              (uism/activate :state/editing))))}})}

    :state/editing
    {::uism/events
     (merge
       form/global-events
       {:event/attribute-changed
        {::uism/handler
         (fn [{::uism/keys [event-data] :as env}]
           ;; NOTE: value at this layer is ALWAYS typed to the attribute.
           ;; The rendering layer is responsible for converting the value to/from
           ;; the representation needed by the UI component (e.g. string)
           (let [{:keys       [old-value form-key value form-ident]
                  ::attr/keys [cardinality type qualified-key]} event-data
                 form-class     (some-> form-key (comp/registry-key->class))
                 {{:keys [on-change]} ::form/triggers} (some-> form-class (comp/component-options))
                 many?          (= :many cardinality)
                 ref?           (= :ref type)
                 missing?       (nil? value)
                 value          (cond
                                  (and ref? many? (nil? value)) []
                                  (and many? (nil? value)) #{}
                                  (and ref? many?) (filterv #(not (nil? (second %))) value)
                                  ref? (if (nil? (second value)) nil value)
                                  :else value)
                 path           (when (and form-ident qualified-key)
                                  (conj form-ident qualified-key))
                 ;; TODO: Decide when to properly set the field to marked
                 mark-complete? true]
             #?(:cljs
                (when goog.DEBUG
                  (when-not path
                    (log/error "Unable to record attribute change. Path cannot be calculated."))
                  (when (and ref? many? (not (every? eql/ident? value)))
                    (log/error "Setting a ref-many attribute to incorrect type. Value should be a vector of idents:" qualified-key value))
                  (when (and ref? (not many?) (not missing?) (not (eql/ident? value)))
                    (log/error "Setting a ref-one attribute to incorrect type. Value should an ident:" qualified-key value))))
             (-> env
               (form/clear-server-errors)
               (cond->
                 mark-complete? (uism/apply-action fs/mark-complete* form-ident qualified-key)
                 (and path (nil? value)) (uism/apply-action update-in form-ident dissoc qualified-key)
                 (and path (not (nil? value))) (uism/apply-action assoc-in path value)
                 on-change (form/protected-on-change on-change form-ident qualified-key old-value value))
               (form/apply-derived-calculations))))}

        :event/blur
        {::uism/handler (fn [env] env)}

        :event/add-row
        {::uism/handler (fn [{::uism/keys [event-data state-map] :as env}]
                          (let [{::form/keys [order parent-relation parent child-class
                                              initial-state default-overrides]} event-data
                                {{:keys [on-change]} ::form/triggers} (some-> parent (comp/component-options))
                                parent-ident         (comp/get-ident parent)
                                relation-attr        (form/form-key->attribute parent parent-relation)
                                many?                (attr/to-many? relation-attr)
                                target-path          (conj parent-ident parent-relation)
                                old-value            (get-in state-map target-path)
                                new-child            (if (map? initial-state)
                                                       initial-state
                                                       (merge
                                                         (form/default-state child-class (tempid/tempid))
                                                         default-overrides))
                                child-ident          (comp/get-ident child-class new-child)
                                optional-keys        (form/optional-fields child-class)
                                mark-fields-complete (fn [state-map]
                                                       (reduce
                                                         (fn [s k]
                                                           (fs/mark-complete* s child-ident k))
                                                         state-map
                                                         (concat optional-keys (keys new-child))))
                                apply-on-change      (fn [env]
                                                       (if on-change
                                                         (let [new-value (get-in (::uism/state-map env) target-path)]
                                                           (form/protected-on-change env on-change parent-ident parent-relation old-value new-value))
                                                         env))]
                            (when-not relation-attr
                              (log/error "Cannot add child because you forgot to put the attribute for" parent-relation
                                "in the fo/attributes of " (comp/component-name parent)))
                            (-> env
                              (uism/apply-action
                                (fn [s]
                                  (-> s
                                    (merge/merge-component child-class new-child (if many?
                                                                                   (or order :append)
                                                                                   :replace) target-path)
                                    (fs/add-form-config* child-class child-ident)
                                    (mark-fields-complete))))
                              (apply-on-change)
                              (form/apply-derived-calculations))))}

        :event/delete-row
        {::uism/handler (fn [{::uism/keys [event-data state-map] :as env}]
                          (let [{::form/keys [form-instance child-ident parent parent-relation]} event-data
                                {{:keys [on-change]} ::form/triggers} (some-> parent (comp/component-options))
                                relation-attr   (form/form-key->attribute parent parent-relation)
                                many?           (attr/to-many? relation-attr)
                                child-ident     (or child-ident (and form-instance (comp/get-ident form-instance)))
                                parent-ident    (comp/get-ident parent)
                                target-path     (conj parent-ident parent-relation)
                                old-value       (get-in state-map target-path)
                                apply-on-change (fn [env]
                                                  (if on-change
                                                    (let [new-value (get-in (::uism/state-map env) target-path)]
                                                      (form/protected-on-change env on-change parent-ident parent-relation old-value new-value))
                                                    env))]
                            (when target-path
                              (-> env
                                (cond->
                                  many? (uism/apply-action fns/remove-ident child-ident target-path)
                                  (not many?) (uism/apply-action update-in parent-ident dissoc parent-relation))
                                (apply-on-change)
                                (form/apply-derived-calculations)))))}

        :event/save
        {::uism/handler (fn [{::uism/keys [state-map event-data] :as env}]
                          (let [form-class          (uism/actor-class env :actor/form)
                                form-ident          (uism/actor->ident env :actor/form)
                                {::form/keys [id save-mutation]} (comp/component-options form-class)
                                master-pk           (::attr/qualified-key id)
                                proposed-form-props (fs/completed-form-props state-map form-class form-ident)]
                            (if (form/valid? form-class proposed-form-props)
                              (let [data-to-save  (form/calc-diff env)
                                    params        (merge event-data data-to-save)
                                    save-mutation (or save-mutation form/*default-save-form-mutation*)]
                                (-> env
                                  (form/clear-server-errors)
                                  (uism/trigger-remote-mutation :actor/form save-mutation
                                    (merge params
                                      {::uism/error-event :event/save-failed
                                       ::form/master-pk   master-pk
                                       ::form/id          (second form-ident)
                                       ::m/returning      form-class
                                       ::uism/ok-event    :event/saved}))
                                  (uism/activate :state/saving)))
                              (-> env
                                (uism/apply-action fs/mark-complete* form-ident)
                                (uism/activate :state/editing)))))}

        :event/reset
        {::uism/handler (fn [env] (form/undo-all env))}

        :event/cancel
        {::uism/handler leave-form}})}}})

(defn start-form!
  "Statechart version of RAD start-form!. Starts a modified version of the UISM on the RAD form. Otherwise works the same
   as rad.form/start-form!.

   NOTE: IF the RAD form sets the fo/machine option then it WILL be honored, but make sure your custom machine uses the
   UISM from this ns as a base."
  [app id form-class params]
  (let [{::attr/keys [qualified-key]} (comp/component-options form-class fo/id)
        machine    (or (comp/component-options form-class ::machine) uir-form-machine)
        new?       (tempid/tempid? id)
        form-ident [qualified-key id]]
    (uism/begin! app machine
      form-ident
      {:actor/form (uism/with-actor-class form-ident form-class)}
      (merge params {::create? new?}))))

(defn form-state
  "Generates a ui routing rstate that starts an edit/create on a RAD form (based on the statechart event data). The
   statechart routing event data should include :id and optionally :params. If the :id is a tempid, then you are creating
   a new thing; otherwise it is an edit. `params` are sent directly to start-form! as the options.

   The busy detection of UI routing will automatically detect busy for RAD forms.

   Leaving this state will ensure the form is abandoned. So, route override will undo the unsaved changes."
  [props]
  (uir/rstate props
    (entry-fn [{:fulcro/keys [app]} data _ event-data]
      (log/debug "Starting form" event-data)
      (let [{:keys [id params]} event-data]
        (start-form! app id (comp/registry-key->class (:route/target props)) params))
      nil)
    (exit-fn [{:fulcro/keys [app]} {:route/keys [idents]} & _]
      ;; Make sure if we abandoned the form that we undo the changes
      (when-let [form-ident (get idents (rc/class->registry-key (:route/target props)))]
        (form/abandon-form! app form-ident)
        [(ops/delete [:route/idents form-ident])]))))

(defn edit!
  "Routes to Form and starts an edit on the given id"
  ([app-ish Form id]
   (edit! app-ish Form id {}))
  ([app-ish Form id params]
   (uir/route-to! app-ish Form {:id     id
                                :params params})))

(defn create!
  "Routes to Form and starts a create."
  ([app-ish Form]
   (edit! app-ish Form (tempid/tempid) {}))
  ([app-ish Form params]
   (uir/route-to! app-ish Form {:id     (tempid/tempid)
                                :params params})))
