(ns com.fulcrologic.statecharts.elements-spec
  (:require
    [clojure.test :refer [is are]]
    [com.fulcrologic.statecharts.elements
     :refer [datamodel final history initial invoke
             onentry onexit parallel state transition]]
    [fulcro-spec.core :refer [specification behavior assertions =>]])
  #?(:clj
     (:import (clojure.lang PersistentQueue))))

(def all-elements [datamodel final history initial invoke
                   onentry onexit parallel state transition])

(def elements-with-children
  [final parallel state])

(specification "All element types"
  (let []
    (behavior "generate IDs if one is not supplied"
      (doseq [ele all-elements]
        (is (true? (contains? (ele {}) :id)))))
    (behavior "Uses user-supplied ID if one is supplied"
      (doseq [ele all-elements]
        (is (= :my-id (:id (ele {:id :my-id}))))))
    (behavior "that have children"
      (behavior "Hold their children in a vector"
        (doseq [ele elements-with-children]
          (is (vector? (:children (ele {} {} {})))))))))

(specification "State"
  (assertions
    "An initial state is a regular state with :initial? set to true"
    (state {:id :a :initial? true}) => (initial {:id :a})
    "A final state is special"
    (:node-type (final {})) => :final))
