(ns com.fulcrologic.statecharts.elements-spec
  (:require
    [clojure.test :refer [is are]]
    [com.fulcrologic.statecharts.elements :as eles]
    [fulcro-spec.core :refer [specification behavior assertions =>]])
  #?(:clj
     (:import (clojure.lang PersistentQueue))))
