(ns com.fulcrologic.statecharts.data-model.working-memory-data-model
  "An implementation of DataModel that stores the data in working memory itself.

   Supports using `src` in data model for CLJ ONLY, which must be a URI that clojure.java.io/reader
   would accept.

   Data retrieval resolves scopes by walking up the tree looking for parent states that have the data desired.

   `current-data` is a merge of all state from root to the contextual state.
   `get-at` will NOT walk scopes, but supports simple keywords for the current context, and paths of the form [state-id data-key].
   "
  (:require
    [clojure.edn :as edn]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.state-machine :as sm]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.protocols :as sp]
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
        (and (vector? path) (= count 2)) (update M (first path) dissoc (second path))
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
                    (vswap! vwmem ::data-model assoc state-id data))
                  (log/error "Unable to use loaded data from" src "because it is not a map.")))
              (catch #?(:clj Throwable :cljs :default) e
                (log/error e "Unable to load data from" src)))
       :cljs (log/error "src not supported.")))
  (current-data [_ {::sc/keys [machine vwmem] :as env}]
    (let [all-data (some-> vwmem deref ::data-model)]
      (loop [state-id (env/context-element-id env)
             result   {}]
        (let [result (merge (get all-data state-id) result)
              parent (sm/get-parent machine state-id)]
          (if (or (nil? parent) (= :ROOT parent))
            (merge (get all-data :ROOT) result)
            (recur parent result))))))
  (get-at [provider env path]
    (when (or (keyword? path) (vector? path))
      (let [all-data (sp/current-data provider env)]
        (get all-data (if (keyword? path) path (last path))))))
  (update! [provider {::sc/keys [machine vwmem] :as env} {:keys [ops] :as args}]
    (when-not (map? args)
      (log/error "You forgot to wrap your operations in a map!" args))
    (let [all-data (some-> vwmem deref ::data-model)
          state-id (env/context-element-id env)
          new-data (reduce (fn [acc op] (run-op acc state-id op)) all-data ops)]
      (vswap! vwmem assoc ::data-model new-data))))

(defn new-model []
  (->WorkingMemoryDataModel))
