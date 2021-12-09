(ns com.fulcrologic.statecharts.elements
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.util :refer [genid]]))

(defn state
  "Create a state. ID will be generated if not supplied. The `initial` element is an alias for this
   element with `:initial? true`."
  [{:keys [id initial?] :as attrs} & children]
  (merge {:id (genid "state")} attrs {:node-type :state
                                      :children  (vec children)}))

(defn history
  "Create a history node. Set `:deep? true` for deep history."
  [{:keys [deep? id] :as attrs}]
  (merge {:id (genid "history")}
    attrs
    {:node-type :history}))

(defn final [{:keys [id] :as attrs} & children]
  (merge {:id (genid "final")}
    attrs
    {:node-type :final
     :children  (vec children)}))

(defn invoke [{:keys [id expr]}]
  {:id        (or id (genid "invoke"))
   :node-type :invoke
   :function  expr})

(defn initial "Alias for `(state {:initial? true ...} ...)`."
  [attrs & children]
  (apply state (assoc attrs :initial? true)
    children))

(defn on-enter [{:keys [id expr] :as attrs}]
  (merge
    {:id (or id (genid "enter"))}
    attrs
    {:node-type :on-entry
     :function  expr}))

(defn on-exit [{:keys [id expr] :as attrs}]
  (merge
    {:id (genid "exit")}
    attrs
    {:node-type :on-exit
     :function  expr}))

(defn parallel [attrs & children]
  (merge
    {:id (genid "parallel")}
    attrs
    {:node-type :parallel
     :children  (vec children)}))

(defn data-model
  "Create a data model (in a state or machine context). `expr` is an expression
   (value or function) that will result in the initial value of the data.

   If the expression is a value, then you are using early binding. If it is
   a lambda, then the data will be bound when its surrounding state is entered, but
   before any `on-entry` is invoked.
   "
  [{:keys [id expr] :as attrs}]
  (merge
    {:id (genid "data-model")}
    attrs
    {:node-type  :data-model
     :expression expr}))

(defn transition
  ([{:keys [event cond target type] :as attrs}]
   (transition attrs nil))
  ([{:keys [event cond target type] :as attrs} action]
   (merge
     {:id   (genid "transition")
      :type (or type :external)}
     attrs
     (cond-> {:node-type :transition}
       action (assoc :action action)))))

