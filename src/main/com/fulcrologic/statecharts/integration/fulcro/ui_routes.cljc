(ns com.fulcrologic.statecharts.integration.fulcro.ui-routes
  "A composable statechart-driven UI routing system"
  (:require
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :as ele :refer [on-entry script state transition]]
    [com.fulcrologic.statecharts.environment :as senv]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]))

(>defn route-to-event-name [target-key]
  [:qualified-keyword => :qualified-keyword]
  (let [[nspc nm] [(namespace target-key) (name target-key)]
        new-ns (str "route-to." nspc)]
    (keyword new-ns nm)))

(defn initialize-route! [{:fulcro/keys [app] :as env} {::keys [target] :as data}]
  (let [state-map (app/current-state app)
        Target    (comp/registry-key->class target)
        {::keys [initialize initial-props]} (comp/component-options Target)
        props     (if initial-props (initial-props env data) {})
        ident     (comp/get-ident Target props)
        exists?   (some? (get-in state-map ident))]
    (when (or
            (and (= :once initialize) (not exists?))
            (= :always initialize))
      (log/trace "Initializing target" target)
      (merge/merge-component! app Target props))
    ident))

(defn update-parent-query!
  "Dynamically set the query of Parent such that :ui/current-route is a join to Target."
  [{:fulcro/keys [app] :as env} data target-id]
  (let [{::sc/keys [elements-by-id] :as chart} (senv/normalized-chart env)
        {::app/keys [state-atom]} app
        {:keys [parent route/target] :as target-state} (get elements-by-id target-id)
        parent-key   (or
                       (get-in elements-by-id [parent :route/target])
                       (get-in elements-by-id [parent :routing/root]))
        Parent       (comp/registry-key->class parent-key)
        Target       (comp/registry-key->class target)
        state-map    (app/current-state app)
        parent-ident (get-in data [:route/idents parent-key])
        target-ident (get-in data [:route/idents target])
        old-query    (comp/get-query Parent state-map)
        oq-ast       (eql/query->ast old-query)
        nq-ast       (update oq-ast :children
                       (fn [cs]
                         (conj (vec (remove #(= :ui/current-route (:dispatch-key %)) cs))
                           (eql/query->ast1 [{:ui/current-route (comp/get-query Target state-map)}]))))
        new-query    (log/spy :info "New Query: " (eql/ast->query nq-ast))]
    (swap! state-atom assoc-in (conj parent-ident :ui/current-route) target-ident)
    (comp/set-query! app Parent {:query new-query})))

(defn rstate
  "Create a routing state. Requires a :route/target attribute which should be
   anything accepted by comp/registry-key->class"
  [{:route/keys [target] :as props} & children]
  (let [target-key (cond
                     (keyword? target) target
                     (symbol? target) (keyword target)
                     (comp/component-class? target) (comp/class->registry-key target))]
    ;; TODO: See which kind of management the component wants. Simple routing, invocation
    ;; of a nested chart, start a separate chart with an actor?
    (apply state (merge props {:id           target-key
                               :route/target target-key})
      (on-entry {}
        (script {:expr (fn [env data & _]
                         (let [ident (initialize-route! env (assoc data ::target target-key))]
                           [(ops/assign [:route/idents target-key] ident)]))})
        (script {:expr (fn [env data & _] (update-parent-query! env data target-key))}))
      children)))

(defn busy? [{:fulcro/keys [app] :as env} {:keys [_event]} & args]
  (if (-> _event :data ::force?)
    false
    (let [state-ids (senv/current-configuration env)
          {::sc/keys [elements-by-id]} (senv/normalized-chart env)
          busy?     (some (fn [state-id]
                            (let [t      (get-in elements-by-id [state-id :route/target])
                                  Target (comp/registry-key->class t)
                                  {::keys [busy?] :as opts} (some-> Target (comp/component-options))]
                              (if busy?
                                ;; TODO: Would be nice to pass the component props, but need live actor
                                (boolean (busy? app state-id))
                                false)))
                      state-ids)]
      busy?)))

(defn record-failed-route! [env {:keys [_event]} & args]
  [(ops/assign ::failed-route-event _event)])

(defn routes
  "Emits a state that represents the region that contains all of the
   routes. This will emit all of the transitions for direct navigation
   to any substate that has a routing target.

   The :routing/root must be a component with a constant ident (or just app root itself),
   and a query."
  [{:routing/keys [root]
    :keys         [id] :as props} & route-states]
  (let [find-targets       (fn find-targets* [targets]
                             (mapcat
                               (fn [{:keys [route/target children]}]
                                 (into (if target [target] [])
                                   (find-targets* children)))
                               targets))
        all-targets        (find-targets route-states)
        direct-transitions (mapv
                             (fn [t]
                               (transition {:event  (route-to-event-name t)
                                            :target t}))
                             all-targets)]
    ;; TODO: Need a "Root" for setting parent query and join data
    (apply state props
      (transition {:event :route-to.*
                   :cond  busy?}
        (script {:expr record-failed-route!})
        (ele/raise {:event :event.routing-info/show}))
      (concat
        direct-transitions
        route-states))))
