(ns com.fulcrologic.statecharts.elements-spec
  (:require
    [clojure.test :refer [is are]]
    [com.fulcrologic.statecharts.elements
     :refer [datamodel final history initial invoke
             onentry onexit parallel state transition]]
    [fulcro-spec.core :refer [specification behavior assertions =>]])
  #?(:clj
     (:import (clojure.lang PersistentQueue))))
