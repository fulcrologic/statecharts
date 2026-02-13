(ns com.fulcrologic.statecharts.integration.fulcro.route-url-test
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state]]
    [com.fulcrologic.statecharts.integration.fulcro.route-url :as ru]
    [fulcro-spec.core :refer [=> assertions behavior specification]]))

;; A test chart that mimics routing structure with hierarchical paths
(def hierarchical-chart
  (chart/statechart {}
    (state {:id :routes :routing/root :app/Root}
      (state {:id :user-list :route/target :user/List :route/path ["users"]}
        (state {:id :user-detail :route/target :user/Detail :route/path [:id]}
          (state {:id :user-settings :route/target :user/Settings :route/path ["settings"]}))
        (state {:id :user-edit :route/target :user/Edit :route/path ["edit"]}))
      (state {:id :dashboard :route/target :ui/Dashboard :route/path ["dashboard"]}))))

;; A flat chart (legacy style) with no nesting
(def flat-chart
  (chart/statechart {}
    (state {:id :routes :routing/root :app/Root}
      (state {:id :user-list :route/target :user/List :route/path ["users"]})
      (state {:id :user-edit :route/target :user/Edit :route/path ["users" "edit"]})
      (state {:id :dashboard :route/target :ui/Dashboard :route/path ["dashboard"]}))))

;; A chart with three levels deep
(def deep-chart
  (chart/statechart {}
    (state {:id :routes :routing/root :app/Root}
      (state {:id :org :route/target :org/Org :route/path ["orgs"]}
        (state {:id :team :route/target :org/Team :route/path [:org-id "teams"]}
          (state {:id :member :route/target :org/Member :route/path [:team-id]}))))))

(specification "resolve-full-path"
  (behavior "composes child path with parent"
    (assertions
      "Single level"
      (ru/resolve-full-path hierarchical-chart :user-list) => ["users"]
      "Child under parent"
      (ru/resolve-full-path hierarchical-chart :user-edit) => ["users" "edit"]
      "Parameterized child"
      (ru/resolve-full-path hierarchical-chart :user-detail) => ["users" :id]
      "Three levels: parent + param child + literal grandchild"
      (ru/resolve-full-path hierarchical-chart :user-settings) => ["users" :id "settings"]
      "Standalone (no routable parent other than root)"
      (ru/resolve-full-path hierarchical-chart :dashboard) => ["dashboard"]))

  (behavior "flat/legacy paths work unchanged"
    (assertions
      "Flat path remains flat"
      (ru/resolve-full-path flat-chart :user-list) => ["users"]
      "Flat multi-segment path"
      (ru/resolve-full-path flat-chart :user-edit) => ["users" "edit"]))

  (behavior "three levels deep"
    (assertions
      (ru/resolve-full-path deep-chart :org) => ["orgs"]
      (ru/resolve-full-path deep-chart :team) => ["orgs" :org-id "teams"]
      (ru/resolve-full-path deep-chart :member) => ["orgs" :org-id "teams" :team-id])))

(specification "match-path"
  (behavior "exact match returns empty params"
    (assertions
      (ru/match-path ["users" "edit"] ["users" "edit"]) => {}
      (ru/match-path [] []) => {}
      (ru/match-path ["dashboard"] ["dashboard"]) => {}))

  (behavior "parameterized match extracts params"
    (assertions
      (ru/match-path ["users" "42" "edit"] ["users" :id "edit"]) => {:id "42"}
      (ru/match-path ["users" "42"] ["users" :id]) => {:id "42"}
      "Multiple params"
      (ru/match-path ["orgs" "acme" "teams" "dev"] ["orgs" :org-id "teams" :team-id])
      => {:org-id "acme" :team-id "dev"}))

  (behavior "length mismatch returns nil"
    (assertions
      (ru/match-path ["users"] ["users" :id]) => nil
      (ru/match-path ["users" "42" "edit"] ["users" :id]) => nil))

  (behavior "literal mismatch returns nil"
    (assertions
      (ru/match-path ["users" "42"] ["posts" :id]) => nil)))

(specification "resolve-path-params"
  (behavior "substitutes keyword placeholders"
    (assertions
      (ru/resolve-path-params ["users" :id "edit"] {:id "42"}) => ["users" "42" "edit"]
      (ru/resolve-path-params ["orgs" :org-id "teams" :team-id] {:org-id "acme" :team-id "dev"})
      => ["orgs" "acme" "teams" "dev"]))

  (behavior "missing params default to empty string"
    (assertions
      (ru/resolve-path-params ["users" :id] {}) => ["users" ""]
      (ru/resolve-path-params ["users" :id "edit"] {:id "42" :extra "ignored"}) => ["users" "42" "edit"]))

  (behavior "all-literal pattern passes through"
    (assertions
      (ru/resolve-path-params ["users" "edit"] {}) => ["users" "edit"])))

(specification "build-route-table"
  (let [rt (ru/build-route-table hierarchical-chart)]
    (behavior "produces correct entries with full paths"
      (assertions
        "All route-target states included"
        (set (mapv :state-id rt))
        => #{:user-list :user-detail :user-settings :user-edit :dashboard}

        "Patterns are fully resolved"
        (:pattern (first (filterv #(= :user-settings (:state-id %)) rt)))
        => ["users" :id "settings"]

        (:pattern (first (filterv #(= :user-edit (:state-id %)) rt)))
        => ["users" "edit"]))

    (behavior "sorted by specificity: more literals first"
      (let [first-two-seg-entries (filterv #(= 2 (count (:pattern %))) rt)
            edit-idx (.indexOf (mapv :state-id first-two-seg-entries) :user-edit)
            detail-idx (.indexOf (mapv :state-id first-two-seg-entries) :user-detail)]
        (assertions
          "Literal 'edit' appears before parameterized :id"
          (< edit-idx detail-idx) => true)))))

(specification "find-route-for-url"
  (let [rt (ru/build-route-table hierarchical-chart)]
    (behavior "finds exact literal match"
      (let [result (ru/find-route-for-url rt ["users"])]
        (assertions
          (:state-id result) => :user-list
          (:route/path-params result) => {})))

    (behavior "finds parameterized match"
      (let [result (ru/find-route-for-url rt ["users" "42"])]
        (assertions
          (:state-id result) => :user-detail
          (:route/path-params result) => {:id "42"})))

    (behavior "finds deeply nested match"
      (let [result (ru/find-route-for-url rt ["users" "42" "settings"])]
        (assertions
          (:state-id result) => :user-settings
          (:route/path-params result) => {:id "42"})))

    (behavior "prefers literal over parameterized match"
      (let [result (ru/find-route-for-url rt ["users" "edit"])]
        (assertions
          "Literal 'edit' wins over :id parameter"
          (:state-id result) => :user-edit
          (:route/path-params result) => {})))

    (behavior "returns nil for no match"
      (assertions
        (ru/find-route-for-url rt ["nonexistent"]) => nil
        (ru/find-route-for-url rt ["users" "42" "unknown"]) => nil))))

(specification "backward compatibility: flat paths"
  (let [rt (ru/build-route-table flat-chart)]
    (behavior "flat paths still match correctly"
      (assertions
        (:state-id (ru/find-route-for-url rt ["users"])) => :user-list
        (:state-id (ru/find-route-for-url rt ["users" "edit"])) => :user-edit
        (:state-id (ru/find-route-for-url rt ["dashboard"])) => :dashboard))))
