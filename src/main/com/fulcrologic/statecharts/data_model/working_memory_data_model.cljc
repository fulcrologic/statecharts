(ns com.fulcrologic.statecharts.data-model.working-memory-data-model
  "An implementation of DataModel that stores the data in working memory itself.

   Supports using `src` in data model for CLJ ONLY, which must be a URI that clojure.java.io/reader
   would accept. NOTE: `src` is read via `slurp` with no path restriction — callers are responsible
   for ensuring `src` refers to a trusted, local resource.

   There are two implementations: One where data is scoped to the state, and another where it is global.
   "
  (:require
    #?(:clj
       [clojure.edn :as edn])
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.chart :as chart]
    [taoensso.timbre :as log]))

(defmulti run-op (fn [all-data context-id {:keys [op]}] op))
(defmethod run-op :default [all-data context-id op]
  (log/warn "Operation not understood" op)
  all-data)
(defmethod run-op :assign [all-data context-id {:keys [data]}]
  (reduce-kv
    (fn [acc path value]
      (cond
        (and context-id (keyword? path))
        (do
          (log/trace "Assigning" value "to" [context-id path])
          (assoc-in acc [context-id path] value))

        (keyword? path)
        (do
          (log/error "Internal error: Unknown context for assignment to" path)
          acc)

        (and (vector? path) (= (count path) 2))
        (do
          (log/trace "Assigning" value "to" path)
          (assoc-in acc path value))

        :else (do
                (log/warn "Cannot assign value. Illegal path expression" path)
                acc)))
    all-data
    data))

(defmethod run-op :delete [all-data context-id {:keys [paths]}]
  (reduce
    (fn [M path]
      (cond
        (and context-id (keyword? path)) (update M context-id dissoc path)
        (and (vector? path) (= (count path) 2)) (update M (first path) dissoc (second path))
        :else (do
                (log/warn "Cannot delete value. Illegal path expression" path)
                M)))
    all-data
    paths))

(deftype WorkingMemoryDataModel []
  sp/DataModel
  (load-data [provider {::sc/keys [vwmem] :as env} src]
    #?(:clj (try
              (let [data     (edn/read-string (slurp src))
                    state-id (or (env/context-element-id env) :ROOT)]
                (if (map? data)
                  (do
                    (log/trace "Loaded" data "into context" state-id)
                    (vswap! vwmem update ::data-model assoc state-id data))
                  (log/error "Unable to use loaded data from" src "because it is not a map.")))
              (catch #?(:clj Throwable :cljs :default) e
                (log/error e "Unable to load data from" src)))
       :cljs (log/error "src not supported.")))
  (current-data [_ {::sc/keys [statechart vwmem] :as env}]
    (let [all-data (some-> vwmem deref ::data-model)]
      (loop [state-id (env/context-element-id env)
             result   {}]
        (let [result (merge (get all-data state-id) result)
              parent (chart/get-parent statechart state-id)]
          (if (or (nil? parent) (= :ROOT parent))
            (merge (get all-data :ROOT) result)
            (recur parent result))))))
  (get-at [provider env path]
    (when (or (keyword? path) (vector? path))
      (let [all-data (sp/current-data provider env)]
        (get all-data (if (keyword? path) path (last path))))))
  (update! [provider {::sc/keys [statechart vwmem] :as env} {:keys [ops] :as args}]
    (when-not (map? args)
      (log/error "You forgot to wrap your operations in a map!" args))
    (let [all-data (some-> vwmem deref ::data-model)
          state-id (env/context-element-id env)
          new-data (reduce (fn [acc op] (run-op acc state-id op)) all-data ops)]
      (vswap! vwmem assoc ::data-model new-data))))

(defn new-model
  "Creates a data model where data is stored in the working memory of the state machine.
   The data is scoped to the state it is declared or set in (visible to states
   below it). Locations in this data model are [state-id key], where the special state-id :ROOT stands
   for the top-level machine scope.  Using a keyword as a location is resolved relative to the current
   state, then parent, parent parent, etc.

   `current-data` is a merge of all data for the contextual state from root, with each nested state overriding anything
   that appeared in a parent state.

   `get-at` will NOT walk scopes, but supports simple keywords for the current context,
   and paths of the form `[state-id data-key]`. The special state-id `:ROOT` is reserved for those at the top-most level.

   The operations implemented for this model can be extended by adding to the multimethod `run-op`.

   WARNING: This model is not recommended for many use-cases. The contextual paths turn out to be
   rather difficult to reason about. The flat data model is recommended."
  []
  (->WorkingMemoryDataModel))

(defmulti run-flat-op (fn [data {:keys [op]}] op))
(defmethod run-flat-op :default [all-data op]
  (log/warn "Operation not understood" op)
  all-data)
(defmethod run-flat-op :assign [all-data {:keys [data]}]
  (reduce-kv
    (fn [acc path value]
      (cond
        (= :ROOT path) value
        (keyword? path) (assoc acc path value)
        (and (vector? path) (= :ROOT (first path))) (assoc-in acc (rest path) value)
        (vector? path) (assoc-in acc path value)
        :else acc))
    all-data
    data))

(defn- dissoc-in [m ks]
  (cond
    (empty? ks) m
    (= 1 (count ks)) (dissoc m (first ks))
    (contains? (get-in m (butlast ks)) (last ks)) (update-in m (butlast ks) dissoc (last ks))
    :else m))

(defmethod run-flat-op :delete [all-data {:keys [paths]}]
  (reduce
    (fn [M path]
      (cond
        (= :ROOT path) {}
        (keyword? path) (dissoc M path)
        (and (vector? path) (= :ROOT (first path))) (dissoc-in M (rest path))
        (vector? path) (dissoc-in M path)
        :else M))
    all-data
    paths))


(deftype FlatWorkingMemoryDataModel []
  sp/DataModel
  (load-data [provider {::sc/keys [vwmem] :as env} src]
    #?(:clj (try
              (let [data (edn/read-string (slurp src))]
                (if (map? data)
                  (do
                    (log/trace "Loaded" data "into root data of model")
                    (vswap! vwmem update ::data-model merge data))
                  (log/error "Unable to use loaded data from" src "because it is not a map.")))
              (catch #?(:clj Throwable :cljs :default) e
                (log/error e "Unable to load data from" src)))
       :cljs (log/error "src not supported.")))
  (current-data [_ {::sc/keys [vwmem]}] (some-> vwmem deref ::data-model))
  (get-at [provider env path]
    (let [data (sp/current-data provider env)]
      (cond
        (= :ROOT path) data
        (keyword? path) (get data path)
        (and (vector? path) (= :ROOT (first path))) (get-in data (rest path))
        (vector? path) (get-in data path)
        :else nil)))
  (update! [provider {::sc/keys [statechart vwmem] :as env} {:keys [ops] :as args}]
    (when-not (map? args)
      (log/error "You forgot to wrap your operations in a map!" args))
    (let [all-data (some-> vwmem deref ::data-model)
          new-data (reduce (fn [acc op] (run-flat-op acc op)) all-data ops)]
      (vswap! vwmem assoc ::data-model new-data))))

(defn new-flat-model
  "Creates a data model where data is stored in the working memory of the state machine.
   ALL data scoped to a single map. Location paths work like get-in and assoc-in on that map. The special location
   path `:ROOT` is simply ignored. The keys [:ROOT :a] === [:a] === :a

   "
  []
  (->FlatWorkingMemoryDataModel))
