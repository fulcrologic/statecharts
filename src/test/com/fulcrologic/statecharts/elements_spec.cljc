(ns com.fulcrologic.statecharts.elements-spec
  (:require
   [com.fulcrologic.statecharts.elements :as e]
   [fulcro-spec.core :refer [=> assertions component specification behavior]]))

(specification "element construction"
  (behavior "allows constructing 'invoke' elements with :finalize attribute"
    (let [result (e/invoke {:id      :with-finalize
                            :type    :future
                            :src     (fn [{:keys [db data]}])
                            :finalize (fn [env data])})]
      (assertions
        "produces an :invoke node"
        (:node-type result) => :invoke
        "has exactly one child (the :finalize node)"
        (count (:children result)) => 1
        "the child is a :finalize element"
        (-> result :children first :node-type) => :finalize
        "the :finalize element wraps a :script"
        (-> result :children first :children first :node-type) => :script))))

(comment
  (require '[kaocha.repl :as k])
  (k/run (-> *ns* str symbol)))
